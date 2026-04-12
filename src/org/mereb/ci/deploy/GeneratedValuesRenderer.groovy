package org.mereb.ci.deploy

import groovy.json.JsonOutput

/**
 * Renders generated Helm values into workspace-local JSON files that Helm can consume via -f.
 */
class GeneratedValuesRenderer implements Serializable {

    private static final Map<String, Map<String, String>> ENVIRONMENT_DEFAULTS = [
        dev: [
            host                  : 'api-dev.mereb.app',
            otelEndpoint          : 'http://otel-collector-dev.platform-dev.svc.cluster.local:4318',
            platformIdentityConfig: 'platform-identity-dev',
            platformIdentitySecret: 'platform-identity-dev-secrets',
            vaultAuthGlobal       : 'mereb-apps-dev',
            vaultMount            : 'kubernetes-dev',
            vaultPath             : 'apps/dev',
            platformDbVaultPath   : 'apps/dev/platform-db',
            rolePrefix            : 'apps-dev'
        ],
        stg: [
            host                  : 'api-stg.mereb.app',
            otelEndpoint          : 'http://otel-collector-stg.platform-stg.svc.cluster.local:4318',
            platformIdentityConfig: 'platform-identity-stg',
            platformIdentitySecret: 'platform-identity-stg-secrets',
            vaultAuthGlobal       : 'mereb-apps-stg',
            vaultMount            : 'kubernetes-stg',
            vaultPath             : 'apps/stg',
            platformDbVaultPath   : 'apps/stg/platform-db',
            rolePrefix            : 'apps-stg'
        ],
        prd: [
            host                  : 'api.mereb.app',
            otelEndpoint          : 'http://otel-collector.platform-prd.svc.cluster.local:4318',
            platformIdentityConfig: 'platform-identity-prd',
            platformIdentitySecret: 'platform-identity-prd-secrets',
            vaultAuthGlobal       : 'mereb-apps-prd',
            vaultMount            : 'kubernetes-prd',
            vaultPath             : 'apps/prd',
            platformDbVaultPath   : 'apps/prd/platform-db',
            rolePrefix            : 'apps-prd'
        ]
    ].asImmutable()

    private final def steps

    GeneratedValuesRenderer(def steps) {
        this.steps = steps
    }

    String renderBase(String envName, String releaseName, Map generatedBaseValuesCfg) {
        return render(envName, releaseName, generatedBaseValuesCfg, 'generatedBaseValues')
    }

    String renderOverlay(String envName, Map generatedValuesCfg) {
        return render(envName, null, generatedValuesCfg, 'generatedValues')
    }

    private String render(String envName, String releaseName, Map config, String mode) {
        String profileName = config?.profile?.toString()?.trim()
        if (!profileName) {
            return null
        }

        Map<String, Object> renderedValues = profileFor(mode, envName, releaseName, profileName, config)
        String outputFile = mode == 'generatedBaseValues'
            ? ".ci/.generated-base-values-${envName}.json"
            : ".ci/.generated-values-${envName}.json"

        steps.writeFile(
            file: outputFile,
            text: JsonOutput.prettyPrint(JsonOutput.toJson(renderedValues)) + '\n'
        )

        return outputFile
    }

    private static Map<String, Object> profileFor(
        String mode,
        String envName,
        String releaseName,
        String profileName,
        Map config
    ) {
        switch (mode) {
            case 'generatedBaseValues':
                switch (profileName) {
                    case 'apiService':
                        return apiServiceProfile(envName, releaseName, config?.inputs instanceof Map ? (Map) config.inputs : [:])
                    default:
                        throw new IllegalArgumentException("Unsupported generatedBaseValues profile '${profileName}'")
                }
            case 'generatedValues':
                switch (profileName) {
                    case 'outboxWorker':
                        Map<String, Object> profileValues = outboxWorkerProfile()
                        Map<String, Object> overlayValues = config?.overlay instanceof Map ? copyMap((Map) config.overlay) : [:]
                        return deepMerge(profileValues, overlayValues)
                    default:
                        throw new IllegalArgumentException("Unsupported generatedValues profile '${profileName}'")
                }
            default:
                throw new IllegalArgumentException("Unsupported generated values render mode '${mode}'")
        }
    }

