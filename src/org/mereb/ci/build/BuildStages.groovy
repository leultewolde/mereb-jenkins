package org.mereb.ci.build

import org.mereb.ci.credentials.CredentialHelper
import org.mereb.ci.util.StageExecutor

import static org.mereb.ci.util.PipelineUtils.mapToEnvList

/**
 * Executes normalized build and matrix stages while keeping ciV1.groovy slim.
 */
class BuildStages implements Serializable {

    private final def steps
    private final Closure verbRunner
    private final CredentialHelper credentialHelper
    private final StageExecutor stageExecutor

    BuildStages(def steps, Closure verbRunner, CredentialHelper credentialHelper) {
        this.steps = steps
        this.verbRunner = verbRunner
        this.credentialHelper = credentialHelper
        this.stageExecutor = new StageExecutor(steps, credentialHelper)
    }

    void runBuildStages(List<Map> stages) {
        if (!stages || stages.isEmpty()) {
            steps.echo 'No build stages defined; skipping build/test.'
            return
        }
        stages.each { Map stageCfg ->
            String name = stageCfg.name ?: 'Build'
            List<String> envList = mapToEnvList(stageCfg.env instanceof Map ? stageCfg.env : [:])
            List<Map> bindings = credentialHelper.bindingsFor(stageCfg)

            stageExecutor.run(name, envList, bindings) {
                if (stageCfg.verb) {
                    verbRunner.call(stageCfg.verb as String)
                } else if (stageCfg.sh) {
                    steps.sh stageCfg.sh as String
                } else {
                    steps.echo "Stage '${name}' has no action."
                }
            }
        }
    }

    void runMatrix(Map matrix) {
        if (!matrix || matrix.isEmpty()) {
            return
        }
        matrix.each { String groupName, stepsList ->
            steps.stage("Matrix: ${groupName}") {
                Map branches = [:]
                (stepsList as List).each { Object s ->
                    def title = titleForStep(s)
                    branches[title] = { verbRunner.call(s.toString()) }
                }
                steps.parallel branches
            }
        }
    }

    private static String titleForStep(Object s) {
        def raw = s.toString().trim()
        if (raw.startsWith('sh ')) {
            return raw.substring(3).take(30)
        }
        return raw.take(30)
    }
}
