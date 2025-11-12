import groovy.transform.Field
import groovy.json.JsonOutput
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.net.URI
import java.net.URLEncoder
import java.util.LinkedHashMap

import org._hidmo.Helpers

@Field final String PRIMARY_CONFIG = '.ci/ci.yml'
@Field final String LEGACY_CONFIG  = 'ci.yml'
@Field final List<String> DEFAULT_ENV_ORDER = ['dev', 'stg', 'prd', 'prod']

def call(Map args = [:]) {
    Map rawCfg = [:]
    List<String> baseEnv = []
    String configPath = (args?.configPath ?: '').toString().trim()

    node(args?.bootstrapLabel ?: '') {
        checkout scm
        if (configPath) {
            if (!fileExists(configPath)) {
                error "Pipeline configuration '${configPath}' not found."
            }
        } else {
            configPath = locateConfig()
            if (!configPath) {
                error "Pipeline configuration not found. Add ${PRIMARY_CONFIG}"
            }
        }
        rawCfg = readYaml(file: configPath) ?: [:]
        int version = (rawCfg.version ?: 1) as Integer
        if (version != 1) {
            error "Pipeline config version must be 1 (found ${version})"
        }
        baseEnv = (rawCfg.env ?: [:]).collect { k, v -> "${k}=${v}" }
    }

    Map cfg = normalizeConfig(rawCfg)
    Map agent = cfg.agent

    def runCore = {
        final String ws = pwd()
        if (!ws?.trim()) {
            error "Workspace path is empty"
        }

        final Map<String, String> state = [:]
        state.commit = resolveCommitSha()
        state.commitShort = state.commit.take(12)
        state.branch = env.BRANCH_NAME ?: ''
        state.branchSanitized = sanitizeBranch(state.branch)
        state.buildNumber = (env.BUILD_NUMBER ?: '').toString()
        state.tagName = env.TAG_NAME ?: ''
        state.repository = ''
        state.imageTag = ''
        state.imageRef = ''

        if ((env.TAG_NAME ?: '').trim()) {
            env.RELEASE_TAG = env.TAG_NAME
        }

        if (cfg.image.enabled) {
            state.repository = cfg.image.repository
            state.imageTag = resolveImageTag(cfg.image, state)
            state.imageRef = "${cfg.image.repository}:${state.imageTag}"
        }

        List<String> exportedEnv = []
        exportedEnv.addAll(baseEnv)
        exportedEnv << "HOME=${determineHome(ws)}"
        exportedEnv << "GRADLE_USER_HOME=${ws}/.gradle"
        if (cfg.image.enabled) {
            exportedEnv << "IMAGE_REPOSITORY=${cfg.image.repository}"
            exportedEnv << "IMAGE_TAG=${state.imageTag}"
            exportedEnv << "IMAGE_REF=${state.imageRef}"
        }

        try {
            sh "mkdir -p '${ws}/.gradle'"

            withEnv(exportedEnv) {
                checkout scm
                sh 'chmod +x ./gradlew || true'

                runBuildStages(cfg.buildStages)
                runMatrix(cfg.matrix)

                if (cfg.image.enabled) {
                    stage('Docker Build') {
                        dockerBuild(cfg.image, state)
                    }

                    if (shouldGenerateSbom(cfg)) {
                        stage('SBOM') {
                            generateSbom(cfg, state)
                        }
                    }

                    if (shouldScan(cfg)) {
                        stage('Vulnerability Scan') {
                            scanImage(cfg, state)
                        }
                    }

                    if (shouldPush(cfg)) {
                        stage('Docker Push') {
                            dockerPush(cfg, state)
                        }
                    } else {
                        echo "Docker push skipped by condition '${cfg.image.push.when}'"
                    }

                    if (shouldSign(cfg)) {
                        stage('Sign Image') {
                            signImage(cfg, state)
                        }
                    }
                } else {
                    echo "Docker stages disabled for this pipeline; skipping build/push/sign."
                }

                List<String> deferredTerraform = runTerraform(cfg.terraform, null, true)
                boolean deferredTerraformRan = false
                Closure runDeferredTerraform = {
                    if (deferredTerraformRan) {
                        return
                    }
                    if (env.RELEASE_TAG?.trim() && deferredTerraform && !deferredTerraform.isEmpty()) {
                        env.TAG_NAME = env.RELEASE_TAG
                        echo "Running deferred Terraform environments after creating tag ${env.RELEASE_TAG}"
                        runTerraform(cfg.terraform, deferredTerraform, false)
                        deferredTerraformRan = true
                    }
                }

                Map autoTagCfg = cfg.release?.autoTag ?: [:]
                boolean autoTagEnabled = autoTagCfg.enabled as Boolean
                String releaseAfterEnv = (autoTagCfg.afterEnvironment ?: '').toString().trim().toLowerCase()
                boolean releaseFlowAttempted = false
                boolean releaseStagesRan = false

                Closure triggerAutoTag = {
                    if (releaseFlowAttempted || !autoTagEnabled) {
                        return
                    }
                    releaseFlowAttempted = true
                    handleRelease(cfg.release, state)
                    runDeferredTerraform()
                    if ((env.RELEASE_TAG ?: '').trim()) {
                        env.TAG_NAME = env.RELEASE_TAG
                    }
                    runReleaseStages(cfg.releaseStages)
                    releaseStagesRan = true
                }

                if (autoTagEnabled && !releaseAfterEnv) {
                    triggerAutoTag()
                }

                runDeferredTerraform()

                Closure afterEnvCallback = null
                if (autoTagEnabled && releaseAfterEnv) {
                    afterEnvCallback = { String finishedEnv ->
                        if (!releaseFlowAttempted && (finishedEnv ?: '').toString().trim().toLowerCase() == releaseAfterEnv) {
                            triggerAutoTag()
                        }
                    }
                }

                deployEnvironments(cfg, state, afterEnvCallback)

                runDeferredTerraform()
                if (!releaseStagesRan) {
                    runReleaseStages(cfg.releaseStages)
                }
                publishRelease(cfg.release, state)
            }
        } finally {
            cleanupWorkspace(ws)
        }
    }

    if (agent.docker && agent.label) {
        node(agent.label) {
            checkout scm
            docker.image(agent.docker).inside {
                runCore()
            }
        }
    } else if (agent.docker) {
        node {
            checkout scm
            docker.image(agent.docker).inside {
                runCore()
            }
        }
    } else if (agent.label) {
        node(agent.label) {
            checkout scm
            runCore()
        }
    } else {
        node {
            checkout scm
            runCore()
        }
    }
}

private void cleanupWorkspace(String ws) {
    try {
        echo "Cleaning workspace..."
        cleanWs deleteDirs: true, disableDeferredWipeout: true, notFailBuild: true
    } catch (MissingMethodException | NoSuchMethodError ignore) {
        echo "cleanWs step unavailable; falling back to deleteDir()"
        if (ws?.trim()) {
            dir(ws) {
                deleteDir()
            }
        } else {
            deleteDir()
        }
    } catch (Throwable t) {
        echo "Workspace cleanup failed: ${t.message}"
    }
}

// --------------------------- BUILD -------------------------------------------
private void runBuildStages(List<Map> stages) {
    if (!stages || stages.isEmpty()) {
        echo "No build stages defined; skipping build/test."
        return
    }
    stages.each { Map stageCfg ->
        String name = stageCfg.name ?: 'Build'
        stage(name) {
            List<String> envList = mapToEnvList(stageCfg.env instanceof Map ? stageCfg.env : [:])
            List<Map> bindings = buildCredentialBindings([credentials: stageCfg.credentials])

            Closure execute = {
                if (stageCfg.verb) {
                    runVerb(stageCfg.verb as String)
                } else if (stageCfg.sh) {
                    sh stageCfg.sh as String
                } else {
                    echo "Stage '${name}' has no action."
                }
            }

            Closure wrapped = execute
            if (envList && !envList.isEmpty()) {
                wrapped = { withEnv(envList) { execute() } }
            }
            if (bindings && !bindings.isEmpty()) {
                withCredentials(bindings) {
                    wrapped.call()
                }
            } else {
                wrapped.call()
            }
        }
    }
}

