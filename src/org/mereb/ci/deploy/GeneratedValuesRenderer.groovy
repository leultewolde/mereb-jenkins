package org.mereb.ci.deploy

import groovy.json.JsonOutput

/**
 * Renders generated Helm values overlays into workspace-local JSON files that Helm can consume via -f.
 */
class GeneratedValuesRenderer implements Serializable {

    private final def steps

    GeneratedValuesRenderer(def steps) {
        this.steps = steps
    }

    String render(String envName, Map generatedValuesCfg) {
        String profileName = generatedValuesCfg?.profile?.toString()?.trim()
        if (!profileName) {
            return null
        }

        Map<String, Object> profileValues = profileFor(profileName)
        Map<String, Object> overlayValues = generatedValuesCfg?.overlay instanceof Map
            ? copyMap((Map) generatedValuesCfg.overlay)
            : [:]
        Map<String, Object> mergedValues = deepMerge(profileValues, overlayValues)
        String outputFile = ".ci/.generated-values-${envName}.json"

        steps.writeFile(
            file: outputFile,
            text: JsonOutput.prettyPrint(JsonOutput.toJson(mergedValues)) + '\n'
        )

        return outputFile
    }

    private static Map<String, Object> profileFor(String profileName) {
        switch (profileName) {
            case 'outboxWorker':
                return [
                    replicaCount        : 1,
                    podLabels           : [
                        'mereb.dev/workload-role': 'outbox-relay'
                    ],
                    image               : [
                        args: ['node', 'dist/outboxRelayWorker.js']
                    ],
                    service             : [
                        enabled: false,
                        ports  : []
                    ],
                    ingress             : [
                        enabled: false
                    ],
                    autoscaling         : [
                        enabled: false
                    ],
                    serviceMonitor      : [
                        enabled: false
                    ],
                    vaultSecretsOperator: [
                        enabled: false
                    ],
                    configMap           : [
                        enabled: false
                    ],
                    secret              : [
                        enabled: false
                    ]
                ]
            default:
                throw new IllegalArgumentException("Unsupported generatedValues profile '${profileName}'")
        }
    }

    private static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> overlay) {
        Map<String, Object> merged = copyMap(base)
        overlay.each { String key, Object value ->
            Object existing = merged[key]
            if (existing instanceof Map && value instanceof Map) {
                merged[key] = deepMerge((Map<String, Object>) existing, (Map<String, Object>) value)
            } else {
                merged[key] = copyValue(value)
            }
        }
        return merged
    }

    private static Map<String, Object> copyMap(Map raw) {
        Map<String, Object> copy = [:]
        raw.each { Object key, Object value ->
            if (key != null) {
                copy[key.toString()] = copyValue(value)
            }
        }
        return copy
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map) {
            return copyMap((Map) value)
        }
        if (value instanceof List) {
            return ((List) value).collect { Object item -> copyValue(item) }
        }
        return value
    }
}
