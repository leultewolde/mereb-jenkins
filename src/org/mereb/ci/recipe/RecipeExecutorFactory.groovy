package org.mereb.ci.recipe

/**
 * Instantiates the concrete executor for the resolved recipe.
 */
class RecipeExecutorFactory implements Serializable {

    static RecipeExecutor create(RecipeType recipeType) {
        switch (recipeType) {
            case RecipeType.PACKAGE:
                return new PackageRecipeExecutor()
            case RecipeType.IMAGE:
                return new ImageRecipeExecutor()
            case RecipeType.SERVICE:
                return new ServiceRecipeExecutor()
            case RecipeType.MICROFRONTEND:
                return new MicrofrontendRecipeExecutor()
            case RecipeType.TERRAFORM:
                return new TerraformRecipeExecutor()
            case RecipeType.BUILD:
            default:
                return new BuildRecipeExecutor()
        }
    }
}