private void runReleaseStages(List<Map> stages) {
    if (!stages || stages.isEmpty()) {
        return
    }
    String effectiveTag = (env.RELEASE_TAG ?: env.TAG_NAME ?: '').trim()
    if (!effectiveTag) {
        echo "Release stages skipped; release tag not available."
        return
    }
    stages.each { Map stageCfg ->
        String name = stageCfg.name ?: 'Release Task'
        String whenCond = stageCfg.when ?: ''
        if (whenCond && !Helpers.matchCondition(whenCond, env)) {
            echo "Release stage '${name}' skipped by condition '${whenCond}'"
        } else {
            stage(name) {
                List<String> envList = mapToEnvList(stageCfg.env instanceof Map ? stageCfg.env : [:])
                envList << "RELEASE_TAG=${effectiveTag}"
                List<Map> bindings = buildCredentialBindings([credentials: stageCfg.credentials])

                Closure execute = {
                    if (stageCfg.verb) {
                        runVerb(stageCfg.verb as String)
                    } else if (stageCfg.sh) {
                        sh stageCfg.sh as String
                    } else {
                        echo "Stage '${name}' has no action."
                    }
                }

                Closure wrapped = execute
                if (envList && !envList.isEmpty()) {
                    wrapped = { withEnv(envList) { execute() } }
                }
                withOptionalCredentials(bindings, wrapped)
            }
        }
    }
}

// --------------------------- MATRIX ------------------------------------------
private void runMatrix(Map matrix) {
    if (!matrix || matrix.isEmpty()) return
    matrix.each { groupName, steps ->
        stage("Matrix: ${groupName}") {
            Map branches = [:]
            (steps as List).each { s ->
                def title = titleForStep(s)
                branches[title] = {
                    runVerb(s as String)
                }
            }
            parallel branches
        }
    }
}

private String titleForStep(Object s) {
    def raw = s.toString().trim()
    if (raw.startsWith("sh ")) return raw.substring(3).take(30)
    return raw.take(30)
}

// --------------------------- DOCKER ------------------------------------------
private void dockerBuild(Map imageCfg, Map state) {
    List<String> cmd = ["docker", "build"]
    if (imageCfg.platforms) {
        cmd << "--platform=${(imageCfg.platforms as List).join(',')}"
    }
    cmd << "-f ${shellEscape(imageCfg.dockerfile as String)}"

    (imageCfg.buildArgs ?: [:]).each { k, v ->
        String value = renderTemplate(v?.toString(), templateContext(state))
        cmd << "--build-arg ${shellEscape("${k}=${value}")}"
    }

    (imageCfg.buildFlags ?: []).each { flag ->
        cmd << flag.toString()
    }

    cmd << "-t ${shellEscape(state.imageRef)}"
    cmd << shellEscape(imageCfg.context as String)
    sh cmd.join(' ')
}

private void dockerPush(Map cfg, Map state) {
    Map pushCfg = cfg.image.push
    dockerLoginIfNeeded(cfg.image, pushCfg)
    sh "docker push ${shellEscape(state.imageRef)}"
    (pushCfg.extraTags ?: []).each { rawTag ->
        String rendered = renderTemplate(rawTag.toString(), templateContext(state))
        if (!rendered?.trim()) {
            return
        }
        String ref = "${cfg.image.repository}:${rendered}"
        sh "docker tag ${shellEscape(state.imageRef)} ${shellEscape(ref)}"
        sh "docker push ${shellEscape(ref)}"
    }
}

// --------------------------- SBOM / SCAN / SIGN ------------------------------
private boolean shouldGenerateSbom(Map cfg) {
    if (!(cfg.image.enabled as Boolean)) {
        return false
    }
    if (!(cfg.sbom.enabled as Boolean)) {
        return false
    }
    if (!commandAvailable('syft')) {
        echo "Skipping SBOM generation because 'syft' is not available on PATH."
        return false
    }
    return true
}

private void generateSbom(Map cfg, Map state) {
    Map sbom = cfg.sbom
    String output = renderTemplate(sbom.output as String, templateContext(state))
    if (!output?.trim()) {
        output = "reports/sbom-${state.imageTag}.json"
    }
    String dir = parentDir(output)
    if (dir) {
        sh "mkdir -p ${shellEscape(dir)}"
    }
    List<String> cmd = []
    cmd << "syft ${shellEscape(state.imageRef)}"
    cmd << "-o ${shellEscape(sbom.format as String)}"
    cmd << "-f ${shellEscape(output)}"
    cmd << "--scope all-layers"
    sh cmd.join(' ')
    archiveArtifacts artifacts: output, fingerprint: true
}

private boolean shouldScan(Map cfg) {
    if (!(cfg.image.enabled as Boolean)) {
        return false
    }
    if (!(cfg.scan.enabled as Boolean)) {
        return false
    }
    if (!commandAvailable('grype')) {
        echo "Skipping vulnerability scan because 'grype' is not available on PATH."
        return false
    }
    return true
}

private void scanImage(Map cfg, Map state) {
    Map scan = cfg.scan
    List<String> cmd = []
    cmd << "grype ${shellEscape(state.imageRef)}"
    cmd << "--add-cpes-if-none"
    cmd << "--fail-on ${shellEscape(scan.failOn as String)}"
    (scan.flags ?: []).each { flag ->
        cmd << flag.toString()
    }
    sh cmd.join(' ')
}

private boolean shouldPush(Map cfg) {
    if (!(cfg.image.enabled as Boolean)) {
        return false
    }
    def pushCfg = cfg.image.push
    if (!(pushCfg.enabled as Boolean)) {
        return false
    }
    return Helpers.matchCondition(pushCfg.when as String, env)
}

private void dockerLoginIfNeeded(Map imageCfg, Map pushCfg) {
    Map credentials = pushCfg.credentials instanceof Map ? pushCfg.credentials : [:]
    String credentialId = (credentials.id ?: '').toString().trim()
    if (!credentialId) {
        return
    }

    String usernameEnv = (credentials.usernameEnv ?: 'DOCKER_USERNAME').toString()
    String passwordEnv = (credentials.passwordEnv ?: 'DOCKER_PASSWORD').toString()
    String registry = (pushCfg.registry ?: imageCfg.registryHost ?: '').toString().trim()
    registry = registry.replaceFirst('^https?://', '')
    if (!registry) {
        registry = 'docker.io'
    }

    String hostSegment = registry ? " ${shellEscape(registry)}" : ''
    echo "Logging in to Docker registry '${registry}' using credential '${credentialId}'."
    withCredentials([usernamePassword(credentialsId: credentialId, usernameVariable: usernameEnv, passwordVariable: passwordEnv)]) {
        sh "echo \"\$${passwordEnv}\" | docker login${hostSegment} -u \"\$${usernameEnv}\" --password-stdin"
    }
}

private boolean shouldSign(Map cfg) {
    Map signing = cfg.signing
    if (!(cfg.image.enabled as Boolean)) {
        return false
    }
    if (!(signing.enabled as Boolean)) {
        return false
    }
    return Helpers.matchCondition(signing.when as String, env)
}

private void signImage(Map cfg, Map state) {
    Map signing = cfg.signing
    Map ctx = templateContext(state)
    List<String> cmd = ["cosign", "sign"]
    if (signing.keyless) {
        cmd << "--keyless"
    } else if (signing.key) {
        cmd << "--key ${shellEscape(signing.key as String)}"
    }
    if (signing.identity) {
        cmd << "--identity ${shellEscape(renderTemplate(signing.identity as String, ctx))}"
    }
    (signing.flags ?: []).each { flag ->
        cmd << flag.toString()
    }
    cmd << shellEscape(state.imageRef)

    List<String> envPairs = []
    (signing.env ?: [:]).each { k, v ->
        envPairs << "${k}=${renderTemplate(v?.toString(), ctx)}"
    }

    if (envPairs) {
        withEnv(envPairs) {
            sh cmd.join(' ')
        }
    } else {
        sh cmd.join(' ')
    }
}

