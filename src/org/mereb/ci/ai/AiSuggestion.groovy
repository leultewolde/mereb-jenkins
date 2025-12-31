package org.mereb.ci.ai

/**
 * Represents AI-provided release metadata.
 */
class AiSuggestion implements Serializable {
    /**
     * Map of package name -> bump type (patch|minor|major)
     */
    Map<String, String> bumpTypes = [:]

    /**
     * Optional changeset markdown/content.
     */
    String changeset = ''

    boolean hasData() {
        return (bumpTypes && !bumpTypes.isEmpty()) || (changeset?.trim())
    }
}
