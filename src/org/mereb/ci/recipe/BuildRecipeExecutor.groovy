package org.mereb.ci.recipe

/**
 * Build-only recipes finish after the shared build, matrix, and AI phases.
 */
class BuildRecipeExecutor implements RecipeExecutor {

    @Override
    void execute(RecipeContext context) {
        context.steps.echo "Recipe '${context.recipe}' has no additional phases."
    }
}
