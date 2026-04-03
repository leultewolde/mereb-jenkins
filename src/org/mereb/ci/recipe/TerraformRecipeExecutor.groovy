package org.mereb.ci.recipe

import org.mereb.ci.ReleaseCoordinator

/**
 * Terraform recipes own terraform execution and deferred tag-gated environments.
 */
class TerraformRecipeExecutor implements RecipeExecutor {

    @Override
    void execute(RecipeContext context) {
        List<String> deferredTerraform = []
        boolean deferredTerraformRan = false
        Closure runDeferredTerraform = {
            if (deferredTerraformRan) {
                return
            }
            if (context.steps.env.RELEASE_TAG?.trim() && deferredTerraform && !deferredTerraform.isEmpty()) {
                context.steps.env.TAG_NAME = context.steps.env.RELEASE_TAG
                context.steps.echo "Running deferred Terraform environments after creating tag ${context.steps.env.RELEASE_TAG}"
                context.services.terraformPipeline.run(context.cfg.terraform, deferredTerraform, false, null)
                deferredTerraformRan = true
            }
        }

        ReleaseCoordinator releaseCoordinator = RecipeExecutionSupport.releaseCoordinator(
            context.cfg,
            [],
            { context.services.releaseFlow.handleRelease(context.cfg.release, context.state) },
            { context.services.releaseFlow.runReleaseStages(context.cfg.releaseStages) },
            { runDeferredTerraform.call() }
        )
        Closure afterEnvironmentHook = releaseCoordinator.afterEnvironmentHook()

        deferredTerraform = context.services.terraformPipeline.run(context.cfg.terraform, null, true, afterEnvironmentHook)
        runDeferredTerraform.call()
        releaseCoordinator.runIfEager()
        runDeferredTerraform.call()
        releaseCoordinator.ensureReleaseStagesRan()
        context.services.releaseFlow.publishRelease(context.cfg.release, context.state)
    }
}
