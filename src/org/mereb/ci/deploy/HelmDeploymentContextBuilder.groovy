package org.mereb.ci.deploy

import org.mereb.ci.credentials.CredentialHelper
import org.mereb.ci.util.VaultCredentialHelper
import org.mereb.ci.util.VaultContext

/**
 * Handles values-file resolution and Helm argument construction for deployment stages.
 */
class HelmDeploymentContextBuilder implements Serializable {

    private final def steps
    private final ValuesTemplateRenderer templateRenderer
    private final CredentialHelper credentialHelper
    private final VaultCredentialHelper vaultHelper

    HelmDeploymentContextBuilder(def steps,
                                 ValuesTemplateRenderer templateRenderer,
                                 CredentialHelper credentialHelper,
                                 VaultCredentialHelper vaultHelper) {
        this.steps = steps
        this.templateRenderer = templateRenderer
        this.credentialHelper = credentialHelper
        this.vaultHelper = vaultHelper
    }

    HelmDeploymentContext build(String envName, Map envCfg, Map imageCfg, Map state, VaultContext vaultContext) {
        List<String> valuesFiles = determineValuesFiles(envName, envCfg)
        List<String> renderedTemplates = []
        Closure renderTemplates = {
            renderedTemplates = templateRenderer.render(envName, envCfg)
        }
        VaultContext ctx = vaultContext ?: new VaultContext(null, [])
        vaultHelper.withVaultEnv(ctx.address, {
            credentialHelper.withOptionalCredentials(ctx.bindings, renderTemplates)
        })
        List<String> finalValues = []
        finalValues.addAll(valuesFiles)
        finalValues.addAll(renderedTemplates)

        Map helmArgs = buildHelmArgs(envCfg, state, imageCfg, finalValues)
        return new HelmDeploymentContext(finalValues, helmArgs)
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
}
