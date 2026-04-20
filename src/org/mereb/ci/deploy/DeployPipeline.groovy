package org.mereb.ci.deploy

import org.mereb.ci.Helpers
import org.mereb.ci.credentials.CredentialHelper
import org.mereb.ci.delivery.DeliveryPolicy
import org.mereb.ci.util.StageExecutor

import static org.mereb.ci.util.PipelineUtils.mapToEnvList
import static org.mereb.ci.util.PipelineUtils.shellEscape

/**
 * Orchestrates Helm-based environment deployments so ciV1 stays declarative.
 */
class DeployPipeline implements Serializable {

    private final def steps
    private final CredentialHelper credentialHelper
    private final HelmDeploymentContextBuilder contextBuilder
    private final StageExecutor stageExecutor
    private final Closure verbRunner

    DeployPipeline(def steps, CredentialHelper credentialHelper, Closure verbRunner = null) {
        this.steps = steps
        this.credentialHelper = credentialHelper
        this.contextBuilder = new HelmDeploymentContextBuilder(steps)
        this.stageExecutor = new StageExecutor(steps, credentialHelper)
        this.verbRunner = verbRunner
    }

    void run(Map cfg, Map state, Closure afterEnvCallback = null) {
        Map deploy = cfg?.deploy ?: [:]
        Map envs = deploy.environments ?: [:]
        List<String> order = deploy.order ?: []
        if (!envs || envs.isEmpty() || !order) {
            steps.echo 'No deployment environments configured; skipping deploy.'
            return
        }

        DeliveryPolicy deliveryPolicy = cfg?.delivery?.policy instanceof DeliveryPolicy ? (cfg.delivery.policy as DeliveryPolicy) : null

        order.each { String envName ->
            Map envCfg = envs[envName]
            if (!envCfg) {
                steps.echo "Environment '${envName}' not defined; skipping."
                return
            }
            if (!shouldDeployEnvironment(deliveryPolicy, envName, envCfg)) {
                steps.echo "Skip deploy '${envCfg.displayName}' — condition '${envCfg.when}' not met"
                return
            }

            List<Map> deployBindings = credentialHelper.bindingsFor(envCfg)
            HelmDeploymentContext deploymentContext = contextBuilder.build(envName, envCfg, cfg.image, state)

            steps.stage("Deploy ${envCfg.displayName}") {
                Map helmArgs = deploymentContext.helmArgs
                Closure runDeploy = {
                    ensureImagePullSecret(envCfg, cfg.image)
                    credentialHelper.withRepoCredentials(envCfg.repoCredentials) { Map repoCreds ->
                        Map args = [:]
                        args.putAll(helmArgs)
                        if (repoCreds?.username) {
                            args.repoUsername = repoCreds.username
                        }
                        if (repoCreds?.password) {
                            args.repoPassword = repoCreds.password
                        }
                        steps.helmDeploy(args)
                        restartAndVerifyWorkloads(envCfg, state)
                    }
                }

                credentialHelper.withOptionalCredentials(deployBindings) {
                    withKubernetesDiagnostics(envCfg, 'deploy', state, runDeploy)
                }
            }

            runSmoke(envCfg, deployBindings)
            runPostDeployStages(envCfg, state)

            afterEnvCallback?.call(envName)
        }
    }

