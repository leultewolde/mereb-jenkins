package org.mereb.ci.ai

/**
 * Abstract AI client. Implementations should override {@link #suggest(Map)} to
 * return bump/change suggestions. A no-op fallback is provided by {@link NullAiClient}.
 */
abstract class AiClient implements Serializable {

    /**
     * @param context pipeline context (state, config, env, etc.)
     * @return {@link AiSuggestion} with optional bump types or changeset text
     */
    abstract AiSuggestion suggest(Map context)
}