private List<String> runTerraform(Map tfCfg, List<String> overrideOrder = null, boolean captureDeferred = false) {
    List<String> deferred = []
    if (!tfCfg?.enabled) return deferred
    Map envs = tfCfg.environments ?: [:]
    if (!envs || envs.isEmpty()) return deferred
    List<String> order = overrideOrder ?: (tfCfg.order ?: [])
    if (!order || order.isEmpty()) {
        order = envs.keySet().collect { it.toString() }
    }
    String basePath = (tfCfg.path ?: 'infra/platform/terraform').toString()
    String binary = resolveTerraformBinary(tfCfg)
    List<String> globalEnv = mapToEnvList(tfCfg.env)
    Map<String, String> globalBackend = tfCfg.backend ?: [:]

    order.each { String envName ->
        Map envCfg = envs[envName]
        if (!envCfg) {
            return
        }
        if (!Helpers.matchCondition(envCfg.when as String, env)) {
            if (captureDeferred && shouldDeferTerraform(envCfg.when as String)) {
                echo "Queue terraform '${envCfg.displayName}' for deferred execution — condition '${envCfg.when}' not yet met"
                deferred << envName
            } else {
                echo "Skip terraform '${envCfg.displayName}' — condition '${envCfg.when}' not met"
            }
            return
        }

        stage("Terraform ${envCfg.displayName}") {
            awaitApproval(envCfg.approval as Map, "Apply Terraform for ${envCfg.displayName}?")

            List<String> envList = []
            envList.addAll(globalEnv)
            envList.addAll(mapToEnvList(envCfg.env))

            Map<String, String> backend = [:]
            backend.putAll(globalBackend ?: [:])
            backend.putAll(envCfg.backend ?: [:])

            List<Map> bindings = buildCredentialBindings(envCfg)
            Closure execute = {
                dir(basePath) {
                    Map<String, String> combinedVars = [:]
                    combinedVars.putAll(envCfg.vars ?: [:])
                    String kubeEnvVar = envCfg.kubeconfigEnv ?: 'KUBECONFIG'
                    String kubePath = resolveEnvVar(kubeEnvVar)
                    if (kubePath?.trim() && !combinedVars.containsKey('kubeconfig_path')) {
                        combinedVars['kubeconfig_path'] = kubePath.trim()
                    }
                    if (envCfg.kubeContext && !combinedVars.containsKey('kube_context')) {
                        combinedVars['kube_context'] = envCfg.kubeContext
                    }

                    Closure commands = {
                        if (!envList.isEmpty()) {
                            withEnv(envList) {
                                runTerraformCommands(binary, tfCfg, envCfg, backend, combinedVars)
                            }
                        } else {
                            runTerraformCommands(binary, tfCfg, envCfg, backend, combinedVars)
                        }
                    }
                    String envPath = envCfg.path ?: '.'
                    if (envPath && envPath != '.' && envPath != './') {
                        dir(envPath) {
                            commands()
                        }
                    } else {
                        commands()
                    }
                }
            }

            withOptionalCredentials(bindings, execute)
        }

        Map smoke = envCfg.smoke ?: [:]
        if (smoke.url || smoke.script || smoke.command) {
            stage("Smoke ${envCfg.displayName}") {
                Map payload = [:]
                payload.putAll(smoke)
                payload.environment = envCfg.displayName
                List<Map> smokeBindings = buildCredentialBindings(envCfg)
                Closure smokeRun = {
                    runSmoke(payload)
                }
                withOptionalCredentials(smokeBindings, smokeRun)
            }
        }
    }

    return deferred
}

private void handleRelease(Map releaseCfg, Map state) {
    Map autoTag = releaseCfg?.autoTag ?: [:]
    if (!(autoTag.enabled as Boolean)) {
        return
    }
    if (!Helpers.matchCondition(autoTag.when as String, env)) {
        echo "Release auto-tag skipped by condition '${autoTag.when}'"
        return
    }

    stage(autoTag.stageName ?: 'Create Release Tag') {
        if ((env.TAG_NAME ?: '').trim()) {
            echo "Tag build detected (${env.TAG_NAME}); skipping auto-tag."
            return
        }

        cleanWorkspaceForTag(autoTag)

        String treeStatus = sh(script: 'git status --porcelain', returnStdout: true).trim()
        if (treeStatus && !(autoTag.allowDirty as Boolean)) {
            error "Working tree contains uncommitted changes; refusing to create a release tag."
        } else if (treeStatus) {
            echo "Working tree has uncommitted changes but continuing because 'allowDirty' is enabled."
        }

        if (autoTag.skipIfTagged as Boolean) {
            String currentTag = sh(script: 'git describe --tags --exact-match 2>/dev/null || true', returnStdout: true).trim()
            if (currentTag) {
                echo "HEAD already tagged with '${currentTag}'; skipping auto-tag."
                return
            }
        }

        List<String> envVars = mapToEnvList(autoTag.env ?: [:])
        Closure tagAction = {
            createAndPushTag(autoTag, state)
        }
        Closure action = {
            if (!envVars.isEmpty()) {
                withEnv(envVars, tagAction)
            } else {
                tagAction()
            }
        }
        withReleaseCredentials(autoTag, action)
    }
}

private void cleanWorkspaceForTag(Map autoTag) {
    if (!(autoTag.clean as Boolean)) {
        return
    }
    sh 'git reset --hard HEAD'
    sh 'git clean -fd'
}

private void createAndPushTag(Map autoTag, Map state) {
    String remote = (autoTag.remote ?: 'origin').toString()
    String prefix = (autoTag.prefix ?: 'v').toString()
    String bump = (autoTag.bump ?: 'patch').toString()
    boolean annotated = autoTag.annotated as Boolean
    boolean push = autoTag.push as Boolean
    String message = (autoTag.message ?: "Automated release for ${state.commitShort}").toString()
    String user = (autoTag.gitUser ?: 'Mereb CI').toString()
    String email = (autoTag.gitEmail ?: 'ci@mereb.local').toString()

    sh "git fetch --tags --quiet ${shellEscape(remote)} || true"

    String pattern = "${prefix}[0-9]*"
    String latest = sh(script: "git tag --list ${shellEscape(pattern)} --sort=-version:refname | head -n1", returnStdout: true).trim()
    Map next = computeNextTag(prefix, latest, bump)
    String nextTag = (next.tag ?: '').toString()
    if (!nextTag?.trim()) {
        error 'Failed to compute next release tag.'
    }

    String exists = sh(script: "git rev-parse --quiet --verify refs/tags/${shellEscape(nextTag)} >/dev/null 2>&1 && echo yes || true", returnStdout: true).trim()
    if ('yes'.equalsIgnoreCase(exists)) {
        echo "Tag ${nextTag} already exists; skipping auto-tag."
        return
    }

    sh "git config user.name ${shellEscape(user)}"
    sh "git config user.email ${shellEscape(email)}"

    if (annotated) {
        sh "git tag -a ${shellEscape(nextTag)} -m ${shellEscape(message)}"
    } else {
        sh "git tag ${shellEscape(nextTag)}"
    }

    if (push) {
        sh "git push ${shellEscape(remote)} ${shellEscape(nextTag)}"
    } else {
        echo "Auto-tag push disabled; created local tag ${nextTag}"
    }

    env.RELEASE_TAG = nextTag
    env.TAG_NAME = nextTag
    echo "Created release tag ${nextTag}"
}

@NonCPS
private Map computeNextTag(String prefix, String latest, String bump) {
    int major = 0
    int minor = 0
    int patch = 0
    boolean haveLatest = latest?.trim()

    if (haveLatest) {
        String base = latest.startsWith(prefix) ? latest.substring(prefix.length()) : latest
        List<String> parts = base.tokenize('.')
        major = parseIntOr(parts, 0, 0)
        minor = parseIntOr(parts, 1, 0)
        patch = parseIntOr(parts, 2, 0)
    }

    switch (bump) {
        case 'major':
            major += 1
            minor = 0
            patch = 0
            break
        case 'minor':
            minor += 1
            patch = 0
            break
        default:
            patch += 1
            break
    }

    String tag = "${prefix}${major}.${minor}.${patch}"
    return [tag: tag, major: major, minor: minor, patch: patch]
}

@NonCPS
private int parseIntOr(List parts, int index, int fallback) {
    if (index >= (parts?.size() ?: 0)) {
        return fallback
    }
    String raw = parts[index]?.toString()
    if (!raw) {
        return fallback
    }
    try {
        return Integer.parseInt(raw)
    } catch (NumberFormatException ignore) {
        return fallback
    }
}

private void runTerraformCommands(String binary, Map tfCfg, Map envCfg, Map backend, Map vars) {
    String planOut = envCfg.planOut
    try {
        runTerraformInit(binary, tfCfg.initArgs, envCfg.initArgs, backend)
        if (envCfg.workspace) {
            selectTerraformWorkspace(binary, envCfg.workspace.toString())
        }
        runTerraformPlan(binary, tfCfg.planArgs, envCfg.planArgs, envCfg.varFiles, vars, planOut)
        if (envCfg.apply as Boolean) {
            runTerraformApply(binary, tfCfg.applyArgs, envCfg.applyArgs, planOut, envCfg.autoApply as Boolean)
        } else {
            echo "Terraform apply skipped for ${envCfg.displayName}"
        }
    } finally {
        cleanupTerraformArtifacts(planOut)
    }
}

