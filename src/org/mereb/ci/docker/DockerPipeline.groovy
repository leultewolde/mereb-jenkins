package org.mereb.ci.docker

import org.mereb.ci.Helpers

import static org.mereb.ci.util.PipelineUtils.parentDir
import static org.mereb.ci.util.PipelineUtils.renderTemplate
import static org.mereb.ci.util.PipelineUtils.shellEscape
import static org.mereb.ci.util.PipelineUtils.templateContext

/**
 * Handles docker build/push/sign orchestration so Jenkinsfile stays declarative.
 */
class DockerPipeline implements Serializable {

    private final def steps

    DockerPipeline(def steps) {
        this.steps = steps
    }

    void run(Map cfg, Map state) {
        if (!(cfg.image.enabled as Boolean)) {
            steps.echo 'Docker stages disabled for this pipeline; skipping build/push/sign.'
            return
        }

        steps.stage('Docker Build') {
            dockerBuild(cfg.image, state)
        }

        if (shouldGenerateSbom(cfg)) {
            steps.stage('SBOM') {
                generateSbom(cfg, state)
            }
        }

        if (shouldScan(cfg)) {
            steps.stage('Vulnerability Scan') {
                scanImage(cfg, state)
            }
        }

        if (shouldPush(cfg)) {
            steps.stage('Docker Push') {
                dockerPush(cfg, state)
            }
            if (cfg.image.verifyPull) {
                steps.stage('Verify Pull') {
                    verifyPull(cfg, state)
                }
            }
        } else {
            steps.echo "Docker push skipped by condition '${cfg.image.push.when}'"
            if (cfg.image.verifyPull) {
                steps.echo 'Verify Pull skipped because push did not run.'
            }
        }

        if (shouldSign(cfg)) {
            steps.stage('Sign Image') {
                signImage(cfg, state)
            }
        }
    }

