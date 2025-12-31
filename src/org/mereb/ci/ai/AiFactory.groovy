package org.mereb.ci.ai

/**
 * Builds AI clients. Currently returns a no-op client until a provider is implemented.
 */
class AiFactory implements Serializable {

    static AiClient create(Map cfg = [:], def steps = null) {
        String provider = (cfg?.provider ?: 'none').toString().toLowerCase()
        switch (provider) {
            case 'deepseek':
                return new DeepseekClient(steps, cfg)
            default:
                return new NullAiClient()
        }
    }
}
