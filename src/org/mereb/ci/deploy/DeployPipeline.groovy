package org.mereb.ci.deploy

import org.mereb.ci.Helpers
import org.mereb.ci.credentials.CredentialHelper
import org.mereb.ci.delivery.DeliveryPolicy
import org.mereb.ci.util.VaultCredentialHelper
import org.mereb.ci.util.VaultContext

import static org.mereb.ci.util.PipelineUtils.shellEscape

/**
 * Orchestrates Helm-based environment deployments so ciV1 stays declarative.
 */
class DeployPipeline implements Serializable {

    private final def steps
    private final CredentialHelper credentialHelper
    private final ValuesTemplateRenderer templateRenderer
    private final VaultCredentialHelper vaultHelper
    private final HelmDeploymentContextBuilder contextBuilder

    DeployPipeline(def steps, CredentialHelper credentialHelper, ValuesTemplateRenderer templateRenderer = null) {
        this.steps = steps
        this.credentialHelper = credentialHelper
        this.templateRenderer = templateRenderer ?: new ValuesTemplateRenderer(steps)
        this.vaultHelper = new VaultCredentialHelper(steps, credentialHelper)
        this.contextBuilder = new HelmDeploymentContextBuilder(steps, this.templateRenderer, credentialHelper, vaultHelper)
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

            VaultContext vaultContext = vaultHelper.prepare(envCfg)
            List<Map> deployBindings = vaultContext.bindings
            HelmDeploymentContext deploymentContext = contextBuilder.build(envName, envCfg, cfg.image, state, vaultContext)

            steps.stage("Deploy ${envCfg.displayName}") {
                Map helmArgs = deploymentContext.helmArgs
                Closure runDeploy = {
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

                credentialHelper.withOptionalCredentials(deployBindings, runDeploy)
            }

            runSmoke(envCfg, vaultContext)

            afterEnvCallback?.call(envName)
        }
    }

    private boolean shouldDeployEnvironment(DeliveryPolicy deliveryPolicy, String envName, Map envCfg) {
        if (deliveryPolicy?.isStagedMode()) {
            return deliveryPolicy.shouldDeployEnvironment(envName)
        }
        return Helpers.matchCondition(envCfg.when as String, steps.env)
    }

    private void runSmoke(Map envCfg, VaultContext vaultContext) {
        Map smoke = envCfg.smoke ?: [:]
        if (!(smoke.url || smoke.script || smoke.command)) {
            return
        }
        steps.stage("Smoke ${envCfg.displayName}") {
            Map payload = [:]
            payload.putAll(smoke)
            payload.environment = envCfg.displayName
            List<Map> smokeBindings = vaultContext.bindings
            Closure run = {
                steps.runSmoke(payload)
            }
            credentialHelper.withOptionalCredentials(smokeBindings, run)
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
        verifyLiveArtifact(namespace, release, state)
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

    private void verifyLiveArtifact(String namespace, String release, Map state) {
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

}
