package org.mereb.ci.recipe

import org.mereb.ci.ReleaseCoordinator

/**
 * Image recipes own docker orchestration and release publication, without deploy or terraform.
 */
class ImageRecipeExecutor implements RecipeExecutor {

    @Override
    void execute(RecipeContext context) {
        RecipeExecutionSupport.maybeRunEagerAutoTag(
            context.steps,
            context.cfg,
            context.state,
            context.services.releaseFlow
        )

        context.services.dockerPipeline.run(context.cfg, context.state)

        ReleaseCoordinator releaseCoordinator = RecipeExecutionSupport.releaseCoordinator(
            context.cfg,
            [],
            { context.services.releaseFlow.handleRelease(context.cfg.release, context.state) },
            { context.services.releaseFlow.runReleaseStages(context.cfg.releaseStages) },
            {}
        )

        releaseCoordinator.runIfEager()
        releaseCoordinator.ensureReleaseStagesRan()
        context.services.releaseFlow.publishRelease(context.cfg.release, context.state)
    }
}
