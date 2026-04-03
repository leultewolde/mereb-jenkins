package org.mereb.ci.recipe

/**
 * Contract for recipe-specific execution after the shared phases have finished.
 */
interface RecipeExecutor extends Serializable {

    void execute(RecipeContext context)
}
