package org.mereb.ci.recipe

import groovy.json.JsonOutput

/**
 * Runs the shared phases every recipe needs before branching into recipe-specific logic.
 */
class CommonPipelineExecutor implements Serializable {

    private final def steps

    CommonPipelineExecutor(def steps) {
        this.steps = steps
    }

    void run(RecipeContext context) {
        if (context.cfg.requiresGradleHome) {
            steps.sh "mkdir -p '${context.workspace}/.gradle'"
        }

        if (steps.fileExists('gradlew')) {
            steps.sh 'chmod +x ./gradlew'
        }

        context.services.buildStages.runBuildStages(context.cfg.buildStages)
        context.services.buildStages.runMatrix(context.cfg.matrix)
        fetchAiSuggestion(context)
    }

    private void fetchAiSuggestion(RecipeContext context) {
        steps.echo "AI: starting suggestion fetch (provider=${(context.cfg.ai?.provider ?: 'none')})"
        def aiSuggestion = context.services.aiClient.suggest([state: context.state, config: context.cfg, env: steps.env])
        if (!aiSuggestion?.hasData()) {
            steps.echo 'AI: no suggestion returned'
            return
        }

        steps.echo(
            "AI: suggestion received (bumpTypes=${aiSuggestion.bumpTypes?.keySet() ?: 'none'}, " +
                "hasChangeset=${aiSuggestion.changeset?.trim() ? 'yes' : 'no'})"
        )
        if (aiSuggestion.changeset?.trim()) {
            steps.sh 'mkdir -p .ci'
            steps.writeFile(file: '.ci/ai-changeset.md', text: aiSuggestion.changeset)
            context.exportedEnv << "AI_CHANGESET_PATH=${context.workspace}/.ci/ai-changeset.md"
            context.exportedEnv << "AI_CHANGESET=${aiSuggestion.changeset}"
        }
        if (aiSuggestion.bumpTypes && !aiSuggestion.bumpTypes.isEmpty()) {
            context.exportedEnv << "AI_BUMP_TYPES=${JsonOutput.toJson(aiSuggestion.bumpTypes)}"
        }
    }
}