    private static Map<String, Object> apiServiceProfile(String envName, String releaseName, Map inputs) {
        String environmentKey = resolveEnvironmentKey(envName)
        Map<String, String> envDefaults = ENVIRONMENT_DEFAULTS[environmentKey]
        if (!envDefaults) {
            throw new IllegalArgumentException("Unsupported generatedBaseValues environment '${envName}'")
        }

        String serviceName = requiredString(inputs, 'serviceName', 'generatedBaseValues.inputs.serviceName')
        String routePrefix = requiredString(inputs, 'routePrefix', 'generatedBaseValues.inputs.routePrefix')
        String configMapName = requiredString(inputs, 'configMapName', 'generatedBaseValues.inputs.configMapName')
        String secretName = requiredString(inputs, 'secretName', 'generatedBaseValues.inputs.secretName')
        String tlsSecretName = requiredString(inputs, 'tlsSecretName', 'generatedBaseValues.inputs.tlsSecretName')
        Integer containerPort = requiredInteger(inputs, 'containerPort', 'generatedBaseValues.inputs.containerPort')
        Map<String, String> secretTemplates = normalizeStringMap(inputs.secretTemplates, 'generatedBaseValues.inputs.secretTemplates')
        Map<String, String> platformSecretTemplates = normalizeOptionalStringMap(inputs.platformSecretTemplates, 'generatedBaseValues.inputs.platformSecretTemplates')
        if (secretTemplates.isEmpty() && platformSecretTemplates.isEmpty()) {
            throw new IllegalArgumentException('generatedBaseValues.inputs must define at least one secret template source')
        }
        List<Map<String, Object>> extraEnv = normalizeExtraEnv(inputs.extraEnv, envDefaults)
        String host = envDefaults.host
        String rolloutTarget = releaseName?.trim() ?: serviceName
        String platformSecretName = "${secretName}-platform"
        List<Map<String, Object>> envFrom = [
            [
                configMapRef: [
                    name: configMapName
                ]
            ]
        ]
        if (!secretTemplates.isEmpty()) {
            envFrom << [
                secretRef: [
                    name    : secretName,
                    optional: false
                ]
            ]
        }
        if (!platformSecretTemplates.isEmpty()) {
            envFrom << [
                secretRef: [
                    name    : platformSecretName,
                    optional: false
                ]
            ]
        }

        List<Map<String, Object>> staticSecrets = []
        if (!secretTemplates.isEmpty()) {
            staticSecrets << buildStaticSecret(
                "${serviceName}-runtime",
                envDefaults.vaultPath,
                secretName,
                rolloutTarget,
                secretTemplates
            )
        }
        if (!platformSecretTemplates.isEmpty()) {
            staticSecrets << buildStaticSecret(
                "${serviceName}-platform-db-runtime",
                envDefaults.platformDbVaultPath,
                platformSecretName,
                rolloutTarget,
                platformSecretTemplates
            )
        }

        return [
            nameOverride        : serviceName,
            imagePullSecrets    : ['regcred'],
            image               : [
                env    : defaultServiceEnv(serviceName, envDefaults) + extraEnv,
                envFrom: envFrom
            ],
            service             : [
                ports: [
                    [
                        name         : 'http',
                        port         : 80,
                        targetPort   : 'http',
                        containerPort: containerPort
                    ]
                ]
            ],
            ingress             : [
                enabled    : true,
                className  : 'kong',
                annotations: [
                    'kubernetes.io/ingress.class'   : 'kong',
                    'cert-manager.io/cluster-issuer': 'letsencrypt-http',
                    'konghq.com/strip-path'         : 'true'
                ],
                hosts      : [
                    [
                        host : host,
                        paths: [
                            [
                                path       : routePrefix,
                                pathType   : 'Prefix',
                                servicePort: 'http'
                            ]
                        ]
                    ]
                ],
                tls        : [
                    [
                        secretName: tlsSecretName,
                        hosts     : [host]
                    ]
                ]
            ],
            configMap           : [
                enabled     : true,
                nameOverride: configMapName
            ],
            vaultSecretsOperator: [
                enabled      : true,
                auth         : [
                    create: true,
                    spec  : [
                        vaultAuthGlobalRef: [
                            name     : envDefaults.vaultAuthGlobal,
                            namespace: 'vault-secrets-operator'
                        ],
                        kubernetes        : [
                            role: "${envDefaults.rolePrefix}-${serviceName}"
                        ],
                        mount             : envDefaults.vaultMount
                    ]
                ],
                staticSecrets: staticSecrets
            ]
        ]
    }

    private static Map<String, Object> buildStaticSecret(
        String name,
        String vaultPath,
        String destinationName,
        String rolloutTarget,
        Map<String, String> secretTemplates
    ) {
        return [
            name: name,
            spec: [
                type                 : 'kv-v2',
                mount                : 'kv',
                path                 : vaultPath,
                refreshAfter         : '5m',
                destination          : [
                    name          : destinationName,
                    create        : true,
                    overwrite     : true,
                    transformation: [
                        excludes : ['.*'],
                        templates: secretTemplates.collectEntries { String destinationKey, String sourceKey ->
                            [(destinationKey): [text: "{{- get .Secrets \"${sourceKey}\" -}}"]]
                        }
                    ]
                ],
                rolloutRestartTargets: [
                    [
                        kind: 'Deployment',
                        name: rolloutTarget
                    ]
                ]
            ]
        ]
    }

