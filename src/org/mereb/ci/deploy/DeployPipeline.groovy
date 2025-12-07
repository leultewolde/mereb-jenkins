package org.mereb.ci.deploy

import org.mereb.ci.Helpers
import org.mereb.ci.credentials.CredentialHelper
import org.mereb.ci.util.ApprovalHelper
import org.mereb.ci.util.VaultCredentialHelper

/**
 * Orchestrates Helm-based environment deployments so ciV1 stays declarative.
 */
class DeployPipeline implements Serializable {

    private final def steps
    private final CredentialHelper credentialHelper
    private final ValuesTemplateRenderer templateRenderer
    private final ApprovalHelper approvalHelper
    private final VaultCredentialHelper vaultHelper

    DeployPipeline(def steps, CredentialHelper credentialHelper, ValuesTemplateRenderer templateRenderer = null) {
        this.steps = steps
        this.credentialHelper = credentialHelper
        this.templateRenderer = templateRenderer ?: new ValuesTemplateRenderer(steps)
        this.approvalHelper = new ApprovalHelper(steps)
        this.vaultHelper = new VaultCredentialHelper(steps, credentialHelper)
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

            VaultCredentialHelper.VaultContext vaultContext = vaultHelper.prepare(envCfg)
            List<Map> deployBindings = vaultContext.bindings
            String vaultAddress = vaultContext.address
            List<String> valuesFiles = determineValuesFiles(envName, envCfg)
            List<String> renderedTemplates = []
            Closure renderTemplates = {
                renderedTemplates = templateRenderer.render(envName, envCfg)
            }
            Closure renderWithCredentials = {
                credentialHelper.withOptionalCredentials(deployBindings, renderTemplates)
            }
            vaultHelper.withVaultEnv(vaultAddress, renderWithCredentials)
            valuesFiles.addAll(renderedTemplates)

            steps.stage("Deploy ${envCfg.displayName}") {
                Map helmArgs = buildHelmArgs(envCfg, state, cfg.image, valuesFiles)
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

    private List<String> determineValuesFiles(String envName, Map envCfg) {
        List<String> valuesFiles = []
        if (envCfg.valuesFiles instanceof List) {
            valuesFiles.addAll(envCfg.valuesFiles as List<String>)
        }
        if (valuesFiles.isEmpty()) {
            String defaultValues = ".ci/values-${envName}.yaml"
            if (steps.fileExists(defaultValues)) {
                valuesFiles = [defaultValues]
            }
        }
        return valuesFiles
    }

    private Map buildHelmArgs(Map envCfg, Map state, Map imageCfg, List<String> valuesFiles) {
        Map args = [
            release     : envCfg.release,
            namespace   : envCfg.namespace,
            chart       : envCfg.chart,
            repo        : envCfg.repo,
            version     : envCfg.chartVersion,
            valuesFiles : valuesFiles,
            set         : mergeImageValues(envCfg.set ?: [:], imageCfg, state),
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
        return args
    }

    private Map mergeImageValues(Map original, Map imageCfg, Map state) {
        Map merged = [:]
        merged.putAll(original ?: [:])
        if (imageCfg?.enabled && state?.repository) {
            merged['image.repository'] = state.repository
        }
        if (imageCfg?.enabled && state?.imageTag) {
            merged['image.tag'] = state.imageTag
        }
        return merged
    }

    private void runSmoke(Map envCfg, VaultCredentialHelper.VaultContext vaultContext) {
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
