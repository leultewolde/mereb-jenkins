package org.mereb.ci.recipe

import org.mereb.ci.ReleaseCoordinator

/**
 * Service recipes own docker, deploy, and release sequencing.
 */
class ServiceRecipeExecutor implements RecipeExecutor {

    @Override
    void execute(RecipeContext context) {
        RecipeExecutionSupport.maybeRunEagerAutoTag(
            context.steps,
            context.cfg,
            context.state,
            context.services.releaseFlow
        )

        context.services.dockerPipeline.run(context.cfg, context.state)

        List<String> deployOrder = context.cfg?.deploy?.order instanceof List ?
            (context.cfg.deploy.order as List).collect { it?.toString() } :
            []
        ReleaseCoordinator releaseCoordinator = RecipeExecutionSupport.releaseCoordinator(
            context.cfg,
            deployOrder,
            { context.services.releaseFlow.handleRelease(context.cfg.release, context.state) },
            { context.services.releaseFlow.runReleaseStages(context.cfg.releaseStages) },
            {}
        )
        Closure afterEnvironmentHook = releaseCoordinator.afterEnvironmentHook()

        releaseCoordinator.runIfEager()
        context.services.deployPipeline.run(context.cfg, context.state, afterEnvironmentHook)
        releaseCoordinator.ensureReleaseStagesRan()
        context.services.releaseFlow.publishRelease(context.cfg.release, context.state)
    }
}