    private static List<Map<String, Object>> defaultServiceEnv(String serviceName, Map<String, String> envDefaults) {
        return [
            [name: 'NODE_ENV', value: 'production'],
            [name: 'SERVICE_NAME', value: serviceName],
            [name: 'OTEL_EXPORTER_OTLP_ENDPOINT', value: envDefaults.otelEndpoint],
            [name: 'OTEL_EXPORTER_OTLP_PROTOCOL', value: 'http/protobuf'],
            [name: 'OTEL_EXPORTER_OTLP_TRACES_PROTOCOL', value: 'http/protobuf']
        ]
    }

    private static List<Map<String, Object>> normalizeExtraEnv(Object raw, Map<String, String> envDefaults) {
        if (raw == null) {
            return []
        }
        if (!(raw instanceof List)) {
            throw new IllegalArgumentException('generatedBaseValues.inputs.extraEnv must be a list')
        }

        List<Map<String, Object>> normalized = []
        ((List) raw).eachWithIndex { Object item, int index ->
            if (!(item instanceof Map)) {
                throw new IllegalArgumentException("generatedBaseValues.inputs.extraEnv[${index}] must be an object")
            }
            Map entry = (Map) item
            String name = requiredString(entry, 'name', "generatedBaseValues.inputs.extraEnv[${index}].name")
            boolean optional = entry.containsKey('optional') ? (entry.optional as Boolean) : false
            int sourceKinds = ['value', 'fromPlatformIdentityConfigKey', 'fromPlatformIdentitySecretKey'].count { String key ->
                hasConfiguredValue(entry.get(key))
            }
            if (sourceKinds != 1) {
                throw new IllegalArgumentException(
                    "generatedBaseValues.inputs.extraEnv[${index}] must set exactly one of value, " +
                        'fromPlatformIdentityConfigKey, or fromPlatformIdentitySecretKey'
                )
            }

            if (hasConfiguredValue(entry.value)) {
                normalized << [
                    name : name,
                    value: entry.value.toString()
                ]
                return
            }

            if (hasConfiguredValue(entry.fromPlatformIdentityConfigKey)) {
                Map<String, Object> configMapKeyRef = [
                    name: envDefaults.platformIdentityConfig,
                    key : entry.fromPlatformIdentityConfigKey.toString()
                ]
                if (optional) {
                    configMapKeyRef.optional = true
                }
                normalized << [
                    name     : name,
                    valueFrom: [
                        configMapKeyRef: configMapKeyRef
                    ]
                ]
                return
            }

            Map<String, Object> secretKeyRef = [
                name: envDefaults.platformIdentitySecret,
                key : entry.fromPlatformIdentitySecretKey.toString()
            ]
            if (optional) {
                secretKeyRef.optional = true
            }
            normalized << [
                name     : name,
                valueFrom: [
                    secretKeyRef: secretKeyRef
                ]
            ]
        }
        return normalized
    }

    private static boolean hasConfiguredValue(Object value) {
        if (value == null) {
            return false
        }
        String rendered = value.toString().trim()
        return !rendered.isEmpty()
    }

    private static String resolveEnvironmentKey(String envName) {
        String key = envName?.toString()?.trim()
        if (!key) {
            throw new IllegalArgumentException('Environment name is required for generated values')
        }
        return key.tokenize('_')[0]
    }

    private static String requiredString(Map source, String key, String label) {
        Object value = source?.get(key)
        String rendered = value?.toString()?.trim()
        if (!rendered) {
            throw new IllegalArgumentException("${label} is required")
        }
        return rendered
    }

    private static Integer requiredInteger(Map source, String key, String label) {
        Object value = source?.get(key)
        if (value instanceof Number) {
            return ((Number) value).intValue()
        }
        if (value != null) {
            String rendered = value.toString().trim()
            if (rendered.isInteger()) {
                return rendered.toInteger()
            }
        }
        throw new IllegalArgumentException("${label} must be an integer")
    }

    private static Map<String, String> normalizeStringMap(Object raw, String label) {
        if (!(raw instanceof Map)) {
            throw new IllegalArgumentException("${label} must be an object")
        }

        Map<String, String> normalized = [:]
        ((Map) raw).each { Object key, Object value ->
            String renderedKey = key?.toString()?.trim()
            String renderedValue = value?.toString()?.trim()
            if (!renderedKey) {
                throw new IllegalArgumentException("${label} keys must be non-empty strings")
            }
            if (!renderedValue) {
                throw new IllegalArgumentException("${label}.${renderedKey} must be a non-empty string")
            }
            normalized[renderedKey] = renderedValue
        }
        return normalized
    }

    private static Map<String, String> normalizeOptionalStringMap(Object raw, String label) {
        if (raw == null) {
            return [:]
        }
        return normalizeStringMap(raw, label)
    }

    private static Map<String, Object> outboxWorkerProfile() {
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
