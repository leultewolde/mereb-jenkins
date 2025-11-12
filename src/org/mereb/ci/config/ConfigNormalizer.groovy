package org.mereb.ci.config

import org.mereb.ci.build.PnpmPreset

import static org.mereb.ci.util.PipelineUtils.toStringList
import static org.mereb.ci.util.PipelineUtils.toStringMap

/**
 * Normalizes the YAML configuration consumed by the Jenkins shared library so it is predictable.
 */
class ConfigNormalizer implements Serializable {

    static Map normalize(Map raw, List<String> defaultEnvOrder, String primaryConfig) {
        Map source = raw ?: [:]
        Map cfg = [:]

        cfg.agent = [
            label : (source.agent?.label ?: '').toString().trim(),
            docker: (source.agent?.docker ?: '').toString().trim()
        ]

        Map buildSection = source.build instanceof Map ? new LinkedHashMap(source.build as Map) : [:]
        if (!(buildSection.containsKey('pnpm')) && source.pnpm) {
            buildSection = buildSection ? new LinkedHashMap(buildSection) : [:]
            buildSection.pnpm = source.pnpm
        }

        cfg.preset = (source.preset ?: source.build?.preset ?: 'node').toString()
        if (!(buildSection.containsKey('pnpm')) && cfg.preset.equalsIgnoreCase('pnpm')) {
            buildSection = buildSection ? new LinkedHashMap(buildSection) : [:]
            buildSection.pnpm = [:]
        }

        cfg.matrix = normalizeMatrix(source.matrix)
        cfg.buildStages = computeBuildStages(cfg.preset, buildSection)
        cfg.requiresGradleHome = needsGradleHome(cfg.preset, cfg.buildStages)
        cfg.releaseStages = normalizeUserStages(source.releaseStages)
        cfg.image = normalizeImage(source, primaryConfig)
        cfg.sbom = normalizeSbom(source.sbom)
        cfg.scan = normalizeScan(source.scan)
        cfg.signing = normalizeSigning(source.signing, cfg.image)
        cfg.terraform = normalizeTerraform(source.terraform)
        cfg.release = normalizeRelease(source.release)
        cfg.deploy = normalizeDeploy(source, defaultEnvOrder)

        return cfg
    }

    static boolean needsGradleHome(String preset, List<Map> stages) {
        if ('java-gradle'.equalsIgnoreCase(preset)) {
            return true
        }
        return stages?.any { Map stage ->
            String verb = stage.verb?.toString() ?: stage.name?.toString()
            verb?.startsWith('gradle.')
        }
    }

    private static Map normalizeMatrix(Object raw) {
        Map result = [:]
        if (!(raw instanceof Map)) {
            return result
        }
        (raw as Map).each { k, v ->
            List<String> entries = []
            if (v instanceof List) {
                v.each { entries << it.toString() }
            } else if (v != null) {
                entries << v.toString()
            }
            result[k.toString()] = entries
        }
        return result
    }

    private static List<Map> computeBuildStages(String preset, Object buildCfgRaw) {
        Map buildCfg = buildCfgRaw instanceof Map ? buildCfgRaw : [:]
        if (buildCfg.stages instanceof List && buildCfg.stages) {
            return (buildCfg.stages as List).collect { Map stage ->
                [
                    name       : stage.name?.toString() ?: 'Build',
                    verb       : stage.verb?.toString(),
                    sh         : stage.sh?.toString(),
                    env        : toStringMap(stage.env),
                    credentials: normalizeCredentialList(stage.credentials)
                ]
            }
        }
        if (buildCfg.pnpm) {
            return PnpmPreset.buildStages(buildCfg.pnpm)
        }

        switch (preset) {
            case 'java-gradle':
                return [
                    [name: 'Unit Tests', verb: 'gradle.test'],
                    [name: 'Build', verb: 'gradle.build']
                ]
            case 'node':
            default:
                return [
                    [name: 'Install', verb: 'node.install'],
                    [name: 'Lint', verb: 'node.lint'],
                    [name: 'Test', verb: 'node.test'],
                    [name: 'Build', verb: 'node.build']
                ]
        }
    }

