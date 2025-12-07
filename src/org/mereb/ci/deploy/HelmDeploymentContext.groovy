package org.mereb.ci.deploy

/**
 * Bundles Helm args plus the entire value-file list produced for the environment.
 */
class HelmDeploymentContext implements Serializable {

    final List<String> valuesFiles
    final Map helmArgs

    HelmDeploymentContext(List<String> valuesFiles, Map helmArgs) {
        this.valuesFiles = valuesFiles ?: []
        this.helmArgs = helmArgs ?: [:]
    }
}
