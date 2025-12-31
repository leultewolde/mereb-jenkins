package org.mereb.ci.ai

/**
 * Default no-op AI client used until a provider is configured.
 */
class NullAiClient extends AiClient {
    @Override
    AiSuggestion suggest(Map context) {
        return new AiSuggestion()
    }
}
