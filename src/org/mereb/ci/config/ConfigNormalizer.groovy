package org.mereb.ci.config

import org.mereb.ci.build.PnpmPreset
import java.util.ArrayList

import static org.mereb.ci.util.PipelineUtils.toStringList
import static org.mereb.ci.util.PipelineUtils.toStringMap

/**
 * Normalizes the YAML configuration consumed by the Jenkins shared library so it is predictable.
 */
class ConfigNormalizer implements Serializable {

    static Map normalize(Map raw, List<String> defaultEnvOrder, String primaryConfig) {
        Map source = mapCopy(raw)
        Map cfg = [:]

        Map agentSection = mapCopy(source.get('agent'))
        cfg.agent = [
            label : asString(agentSection.get('label')).trim(),
            docker: asString(agentSection.get('docker')).trim()
        ]

        Map buildSection = mapCopy(source.get('build'))
        Object pnpmSection = source.get('pnpm')
        if (!buildSection.containsKey('pnpm') && pnpmSection != null) {
            buildSection.put('pnpm', pnpmSection)
        }

        String preset = asString(source.get('preset'))
        if (!preset) {
            preset = asString(buildSection.get('preset'))
        }
        if (!preset) {
            preset = 'node'
        }
        cfg.preset = preset

        if (!buildSection.containsKey('pnpm') && 'pnpm'.equalsIgnoreCase(preset)) {
            buildSection.put('pnpm', [:])
        }

        cfg.matrix = normalizeMatrix(source.get('matrix'))
        cfg.buildStages = computeBuildStages(preset, buildSection)
        cfg.requiresGradleHome = needsGradleHome(preset, cfg.buildStages)
        cfg.releaseStages = normalizeUserStages(source.get('releaseStages'))
        cfg.image = normalizeImage(source, primaryConfig)
        cfg.sbom = normalizeSbom(source.get('sbom'))
        cfg.scan = normalizeScan(source.get('scan'))
        cfg.signing = normalizeSigning(source.get('signing'), cfg.image)
        cfg.terraform = normalizeTerraform(source.get('terraform'))
        cfg.release = normalizeRelease(source.get('release'))
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
        Map matrix = raw as Map
        for (Object entryObj : matrix.entrySet()) {
            Map.Entry entry = entryObj as Map.Entry
            Object key = entry.key
            Object value = entry.value
            List<String> entries = []
            if (value instanceof List) {
                for (Object item : (List) value) {
                    if (item != null) {
                        entries << item.toString()
                    }
                }
            } else if (value != null) {
                entries << value.toString()
            }
            result.put(key?.toString(), entries)
        }
        return result
    }

