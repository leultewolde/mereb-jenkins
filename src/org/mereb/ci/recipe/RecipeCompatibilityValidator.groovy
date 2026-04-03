package org.mereb.ci.recipe

/**
 * Validates that the config shape matches a single supported recipe.
 */
class RecipeCompatibilityValidator implements Serializable {

    static List<String> validate(Map raw = [:]) {
        Map cfg = raw ?: [:]
        List<String> errors = []
        Object recipeValue = cfg.get('recipe')
        RecipeType explicit = RecipeType.fromValue(recipeValue)

        if (recipeValue != null && !explicit) {
            errors << "recipe must be one of: build, package, image, service, microfrontend, terraform"
            return errors
        }

        if (explicit) {
            errors.addAll(validateExplicit(explicit, cfg))
        } else {
            errors.addAll(validateAutoDetected(cfg))
        }
        return errors.unique()
    }

    private static List<String> validateAutoDetected(Map cfg) {
        boolean terraform = RecipeResolver.hasTerraformEnvironments(cfg)
        boolean microfrontend = RecipeResolver.hasMicrofrontendEnvironments(cfg)
        boolean deploy = RecipeResolver.hasDeployEnvironments(cfg)
        boolean image = RecipeResolver.isImageEnabled(cfg)
        List<String> errors = []

        if (terraform && (microfrontend || deploy || image)) {
            errors << "terraform environments cannot be combined with deploy, microfrontend, or image-enabled orchestration"
        }
        if (microfrontend && (deploy || image)) {
            errors << "microfrontend environments cannot be combined with deploy or image-enabled orchestration"
        }
        if (deploy && !image) {
            errors << "deploy environments require image builds; service recipes must enable image orchestration"
        }

        return errors
    }

    private static List<String> validateExplicit(RecipeType recipe, Map cfg) {
        boolean terraform = RecipeResolver.hasTerraformEnvironments(cfg)
        boolean microfrontend = RecipeResolver.hasMicrofrontendEnvironments(cfg)
        boolean deploy = RecipeResolver.hasDeployEnvironments(cfg)
        boolean image = RecipeResolver.isImageEnabled(cfg)
        boolean release = RecipeResolver.hasReleaseAutomation(cfg)
        List<String> errors = []

        switch (recipe) {
            case RecipeType.BUILD:
                if (image) {
                    errors << "recipe=build cannot enable image orchestration"
                }
                if (deploy) {
                    errors << "recipe=build cannot define deploy environments"
                }
                if (microfrontend) {
                    errors << "recipe=build cannot define microfrontend environments"
                }
                if (terraform) {
                    errors << "recipe=build cannot define terraform environments"
                }
                if (release) {
                    errors << "recipe=build cannot define release automation or release stages"
                }
                break
            case RecipeType.PACKAGE:
                if (image) {
                    errors << "recipe=package cannot enable image orchestration"
                }
                if (deploy) {
                    errors << "recipe=package cannot define deploy environments"
                }
                if (microfrontend) {
                    errors << "recipe=package cannot define microfrontend environments"
                }
                if (terraform) {
                    errors << "recipe=package cannot define terraform environments"
                }
                if (!release) {
                    errors << "recipe=package requires release automation or release stages"
                }
                break
            case RecipeType.IMAGE:
                if (!image) {
                    errors << "recipe=image requires image-enabled orchestration"
                }
                if (deploy) {
                    errors << "recipe=image cannot define deploy environments"
                }
                if (microfrontend) {
                    errors << "recipe=image cannot define microfrontend environments"
                }
                if (terraform) {
                    errors << "recipe=image cannot define terraform environments"
                }
                break
            case RecipeType.SERVICE:
                if (!deploy) {
                    errors << "recipe=service requires deploy environments"
                }
                if (!image) {
                    errors << "recipe=service requires image-enabled orchestration"
                }
                if (microfrontend) {
                    errors << "recipe=service cannot define microfrontend environments"
                }
                if (terraform) {
                    errors << "recipe=service cannot define terraform environments"
                }
                break
            case RecipeType.MICROFRONTEND:
                if (!microfrontend) {
                    errors << "recipe=microfrontend requires microfrontend environments"
                }
                if (image) {
                    errors << "recipe=microfrontend cannot enable image orchestration"
                }
                if (deploy) {
                    errors << "recipe=microfrontend cannot define deploy environments"
                }
                if (terraform) {
                    errors << "recipe=microfrontend cannot define terraform environments"
                }
                break
            case RecipeType.TERRAFORM:
                if (!terraform) {
                    errors << "recipe=terraform requires terraform environments"
                }
                if (image) {
                    errors << "recipe=terraform cannot enable image orchestration"
                }
                if (deploy) {
                    errors << "recipe=terraform cannot define deploy environments"
                }
                if (microfrontend) {
                    errors << "recipe=terraform cannot define microfrontend environments"
                }
                break
        }

        return errors
    }
}