private void runTerraformInit(String binary, List<String> globalArgs, List<String> envArgs, Map backend) {
    List<String> cmd = []
    cmd << shellEscape(binary)
    cmd << 'init'
    (globalArgs ?: []).each { cmd << it.toString() }
    (envArgs ?: []).each { cmd << it.toString() }
    (backend ?: [:]).each { k, v ->
        cmd << "-backend-config=${shellEscape("${k}=${v}")}"
    }
    sh cmd.join(' ')
}

private void selectTerraformWorkspace(String binary, String workspace) {
    if (!workspace?.trim()) {
        return
    }
    String bin = shellEscape(binary)
    String ws = shellEscape(workspace)
    String selectCmd = "${bin} workspace select ${ws} || ${bin} workspace new ${ws}"
    sh selectCmd
}

private void runTerraformPlan(String binary, List<String> globalArgs, List<String> envArgs, List<String> varFiles, Map vars, String planOut) {
    List<String> cmd = []
    cmd << shellEscape(binary)
    cmd << 'plan'
    (globalArgs ?: []).each { cmd << it.toString() }
    (envArgs ?: []).each { cmd << it.toString() }
    (varFiles ?: []).each { file ->
        cmd << "-var-file=${shellEscape(file.toString())}"
    }
    (vars ?: [:]).each { k, v ->
        cmd << "-var=${shellEscape("${k}=${v}")}"
    }
    if (planOut) {
        cmd << "-out=${shellEscape(planOut)}"
    }
    sh cmd.join(' ')
}

private void runTerraformApply(String binary, List<String> globalArgs, List<String> envArgs, String planOut, boolean autoApprove) {
    List<String> cmd = []
    cmd << shellEscape(binary)
    cmd << 'apply'
    (globalArgs ?: []).each { cmd << it.toString() }
    (envArgs ?: []).each { cmd << it.toString() }
    if (autoApprove && !cmd.any { it.contains('-auto-approve') }) {
        cmd << '-auto-approve'
    }
    if (planOut) {
        cmd << shellEscape(planOut)
    }
    sh cmd.join(' ')
}

private void cleanupTerraformArtifacts(String planOut) {
    if (planOut?.trim()) {
        sh "rm -f ${shellEscape(planOut)}"
    }
    sh "rm -rf .terraform"
    if (fileExists('.terraform.lock.hcl')) {
        sh "git checkout -- .terraform.lock.hcl || true"
    }
}

private boolean shouldDeferTerraform(String condition) {
    if (!condition) {
        return false
    }
    String trimmed = condition.trim()
    return trimmed.startsWith('tag=') || trimmed.contains(' tag=')
}

// --------------------------- DEPLOY ------------------------------------------
private void deployEnvironments(Map cfg, Map state, Closure afterEnvCallback = null) {
    Map envs = cfg.deploy.environments ?: [:]
    List<String> order = cfg.deploy.order ?: []
    if (!envs || envs.isEmpty() || !order) {
        echo "No deployment environments configured; skipping deploy."
        return
    }

    order.eachWithIndex { String envName, int idx ->
        Map envCfg = envs[envName]
        if (!envCfg) {
            echo "Environment '${envName}' not defined; skipping."
            return
        }
        if (!Helpers.matchCondition(envCfg.when as String, env)) {
            echo "Skip deploy '${envCfg.displayName}' — condition '${envCfg.when}' not met"
            return
        }

        Map approvalCfg = envCfg.approval ?: [:]
        boolean approvalBefore = approvalCfg.before as Boolean
        if (approvalBefore) {
            requestDeploymentApproval(envCfg)
        }

        List<String> valuesFiles = []
        if (envCfg.valuesFiles) {
            valuesFiles.addAll(envCfg.valuesFiles as List)
        }
        if (valuesFiles.isEmpty()) {
            String defaultValues = ".ci/values-${envName}.yaml"
            if (fileExists(defaultValues)) {
                valuesFiles = [defaultValues]
            }
        }

        stage("Deploy ${envCfg.displayName}") {
            Map setCombined = [:]
            setCombined.putAll(envCfg.set ?: [:])
            if (cfg.image.enabled && state.repository) {
                setCombined['image.repository'] = state.repository
            }
            if (cfg.image.enabled && state.imageTag) {
                setCombined['image.tag'] = state.imageTag
            }

            Map helmArgs = [
                release     : envCfg.release,
                namespace   : envCfg.namespace,
                chart       : envCfg.chart,
                repo        : envCfg.repo,
                version     : envCfg.chartVersion,
                valuesFiles : valuesFiles,
                set         : setCombined,
                setString   : envCfg.setString,
                setFile     : envCfg.setFile,
                kubeContext : envCfg.kubeContext,
                kubeconfig  : envCfg.kubeconfig,
                wait        : envCfg.wait,
                atomic      : envCfg.atomic,
                timeout     : envCfg.timeout,
                repoUsername: envCfg.repoUsername,
                repoPassword: envCfg.repoPassword
            ]

            Map repoCreds = envCfg.repoCredentials instanceof Map ? envCfg.repoCredentials : [:]
            String repoCredId = (repoCreds.id ?: '').toString().trim()
            if (repoCredId) {
                withCredentials([
                    usernamePassword(credentialsId: repoCredId, usernameVariable: 'HELM_REPO_USERNAME', passwordVariable: 'HELM_REPO_PASSWORD')
                ]) {
                    Map argsWithCreds = helmArgs.clone() as Map
                    argsWithCreds.repoUsername = env.HELM_REPO_USERNAME
                    argsWithCreds.repoPassword = env.HELM_REPO_PASSWORD
                    helmDeploy(argsWithCreds)
                }
            } else {
                helmDeploy(helmArgs)
            }
        }

        Map smoke = envCfg.smoke ?: [:]
        if (smoke.url || smoke.script || smoke.command) {
            stage("Smoke ${envCfg.displayName}") {
                Map payload = [:]
                payload.putAll(smoke)
                payload.environment = envCfg.displayName
                runSmoke(payload)
            }
        }

        if (afterEnvCallback) {
            afterEnvCallback.call(envName)
        }

        if (idx < order.size() - 1 && !(envCfg.autoPromote as Boolean)) {
            requestPromotion(envCfg, envName, order[idx + 1])
        }
    }
}

private void requestPromotion(Map envCfg, String current, String next) {
    Map approval = envCfg.approval ?: [:]
    String message = approval.message ?: "Promote ${current} deployment to ${next}?"
    String ok = approval.ok ?: 'Promote'
    if (approval.submitter) {
        input message: message, ok: ok, submitter: approval.submitter.toString()
    } else {
        input message: message, ok: ok
    }
}

private void requestDeploymentApproval(Map envCfg) {
    Map approval = envCfg.approval ?: [:]
    String message = approval.message ?: "Deploy ${envCfg.displayName ?: envCfg.name}?"
    String ok = approval.ok ?: 'Deploy'
    if (approval.submitter) {
        input message: message, ok: ok, submitter: approval.submitter.toString()
    } else {
        input message: message, ok: ok
    }
}

// --------------------------- NORMALISATION -----------------------------------
private Map normalizeConfig(Map raw) {
    Map cfg = [:]

    cfg.agent = [
        label : (raw.agent?.label ?: '').toString().trim(),
        docker: (raw.agent?.docker ?: '').toString().trim()
    ]

    cfg.preset = (raw.preset ?: raw.build?.preset ?: 'node').toString()
    cfg.matrix = normalizeMatrix(raw.matrix)
    cfg.buildStages = computeBuildStages(cfg.preset, raw.build)
    cfg.releaseStages = normalizeUserStages(raw.releaseStages)

    cfg.image = normalizeImage(raw)
    cfg.sbom = normalizeSbom(raw.sbom)
    cfg.scan = normalizeScan(raw.scan)
    cfg.signing = normalizeSigning(raw.signing, cfg.image)
    cfg.terraform = normalizeTerraform(raw.terraform)
    cfg.release = normalizeRelease(raw.release)
    cfg.deploy = normalizeDeploy(raw)

    return cfg
}

