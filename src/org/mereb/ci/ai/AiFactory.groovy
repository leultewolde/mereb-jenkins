package org.mereb.ci.ai

/**
 * Builds AI clients. Currently returns a no-op client until a provider is implemented.
 */
class AiFactory implements Serializable {

    static AiClient create(Map cfg = [:]) {
        String provider = (cfg?.provider ?: 'none').toString().toLowerCase()
        switch (provider) {
            default:
                return new NullAiClient()
        }
    }
}
