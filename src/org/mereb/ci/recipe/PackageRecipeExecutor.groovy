package org.mereb.ci.recipe

import org.mereb.ci.ReleaseCoordinator

/**
 * Package recipes own release tagging, release stages, and publication.
 */
class PackageRecipeExecutor implements RecipeExecutor {

    @Override
    void execute(RecipeContext context) {
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
