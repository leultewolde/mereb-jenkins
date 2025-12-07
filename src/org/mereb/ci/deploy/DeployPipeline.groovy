package org.mereb.ci.deploy

import org.mereb.ci.Helpers
import org.mereb.ci.credentials.CredentialHelper
import org.mereb.ci.util.ApprovalHelper
import org.mereb.ci.util.VaultCredentialHelper
import org.mereb.ci.util.VaultContext

/**
 * Orchestrates Helm-based environment deployments so ciV1 stays declarative.
 */
class DeployPipeline implements Serializable {

    private final def steps
    private final CredentialHelper credentialHelper
    private final ValuesTemplateRenderer templateRenderer
    private final ApprovalHelper approvalHelper
    private final VaultCredentialHelper vaultHelper
    private final HelmDeploymentContextBuilder contextBuilder

    DeployPipeline(def steps, CredentialHelper credentialHelper, ValuesTemplateRenderer templateRenderer = null) {
        this.steps = steps
        this.credentialHelper = credentialHelper
        this.templateRenderer = templateRenderer ?: new ValuesTemplateRenderer(steps)
        this.approvalHelper = new ApprovalHelper(steps)
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

        order.eachWithIndex { String envName, int idx ->
            Map envCfg = envs[envName]
            if (!envCfg) {
                steps.echo "Environment '${envName}' not defined; skipping."
                return
            }
            if (!Helpers.matchCondition(envCfg.when as String, steps.env)) {
                steps.echo "Skip deploy '${envCfg.displayName}' — condition '${envCfg.when}' not met"
                return
            }

            boolean finalEnv = idx == order.size() - 1
            Map approvalCfg = envCfg.approval ?: [:]
            boolean approvalExplicit = approvalCfg.beforeExplicit as Boolean
            boolean approvalBefore = approvalCfg.before as Boolean
            if (approvalBefore || (finalEnv && approvalCfg && !approvalExplicit)) {
                requestDeploymentApproval(envCfg)
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
                        restartWorkloads(envCfg)
                    }
                }

                credentialHelper.withOptionalCredentials(deployBindings, runDeploy)
            }

            runSmoke(envCfg, vaultContext)

            afterEnvCallback?.call(envName)

            if (idx < order.size() - 1 && !(envCfg.autoPromote as Boolean)) {
                requestPromotion(envCfg, envName, order[idx + 1])
            }
        }
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

    private void requestDeploymentApproval(Map envCfg) {
        approvalHelper.request(envCfg.approval ?: [:], "Deploy ${envCfg.displayName ?: envCfg.name}?")
    }

    private void requestPromotion(Map envCfg, String current, String next) {
        approvalHelper.request(envCfg.approval ?: [:], "Promote ${current} deployment to ${next}?", 'Promote')
    }

    private void restartWorkloads(Map envCfg) {
        if (envCfg.containsKey('restartWorkloads') && !(envCfg.restartWorkloads as Boolean)) {
            return
        }
        String namespace = envCfg.namespace ?: 'default'
        String release = envCfg.release ?: envCfg.name
        List<String> commands = [
            "kubectl -n ${namespace} rollout restart deployment -l app.kubernetes.io/instance=${release}",
            "kubectl -n ${namespace} rollout restart statefulset -l app.kubernetes.io/instance=${release}"
        ]
        commands.each { cmd ->
            steps.sh(cmd)
        }
    }

}
