package org.mereb.ci.recipe

/**
 * Microfrontend recipes own publish verification and release callbacks.
 */
class MicrofrontendRecipeExecutor implements RecipeExecutor {

    @Override
    void execute(RecipeContext context) {
        context.services.microfrontendPipeline.run(
            context.cfg.microfrontend,
            context.state,
            { Map releaseCfg, Map currentState ->
                context.services.releaseFlow.handleRelease(context.cfg.release, currentState)
            },
            { Map releaseCfg, Map currentState ->
                context.services.releaseFlow.publishRelease(context.cfg.release, currentState)
            },
            context.cfg.release
        )
        context.services.releaseFlow.runReleaseStages(context.cfg.releaseStages)
    }
}