    private static List<Map> normalizeCredentialList(Object raw) {
        if (!(raw instanceof List)) {
            return []
        }
        List<Map> creds = []
        (raw as List).each { Object node ->
            if (!(node instanceof Map)) {
                return
            }
            Map cred = node as Map
            Map normalized = [
                id : cred.id?.toString(),
                env: cred.env?.toString(),
                type: (cred.type ?: 'string').toString()
            ]
            if (cred.usernameEnv || cred.usernameVariable) {
                normalized.usernameEnv = (cred.usernameEnv ?: cred.usernameVariable).toString()
            }
            if (cred.passwordEnv || cred.passwordVariable) {
                normalized.passwordEnv = (cred.passwordEnv ?: cred.passwordVariable).toString()
            }
            creds << normalized
        }
        return creds
    }

    private static Map normalizeImage(Map raw, String primaryConfig) {
        Map app = raw.app instanceof Map ? raw.app : [:]
        Object imageSection = raw.image
        Map imageRaw = imageSection instanceof Map ? imageSection : [:]

        boolean enabled = true
        if (imageSection instanceof Boolean) {
            enabled = imageSection as Boolean
        } else if (imageRaw.containsKey('enabled')) {
            enabled = imageRaw.enabled as Boolean
        }

        Map pushRaw = imageRaw.push instanceof Map ? imageRaw.push : [:]

        if (!enabled) {
            return [
                enabled    : false,
                repository : '',
                context    : (imageRaw.context ?: '.').toString(),
                dockerfile : (imageRaw.dockerfile ?: 'Dockerfile').toString(),
                buildArgs  : toStringMap(imageRaw.buildArgs),
                buildFlags : toStringList(imageRaw.buildFlags),
                platforms  : toStringList(imageRaw.platforms),
                tagStrategy: (imageRaw.tagStrategy ?: 'branch-sha').toString(),
                tagTemplate: imageRaw.tagTemplate ?: imageRaw.tag,
                push       : [
                    enabled  : false,
                    when     : (pushRaw.when ?: imageRaw.pushWhen ?: '!pr').toString(),
                    extraTags: toStringList(pushRaw.extraTags ?: imageRaw.extraTags)
                ]
            ]
        }

        String repository = (imageRaw.repository ?: '').toString().trim()
        if (!repository) {
            String imageName = (imageRaw.name ?: app.image ?: app.name ?: '').toString().trim()
            if (!imageName) {
                throw new IllegalArgumentException("image.repository or app.name must be defined in ${primaryConfig}")
            }
            String registry = (imageRaw.registry ?: app.registry ?: '').toString().trim()
            if (registry) {
                String cleaned = registry.replaceAll(/\/+$/, '')
                repository = cleaned ? "${cleaned}/${imageName}" : imageName
            } else {
                repository = imageName
            }
        }

        String registryOverride = (imageRaw.registry ?: '').toString().trim()
        String derivedRegistryHost = deriveRegistryHost(repository)
        if (registryOverride) {
            derivedRegistryHost = registryOverride
        }

        Map imageCfg = [
            enabled      : true,
            repository   : repository,
            registryHost : derivedRegistryHost,
            context      : (imageRaw.context ?: '.').toString(),
            dockerfile   : (imageRaw.dockerfile ?: 'Dockerfile').toString(),
            buildArgs    : toStringMap(imageRaw.buildArgs),
            buildFlags   : toStringList(imageRaw.buildFlags),
            platforms    : toStringList(imageRaw.platforms),
            tagStrategy  : (imageRaw.tagStrategy ?: 'branch-sha').toString(),
            tagTemplate  : imageRaw.tagTemplate ?: imageRaw.tag
        ]

        imageCfg.push = [
            enabled  : pushRaw.enabled == null ? true : pushRaw.enabled as Boolean,
            when     : (pushRaw.when ?: imageRaw.pushWhen ?: '!pr').toString(),
            extraTags: toStringList(pushRaw.extraTags ?: imageRaw.extraTags),
            registry : (pushRaw.registry ?: '').toString().trim()
        ]

        String pushCredentialId = (pushRaw.credentialsId ?: pushRaw.credentialId ?: pushRaw.credential ?: pushRaw.id ?: '').toString().trim()
        if (pushCredentialId) {
            imageCfg.push.credentials = [
                id         : pushCredentialId,
                usernameEnv: (pushRaw.usernameEnv ?: pushRaw.usernameVariable ?: 'DOCKER_USERNAME').toString(),
                passwordEnv: (pushRaw.passwordEnv ?: pushRaw.passwordVariable ?: 'DOCKER_PASSWORD').toString()
            ]
        }

        return imageCfg
    }