@NonCPS
private Map normalizeMatrix(Object raw) {
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

@NonCPS
private List<Map> computeBuildStages(String preset, Object buildCfgRaw) {
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

    switch (preset) {
        case 'java-gradle':
            return [
                [name: 'Unit Tests', verb: 'gradle.test'],
                [name: 'Build', verb: 'gradle.build']
            ]
        case 'node':
        default:
            return [
                [name: 'Install', verb: 'node.install', env: [:], credentials: []],
                [name: 'Unit Tests', verb: 'node.test', env: [:], credentials: []],
                [name: 'Build', verb: 'node.build', env: [:], credentials: []]
            ]
    }
}

@NonCPS
private List<Map> normalizeUserStages(Object raw) {
    if (!(raw instanceof List)) {
        return []
    }
    List<Map> stages = []
    (raw as List).each { Object entry ->
        Map stage = entry instanceof Map ? entry : [:]
        if (stage.isEmpty()) {
            return
        }
        stages << [
            name       : stage.name?.toString() ?: 'Release Task',
            verb       : stage.verb?.toString(),
            sh         : stage.sh?.toString(),
            when       : stage.when?.toString(),
            env        : toStringMap(stage.env),
            credentials: normalizeCredentialList(stage.credentials)
        ]
    }
    return stages
}

private Map normalizeImage(Map raw) {
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
            error "image.repository or app.name must be defined in ${PRIMARY_CONFIG}"
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

@NonCPS
private String deriveRegistryHost(String repository) {
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

private Map normalizeSbom(Object raw) {
    Map cfg = raw instanceof Map ? raw : [:]
    boolean enabled = cfg.enabled == null ? true : cfg.enabled as Boolean
    return [
        enabled: enabled,
        format : (cfg.format ?: 'cyclonedx-json').toString(),
        output : (cfg.output ?: 'reports/sbom-{{imageTag}}.json').toString()
    ]
}

private Map normalizeScan(Object raw) {
    Map cfg = raw instanceof Map ? raw : [:]
    boolean enabled = cfg.enabled == null ? true : cfg.enabled as Boolean
    return [
        enabled: enabled,
        failOn : (cfg.failOn ?: 'critical').toString(),
        flags  : toStringList(cfg.flags ?: cfg.additionalFlags)
    ]
}

private Map normalizeSigning(Object raw, Map imageCfg) {
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

private Map normalizeTerraform(Object raw) {
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

    Map cfg = [
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
    return cfg
}

private Map normalizeRelease(Object raw) {
    Map section = raw instanceof Map ? raw : [:]
    Map autoTag = normalizeReleaseAutoTag(section.autoTag)
    Map github = normalizeReleaseGithub(section.github, autoTag)

    return [
        autoTag: autoTag,
        github : github
    ]
}

@NonCPS
private Map normalizeReleaseAutoTag(Object raw) {
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
        enabled      : enabled,
        when         : (data.when ?: '!pr').toString(),
        stageName    : (data.stageName ?: 'Create Release Tag').toString(),
        prefix       : (data.prefix ?: 'v').toString(),
        bump         : bumpValue,
        remote       : (data.remote ?: 'origin').toString(),
        gitUser      : (data.gitUser ?: data.user)?.toString(),
        gitEmail     : (data.gitEmail ?: data.email)?.toString(),
        message      : data.message?.toString(),
        annotated    : data.containsKey('annotated') ? data.annotated as Boolean : (data.containsKey('annotate') ? data.annotate as Boolean : false),
        push         : data.containsKey('push') ? data.push as Boolean : true,
        skipIfTagged : data.containsKey('skipIfTagged') ? data.skipIfTagged as Boolean : true,
        clean        : data.containsKey('clean') ? data.clean as Boolean : true,
        allowDirty   : data.containsKey('allowDirty') ? data.allowDirty as Boolean : false,
        credential   : credential,
        env          : toStringMap(data.env),
        afterEnvironment: afterEnv
    ]
}

private Map normalizeReleaseGithub(Object raw, Map autoTag) {
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

@NonCPS
private Map normalizeReleaseCredential(Map data) {
    Map node = [:]
    if (data.credential instanceof Map) {
        node.putAll(data.credential as Map)
    }
    String id = (data.credentialId ?: node.id)?.toString()
    if (!id) {
        return [:]
    }
    String type = (data.credentialType ?: node.type ?: 'usernamePassword').toString()
    Map credential = [
        id         : id,
        type       : type,
        usernameEnv: (data.usernameEnv ?: node.usernameEnv ?: 'GIT_USERNAME').toString(),
        passwordEnv: (data.passwordEnv ?: node.passwordEnv ?: 'GIT_PASSWORD').toString(),
        tokenEnv   : (data.tokenEnv ?: node.tokenEnv ?: 'GIT_TOKEN').toString(),
        tokenUser  : (data.tokenUser ?: node.tokenUser ?: 'x-access-token').toString()
    ]
    return credential
}


@NonCPS
private Map normalizeTerraformEnvironment(String name, Object raw) {
    Map data = raw instanceof Map ? raw : [:]
    Map envMap = toStringMap(data.env)
    Map varsMap = toStringMap(data.vars)
    Map backendMap = toStringMap(data.backendConfig)
    List<Map> credentials = normalizeTerraformCredentials(data.credentials)
    Map approval = normalizeApproval(data.approve ?: data.approval)
    Map smoke = normalizeSmoke(data.smoke)

    return [
        name                : name,
        displayName         : (data.displayName ?: name.toUpperCase()).toString(),
        when                : (data.when ?: '!pr').toString(),
        kubeconfigCredential: data.kubeconfigCredential?.toString(),
        kubeconfigEnv       : (data.kubeconfigEnv ?: 'KUBECONFIG').toString(),
        kubeContext         : (data.kubeContext ?: data.context)?.toString(),
        credentials         : credentials,
        env                 : envMap,
        vars                : varsMap,
        varFiles            : toStringList(data.varFiles),
        initArgs            : toStringList(data.initArgs),
        planArgs            : toStringList(data.planArgs),
        applyArgs           : toStringList(data.applyArgs),
        backend             : backendMap,
        planOut             : (data.planOut ?: "tfplan-${name}").toString(),
        path                : data.path?.toString(),
        apply               : data.containsKey('apply') ? data.apply as Boolean : true,
        autoApply           : data.containsKey('autoApply') ? data.autoApply as Boolean : true,
        workspace           : data.workspace?.toString(),
        approval            : approval,
        smoke               : smoke
    ]
}

@NonCPS
private List<Map> normalizeTerraformCredentials(Object raw) {
    List<Map> list = []
    if (!(raw instanceof List)) {
        return list
    }
    (raw as List).each { item ->
        Map entry = item instanceof Map ? item : [:]
        String type = (entry.type ?: 'string').toString()
        String id = entry.id?.toString()
        if (!id) {
            return
        }
        switch (type) {
            case 'file':
                String envVar = entry.env ?: entry.variable ?: entry.var
                if (envVar) {
                    list << [type: 'file', id: id, env: envVar.toString()]
                }
                break
            case 'usernamePassword':
                String userEnv = entry.usernameEnv ?: entry.userEnv ?: entry.usernameVariable
                String passEnv = entry.passwordEnv ?: entry.passEnv ?: entry.passwordVariable
                if (userEnv && passEnv) {
                    list << [
                        type: 'usernamePassword',
                        id  : id,
                        usernameEnv: userEnv.toString(),
                        passwordEnv: passEnv.toString()
                    ]
                }
                break
            default:
                String envVar = entry.env ?: entry.variable ?: entry.var
                if (envVar) {
                    list << [type: 'string', id: id, env: envVar.toString()]
                }
                break
        }
    }
    return list
}

@NonCPS
private List<Map> normalizeCredentialList(Object raw) {
    List<Map> list = []
    if (!(raw instanceof List)) {
        return list
    }
    (raw as List).each { item ->
        Map entry = item instanceof Map ? item : [:]
        String id = entry.id?.toString()
        if (!id) {
            return
        }
        String type = (entry.type ?: 'string').toString()
        switch (type) {
            case 'file':
                String envVar = entry.env ?: entry.variable ?: entry.var
                if (envVar) {
                    list << [type: 'file', id: id, env: envVar.toString()]
                }
                break
            case 'usernamePassword':
                String userEnv = entry.usernameEnv ?: entry.userEnv ?: entry.usernameVariable
                String passEnv = entry.passwordEnv ?: entry.passEnv ?: entry.passwordVariable
                if (userEnv && passEnv) {
                    list << [
                        type       : 'usernamePassword',
                        id         : id,
                        usernameEnv: userEnv.toString(),
                        passwordEnv: passEnv.toString()
                    ]
                }
                break
            default:
                String envVar = entry.env ?: entry.variable ?: entry.var
                if (envVar) {
                    list << [type: 'string', id: id, env: envVar.toString()]
                }
                break
        }
    }
    return list
}

private String resolveTerraformBinary(Map tfCfg) {
    String configured = (tfCfg.binary ?: 'terraform').toString()
    String normalized = normalizeTerraformBinaryPath(configured)
    if (terraformExecutableExists(normalized)) {
        return normalized
    }
    if (!(tfCfg.autoInstall as Boolean)) {
        error "Terraform binary '${configured}' not found on PATH. Enable terraform.autoInstall or provide terraform.binary."
    }
    return installTerraformBinary(tfCfg)
}

private String normalizeTerraformBinaryPath(String binary) {
    if (!binary) {
        return 'terraform'
    }
    if (binary.startsWith('/')) {
        return binary
    }
    if (binary.contains('/') || binary.contains('\\')) {
        String ws = pwd()
        return "${ws.replaceAll(/\/+$/, '')}/${binary}"
    }
    return binary
}

private boolean terraformExecutableExists(String binary) {
    String script
    if (!binary) {
        return false
    }
    if (binary.contains('/') || binary.contains('\\')) {
        script = "[ -x ${shellEscape(binary)} ] && echo yes || echo no"
    } else {
        script = "command -v ${shellEscape(binary)} >/dev/null 2>&1 && echo yes || echo no"
    }
    return sh(script: script, returnStdout: true).trim() == 'yes'
}

private boolean commandAvailable(String binary) {
    if (!binary?.trim()) {
        return false
    }
    String escaped = shellEscape(binary)
    String script = "command -v ${escaped} >/dev/null 2>&1 && echo yes || echo no"
    return sh(script: script, returnStdout: true).trim() == 'yes'
}

private String installTerraformBinary(Map tfCfg) {
    String installDir = resolveTerraformInstallDir(tfCfg)
    String targetPath = "${installDir}/terraform"
    if (terraformExecutableExists(targetPath)) {
        return targetPath
    }

    String version = (tfCfg.version ?: '1.6.6').toString()
    Map platform = detectTerraformPlatform()
    String archiveUrl = "https://releases.hashicorp.com/terraform/${version}/terraform_${version}_${platform.os}_${platform.arch}.zip"
    String zipPath = "${installDir}/terraform_${version}.zip"

    echo "Terraform binary not found. Installing Terraform ${version} to ${targetPath}"
    sh "mkdir -p ${shellEscape(installDir)}"
    sh """
        set -e
        curl -fsSL ${shellEscape(archiveUrl)} -o ${shellEscape(zipPath)}
        unzip -o ${shellEscape(zipPath)} -d ${shellEscape(installDir)}
        rm -f ${shellEscape(zipPath)}
        chmod +x ${shellEscape(targetPath)}
    """
    return targetPath
}

private String resolveTerraformInstallDir(Map tfCfg) {
    String dir = (tfCfg.installDir ?: '.ci/bin').toString()
    if (dir.startsWith('/')) {
        return dir
    }
    String ws = pwd()
    return "${ws.replaceAll(/\/+$/, '')}/${dir}"
}

@NonCPS
private Map detectTerraformPlatform() {
    String os = System.getProperty('os.name')?.toLowerCase() ?: ''
    String arch = System.getProperty('os.arch')?.toLowerCase() ?: ''

    String osLabel
    if (os.contains('linux')) {
        osLabel = 'linux'
    } else if (os.contains('mac') || os.contains('darwin')) {
        osLabel = 'darwin'
    } else if (os.contains('windows')) {
        osLabel = 'windows'
    } else {
        throw new RuntimeException("Unsupported operating system '${os}' for Terraform auto-install.")
    }

    String archLabel
    switch (arch) {
        case ['amd64', 'x86_64']:
            archLabel = 'amd64'
            break
        case ['arm64', 'aarch64']:
            archLabel = 'arm64'
            break
        default:
            throw new RuntimeException("Unsupported architecture '${arch}' for Terraform auto-install.")
    }

    return [os: osLabel, arch: archLabel]
}

private Map normalizeDeploy(Map raw) {
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
        DEFAULT_ENV_ORDER.each { def candidate ->
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

private Map normalizeEnvironment(String name, Map envCfg, Map appCfg) {
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
        repoCredentials = [id: repoCredentialId]
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

@NonCPS
private String defaultConditionFor(String envName) {
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

@NonCPS
private Map normalizeSmoke(Object raw) {
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

@NonCPS
private Map normalizeApproval(Object raw) {
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

// --------------------------- HELPERS -----------------------------------------
private String locateConfig() {
    if (fileExists(PRIMARY_CONFIG)) return PRIMARY_CONFIG
    if (fileExists(LEGACY_CONFIG)) return LEGACY_CONFIG
    return null
}

private String determineHome(String ws) {
    if (env.HOME?.trim() && env.HOME != '/') {
        return env.HOME
    }
    return ws
}

private String resolveCommitSha() {
    if (env.GIT_COMMIT?.trim()) {
        return env.GIT_COMMIT.trim()
    }
    return sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
}

private String resolveImageTag(Map imageCfg, Map state) {
    Map ctx = templateContext(state)
    String candidate = null

    if (imageCfg.tagTemplate) {
        candidate = renderTemplate(imageCfg.tagTemplate.toString(), ctx)
    } else if (state.tagName?.trim()) {
        candidate = state.tagName.trim()
    } else {
        switch (imageCfg.tagStrategy) {
            case 'commit':
                candidate = state.commitShort ?: state.commit
                break
            case 'build':
                if (ctx.branchSlug && ctx.buildNumber) {
                    candidate = "${ctx.branchSlug}-${ctx.buildNumber}"
                } else {
                    candidate = state.commitShort ?: state.commit
                }
                break
            case 'branch':
                candidate = ctx.branchSlug ?: 'latest'
                break
            case 'branch-sha':
            default:
                String branch = ctx.branchSlug ?: 'build'
                String sha = state.commitShort ?: state.commit
                candidate = "${branch}-${sha}"
                break
        }
    }

    candidate = (candidate ?: '').trim()
    if (!candidate) {
        String fallbackBranch = ctx.branchSlug ?: 'build'
        String fallbackSha = state.commitShort ?: state.commit
        if (!fallbackSha?.trim()) {
            fallbackSha = System.currentTimeMillis().toString()
        }
        candidate = "${fallbackBranch}-${fallbackSha}"
    }

    return candidate
}

@NonCPS
private String sanitizeBranch(String branch) {
    if (!branch) {
        return ''
    }
    String cleaned = branch.replaceAll(/[^A-Za-z0-9_.-]+/, '-')
    cleaned = cleaned.replaceAll(/^-+/, '').replaceAll(/-+$/, '')
    return cleaned.toLowerCase()
}

@NonCPS
private String parentDir(String path) {
    if (!path) {
        return null
    }
    int idx = path.lastIndexOf('/')
    if (idx <= 0) {
        return null
    }
    return path.substring(0, idx)
}

@NonCPS
private Map templateContext(Map state) {
    return [
        commit     : state.commit ?: '',
        commitShort: state.commitShort ?: '',
        branch     : state.branch ?: '',
        branchSlug : state.branchSanitized ?: '',
        imageTag   : state.imageTag ?: '',
        buildNumber: state.buildNumber ?: '',
        tagName    : state.tagName ?: '',
        repository : state.repository ?: ''
    ]
}

@NonCPS
private String renderTemplate(String template, Map ctx) {
    if (!template) return ''
    String result = template
    ctx.each { k, v ->
        String pattern = "\\{\\{\\s*${Pattern.quote(k)}\\s*\\}\\}"
        result = result.replaceAll(pattern, Matcher.quoteReplacement(v ?: ''))
    }
    return result
}

@NonCPS
private List<String> toStringList(Object raw) {
    List<String> list = []
    if (raw instanceof Collection) {
        raw.each { if (it != null) list << it.toString() }
    } else if (raw != null) {
        list << raw.toString()
    }
    return list
}

@NonCPS
private Map<String, String> toStringMap(Object raw) {
    Map<String, String> map = [:]
    if (raw instanceof Map) {
        (raw as Map).each { k, v ->
            if (k != null && v != null) {
                map[k.toString()] = v.toString()
            }
        }
    }
    return map
}

@NonCPS
private List<String> mapToEnvList(Map data) {
    if (!(data instanceof Map) || data.isEmpty()) {
        return []
    }
    List<String> envList = []
    (data as Map).each { k, v ->
        envList << "${k}=${v}"
    }
    return envList
}

private String resolveEnvVar(String name) {
    if (!name?.trim()) {
        return ''
    }
    String trimmed = name.trim()
    if (!(trimmed ==~ /^[A-Za-z_][A-Za-z0-9_]*$/)) {
        echo "Invalid environment variable name '${trimmed}'; skipping lookup."
        return ''
    }
    StringBuilder script = new StringBuilder()
    script.append('#!/bin/sh\n')
    script.append('set +x\n')
    script.append('if [ -z "${').append(trimmed).append('+x}" ]; then\n')
    script.append('  exit 0\n')
    script.append('fi\n')
    script.append('printf \'%s\' "${').append(trimmed).append('}"\n')
    return sh(script: script.toString(), returnStdout: true).trim()
}

private List<Map> buildCredentialBindings(Map envCfg) {
    List<Map> bindings = []
    if (envCfg.kubeconfigCredential) {
        bindings << [$class: 'FileBinding', credentialsId: envCfg.kubeconfigCredential, variable: (envCfg.kubeconfigEnv ?: 'KUBECONFIG')]
    }
    (envCfg.credentials ?: []).each { Map cred ->
        switch (cred.type) {
            case 'file':
                bindings << [$class: 'FileBinding', credentialsId: cred.id, variable: cred.env]
                break
            case 'usernamePassword':
                bindings << [$class: 'UsernamePasswordMultiBinding', credentialsId: cred.id, usernameVariable: cred.usernameEnv, passwordVariable: cred.passwordEnv]
                break
            default:
                bindings << [$class: 'StringBinding', credentialsId: cred.id, variable: cred.env]
                break
        }
    }
    return bindings
}

private void withOptionalCredentials(List<Map> bindings, Closure body) {
    if (bindings && !bindings.isEmpty()) {
        withCredentials(bindings) {
            body()
        }
    } else {
        body()
    }
}

private void awaitApproval(Map approval, String defaultMessage) {
    if (!approval || approval.isEmpty()) {
        return
    }
    String message = approval.message ?: defaultMessage
    String ok = approval.ok ?: 'Approve'
    if (approval.submitter) {
        input message: message, ok: ok, submitter: approval.submitter.toString()
    } else {
        input message: message, ok: ok
    }
}

// --------------------------- VERBS -------------------------------------------
private void runVerb(String spec) {
    spec = spec.trim()
    if (spec.startsWith('sh ')) {
        def cmd = unquote(spec.substring(3).trim())
        sh cmd
        return
    }
    if (spec.startsWith('npm ')) {
        def args = unquote(spec.substring(4).trim())
        sh "npm ${args}"
        return
    }
    if (spec == 'node.install') {
        sh "npm ci"
        return
    }
    if (spec == 'node.build') {
        sh "npm run build"
        return
    }
    if (spec == 'node.test') {
        sh "npm test -- --ci"
        return
    }
    if (spec == 'node.lint') {
        sh "npm run lint"
        return
    }
    if (spec == 'gradle.build') {
        sh "./gradlew build --no-daemon"
        return
    }
    if (spec == 'gradle.test') {
        sh "./gradlew test --no-daemon"
        return
    }
    if (spec == 'gradle.publish') {
        sh "./gradlew publish --no-daemon"
        return
    }
    if (spec.startsWith('docker.build')) {
        def kv = parseKVs(spec - 'docker.build')
        def tag = kv['tag'] ?: error("docker.build requires tag=<tag>")
        def file = kv['file'] ?: '.'
        sh "docker build -t ${shellEscape(tag)} ${shellEscape(file)}"
        return
    }
    if (spec.startsWith('docker.push')) {
        def kv = parseKVs(spec - 'docker.push')
        def tag = kv['tag'] ?: error("docker.push requires tag=<tag>")
        sh "docker push ${shellEscape(tag)}"
        return
    }
    if (spec.startsWith('k8s.apply')) {
        def kv = parseKVs(spec - 'k8s.apply')
        def file = kv['file'] ?: error("k8s.apply requires file=<path>")
        sh "kubectl apply -f ${shellEscape(file)}"
        return
    }
    error "Unknown verb: '${spec}'"
}

@NonCPS
private String unquote(String s) {
    s = s.trim()
    if ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith("'") && s.endsWith("'"))) {
        return s.substring(1, s.length() - 1)
    }
    return s
}

@NonCPS
private Map parseKVs(String tail) {
    def m = [:]
    tail.trim().split(/\s+/).findAll { it.contains('=') }.each { p ->
        def i = p.indexOf('=')
        def k = p.substring(0, i).trim()
        def v = p.substring(i + 1).trim()
        m[k] = unquote(v)
    }
    return m
}

@NonCPS
private String shellEscape(String s) {
    return "'${s.replace("'", "'\"'\"'")}'"
}
private void withReleaseCredentials(Map autoTag, Closure body) {
    Map cred = autoTag?.credential ?: [:]
    if (!cred.id) {
        body()
        return
    }

    String remote = (autoTag.remote ?: 'origin').toString()
    String originalUrl = ''
    boolean hasRemote = true
    try {
        originalUrl = sh(script: "git remote get-url ${shellEscape(remote)}", returnStdout: true).trim()
    } catch (Exception ignored) {
        hasRemote = false
    }

    Closure restore = {
        if (hasRemote && originalUrl) {
            sh "git remote set-url ${shellEscape(remote)} ${shellEscape(originalUrl)}"
        }
    }

    switch ((cred.type ?: 'usernamePassword').toString()) {
        case 'string':
            String tokenEnv = cred.tokenEnv ?: 'GIT_TOKEN'
            String tokenUser = cred.tokenUser ?: 'x-access-token'
            withCredentials([string(credentialsId: cred.id, variable: tokenEnv)]) {
                if (hasRemote && originalUrl) {
                    String tokenVal = resolveEnvVar(tokenEnv)
                    String updated = injectCredentialsIntoUrl(originalUrl, tokenUser, tokenVal ?: '')
                    sh "git remote set-url ${shellEscape(remote)} ${shellEscape(updated)}"
                }
                try {
                    body()
                } finally {
                    restore()
                }
            }
            break
        default:
            String userEnv = cred.usernameEnv ?: 'GIT_USERNAME'
            String passEnv = cred.passwordEnv ?: 'GIT_PASSWORD'
            withCredentials([usernamePassword(credentialsId: cred.id, usernameVariable: userEnv, passwordVariable: passEnv)]) {
                if (hasRemote && originalUrl) {
                    String userVal = resolveEnvVar(userEnv) ?: ''
                    String passVal = resolveEnvVar(passEnv) ?: ''
                    String updated = injectCredentialsIntoUrl(originalUrl, userVal, passVal)
                    sh "git remote set-url ${shellEscape(remote)} ${shellEscape(updated)}"
                }
                try {
                    body()
                } finally {
                    restore()
                }
            }
            break
    }
}

private String injectCredentialsIntoUrl(String remoteUrl, String username, String password) {
    if (!remoteUrl) {
        return remoteUrl
    }
    if (!(remoteUrl.startsWith('http://') || remoteUrl.startsWith('https://'))) {
        return remoteUrl
    }
    String scheme = remoteUrl.startsWith('https://') ? 'https://' : 'http://'
    String remainder = remoteUrl.substring(scheme.length())
    String safeUser = urlEncode(username ?: '')
    String safePass = password ? urlEncode(password) : ''
    String userInfo = safePass ? "${safeUser}:${safePass}@" : "${safeUser}@"
    if (remainder.startsWith(userInfo)) {
        return scheme + remainder
    }
    return scheme + userInfo + remainder
}

@NonCPS
private String urlEncode(String value) {
    try {
        return URLEncoder.encode(value ?: '', 'UTF-8').replace('+', '%20')
    } catch (Exception ignored) {
        return value ?: ''
    }
}

private void publishRelease(Map releaseCfg, Map state) {
    Map githubCfg = releaseCfg?.github ?: [:]
    String tag = (env.RELEASE_TAG ?: '').trim()
    if (!(githubCfg.enabled as Boolean)) {
        return
    }
    if (!tag) {
        echo "Release tag not available; skipping GitHub release."
        return
    }
    if (!Helpers.matchCondition(githubCfg.when as String, env)) {
        echo "Skip GitHub release — condition '${githubCfg.when}' not met"
        return
    }

    stage(githubCfg.stageName ?: 'GitHub Release') {
        withGithubReleaseCredential(githubCfg, releaseCfg?.autoTag?.credential ?: [:]) { Map auth ->
            publishGithubReleaseInternal(githubCfg, state, auth, tag)
        }
    }
}

private void withGithubReleaseCredential(Map githubCfg, Map fallbackCredential, Closure body) {
    Map cred = githubCfg?.credential ?: [:]
    if (!cred.id && fallbackCredential?.id) {
        cred = fallbackCredential
    }
    if (!cred.id) {
        echo "GitHub release credential not configured; skipping release."
        return
    }
    String type = (cred.type ?: 'string').toString()
    switch (type) {
        case 'string':
            String tokenEnv = cred.tokenEnv ?: 'GITHUB_TOKEN'
            withCredentials([string(credentialsId: cred.id, variable: tokenEnv)]) {
                body([mode: 'token', tokenEnv: tokenEnv])
            }
            break
        default:
            String userEnv = cred.usernameEnv ?: 'GITHUB_USERNAME'
            String passEnv = cred.passwordEnv ?: 'GITHUB_PASSWORD'
            withCredentials([usernamePassword(credentialsId: cred.id, usernameVariable: userEnv, passwordVariable: passEnv)]) {
                body([mode: 'basic', usernameEnv: userEnv, passwordEnv: passEnv])
            }
            break
    }
}

private void publishGithubReleaseInternal(Map githubCfg, Map state, Map auth, String tag) {
    String apiBase = (githubCfg.apiUrl ?: 'https://api.github.com').toString().replaceAll('/+$', '')
    String repo = determineGithubRepo(githubCfg.repo)
    if (!repo) {
        echo "Unable to determine GitHub repository; skipping release."
        return
    }

    String target = (githubCfg.target ?: state.commit ?: '').toString()
    if (!target?.trim()) {
        target = state.commit ?: ''
    }
    String nameTemplate = githubCfg.nameTemplate ?: tag
    String releaseName = renderReleaseTemplate(nameTemplate.toString(), state, tag)
    String bodyTemplate = githubCfg.bodyTemplate?.toString()
    String releaseBody = bodyTemplate ? renderReleaseTemplate(bodyTemplate, state, tag) : null
    boolean draft = githubCfg.containsKey('draft') ? githubCfg.draft as Boolean : false
    boolean prerelease = githubCfg.containsKey('prerelease') ? githubCfg.prerelease as Boolean : false
    boolean generateNotes = githubCfg.containsKey('generateReleaseNotes') ? githubCfg.generateReleaseNotes as Boolean : true
    String discussionCategory = githubCfg.discussionCategory?.toString()

    Map payload = [
        tag_name          : tag,
        target_commitish  : target ?: state.commit ?: '',
        name              : releaseName,
        draft             : draft,
        prerelease        : prerelease
    ]
    if (generateNotes) {
        payload.generate_release_notes = true
    }
    if (releaseBody) {
        payload.body = releaseBody
    }
    if (discussionCategory) {
        payload.discussion_category_name = discussionCategory
    }

    String payloadJson = JsonOutput.toJson(payload)
    String apiUrl = "${apiBase}/repos/${repo}/releases"
    String checkUrl = "${apiBase}/repos/${repo}/releases/tags/${tag}"

    String script
    if ('basic'.equalsIgnoreCase(auth.mode as String)) {
        String userEnv = auth.usernameEnv ?: 'GITHUB_USERNAME'
        String passEnv = auth.passwordEnv ?: 'GITHUB_PASSWORD'
        String userRef = '${' + userEnv + '}'
        String passRef = '${' + passEnv + '}'
        List<String> lines = []
        lines << '#!/usr/bin/env bash'
        lines << 'set -euo pipefail'
        lines << 'set +x'
        lines << "TAG=${shellEscape(tag)}"
        lines << "REPO=${shellEscape(repo)}"
        lines << "if [ -z \"${userRef}\" ] || [ -z \"${passRef}\" ]; then"
        lines << '  echo "GitHub credentials unavailable; skipping release." >&2'
        lines << '  exit 1'
        lines << 'fi'
        lines << "auth=\"${userRef}:${passRef}\""
        lines << "CHECK_STATUS=\$(curl -s -o /dev/null -w '%{http_code}' -u \"\${auth}\" -H \"Accept: application/vnd.github+json\" ${shellEscape(checkUrl)} || true)"
        lines << 'if [ "$CHECK_STATUS" = "200" ]; then'
        lines << '  echo "GitHub release for ${TAG} already exists; skipping."'
        lines << '  exit 0'
        lines << 'fi'
        lines << "curl -sSf -X POST -u \"\${auth}\" -H \"Accept: application/vnd.github+json\" -H \"Content-Type: application/json\" --data ${shellEscape(payloadJson)} ${shellEscape(apiUrl)} > /dev/null"
        lines << 'echo "Published GitHub release ${TAG} to ${REPO}"'
        script = lines.join('\n')
    } else {
        String tokenEnv = auth.tokenEnv ?: 'GITHUB_TOKEN'
        String tokenRef = '${' + tokenEnv + '}'
        List<String> lines = []
        lines << '#!/usr/bin/env bash'
        lines << 'set -euo pipefail'
        lines << 'set +x'
        lines << "TAG=${shellEscape(tag)}"
        lines << "REPO=${shellEscape(repo)}"
        lines << "if [ -z \"${tokenRef}\" ]; then"
        lines << '  echo "GitHub token unavailable; skipping release." >&2'
        lines << '  exit 1'
        lines << 'fi'
        lines << "auth_header=\"Authorization: Bearer ${tokenRef}\""
        lines << "CHECK_STATUS=\$(curl -s -o /dev/null -w '%{http_code}' -H \"\${auth_header}\" -H \"Accept: application/vnd.github+json\" ${shellEscape(checkUrl)} || true)"
        lines << 'if [ "$CHECK_STATUS" = "200" ]; then'
        lines << '  echo "GitHub release for ${TAG} already exists; skipping."'
        lines << '  exit 0'
        lines << 'fi'
        lines << "curl -sSf -X POST -H \"\${auth_header}\" -H \"Accept: application/vnd.github+json\" -H \"Content-Type: application/json\" --data ${shellEscape(payloadJson)} ${shellEscape(apiUrl)} > /dev/null"
        lines << 'echo "Published GitHub release ${TAG} to ${REPO}"'
        script = lines.join('\n')
    }

    sh script: script, label: 'Publish GitHub release'
}

private String determineGithubRepo(String configured) {
    if (configured?.trim()) {
        return configured.trim().replaceAll(/\\.git$/, '')
    }
    try {
        String remote = sh(script: 'git remote get-url origin', returnStdout: true).trim()
        String parsed = parseGithubRepoFromUrl(remote)
        return parsed?.replaceAll(/\\.git$/, '')
    } catch (Exception e) {
        echo "Failed to determine GitHub repository: ${e.message}"
        return ''
    }
}

@NonCPS
private String parseGithubRepoFromUrl(String url) {
    if (!url) {
        return ''
    }
    String cleaned = url.trim()
    if (cleaned.endsWith('.git')) {
        cleaned = cleaned.substring(0, cleaned.length() - 4)
    }
    if (cleaned.startsWith('git@')) {
        int idx = cleaned.indexOf(':')
        if (idx >= 0 && idx + 1 < cleaned.length()) {
            return cleaned.substring(idx + 1)
        }
        return ''
    }
    try {
        URI uri = new URI(cleaned)
        String path = uri.getPath() ?: ''
        path = path.replaceAll('^/', '')
        return path
    } catch (Exception ignored) {
        // Fallback to simple stripping
    }
    int githubIdx = cleaned.indexOf('github.com/')
    if (githubIdx >= 0) {
        return cleaned.substring(githubIdx + 'github.com/'.length())
    }
    return cleaned
}

@NonCPS
private String renderReleaseTemplate(String template, Map state, String tag) {
    if (!template) {
        return ''
    }
    Map<String, String> tokens = [
        tag         : tag,
        commit      : state.commit ?: '',
        commit_short: state.commitShort ?: '',
        branch      : state.branch ?: '',
        image_tag   : state.imageTag ?: '',
        build       : state.buildNumber ?: ''
    ]
    String result = template
    tokens.each { k, v ->
        result = result.replace("{{${k}}}", v ?: '')
    }
    return result
}
