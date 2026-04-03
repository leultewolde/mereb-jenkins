package org.mereb.ci.recipe

/**
 * Immutable execution context shared by the common pipeline phases and recipe executors.
 */
class RecipeContext implements Serializable {

    final def steps
    final Map cfg
    final Map state
    final List<String> exportedEnv
    final RecipeType recipe
    final RecipeServices services
    final String workspace

    RecipeContext(def steps,
                  Map cfg,
                  Map state,
                  List<String> exportedEnv,
                  RecipeType recipe,
                  RecipeServices services,
                  String workspace) {
        this.steps = steps
        this.cfg = cfg ?: [:]
        this.state = state ?: [:]
        this.exportedEnv = exportedEnv ?: []
        this.recipe = recipe
        this.services = services
        this.workspace = workspace ?: ''
    }
}