    private static String deriveRegistryHost(String repository) {
        if (!repository?.trim()) {
            return ''
        }
        String repo = repository.trim()
        int slash = repo.indexOf('/')
        if (slash <= 0) {
            return 'docker.io'
        }
        String candidate = repo.substring(0, slash)
        if (candidate.contains('.') || candidate.contains(':')) {
            return candidate
        }
        return 'docker.io'
    }

    private static Map normalizeSbom(Object raw) {
        Map cfg = raw instanceof Map ? raw : [:]
        boolean enabled = cfg.enabled == null ? true : cfg.enabled as Boolean
        return [
            enabled: enabled,
            format : (cfg.format ?: 'cyclonedx-json').toString(),
            output : (cfg.output ?: 'reports/sbom-{{imageTag}}.json').toString()
        ]
    }

    private static Map normalizeScan(Object raw) {
        Map cfg = raw instanceof Map ? raw : [:]
        boolean enabled = cfg.enabled == null ? true : cfg.enabled as Boolean
        return [
            enabled: enabled,
            failOn : (cfg.failOn ?: 'critical').toString(),
            flags  : toStringList(cfg.flags ?: cfg.additionalFlags)
        ]
    }

    private static Map normalizeSigning(Object raw, Map imageCfg) {
        Map cfg = raw instanceof Map ? raw : [:]
        boolean enabled = cfg.enabled == null ? false : cfg.enabled as Boolean
        if (!(imageCfg.enabled as Boolean)) {
            enabled = false
        }
        return [
            enabled : enabled,
            when    : (cfg.when ?: imageCfg.push.when).toString(),
            key     : cfg.key,
            keyless : cfg.keyless ? cfg.keyless as Boolean : false,
            identity: (cfg.identity ?: (imageCfg.repository ?: '')).toString(),
            flags   : toStringList(cfg.flags),
            env     : toStringMap(cfg.env)
        ]
    }

    private static Map normalizeTerraform(Object raw) {
        Map section = raw instanceof Map ? raw : [:]
        Map envs = [:]
        if (section.environments instanceof Map) {
            (section.environments as Map).each { k, v ->
                envs[k.toString()] = normalizeTerraformEnvironment(k.toString(), v)
            }
        }

        List<String> order = []
        if (section.order instanceof List && section.order) {
            section.order.each { order << it.toString() }
        } else {
            envs.keySet().each { order << it }
        }

        boolean autoInstall = section.containsKey('autoInstall') ? section.autoInstall as Boolean : true
        String installDir = (section.installDir ?: '.ci/bin').toString()
        String version = (section.version ?: '1.6.6').toString()

        return [
            enabled     : !envs.isEmpty(),
            path        : (section.path ?: 'infra/platform/terraform').toString(),
            binary      : (section.binary ?: 'terraform').toString(),
            env         : toStringMap(section.env),
            initArgs    : toStringList(section.initArgs),
            planArgs    : toStringList(section.planArgs),
            applyArgs   : toStringList(section.applyArgs),
            backend     : toStringMap(section.backendConfig),
            order       : order,
            environments: envs,
            autoInstall : autoInstall,
            installDir  : installDir,
            version     : version
        ]
    }

    private static Map normalizeTerraformEnvironment(String name, Object raw) {
        Map data = raw instanceof Map ? raw : [:]
        Map env = [
            name        : name,
            displayName : (data.displayName ?: name.toUpperCase()).toString(),
            when        : (data.when ?: '!pr').toString(),
            vars        : toStringMap(data.vars),
            env         : toStringMap(data.env),
            backend     : toStringMap(data.backendConfig),
            path        : data.path?.toString(),
            workspace   : data.workspace?.toString(),
            planOut     : data.planOut?.toString(),
            initArgs    : toStringList(data.initArgs),
            planArgs    : toStringList(data.planArgs),
            applyArgs   : toStringList(data.applyArgs),
            varFiles    : toStringList(data.varFiles),
            autoApply   : data.containsKey('autoApply') ? data.autoApply as Boolean : true,
            apply       : data.containsKey('apply') ? data.apply as Boolean : true,
            kubeconfig  : data.kubeconfig,
            kubeconfigEnv: data.kubeconfigEnv,
            kubeconfigCredential: data.kubeconfigCredential,
            kubeContext : data.kubeContext,
            approval    : normalizeApproval(data.approval ?: data.approve),
            credentials : data.credentials instanceof List ? data.credentials : [],
            smoke       : normalizeSmoke(data.smoke)
        ]
        return env
    }

