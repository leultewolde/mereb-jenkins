package org.mereb.ci.recipe

import org.mereb.ci.ai.AiFactory
import org.mereb.ci.build.BuildStages
import org.mereb.ci.credentials.CredentialHelper
import org.mereb.ci.deploy.DeployPipeline
import org.mereb.ci.docker.DockerPipeline
import org.mereb.ci.mfe.MicrofrontendPipeline
import org.mereb.ci.release.ReleaseFlow
import org.mereb.ci.terraform.TerraformPipeline
import org.mereb.ci.util.PipelineHelper
import org.mereb.ci.util.StageExecutor
import org.mereb.ci.verbs.VerbRunner

/**
 * Builds the shared service bundle once so recipes can focus on sequencing.
 */
class RecipeServicesFactory implements Serializable {

    static RecipeServices create(def steps, PipelineHelper pipelineHelper, Map cfg) {
        CredentialHelper credentialHelper = new CredentialHelper(steps)
        VerbRunner verbRunner = new VerbRunner(steps)
        Closure runVerb = { String spec -> verbRunner.run(spec) }
        BuildStages buildStages = new BuildStages(steps, runVerb, credentialHelper)
        DockerPipeline dockerPipeline = new DockerPipeline(steps)
        StageExecutor stageExecutor = new StageExecutor(steps, credentialHelper)
        Closure approvalHandler = { Map approvalCfg, String message ->
            pipelineHelper.awaitApproval(approvalCfg, message)
        }
        ReleaseFlow releaseFlow = new ReleaseFlow(steps, credentialHelper, runVerb, null, stageExecutor)
        TerraformPipeline terraformPipeline = new TerraformPipeline(steps, credentialHelper, stageExecutor, approvalHandler)
        DeployPipeline deployPipeline = new DeployPipeline(steps, credentialHelper)
        MicrofrontendPipeline microfrontendPipeline = new MicrofrontendPipeline(
            steps,
            credentialHelper,
            approvalHandler,
            stageExecutor
        )
        def aiClient = AiFactory.create(cfg.ai, steps)

        return new RecipeServices(
            pipelineHelper,
            credentialHelper,
            verbRunner,
            stageExecutor,
            buildStages,
            dockerPipeline,
            releaseFlow,
            terraformPipeline,
            deployPipeline,
            microfrontendPipeline,
            aiClient
        )
    }
}
