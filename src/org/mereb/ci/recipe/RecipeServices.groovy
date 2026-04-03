package org.mereb.ci.recipe

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
 * Shared service bundle available to every recipe executor.
 */
class RecipeServices implements Serializable {

    final PipelineHelper pipelineHelper
    final CredentialHelper credentialHelper
    final VerbRunner verbRunner
    final StageExecutor stageExecutor
    final BuildStages buildStages
    final DockerPipeline dockerPipeline
    final ReleaseFlow releaseFlow
    final TerraformPipeline terraformPipeline
    final DeployPipeline deployPipeline
    final MicrofrontendPipeline microfrontendPipeline
    final def aiClient

    RecipeServices(PipelineHelper pipelineHelper,
                   CredentialHelper credentialHelper,
                   VerbRunner verbRunner,
                   StageExecutor stageExecutor,
                   BuildStages buildStages,
                   DockerPipeline dockerPipeline,
                   ReleaseFlow releaseFlow,
                   TerraformPipeline terraformPipeline,
                   DeployPipeline deployPipeline,
                   MicrofrontendPipeline microfrontendPipeline,
                   def aiClient) {
        this.pipelineHelper = pipelineHelper
        this.credentialHelper = credentialHelper
        this.verbRunner = verbRunner
        this.stageExecutor = stageExecutor
        this.buildStages = buildStages
        this.dockerPipeline = dockerPipeline
        this.releaseFlow = releaseFlow
        this.terraformPipeline = terraformPipeline
        this.deployPipeline = deployPipeline
        this.microfrontendPipeline = microfrontendPipeline
        this.aiClient = aiClient
    }
}