    private static Map normalizeRelease(Object raw) {
        Map section = raw instanceof Map ? raw : [:]
        Map autoTag = normalizeReleaseAutoTag(section.autoTag)
        Map github = normalizeReleaseGithub(section.github, autoTag)
        return [autoTag: autoTag, github: github]
    }

    private static Map normalizeReleaseAutoTag(Object raw) {
        Map data = raw instanceof Map ? raw : [:]
        if (data.isEmpty()) {
            return [enabled: false]
        }
        boolean enabled = data.containsKey('enabled') ? (data.enabled as Boolean) : true
        String bumpValue = (data.bump ?: 'patch').toString().toLowerCase()
        if (!(bumpValue in ['major', 'minor', 'patch'])) {
            bumpValue = 'patch'
        }

        Map credential = normalizeReleaseCredential(data)
        String afterEnvRaw = data.afterEnvironment ?: data.afterEnv
        String afterEnv = afterEnvRaw ? afterEnvRaw.toString().trim().toLowerCase() : ''

        return [
            enabled        : enabled,
            when           : (data.when ?: '!pr').toString(),
            stageName      : (data.stageName ?: 'Create Release Tag').toString(),
            prefix         : (data.prefix ?: 'v').toString(),
            bump           : bumpValue,
            remote         : (data.remote ?: 'origin').toString(),
            gitUser        : (data.gitUser ?: data.user)?.toString(),
            gitEmail       : (data.gitEmail ?: data.email)?.toString(),
            message        : data.message?.toString(),
            annotated      : data.containsKey('annotated') ? data.annotated as Boolean : (data.containsKey('annotate') ? data.annotate as Boolean : false),
            push           : data.containsKey('push') ? data.push as Boolean : true,
            skipIfTagged   : data.containsKey('skipIfTagged') ? data.skipIfTagged as Boolean : true,
            clean          : data.containsKey('clean') ? data.clean as Boolean : true,
            allowDirty     : data.containsKey('allowDirty') ? data.allowDirty as Boolean : false,
            credential     : credential,
            env            : toStringMap(data.env),
            afterEnvironment: afterEnv
        ]
    }

    private static Map normalizeReleaseGithub(Object raw, Map autoTag) {
        Map data = raw instanceof Map ? raw : [:]
        if (data.isEmpty()) {
            return [enabled: false]
        }
        boolean enabled = data.containsKey('enabled') ? data.enabled as Boolean : true
        if (!data.containsKey('credentialId') && !(data.credential instanceof Map) && autoTag?.credential?.id) {
            data = new HashMap(data)
            data.put('credential', [
                id         : autoTag.credential.id,
                type       : autoTag.credential.type,
                usernameEnv: autoTag.credential.usernameEnv,
                passwordEnv: autoTag.credential.passwordEnv,
                tokenEnv   : autoTag.credential.tokenEnv,
                tokenUser  : autoTag.credential.tokenUser
            ])
        }
        Map credential = normalizeReleaseCredential(data)

        return [
            enabled             : enabled,
            when                : (data.when ?: '!pr').toString(),
            stageName           : (data.stageName ?: 'GitHub Release').toString(),
            repo                : data.repo?.toString(),
            apiUrl              : (data.apiUrl ?: 'https://api.github.com').toString(),
            draft               : data.containsKey('draft') ? data.draft as Boolean : false,
            prerelease          : data.containsKey('prerelease') ? data.prerelease as Boolean : false,
            generateReleaseNotes: data.containsKey('generateReleaseNotes') ? data.generateReleaseNotes as Boolean : true,
            nameTemplate        : data.nameTemplate?.toString(),
            bodyTemplate        : data.bodyTemplate?.toString(),
            discussionCategory  : data.discussionCategory?.toString(),
            credential          : credential
        ]
    }

