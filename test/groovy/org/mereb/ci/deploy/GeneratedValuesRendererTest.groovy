import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test
import org.mereb.ci.deploy.GeneratedValuesRenderer

import static org.junit.jupiter.api.Assertions.*

class GeneratedValuesRendererTest {

    @Test
    void "renders the outbox profile with overlay merged on top"() {
        FakeSteps steps = new FakeSteps()
        GeneratedValuesRenderer renderer = new GeneratedValuesRenderer(steps)

        String path = renderer.renderOverlay('prd_outbox', [
            profile: 'outboxWorker',
            overlay: [
                deploymentStrategy: [
                    type         : 'RollingUpdate',
                    rollingUpdate: [maxSurge: 0, maxUnavailable: 1]
                ],
                resources         : [
                    requests: [cpu: '50m']
                ]
            ]
        ])

        assertEquals('.ci/.generated-values-prd_outbox.json', path)
        Map parsed = new JsonSlurper().parseText(steps.writes[path]) as Map
        assertEquals('outbox-relay', parsed.podLabels['mereb.dev/workload-role'])
        assertEquals(['node', 'dist/outboxRelayWorker.js'], parsed.image.args)
        assertEquals(false, parsed.service.enabled)
        assertEquals([], parsed.service.ports)
        assertEquals('RollingUpdate', parsed.deploymentStrategy.type)
        assertEquals(0, parsed.deploymentStrategy.rollingUpdate.maxSurge)
        assertEquals('50m', parsed.resources.requests.cpu)
    }

    @Test
    void "renders the api service base profile from explicit inputs"() {
        FakeSteps steps = new FakeSteps()
        GeneratedValuesRenderer renderer = new GeneratedValuesRenderer(steps)

        String path = renderer.renderBase('prd', 'svc-profile', [
            profile: 'apiService',
            inputs : [
                serviceName   : 'svc-profile',
                containerPort : 4001,
                routePrefix   : '/profile',
                configMapName : 'svc-profile-prd-config',
                secretName    : 'svc-profile-secrets',
                tlsSecretName : 'profile-prd-tls',
                secretTemplates: [
                    DATABASE_URL             : 'PROFILE_DATABASE_URL',
                    KEYCLOAK_WEBHOOK_SECRET  : 'KEYCLOAK_WEBHOOK_SECRET',
                    KEYCLOAK_WEBHOOK_BASIC_USER: 'KEYCLOAK_WEBHOOK_BASIC_USER',
                    KEYCLOAK_WEBHOOK_BASIC_PASS: 'KEYCLOAK_WEBHOOK_BASIC_PASS',
                    SPLUNK_HEC_TOKEN         : 'SPLUNK_HEC_TOKEN'
                ],
                extraEnv      : [
                    [name: 'OIDC_ISSUER', fromPlatformIdentityConfigKey: 'OIDC_ISSUER'],
                    [name: 'KEYCLOAK_WEBHOOK_CLIENT_IDS', fromPlatformIdentityConfigKey: 'KEYCLOAK_WEBHOOK_CLIENT_IDS'],
                    [name: 'KEYCLOAK_WEBHOOK_SECRET', fromPlatformIdentitySecretKey: 'PROFILE_BOOTSTRAP_SHARED_SECRET']
                ]
            ]
        ])

        assertEquals('.ci/.generated-base-values-prd.json', path)
        Map parsed = new JsonSlurper().parseText(steps.writes[path]) as Map
        assertEquals('svc-profile', parsed.nameOverride)
        assertEquals(['regcred'], parsed.imagePullSecrets)
        assertEquals('svc-profile-prd-config', parsed.configMap.nameOverride)
        assertEquals('svc-profile-secrets', parsed.image.envFrom[1].secretRef.name)
        assertEquals(false, parsed.image.envFrom[1].secretRef.optional)
        assertEquals(4001, parsed.service.ports[0].containerPort)
        assertEquals('/profile', parsed.ingress.hosts[0].paths[0].path)
        assertEquals('api.mereb.app', parsed.ingress.hosts[0].host)
        assertEquals('profile-prd-tls', parsed.ingress.tls[0].secretName)
        assertEquals('mereb-apps-prd', parsed.vaultSecretsOperator.auth.spec.vaultAuthGlobalRef.name)
        assertEquals('kubernetes-prd', parsed.vaultSecretsOperator.auth.spec.mount)
        assertEquals('apps-prd-svc-profile', parsed.vaultSecretsOperator.auth.spec.kubernetes.role)
        assertEquals('apps/prd', parsed.vaultSecretsOperator.staticSecrets[0].spec.path)
        assertEquals('svc-profile-secrets', parsed.vaultSecretsOperator.staticSecrets[0].spec.destination.name)
        assertEquals('svc-profile', parsed.vaultSecretsOperator.staticSecrets[0].spec.rolloutRestartTargets[0].name)
        assertEquals('{{- get .Secrets "PROFILE_DATABASE_URL" -}}', parsed.vaultSecretsOperator.staticSecrets[0].spec.destination.transformation.templates.DATABASE_URL.text)
    }

    @Test
    void "rejects unsupported generated values overlay profiles"() {
        FakeSteps steps = new FakeSteps()
        GeneratedValuesRenderer renderer = new GeneratedValuesRenderer(steps)

        IllegalArgumentException ex = assertThrows(IllegalArgumentException) {
            renderer.renderOverlay('dev', [profile: 'unknown'])
        }

        assertTrue(ex.message.contains('Unsupported generatedValues profile'))
    }

    @Test
    void "rejects unsupported generated base values profiles"() {
        FakeSteps steps = new FakeSteps()
        GeneratedValuesRenderer renderer = new GeneratedValuesRenderer(steps)

        IllegalArgumentException ex = assertThrows(IllegalArgumentException) {
            renderer.renderBase('dev', 'svc-feed-dev', [profile: 'unknown', inputs: [:]])
        }

        assertTrue(ex.message.contains('Unsupported generatedBaseValues profile'))
    }

    private static class FakeSteps {
        final Map<String, String> writes = [:]

        void writeFile(Map args) {
            writes[args.file] = args.text
        }
    }
}