    private void ensureImagePullSecret(Map envCfg, Map imageCfg) {
        if (!(imageCfg?.enabled as Boolean)) {
            return
        }

        Map pullCreds = imageCfg?.push?.credentials instanceof Map ? (Map) imageCfg.push.credentials : [:]
        String credentialId = pullCreds.id?.toString()?.trim()
        String namespace = (envCfg.namespace ?: 'default').toString().trim()
        String registry = imageCfg?.registryHost?.toString()?.trim()
        if (!credentialId || !namespace || !registry) {
            return
        }

        String secretName = 'regcred'
        String usernameEnv = sanitizeEnvVar((pullCreds.usernameEnv ?: 'DOCKER_USERNAME').toString(), 'DOCKER_USERNAME')
        String passwordEnv = sanitizeEnvVar((pullCreds.passwordEnv ?: 'DOCKER_PASSWORD').toString(), 'DOCKER_PASSWORD')

        Closure applySecret = {
            String kubectl = buildKubectlBase(envCfg)
            steps.withCredentials([
                steps.usernamePassword(credentialsId: credentialId, usernameVariable: usernameEnv, passwordVariable: passwordEnv)
            ]) {
                steps.sh("""#!/usr/bin/env bash
set -euo pipefail
${kubectl} get namespace ${shellEscape(namespace)} >/dev/null 2>&1 || ${kubectl} create namespace ${shellEscape(namespace)} >/dev/null
${kubectl} -n ${shellEscape(namespace)} create secret docker-registry ${shellEscape(secretName)} \\
  --docker-server=${shellEscape(registry)} \\
  --docker-username="\$${usernameEnv}" \\
  --docker-password="\$${passwordEnv}" \\
  --dry-run=client -o yaml | ${kubectl} apply -f -
""")
            }
        }

        if (envCfg.kubeconfig) {
            steps.withEnv(["KUBECONFIG=${envCfg.kubeconfig}"]) {
                applySecret.call()
            }
            return
        }
        applySecret.call()
    }

    private boolean shouldDeployEnvironment(DeliveryPolicy deliveryPolicy, String envName, Map envCfg) {
        if (deliveryPolicy?.isStagedMode()) {
            return deliveryPolicy.shouldDeployEnvironment(envName)
        }
        return Helpers.matchCondition(envCfg.when as String, steps.env)
    }

    private void runSmoke(Map envCfg, List<Map> bindings) {
        Map smoke = envCfg.smoke ?: [:]
        if (!(smoke.url || smoke.script || smoke.command)) {
            return
        }
        steps.stage("Smoke ${envCfg.displayName}") {
            Map payload = [:]
            payload.putAll(smoke)
            payload.environment = envCfg.displayName
            Closure run = {
                withKubernetesDiagnostics(envCfg, 'smoke', [:]) {
                    steps.runSmoke(payload)
                }
            }
            credentialHelper.withOptionalCredentials(bindings, run)
        }
    }

    private void runPostDeployStages(Map envCfg, Map state) {
        List<Map> stages = envCfg.postDeployStages instanceof List ? (envCfg.postDeployStages as List<Map>) : []
        if (!stages || stages.isEmpty()) {
            return
        }

        stages.each { Map stageCfg ->
            String name = stageCfg.name ?: "Post-Deploy ${envCfg.displayName}"
            String whenCond = stageCfg.when ?: ''
            if (whenCond && !Helpers.matchCondition(whenCond, steps.env)) {
                steps.echo "Post-deploy stage '${name}' skipped by condition '${whenCond}'"
                return
            }

            List<String> envList = mapToEnvList(stageCfg.env instanceof Map ? stageCfg.env : [:])
            envList << "DEPLOY_ENV=${envCfg.name}".toString()
            envList << "DEPLOY_DISPLAY_NAME=${envCfg.displayName}".toString()
            envList << "DEPLOY_NAMESPACE=${envCfg.namespace}".toString()
            envList << "DEPLOY_RELEASE=${envCfg.release}".toString()
            if (state?.imageTag) {
                envList << "IMAGE_TAG=${state.imageTag}".toString()
            }
            if (state?.imageRef) {
                envList << "IMAGE_REF=${state.imageRef}".toString()
            }
            if (state?.repository) {
                envList << "IMAGE_REPOSITORY=${state.repository}".toString()
            }
            Map bindingSource = [credentials: stageCfg.credentials]
            List<Map> bindings = credentialHelper.bindingsFor(bindingSource)

            stageExecutor.run(name, envList, bindings) {
                if (stageCfg.verb) {
                    if (verbRunner == null) {
                        steps.error "Stage '${name}' uses verb '${stageCfg.verb}', but no verb runner is available."
                    }
                    verbRunner.call(stageCfg.verb as String)
                } else if (stageCfg.sh) {
                    steps.sh stageCfg.sh as String
                } else {
                    steps.echo "Stage '${name}' has no action."
                }
            }
        }
    }

