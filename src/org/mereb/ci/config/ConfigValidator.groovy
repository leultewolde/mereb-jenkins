package org.mereb.ci.config

/**
 * Lightweight structural validation for .ci/ci.yml. Not a full JSON Schema parser,
 * but enough to fail fast with actionable messages.
 */
class ConfigValidator implements Serializable {

    static ValidationResult validate(Map raw) {
        Map cfg = raw ?: [:]
        List<String> errors = []
        List<String> warnings = []

        if (cfg.version != null && (cfg.version as Integer) != 1) {
            errors << "version must be 1"
        }

        Object build = cfg.build
        if (build && !(build instanceof Map)) {
            errors << "build must be a map"
        }
        Map buildMap = build instanceof Map ? build as Map : [:]
        if (buildMap.stages && !(buildMap.stages instanceof List)) {
            errors << "build.stages must be a list"
        }

        if (cfg.pnpm && !(cfg.pnpm instanceof Map)) {
            errors << "pnpm section must be a map"
        }

        boolean imageEnabled = true
        if (cfg.image instanceof Boolean) {
            imageEnabled = cfg.image as Boolean
        } else if (cfg.image instanceof Map && cfg.image.containsKey('enabled')) {
            imageEnabled = cfg.image.enabled as Boolean
        }
        if (imageEnabled) {
            boolean hasRepo = cfg.image instanceof Map && cfg.image.repository?.toString()?.trim()
            boolean hasAppName = cfg.app instanceof Map && cfg.app.name?.toString()?.trim()
            if (!hasRepo && !hasAppName) {
                errors << "image.repository or app.name must be provided when docker image builds are enabled"
            }
        }

        Map deploySection = [:]
        if (cfg.deploy instanceof Map) {
            deploySection = cfg.deploy
        } else if (cfg.environments instanceof Map) {
            deploySection = cfg.environments
        }
        if (!deploySection.isEmpty()) {
            Map envNodes = deploySection.findAll { entry -> entry.key != 'order' }
            List<String> envNames = envNodes.keySet().collect { it.toString() }
            if (deploySection.order instanceof List) {
                List<String> invalid = (deploySection.order as List).collect { it.toString() }.findAll { !envNames.contains(it) }
                if (!invalid.isEmpty()) {
                    errors << "deploy.order references unknown environments: ${invalid.join(', ')}"
                }
            }
            envNodes.each { name, cfgNode ->
                if (!(cfgNode instanceof Map)) {
                    errors << "deploy.${name} must be a map"
                }
            }
        }

        if (cfg.terraform && !(cfg.terraform instanceof Map)) {
            errors << "terraform must be a map"
        }
        if (cfg.terraform?.environments && !(cfg.terraform.environments instanceof Map)) {
            errors << "terraform.environments must be a map"
        }

        if (cfg.microfrontend && !(cfg.microfrontend instanceof Map)) {
            errors << "microfrontend must be a map"
        }
        Map mfeSection = cfg.microfrontend instanceof Map ? (cfg.microfrontend as Map) : [:]
        if (mfeSection.environments && !(mfeSection.environments instanceof Map)) {
            errors << "microfrontend.environments must be a map"
        }
        if (mfeSection.environments instanceof Map) {
            Map envNodes = mfeSection.environments as Map
            List<String> envNames = envNodes.keySet().collect { it.toString() }
            if (mfeSection.order instanceof List) {
                List<String> invalid = (mfeSection.order as List).collect { it.toString() }.findAll { !envNames.contains(it) }
                if (!invalid.isEmpty()) {
                    errors << "microfrontend.order references unknown environments: ${invalid.join(', ')}"
                }
            }
            envNodes.each { name, node ->
                if (!(node instanceof Map)) {
                    errors << "microfrontend.${name} must be a map"
                }
            }
        }

        if (cfg.release?.autoTag instanceof Map) {
            Map autoTag = cfg.release.autoTag as Map
            if ((autoTag.enabled == null || autoTag.enabled) && !autoTag.bump) {
                warnings << "release.autoTag.bump defaults to patch; set explicitly to avoid surprises"
            }
        }

        if (!cfg.pnpm && !buildMap && !(cfg.preset ?: '').toString().trim()) {
            warnings << "No build stages defined. The pipeline will skip build/test unless custom stages are added."
        }

        return new ValidationResult(errors.unique(), warnings.unique())
    }

    static class ValidationResult implements Serializable {
        final List<String> errors
        final List<String> warnings

        ValidationResult(List<String> errors, List<String> warnings) {
            this.errors = errors ?: []
            this.warnings = warnings ?: []
        }

        boolean hasErrors() {
            return errors && !errors.isEmpty()
        }

        boolean hasWarnings() {
            return warnings && !warnings.isEmpty()
        }
    }
}