    private static List<Map> computeBuildStages(String preset, Object buildCfgRaw) {
        Map buildCfg = mapCopy(buildCfgRaw)
        Object stagesRaw = buildCfg.get('stages')
        if (stagesRaw instanceof List && !((List) stagesRaw).isEmpty()) {
            List<Map> result = []
            for (Object stageObj : (List) stagesRaw) {
                if (!(stageObj instanceof Map)) {
                    continue
                }
                Map stage = stageObj as Map
                String stageName = asString(stage.get('name'))
                if (!stageName) {
                    stageName = 'Build'
                }
                String verb = asString(stage.get('verb'))
                if (!verb) {
                    verb = null
                }
                String script = asString(stage.get('sh'))
                if (!script) {
                    script = null
                }
                Map normalized = [
                    name       : stageName,
                    verb       : verb,
                    sh         : script,
                    env        : toStringMap(stage.get('env')),
                    credentials: normalizeCredentialList(stage.get('credentials'))
                ]
                result << normalized
            }
            return result
        }
        Object pnpmCfg = buildCfg.get('pnpm')
        if (pnpmCfg) {
            return PnpmPreset.buildStages(pnpmCfg)
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
        for (Object node : (List) raw) {
            if (!(node instanceof Map)) {
                continue
            }
            Map cred = node as Map
            Map normalized = [
                id  : asString(cred.get('id')),
                env : asString(cred.get('env')),
                type: asString(cred.get('type') ?: 'string')
            ]
            Object usernameEnv = cred.get('usernameEnv') ?: cred.get('usernameVariable')
            if (usernameEnv) {
                normalized.usernameEnv = usernameEnv.toString()
            }
            Object passwordEnv = cred.get('passwordEnv') ?: cred.get('passwordVariable')
            if (passwordEnv) {
                normalized.passwordEnv = passwordEnv.toString()
            }
            creds << normalized
        }
        return creds
    }

    private static Map normalizeImage(Map raw, String primaryConfig) {
        Map source = mapCopy(raw)
        Map app = mapCopy(source.get('app'))
        Object imageSection = source.get('image')
        Map imageRaw = imageSection instanceof Map ? mapCopy(imageSection) : [:]

        boolean enabled = true
        if (imageSection instanceof Boolean) {
            enabled = imageSection as Boolean
        } else if (imageRaw.containsKey('enabled')) {
            enabled = imageRaw.enabled as Boolean
        }

        Map pushRaw = imageRaw.get('push') instanceof Map ? mapCopy(imageRaw.get('push')) : [:]

        if (!enabled) {
            return [
                enabled    : false,
                repository : '',
                context    : asString(imageRaw.get('context') ?: '.'),
                dockerfile : asString(imageRaw.get('dockerfile') ?: 'Dockerfile'),
                buildArgs  : toStringMap(imageRaw.get('buildArgs')),
                buildFlags : toStringList(imageRaw.get('buildFlags')),
                platforms  : toStringList(imageRaw.get('platforms')),
                tagStrategy: asString(imageRaw.get('tagStrategy') ?: 'branch-sha'),
                tagTemplate: imageRaw.get('tagTemplate') ?: imageRaw.get('tag'),
                push       : [
                    enabled  : false,
                    when     : (pushRaw.when ?: imageRaw.pushWhen ?: '!pr').toString(),
                    extraTags: toStringList(pushRaw.extraTags ?: imageRaw.extraTags)
                ]
            ]
        }

        String repository = stripRegistryScheme(asString(imageRaw.get('repository')).trim())
        if (!repository) {
            String imageName = asString(imageRaw.get('name') ?: app.get('image') ?: app.get('name')).trim()
            if (!imageName) {
                throw new IllegalArgumentException("image.repository or app.name must be defined in ${primaryConfig}")
            }
            String registry = stripRegistryScheme(asString(imageRaw.get('registry') ?: app.get('registry')).trim())
            if (registry) {
                String cleaned = registry.replaceAll(/\/+$/, '')
                repository = cleaned ? "${cleaned}/${imageName}" : imageName
            } else {
                repository = imageName
            }
        }

        String registryOverride = stripRegistryScheme(asString(imageRaw.get('registry')).trim())
        if (registryOverride) {
            registryOverride = registryOverride.replaceAll(/\/+$/, '')
        }
        String derivedRegistryHost = deriveRegistryHost(repository)
        if (registryOverride) {
            derivedRegistryHost = registryOverride
        }

        Map imageCfg = [
            enabled      : true,
            repository   : repository,
            registryHost : derivedRegistryHost,
            context      : asString(imageRaw.get('context') ?: '.'),
            dockerfile   : asString(imageRaw.get('dockerfile') ?: 'Dockerfile'),
            buildArgs    : toStringMap(imageRaw.get('buildArgs')),
            buildFlags   : toStringList(imageRaw.get('buildFlags')),
            platforms    : toStringList(imageRaw.get('platforms')),
            tagStrategy  : asString(imageRaw.get('tagStrategy') ?: 'branch-sha'),
            tagTemplate  : imageRaw.get('tagTemplate') ?: imageRaw.get('tag')
        ]

        imageCfg.push = [
            enabled  : pushRaw.get('enabled') == null ? true : (pushRaw.get('enabled') as Boolean),
            when     : asString(pushRaw.get('when') ?: imageRaw.get('pushWhen') ?: '!pr'),
            extraTags: toStringList(pushRaw.get('extraTags') ?: imageRaw.get('extraTags')),
            registry : asString(pushRaw.get('registry') ?: '').trim()
        ]

        String pushCredentialId = asString(
            pushRaw.get('credentialsId') ?: pushRaw.get('credentialId') ?: pushRaw.get('credential') ?: pushRaw.get('id')
        ).trim()
        if (pushCredentialId) {
            imageCfg.push.credentials = [
                id         : pushCredentialId,
                usernameEnv: (pushRaw.usernameEnv ?: pushRaw.usernameVariable ?: 'DOCKER_USERNAME').toString(),
                passwordEnv: (pushRaw.passwordEnv ?: pushRaw.passwordVariable ?: 'DOCKER_PASSWORD').toString()
            ]
        }

        return imageCfg
    }

    private static String stripRegistryScheme(String value) {
        if (!value) {
            return ''
        }
        return value.replaceFirst('^https?://', '')
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
        Map cfg = mapCopy(raw)
        boolean enabled = cfg.get('enabled') == null ? true : (cfg.get('enabled') as Boolean)
        return [
            enabled: enabled,
            format : asString(cfg.get('format') ?: 'cyclonedx-json'),
            output : asString(cfg.get('output') ?: 'reports/sbom-{{imageTag}}.json')
        ]
    }

    private static Map normalizeScan(Object raw) {
        Map cfg = mapCopy(raw)
        boolean enabled = cfg.get('enabled') == null ? true : (cfg.get('enabled') as Boolean)
        return [
            enabled: enabled,
            failOn : asString(cfg.get('failOn') ?: 'critical'),
            flags  : toStringList(cfg.get('flags') ?: cfg.get('additionalFlags'))
        ]
    }

    private static Map normalizeSigning(Object raw, Map imageCfg) {
        Map cfg = mapCopy(raw)
        boolean enabled = cfg.get('enabled') == null ? false : (cfg.get('enabled') as Boolean)
        if (!(imageCfg.enabled as Boolean)) {
            enabled = false
        }
        return [
            enabled : enabled,
            when    : asString(cfg.get('when') ?: imageCfg.push.when),
            key     : cfg.get('key'),
            keyless : cfg.get('keyless') ? (cfg.get('keyless') as Boolean) : false,
            identity: asString(cfg.get('identity') ?: (imageCfg.repository ?: '')),
            flags   : toStringList(cfg.get('flags')),
            env     : toStringMap(cfg.get('env'))
        ]
    }

    private static Map normalizeTerraform(Object raw) {
        Map section = mapCopy(raw)
        Map envs = [:]
        Object envNode = section.get('environments')
        if (envNode instanceof Map) {
            Map envMap = envNode as Map
            for (Object entryObj : envMap.entrySet()) {
                Map.Entry entry = entryObj as Map.Entry
                String key = entry.key?.toString()
                envs.put(key, normalizeTerraformEnvironment(key, entry.value))
            }
        }

        List<String> order = []
        Object orderNode = section.get('order')
        if (orderNode instanceof List && !((List) orderNode).isEmpty()) {
            for (Object item : (List) orderNode) {
                if (item != null) {
                    order << item.toString()
                }
            }
        } else {
            for (String name : envs.keySet()) {
                order << name
            }
        }

        boolean autoInstall = section.containsKey('autoInstall') ? (section.get('autoInstall') as Boolean) : true
        String installDir = asString(section.get('installDir') ?: '.ci/bin')
        String version = asString(section.get('version') ?: '1.6.6')

        return [
            enabled     : !envs.isEmpty(),
            path        : asString(section.get('path') ?: 'infra/platform/terraform'),
            binary      : asString(section.get('binary') ?: 'terraform'),
            env         : toStringMap(section.get('env')),
            initArgs    : toStringList(section.get('initArgs')),
            planArgs    : toStringList(section.get('planArgs')),
            applyArgs   : toStringList(section.get('applyArgs')),
            backend     : toStringMap(section.get('backendConfig')),
            order       : order,
            environments: envs,
            autoInstall : autoInstall,
            installDir  : installDir,
            version     : version
        ]
    }

    private static Map normalizeTerraformEnvironment(String name, Object raw) {
        Map data = mapCopy(raw)
        Map env = [
            name        : name,
            displayName : asString(data.get('displayName') ?: name.toUpperCase()),
            when        : asString(data.get('when') ?: '!pr'),
            vars        : toStringMap(data.get('vars')),
            env         : toStringMap(data.get('env')),
            backend     : toStringMap(data.get('backendConfig')),
            path        : data.get('path') != null ? data.get('path').toString() : null,
            workspace   : data.get('workspace') != null ? data.get('workspace').toString() : null,
            planOut     : data.get('planOut') != null ? data.get('planOut').toString() : null,
            initArgs    : toStringList(data.get('initArgs')),
            planArgs    : toStringList(data.get('planArgs')),
            applyArgs   : toStringList(data.get('applyArgs')),
            varFiles    : toStringList(data.get('varFiles')),
            autoApply   : data.containsKey('autoApply') ? (data.get('autoApply') as Boolean) : true,
            apply       : data.containsKey('apply') ? (data.get('apply') as Boolean) : true,
            kubeconfig  : data.get('kubeconfig'),
            kubeconfigEnv: data.get('kubeconfigEnv'),
            kubeconfigCredential: data.get('kubeconfigCredential'),
            kubeContext : data.get('kubeContext'),
            approval    : normalizeApproval(data.get('approval') ?: data.get('approve')),
            credentials : data.get('credentials') instanceof List ? data.get('credentials') : [],
            smoke       : normalizeSmoke(data.get('smoke'))
        ]
        return env
    }

    private static Map normalizeRelease(Object raw) {
        Map section = mapCopy(raw)
        Map autoTag = normalizeReleaseAutoTag(section.get('autoTag'))
        Map github = normalizeReleaseGithub(section.get('github'), autoTag)
        return [autoTag: autoTag, github: github]
    }

    private static Map normalizeReleaseAutoTag(Object raw) {
        Map data = mapCopy(raw)
        if (data.isEmpty()) {
            return [enabled: false]
        }
        boolean enabled = data.containsKey('enabled') ? (data.get('enabled') as Boolean) : true
        String bumpValue = asString(data.get('bump') ?: 'patch').toLowerCase()
        if (!(bumpValue in ['major', 'minor', 'patch'])) {
            bumpValue = 'patch'
        }

        Map credential = normalizeReleaseCredential(data)
        String afterEnvRaw = data.get('afterEnvironment') ?: data.get('afterEnv')
        String afterEnv = afterEnvRaw ? afterEnvRaw.toString().trim().toLowerCase() : ''

        return [
            enabled        : enabled,
            when           : asString(data.get('when') ?: '!pr'),
            stageName      : asString(data.get('stageName') ?: 'Create Release Tag'),
            prefix         : asString(data.get('prefix') ?: 'v'),
            bump           : bumpValue,
            remote         : asString(data.get('remote') ?: 'origin'),
            gitUser        : asString(data.get('gitUser') ?: data.get('user')) ?: null,
            gitEmail       : asString(data.get('gitEmail') ?: data.get('email')) ?: null,
            message        : data.get('message') != null ? data.get('message').toString() : null,
            annotated      : data.containsKey('annotated') ? (data.get('annotated') as Boolean) :
                (data.containsKey('annotate') ? (data.get('annotate') as Boolean) : false),
            push           : data.containsKey('push') ? (data.get('push') as Boolean) : true,
            skipIfTagged   : data.containsKey('skipIfTagged') ? (data.get('skipIfTagged') as Boolean) : true,
            clean          : data.containsKey('clean') ? (data.get('clean') as Boolean) : true,
            allowDirty     : data.containsKey('allowDirty') ? (data.get('allowDirty') as Boolean) : false,
            credential     : credential,
            approval       : normalizeApproval(data.get('approval')),
            env            : toStringMap(data.get('env')),
            afterEnvironment: afterEnv
        ]
    }

    private static Map normalizeReleaseGithub(Object raw, Map autoTag) {
        Map data = mapCopy(raw)
        if (data.isEmpty()) {
            return [enabled: false]
        }
        boolean enabled = data.containsKey('enabled') ? (data.get('enabled') as Boolean) : true
        Map autoTagCredential = (autoTag != null && autoTag.get('credential') instanceof Map) ? (Map) autoTag.get('credential') : null
        Object autoTagCredentialId = autoTagCredential?.get('id')
        if (!data.containsKey('credentialId') && !(data.get('credential') instanceof Map) && autoTagCredentialId) {
            Map credentialClone = [:]
            credentialClone.id = autoTagCredential.get('id')
            credentialClone.type = autoTagCredential.get('type')
            credentialClone.usernameEnv = autoTagCredential.get('usernameEnv')
            credentialClone.passwordEnv = autoTagCredential.get('passwordEnv')
            credentialClone.tokenEnv = autoTagCredential.get('tokenEnv')
            credentialClone.tokenUser = autoTagCredential.get('tokenUser')
            data.put('credential', credentialClone)
        }
        Map credential = normalizeReleaseCredential(data)

        return [
            enabled             : enabled,
            when                : asString(data.get('when') ?: '!pr'),
            stageName           : asString(data.get('stageName') ?: 'GitHub Release'),
            repo                : data.get('repo')?.toString(),
            apiUrl              : asString(data.get('apiUrl') ?: 'https://api.github.com'),
            draft               : data.containsKey('draft') ? (data.get('draft') as Boolean) : false,
            prerelease          : data.containsKey('prerelease') ? (data.get('prerelease') as Boolean) : false,
            generateReleaseNotes: data.containsKey('generateReleaseNotes') ? (data.get('generateReleaseNotes') as Boolean) : true,
            nameTemplate        : data.get('nameTemplate')?.toString(),
            bodyTemplate        : data.get('bodyTemplate')?.toString(),
            discussionCategory  : data.get('discussionCategory')?.toString(),
            credential          : credential
        ]
    }

    private static Map normalizeReleaseCredential(Map dataInput) {
        Map source = mapCopy(dataInput)
        Map node = [:]
        if (source.get('credential') instanceof Map) {
            node.putAll(source.get('credential') as Map)
        }
        String id = asString(source.get('credentialId') ?: node.get('id'))
        if (!id) {
            return [:]
        }
        String rawType = asString(node.get('type') ?: source.get('credentialType') ?: 'usernamePassword')
        String normalized = rawType?.trim()?.toLowerCase()
        if ('string'.equals(normalized)) {
            return [
                id      : id,
                type    : 'string',
                tokenEnv: asString(node.get('tokenEnv') ?: source.get('tokenEnv') ?: 'GIT_TOKEN'),
                tokenUser: asString(node.get('tokenUser') ?: source.get('tokenUser') ?: 'x-access-token')
            ]
        }
        return [
            id          : id,
            type        : 'usernamePassword',
            usernameEnv : asString(node.get('usernameEnv') ?: source.get('usernameEnv') ?: source.get('usernameVariable') ?: 'GIT_USERNAME'),
            passwordEnv : asString(node.get('passwordEnv') ?: source.get('passwordEnv') ?: source.get('passwordVariable') ?: 'GIT_PASSWORD'),
            tokenEnv    : asString(node.get('tokenEnv') ?: source.get('tokenEnv') ?: 'GIT_TOKEN'),
            tokenUser   : asString(node.get('tokenUser') ?: source.get('tokenUser') ?: 'x-access-token')
        ]
    }

    private static Map normalizeDeploy(Map raw, List<String> defaultEnvOrder) {
        Map source = mapCopy(raw)
        Map deploySection = [:]
        if (source.get('deploy') instanceof Map) {
            deploySection = mapCopy(source.get('deploy'))
        } else if (source.get('environments') instanceof Map) {
            deploySection = mapCopy(source.get('environments'))
        }
        Map appCfg = mapCopy(source.get('app'))
        Map envs = [:]

        for (Object entryObj : deploySection.entrySet()) {
            Map.Entry entry = entryObj as Map.Entry
            Object key = entry.key
            if ('order'.equals(key)) {
                continue
            }
            Map envCfg = mapCopy(entry.value)
            String name = asString(key)
            envs.put(name, normalizeEnvironment(name, envCfg, appCfg))
        }

        List<String> order = []
        Object orderNode = deploySection.get('order')
        if (orderNode instanceof List && !((List) orderNode).isEmpty()) {
            for (Object item : (List) orderNode) {
                if (item != null) {
                    order << item.toString()
                }
            }
        } else {
            for (String candidate : defaultEnvOrder) {
                if (envs.containsKey(candidate) && !order.contains(candidate)) {
                    order << candidate
                }
            }
            for (String name : envs.keySet()) {
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

    private static Map normalizeEnvironment(String name, Map envCfgRaw, Map appCfgRaw) {
        Map envCfg = mapCopy(envCfgRaw)
        Map appCfg = mapCopy(appCfgRaw)
        Map smoke = normalizeSmoke(envCfg.get('smoke'))
        Map approval = normalizeApproval(envCfg.get('approve') ?: envCfg.get('approval'))
        Map vaultCfg = mapCopy(envCfg.get('vault'))

        String display = asString(envCfg.get('displayName'))
        if (!display) {
            display = name.toUpperCase()
        }
        String namespace = asString(envCfg.get('namespace') ?: appCfg.get('namespace') ?: 'apps')
        String release = asString(envCfg.get('release') ?: appCfg.get('release') ?: appCfg.get('name') ?: name)
        String chart = asString(envCfg.get('chart') ?: appCfg.get('chart') ?: 'infra/charts/app-chart')

        Map repoCredsRaw = envCfg.get('repoCredentials') instanceof Map ? mapCopy(envCfg.get('repoCredentials')) : [:]
        String repoCredentialId = asString(
            envCfg.get('repoCredentialId') ?: envCfg.get('repoCredential') ?: repoCredsRaw.get('id')
        )
        if (repoCredentialId && repoCredentialId.trim().isEmpty()) {
            repoCredentialId = null
        }

        Map repoCredentials = [:]
        if (repoCredentialId) {
            String repoUserEnv = asString(repoCredsRaw.get('usernameEnv') ?: repoCredsRaw.get('usernameVariable') ?: 'HELM_REPO_USERNAME')
            String repoPassEnv = asString(repoCredsRaw.get('passwordEnv') ?: repoCredsRaw.get('passwordVariable') ?: 'HELM_REPO_PASSWORD')
            repoCredentials = [
                id         : repoCredentialId,
                usernameEnv: repoUserEnv,
                passwordEnv: repoPassEnv
            ]
        }

        List<String> valuesFiles = []
        Object valuesNode = envCfg.get('valuesFiles') ?: envCfg.get('values')
        if (valuesNode instanceof List) {
            for (Object item : (List) valuesNode) {
                if (item != null) {
                    valuesFiles << item.toString()
                }
            }
        }

        List<Map> valuesTemplates = normalizeValuesTemplates(envCfg.get('valuesTemplates'))

        Map result = [
            name           : name,
            displayName    : display,
            namespace      : namespace,
            release        : release,
            chart          : chart,
            repo           : envCfg.get('repo') ?: appCfg.get('repo'),
            chartVersion   : envCfg.get('version') ?: envCfg.get('chartVersion') ?: appCfg.get('chartVersion'),
            valuesFiles    : valuesFiles,
            set            : toStringMap(envCfg.get('set')),
            setString      : toStringMap(envCfg.get('setString')),
            setFile        : toStringMap(envCfg.get('setFile')),
            when           : asString(envCfg.get('when') ?: defaultConditionFor(name)),
            smoke          : smoke,
            autoPromote    : envCfg.get('autoPromote') ? (envCfg.get('autoPromote') as Boolean) : false,
            approval       : approval,
            kubeContext    : envCfg.get('context') ?: envCfg.get('kubeContext'),
            kubeconfig     : envCfg.get('kubeconfig'),
            repoUsername   : envCfg.get('repoUsername') ?: appCfg.get('repoUsername'),
            repoPassword   : envCfg.get('repoPassword') ?: appCfg.get('repoPassword'),
            repoCredentials: repoCredentials,
            wait           : envCfg.get('wait') == null ? true : (envCfg.get('wait') as Boolean),
            atomic         : envCfg.get('atomic') == null ? true : (envCfg.get('atomic') as Boolean),
            timeout        : asString(envCfg.get('timeout') ?: appCfg.get('timeout') ?: '10m'),
            valuesTemplates: valuesTemplates,
            credentials    : envCfg.get('credentials') instanceof List ? envCfg.get('credentials') : [],
            vault          : vaultCfg
        ]

        return result
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

    private static List<Map> normalizeValuesTemplates(Object raw) {
        if (!(raw instanceof List)) {
            return []
        }
        List<Map> templates = []
        for (Object entry : (List) raw) {
            if (!(entry instanceof Map)) {
                continue
            }
            Map data = mapCopy(entry as Map)
            String templatePath = asString(data.get('template') ?: data.get('path'))
            if (!templatePath) {
                continue
            }
            String outputPath = asString(data.get('output') ?: data.get('destination'))
            Map vars = data.get('vars') instanceof Map ? mapCopy(data.get('vars')) : [:]
            Map vault = data.get('vault') instanceof Map ? mapCopy(data.get('vault')) : [:]
            templates << [
                template: templatePath,
                output  : outputPath,
                vars    : vars,
                vault   : vault
            ]
        }
        return templates
    }

    private static Map normalizeSmoke(Object raw) {
        if (!raw) return [:]
        if (raw instanceof Map) {
            Map cfg = mapCopy(raw)
            Map result = [:]
            if (cfg.get('url')) result.url = cfg.get('url').toString()
            if (cfg.get('script')) result.script = cfg.get('script').toString()
            if (cfg.get('command')) result.command = cfg.get('command').toString()
            if (cfg.get('cmd')) result.command = cfg.get('cmd').toString()
            Object retries = cfg.get('retries') ?: cfg.get('retry') ?: 0
            result.retries = (retries ?: 0) as Integer
            result.delay = asString(cfg.get('delay') ?: 5)
            result.timeout = asString(cfg.get('timeout') ?: cfg.get('maxTime') ?: '60s')
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
            Map cfg = mapCopy(raw)
            boolean beforeExplicit = cfg.containsKey('before')
            boolean before = false
            if (beforeExplicit) {
                before = cfg.get('before') as Boolean
            } else {
                String timing = asString(cfg.get('timing') ?: cfg.get('when')).trim().toLowerCase()
                before = ['pre', 'before', 'deploy'].contains(timing)
            }
            return [
                message  : asString(cfg.get('message') ?: 'Approval required'),
                submitter: cfg.get('submitter') ?: cfg.get('user') ?: cfg.get('users'),
                ok       : asString(cfg.get('ok') ?: 'Approve'),
                before   : before,
                beforeExplicit: beforeExplicit
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
            before   : false,
            beforeExplicit: false
        ]
    }

    private static List<Map> normalizeUserStages(Object raw) {
        if (!(raw instanceof List)) {
            return []
        }
        List<Map> stages = []
        for (Object entry : (List) raw) {
            if (!(entry instanceof Map)) {
                continue
            }
            Map stageCfg = mapCopy(entry)
            Map normalized = [
                name       : asString(stageCfg.get('name')) ?: 'Release Task',
                when       : asString(stageCfg.get('when')) ?: null,
                env        : toStringMap(stageCfg.get('env')),
                verb       : asString(stageCfg.get('verb')) ?: null,
                sh         : asString(stageCfg.get('sh')) ?: null,
                credentials: stageCfg.get('credentials') instanceof List ? stageCfg.get('credentials') : []
            ]
            stages << normalized
        }
        return stages
    }

    private static Map mapCopy(Object candidate) {
        if (candidate instanceof Map) {
            Map copy = [:]
            copy.putAll(candidate as Map)
            return copy
        }
        return [:]
    }

    private static List listCopy(Object candidate) {
        if (candidate instanceof List) {
            return new ArrayList(candidate as List)
        }
        return []
    }

    private static String asString(Object value) {
        return value == null ? '' : value.toString()
    }
}