    private void restartAndVerifyWorkloads(Map envCfg, Map state) {
        if (envCfg.containsKey('restartWorkloads') && !(envCfg.restartWorkloads as Boolean)) {
            return
        }
        String namespace = envCfg.namespace ?: 'default'
        String release = envCfg.release ?: envCfg.name
        String timeout = (envCfg.rolloutTimeout ?: envCfg.timeout ?: '10m').toString()
        List<String> workloads = discoverWorkloads(namespace, release)
        if (workloads.isEmpty()) {
            steps.error "Helm release '${release}' did not produce any Deployment or StatefulSet workloads in namespace '${namespace}'."
        }
        workloads.each { String workload ->
            steps.sh "kubectl -n ${shellEscape(namespace)} rollout restart ${shellEscape(workload)}"
        }
        workloads.each { String workload ->
            steps.sh "kubectl -n ${shellEscape(namespace)} rollout status ${shellEscape(workload)} --timeout=${shellEscape(timeout)}"
        }
        verifyLiveArtifact(namespace, release, workloads, state)
    }

    private List<String> discoverWorkloads(String namespace, String release) {
        String selector = "app.kubernetes.io/instance=${release}"
        List<String> workloads = []
        ['deployment', 'statefulset'].each { String kind ->
            String raw = steps.sh(
                script: "kubectl -n ${shellEscape(namespace)} get ${kind} -l ${shellEscape(selector)} -o name 2>/dev/null || true",
                returnStdout: true
            ).trim()
            if (raw) {
                workloads.addAll(raw.readLines().collect { it?.trim() }.findAll { it })
            }
        }
        return workloads
    }

    private void verifyLiveArtifact(String namespace, String release, List<String> workloads, Map state) {
        if (allWorkloadsScaledToZero(namespace, workloads)) {
            steps.echo "Skip live artifact verification for release '${release}' in namespace '${namespace}' because all workloads are scaled to 0."
            return
        }

        String selector = "app.kubernetes.io/instance=${release}"
        if (state.imageDigest?.trim()) {
            String imageIds = steps.sh(
                script: "kubectl -n ${shellEscape(namespace)} get pods -l ${shellEscape(selector)} -o jsonpath='{range .items[*]}{range .status.containerStatuses[*]}{.imageID}{\"\\n\"}{end}{end}'",
                returnStdout: true
            ).trim()
            if (!imageIds) {
                steps.error "No running pod image IDs found for release '${release}' in namespace '${namespace}'."
            }
            boolean matchesDigest = imageIds.readLines().any { it?.contains(state.imageDigest) }
            if (!matchesDigest) {
                steps.error "Release '${release}' is live, but pod image IDs do not contain expected digest '${state.imageDigest}'."
            }
            return
        }

        String expectedImage = "${state.repository}:${state.imageTag}"
        String images = steps.sh(
            script: "kubectl -n ${shellEscape(namespace)} get pods -l ${shellEscape(selector)} -o jsonpath='{range .items[*]}{range .spec.containers[*]}{.image}{\"\\n\"}{end}{end}'",
            returnStdout: true
        ).trim()
        if (!images) {
            steps.error "No running pod images found for release '${release}' in namespace '${namespace}'."
        }
        boolean matchesImage = images.readLines().any { it?.trim() == expectedImage }
        if (!matchesImage) {
            steps.error "Release '${release}' is live, but none of the running pod images match '${expectedImage}'."
        }
    }

    private boolean allWorkloadsScaledToZero(String namespace, List<String> workloads) {
        if (!workloads || workloads.isEmpty()) {
            return false
        }

        List<Integer> replicas = workloads.collect { String workload ->
            String raw = steps.sh(
                script: "kubectl -n ${shellEscape(namespace)} get ${shellEscape(workload)} -o jsonpath='{.spec.replicas}' 2>/dev/null || true",
                returnStdout: true
            ).trim()
            if (!raw) {
                return 1
            }
            return raw.toInteger()
        }

        return replicas.every { Integer value -> value == 0 }
    }

