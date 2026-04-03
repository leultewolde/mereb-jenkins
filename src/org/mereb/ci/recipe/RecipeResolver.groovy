package org.mereb.ci.recipe

/**
 * Resolves the concrete pipeline recipe from the config shape or explicit override.
 */
class RecipeResolver implements Serializable {

    static RecipeType resolveRaw(Map raw = [:]) {
        return resolve(raw)
    }

    static RecipeType resolveNormalized(Map cfg = [:]) {
        return resolve(cfg)
    }

    static RecipeType configuredRecipe(Map candidate = [:]) {
        RecipeType requested = RecipeType.fromValue(candidate?.requestedRecipe)
        if (requested) {
            return requested
        }
        return RecipeType.fromValue(candidate?.recipe)
    }

    static boolean hasTerraformEnvironments(Map candidate = [:]) {
        if (!(candidate?.terraform instanceof Map)) {
            return false
        }
        Object envNode = (candidate.terraform as Map).get('environments')
        return envNode instanceof Map && !((Map) envNode).isEmpty()
    }

    static boolean hasMicrofrontendEnvironments(Map candidate = [:]) {
        Object section = candidate?.microfrontend ?: candidate?.mfe
        if (!(section instanceof Map)) {
            return false
        }
        Map data = section as Map
        Object envNode = data.get('environments')
        if (envNode instanceof Map) {
            return !((Map) envNode).isEmpty()
        }
        return data.get('enabled') as Boolean
    }

    static boolean hasDeployEnvironments(Map candidate = [:]) {
        if (candidate?.deploy instanceof Map) {
            Map deploy = candidate.deploy as Map
            Object envNode = deploy.get('environments')
            if (envNode instanceof Map) {
                return !((Map) envNode).isEmpty()
            }
            return deploy.any { entry ->
                String key = entry.key?.toString()
                key && key != 'order'
            }
        }
        if (candidate?.environments instanceof Map) {
            return !((Map) candidate.environments).isEmpty()
        }
        return false
    }

    static boolean isImageEnabled(Map candidate = [:]) {
        Object image = candidate?.image
        if (image instanceof Boolean) {
            return image as Boolean
        }
        if (image instanceof Map) {
            Map data = image as Map
            if (data.containsKey('enabled')) {
                return data.get('enabled') as Boolean
            }
            return !data.isEmpty()
        }
        return false
    }

    static boolean hasReleaseAutomation(Map candidate = [:]) {
        if (candidate?.releaseStages instanceof List && !((List) candidate.releaseStages).isEmpty()) {
            return true
        }
        return candidate?.release instanceof Map && !((Map) candidate.release).isEmpty()
    }

    private static RecipeType resolve(Map candidate) {
        RecipeType explicit = configuredRecipe(candidate)
        if (explicit) {
            return explicit
        }
        if (hasTerraformEnvironments(candidate)) {
            return RecipeType.TERRAFORM
        }
        if (hasMicrofrontendEnvironments(candidate)) {
            return RecipeType.MICROFRONTEND
        }
        if (hasDeployEnvironments(candidate)) {
            return RecipeType.SERVICE
        }
        if (isImageEnabled(candidate)) {
            return RecipeType.IMAGE
        }
        if (hasReleaseAutomation(candidate)) {
            return RecipeType.PACKAGE
        }
        return RecipeType.BUILD
    }
}
