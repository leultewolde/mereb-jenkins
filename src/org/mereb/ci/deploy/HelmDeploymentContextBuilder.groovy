package org.mereb.ci.deploy

/**
 * Handles values-file resolution and Helm argument construction for deployment stages.
 */
class HelmDeploymentContextBuilder implements Serializable {

    private final def steps
    private final GeneratedValuesRenderer generatedValuesRenderer

    HelmDeploymentContextBuilder(def steps) {
        this.steps = steps
        this.generatedValuesRenderer = new GeneratedValuesRenderer(steps)
    }

    HelmDeploymentContext build(String envName, Map envCfg, Map imageCfg, Map state) {
        List<String> valuesFiles = determineValuesFiles(envName, envCfg)
        String generatedValuesFile = determineGeneratedValuesFile(envName, envCfg)
        if (generatedValuesFile) {
            valuesFiles << generatedValuesFile
        }

        Map helmArgs = buildHelmArgs(envCfg, state, imageCfg, valuesFiles)
        return new HelmDeploymentContext(valuesFiles, helmArgs)
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

    private String determineGeneratedValuesFile(String envName, Map envCfg) {
        if (!(envCfg.generatedValues instanceof Map) || ((Map) envCfg.generatedValues).isEmpty()) {
            return null
        }
        return generatedValuesRenderer.render(envName, (Map) envCfg.generatedValues)
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
        if (imageCfg?.enabled && state?.imageDigest) {
            merged['image.digest'] = state.imageDigest
        }
        return merged
    }
}