    private void withKubernetesDiagnostics(Map envCfg, String phase, Map state, Closure action) {
        try {
            action.call()
        } catch (Throwable err) {
            try {
                emitKubernetesDiagnostics(envCfg, phase, state)
            } catch (Throwable diagnosticErr) {
                steps.echo "k8s diagnostics failed during ${phase}: ${diagnosticErr}"
            }
            throw err
        }
    }

    private void emitKubernetesDiagnostics(Map envCfg, String phase, Map state) {
        String namespace = envCfg.namespace ?: 'default'
        String release = envCfg.release ?: envCfg.name
        if (!namespace?.trim() || !release?.trim()) {
            steps.echo "k8s diagnostics skipped for ${phase}; namespace or release is missing."
            return
        }

        String selector = "app.kubernetes.io/instance=${release}"
        steps.echo "k8s diagnostics: collecting ${phase} failure details for release '${release}' in namespace '${namespace}'."

        safeDiagnosticCommand("workloads", "kubectl -n ${shellEscape(namespace)} get deployment,statefulset -l ${shellEscape(selector)} -o wide 2>/dev/null || true")
        safeDiagnosticCommand("pods", "kubectl -n ${shellEscape(namespace)} get pods -l ${shellEscape(selector)} -o wide 2>/dev/null || true")
        safeDiagnosticCommand("events", "kubectl -n ${shellEscape(namespace)} get events --sort-by=.lastTimestamp 2>/dev/null | tail -n 50 || true")

        List<String> workloads = discoverWorkloads(namespace, release)
        workloads.each { String workload ->
            safeDiagnosticCommand("describe ${workload}", "kubectl -n ${shellEscape(namespace)} describe ${shellEscape(workload)} 2>/dev/null || true")
        }

        List<String> pods = discoverPods(namespace, release)
        pods.take(3).each { String pod ->
            safeDiagnosticCommand("describe pod ${pod}", "kubectl -n ${shellEscape(namespace)} describe pod ${shellEscape(pod)} 2>/dev/null || true")
            safeDiagnosticCommand("logs ${pod}", "kubectl -n ${shellEscape(namespace)} logs ${shellEscape(pod)} --all-containers=true --tail=200 2>/dev/null || true")
            safeDiagnosticCommand("previous logs ${pod}", "kubectl -n ${shellEscape(namespace)} logs ${shellEscape(pod)} --all-containers=true --previous --tail=200 2>/dev/null || true")
        }
    }

    private List<String> discoverPods(String namespace, String release) {
        String selector = "app.kubernetes.io/instance=${release}"
        String raw = steps.sh(
            script: "kubectl -n ${shellEscape(namespace)} get pods -l ${shellEscape(selector)} -o jsonpath='{range .items[*]}{.metadata.name}{\"\\n\"}{end}' 2>/dev/null || true",
            returnStdout: true
        ).trim()
        if (!raw) {
            return []
        }
        return raw.readLines().collect { it?.trim() }.findAll { it }
    }

    private void safeDiagnosticCommand(String label, String command) {
        steps.echo "k8s diagnostics: ${label}"
        steps.sh command
    }

    private String buildKubectlBase(Map envCfg) {
        List<String> parts = ['kubectl']
        if (envCfg.kubeContext) {
            parts << '--context'
            parts << shellEscape(envCfg.kubeContext.toString())
        }
        return parts.join(' ')
    }

    private void validateEnvVarName(String name) {
        if (!(name ==~ /[A-Za-z_][A-Za-z0-9_]*/)) {
            steps.error "Invalid environment variable name '${name}' for registry credentials."
        }
    }

    private String sanitizeEnvVar(String raw, String fallback) {
        String name = raw?.trim() ?: fallback
        validateEnvVarName(name)
        return name
    }

}