    static String computeImageTag(Map imageCfg, Map state) {
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

    private void dockerBuild(Map imageCfg, Map state) {
        List<String> cmd = ['docker', 'build']
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
        steps.sh cmd.join(' ')
    }

    private void dockerPush(Map cfg, Map state) {
        Map pushCfg = cfg.image.push
        dockerLoginIfNeeded(cfg.image, pushCfg)
        String pushRepository = resolvePushRepository(cfg.image, pushCfg)
        if (!pushRepository?.trim()) {
            throw new IllegalStateException('Unable to determine repository to push.')
        }
        String pushRef = "${pushRepository}:${state.imageTag}"
        if (pushRef != state.imageRef) {
            steps.sh "docker tag ${shellEscape(state.imageRef)} ${shellEscape(pushRef)}"
        }
        steps.sh "docker push ${shellEscape(pushRef)}"
        String sourceForExtraTags = pushRef
        (pushCfg.extraTags ?: []).each { rawTag ->
            String rendered = renderTemplate(rawTag.toString(), templateContext(state))
            if (!rendered?.trim()) {
                return
            }
            String ref = "${pushRepository}:${rendered}"
            steps.sh "docker tag ${shellEscape(sourceForExtraTags)} ${shellEscape(ref)}"
            steps.sh "docker push ${shellEscape(ref)}"
        }
    }

    private void verifyPull(Map cfg, Map state) {
        Map pushCfg = cfg.image.push
        dockerLoginIfNeeded(cfg.image, pushCfg)
        String pushRepository = resolvePushRepository(cfg.image, pushCfg)
        if (!pushRepository?.trim()) {
            throw new IllegalStateException('Unable to determine repository for pull verification.')
        }

        String pushRef = "${pushRepository}:${state.imageTag}"
        // Remove local copies to ensure pull hits the registry.
        steps.sh "docker rmi ${shellEscape(pushRef)} || true"
        if (pushRef != state.imageRef) {
            steps.sh "docker rmi ${shellEscape(state.imageRef)} || true"
        }

        steps.sh "docker pull ${shellEscape(pushRef)}"
    }

    private boolean shouldGenerateSbom(Map cfg) {
        if (!(cfg.image.enabled as Boolean)) {
            return false
        }
        if (!(cfg.sbom.enabled as Boolean)) {
            return false
        }
        if (!commandAvailable('syft')) {
            steps.echo "Skipping SBOM generation because 'syft' is not available on PATH."
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
            steps.sh "mkdir -p ${shellEscape(dir)}"
        }
        List<String> cmd = []
        cmd << "syft ${shellEscape(state.imageRef)}"
        cmd << "-o ${shellEscape(sbom.format as String)}"
        cmd << "-f ${shellEscape(output)}"
        cmd << '--scope all-layers'
        steps.sh cmd.join(' ')
        steps.archiveArtifacts artifacts: output, fingerprint: true
    }

    private boolean shouldScan(Map cfg) {
        if (!(cfg.image.enabled as Boolean)) {
            return false
        }
        if (!(cfg.scan.enabled as Boolean)) {
            return false
        }
        if (!commandAvailable('grype')) {
            steps.echo "Skipping vulnerability scan because 'grype' is not available on PATH."
            return false
        }
        return true
    }

    private void scanImage(Map cfg, Map state) {
        Map scan = cfg.scan
        List<String> cmd = []
        cmd << "grype ${shellEscape(state.imageRef)}"
        cmd << '--add-cpes-if-none'
        cmd << "--fail-on ${shellEscape(scan.failOn as String)}"
        (scan.flags ?: []).each { flag ->
            cmd << flag.toString()
        }
        steps.sh cmd.join(' ')
    }

    private boolean shouldPush(Map cfg) {
        if (!(cfg.image.enabled as Boolean)) {
            return false
        }
        def pushCfg = cfg.image.push
        if (!(pushCfg.enabled as Boolean)) {
            return false
        }
        return Helpers.matchCondition(pushCfg.when as String, steps.env)
    }

    private void dockerLoginIfNeeded(Map imageCfg, Map pushCfg) {
        Map credentials = pushCfg.credentials instanceof Map ? pushCfg.credentials : [:]
        String credentialId = (credentials.id ?: '').toString().trim()
        if (!credentialId) {
            return
        }

        String usernameEnv = (credentials.usernameEnv ?: 'DOCKER_USERNAME').toString()
        String passwordEnv = (credentials.passwordEnv ?: 'DOCKER_PASSWORD').toString()
        String registry = determineRegistryLoginHost(imageCfg, pushCfg)
        String hostSegment = registry ? " ${shellEscape(registry)}" : ''
        steps.echo "Logging in to Docker registry '${registry}' using credential '${credentialId}', username '${usernameEnv}'."
        steps.withCredentials([
            steps.usernamePassword(credentialsId: credentialId, usernameVariable: usernameEnv, passwordVariable: passwordEnv)
        ]) {
            String passRef = '$' + passwordEnv
            String userRef = '$' + usernameEnv
            steps.sh "echo \"${passRef}\" | docker login${hostSegment} -u \"${userRef}\" --password-stdin"
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
        return Helpers.matchCondition(signing.when as String, steps.env)
    }

    private void signImage(Map cfg, Map state) {
        Map signing = cfg.signing
        Map ctx = templateContext(state)
        List<String> cmd = ['cosign', 'sign']
        if (signing.keyless) {
            cmd << '--keyless'
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
            steps.withEnv(envPairs) {
                steps.sh cmd.join(' ')
            }
        } else {
            steps.sh cmd.join(' ')
        }
    }

    private static String determineRegistryLoginHost(Map imageCfg, Map pushCfg) {
        String override = sanitizeRegistryValue(pushCfg?.registry)
        String fallback = sanitizeRegistryValue(imageCfg?.registryHost)
        String candidate = override ?: fallback
        if (!candidate) {
            candidate = 'docker.io'
        }
        int slash = candidate.indexOf('/')
        if (slash >= 0) {
            candidate = candidate.substring(0, slash)
        }
        return candidate
    }

    static String resolvePushRepository(Map imageCfg, Map pushCfg) {
        String repository = (imageCfg?.repository ?: '').toString()
        String registryOverride = (pushCfg?.registry ?: '').toString()
        return resolvePushRepository(repository, registryOverride)
    }

    static String resolvePushRepository(String repository, String registryOverride) {
        String sanitizedRepo = sanitizeRegistryValue(repository)
        if (!sanitizedRepo) {
            return ''
        }
        String override = sanitizeRegistryValue(registryOverride)
        if (!override) {
            return sanitizedRepo
        }
        String repoPath = repositoryPath(sanitizedRepo)
        if (!repoPath) {
            return override
        }
        return "${override}/${repoPath}"
    }

    private static String sanitizeRegistryValue(Object raw) {
        if (!raw) {
            return ''
        }
        String value = raw.toString().trim()
        if (!value) {
            return ''
        }
        value = value.replaceFirst('^https?://', '')
        return value.replaceAll('/+$', '')
    }

    private static String repositoryPath(String repository) {
        if (!repository) {
            return ''
        }
        String repo = repository.replaceFirst('^https?://', '')
        int slash = repo.indexOf('/')
        if (slash < 0) {
            return repo
        }
        String firstSegment = repo.substring(0, slash)
        boolean looksLikeRegistry = firstSegment.contains('.') || firstSegment.contains(':') || firstSegment == 'localhost'
        if (looksLikeRegistry) {
            return slash + 1 < repo.length() ? repo.substring(slash + 1) : ''
        }
        return repo
    }

    private boolean commandAvailable(String binary) {
        if (!binary?.trim()) {
            return false
        }
        String escaped = shellEscape(binary)
        String script = "command -v ${escaped} >/dev/null 2>&1 && echo yes || echo no"
        return steps.sh(script: script, returnStdout: true).trim() == 'yes'
    }
}