    private static Map normalizeReleaseCredential(Map data) {
        Map node = [:]
        if (data.credential instanceof Map) {
            node.putAll(data.credential as Map)
        }
        String id = (data.credentialId ?: node.id)?.toString()
        if (!id) {
            return [:]
        }
        String type = (node.type ?: data.credentialType ?: 'usernamePassword').toString()
        switch (type) {
            case 'string':
                return [
                    id      : id,
                    type    : 'string',
                    tokenEnv: (node.tokenEnv ?: data.tokenEnv ?: 'GIT_TOKEN').toString(),
                    tokenUser: (node.tokenUser ?: data.tokenUser ?: 'x-access-token').toString()
                ]
            case 'usernamePassword':
            default:
                return [
                    id          : id,
                    type        : 'usernamePassword',
                    usernameEnv : (node.usernameEnv ?: data.usernameEnv ?: data.usernameVariable ?: 'GIT_USERNAME').toString(),
                    passwordEnv : (node.passwordEnv ?: data.passwordEnv ?: data.passwordVariable ?: 'GIT_PASSWORD').toString(),
                    tokenEnv    : (node.tokenEnv ?: data.tokenEnv ?: 'GIT_TOKEN').toString(),
                    tokenUser   : (node.tokenUser ?: data.tokenUser ?: 'x-access-token').toString()
                ]
        }
    }

    private static Map normalizeDeploy(Map raw, List<String> defaultEnvOrder) {
        Map deploySection = [:]
        if (raw.deploy instanceof Map) {
            deploySection = raw.deploy
        } else if (raw.environments instanceof Map) {
            deploySection = raw.environments
        }
        Map appCfg = raw.app instanceof Map ? raw.app : [:]
        Map envs = [:]

        deploySection.each { k, v ->
            if (k == 'order') {
                return
            }
            String name = k.toString()
            Map envCfg = v instanceof Map ? v : [:]
            envs[name] = normalizeEnvironment(name, envCfg, appCfg)
        }

        List<String> order = []
        if (deploySection.order instanceof List && deploySection.order) {
            deploySection.order.each { order << it.toString() }
        } else {
            defaultEnvOrder.each { def candidate ->
                if (envs.containsKey(candidate) && !order.contains(candidate)) {
                    order << candidate
                }
            }
            envs.keySet().each { name ->
                if (!order.contains(name)) {
                    order << name
                }
            }
        }

        return [
            order       : order,
            environments: envs
        ]
    }

    private static Map normalizeEnvironment(String name, Map envCfg, Map appCfg) {
        Map smoke = normalizeSmoke(envCfg.smoke)
        Map approval = normalizeApproval(envCfg.approve ?: envCfg.approval)

        String display = (envCfg.displayName ?: name.toUpperCase()).toString()
        String namespace = (envCfg.namespace ?: appCfg.namespace ?: 'apps').toString()
        String release = (envCfg.release ?: appCfg.release ?: appCfg.name ?: name).toString()
        String chart = (envCfg.chart ?: appCfg.chart ?: 'infra/charts/app-chart').toString()

        Map repoCredsRaw = envCfg.repoCredentials instanceof Map ? envCfg.repoCredentials : [:]
        String repoCredentialId = (
            envCfg.repoCredentialId ? envCfg.repoCredentialId :
            envCfg.repoCredential ? envCfg.repoCredential :
            repoCredsRaw.id
        )?.toString()

        Map repoCredentials = [:]
        if (repoCredentialId) {
            String repoUserEnv = (repoCredsRaw.usernameEnv ?: repoCredsRaw.usernameVariable ?: 'HELM_REPO_USERNAME').toString()
            String repoPassEnv = (repoCredsRaw.passwordEnv ?: repoCredsRaw.passwordVariable ?: 'HELM_REPO_PASSWORD').toString()
            repoCredentials = [
                id         : repoCredentialId,
                usernameEnv: repoUserEnv,
                passwordEnv: repoPassEnv
            ]
        }

        return [
            name        : name,
            displayName : display,
            namespace   : namespace,
            release     : release,
            chart       : chart,
            repo        : envCfg.repo ?: appCfg.repo,
            chartVersion: envCfg.version ?: envCfg.chartVersion ?: appCfg.chartVersion,
            valuesFiles : toStringList(envCfg.values ?: envCfg.valuesFiles),
            set         : toStringMap(envCfg.set),
            setString   : toStringMap(envCfg.setString),
            setFile     : toStringMap(envCfg.setFile),
            when        : (envCfg.when ?: defaultConditionFor(name)).toString(),
            smoke       : smoke,
            autoPromote : envCfg.autoPromote ? envCfg.autoPromote as Boolean : false,
            approval    : approval,
            kubeContext : envCfg.context ?: envCfg.kubeContext,
            kubeconfig  : envCfg.kubeconfig,
            repoUsername: envCfg.repoUsername ?: appCfg.repoUsername,
            repoPassword: envCfg.repoPassword ?: appCfg.repoPassword,
            repoCredentials: repoCredentials,
            wait        : envCfg.wait == null ? true : envCfg.wait as Boolean,
            atomic      : envCfg.atomic == null ? true : envCfg.atomic as Boolean,
            timeout     : (envCfg.timeout ?: appCfg.timeout ?: '10m').toString()
        ]
    }

    private static String defaultConditionFor(String envName) {
        switch (envName) {
            case 'dev':
                return 'branch=main & !pr'
            case 'stg':
            case 'stage':
            case 'staging':
                return 'branch=main & !pr'
            case 'prd':
            case 'prod':
            case 'production':
                return 'tag=^v[0-9].*'
            default:
                return '!pr'
        }
    }

    private static Map normalizeSmoke(Object raw) {
        if (!raw) return [:]
        if (raw instanceof Map) {
            Map cfg = raw as Map
            Map result = [:]
            if (cfg.url) result.url = cfg.url.toString()
            if (cfg.script) result.script = cfg.script.toString()
            if (cfg.command) result.command = cfg.command.toString()
            if (cfg.cmd) result.command = cfg.cmd.toString()
            result.retries = (cfg.retries ?: cfg.retry ?: 0) as Integer
            result.delay = (cfg.delay ?: 5).toString()
            result.timeout = (cfg.timeout ?: cfg.maxTime ?: '60s').toString()
            return result
        }
        String value = raw.toString()
        if (value.startsWith('http')) {
            return [url: value, retries: 0, delay: '5', timeout: '60s']
        }
        return [script: value, retries: 0, delay: '5', timeout: '60s']
    }

    private static Map normalizeApproval(Object raw) {
        if (!raw) return [:]
        if (raw instanceof Map) {
            Map cfg = raw as Map
            boolean before = false
            if (cfg.containsKey('before')) {
                before = cfg.before as Boolean
            } else {
                String timing = (cfg.timing ?: cfg.when ?: '').toString().trim().toLowerCase()
                before = timing in ['pre', 'before', 'deploy']
            }
            return [
                message  : (cfg.message ?: 'Approval required').toString(),
                submitter: cfg.submitter ?: cfg.user ?: cfg.users,
                ok       : (cfg.ok ?: 'Approve').toString(),
                before   : before
            ]
        }
        String value = raw.toString()
        String submitter = null
        if (value.startsWith('user:')) {
            submitter = value.substring('user:'.length())
        }
        return [
            message  : "Approval required (${value})",
            submitter: submitter,
            ok       : 'Approve',
            before   : false
        ]
    }

    private static List<Map> normalizeUserStages(Object raw) {
        if (!(raw instanceof List)) {
            return []
        }
        List<Map> stages = []
        (raw as List).each { Object entry ->
            if (entry instanceof Map) {
                Map stageCfg = entry as Map
                stages << [
                    name       : stageCfg.name?.toString() ?: 'Release Task',
                    when       : stageCfg.when?.toString(),
                    env        : toStringMap(stageCfg.env),
                    verb       : stageCfg.verb?.toString(),
                    sh         : stageCfg.sh?.toString(),
                    credentials: stageCfg.credentials instanceof List ? stageCfg.credentials : []
                ]
            }
        }
        return stages
    }
}
