import org.junit.jupiter.api.Test
import org.mereb.ci.config.ConfigNormalizer
import org.mereb.ci.deploy.HelmDeploymentContext
import org.mereb.ci.deploy.HelmDeploymentContextBuilder

import static org.junit.jupiter.api.Assertions.*

class HelmDeploymentContextBuilderTest {

    @Test
    void "includes the default values file when no override is provided"() {
        FakeSteps steps = new FakeSteps(existing: ['.ci/values-dev.yaml'])
        HelmDeploymentContextBuilder builder = new HelmDeploymentContextBuilder(steps)

        Map envCfg = [displayName: 'Dev', valuesFiles: [], set: [foo: 'bar']]
        HelmDeploymentContext context = builder.build('dev', envCfg, [enabled: true], [repository: 'repo', imageTag: 'tag', imageDigest: 'sha256:abc'])

        assertEquals(['.ci/values-dev.yaml'], context.valuesFiles)
        assertEquals('repo', context.helmArgs.set['image.repository'])
        assertEquals('tag', context.helmArgs.set['image.tag'])
        assertEquals('sha256:abc', context.helmArgs.set['image.digest'])
    }

    @Test
    void "appends generated values overlays after checked in values files"() {
        FakeSteps steps = new FakeSteps(existing: ['.ci/values-dev.yaml'])
        HelmDeploymentContextBuilder builder = new HelmDeploymentContextBuilder(steps)

        Map envCfg = [
            displayName    : 'DEV_OUTBOX',
            valuesFiles    : ['.ci/values-dev.yaml'],
            generatedValues: [
                profile: 'outboxWorker',
                overlay: [
                    deploymentStrategy: [
                        type         : 'RollingUpdate',
                        rollingUpdate: [maxSurge: 0, maxUnavailable: 1]
                    ]
                ]
            ]
        ]
        HelmDeploymentContext context = builder.build('dev_outbox', envCfg, [enabled: true], [repository: 'repo', imageTag: 'tag'])

        assertEquals(['.ci/values-dev.yaml', '.ci/.generated-values-dev_outbox.json'], context.valuesFiles)
        assertTrue(steps.writes.containsKey('.ci/.generated-values-dev_outbox.json'))
        assertTrue(steps.writes['.ci/.generated-values-dev_outbox.json'].contains('"mereb.dev/workload-role"'))
        assertTrue(steps.writes['.ci/.generated-values-dev_outbox.json'].contains('"outbox-relay"'))
        assertTrue(steps.writes['.ci/.generated-values-dev_outbox.json'].contains('"maxUnavailable"'))
    }

    @Test
    void "places generated base values before checked in values files and overlays after them"() {
        FakeSteps steps = new FakeSteps(existing: ['.ci/values-dev.yaml'])
        HelmDeploymentContextBuilder builder = new HelmDeploymentContextBuilder(steps)

        Map envCfg = [
            displayName        : 'DEV',
            release            : 'svc-feed-dev',
            valuesFiles        : ['.ci/values-dev.yaml'],
            generatedBaseValues: [
                profile: 'apiService',
                inputs : [
                    serviceName   : 'svc-feed',
                    containerPort : 4002,
                    routePrefix   : '/feed',
                    configMapName : 'svc-feed-dev-config',
                    secretName    : 'svc-feed-dev-secrets',
                    tlsSecretName : 'feed-dev-tls',
                    secretTemplates: [
                        DATABASE_URL   : 'FEED_DATABASE_URL',
                        SPLUNK_HEC_TOKEN: 'SPLUNK_HEC_TOKEN'
                    ],
                    extraEnv      : [
                        [name: 'OIDC_ISSUER', fromPlatformIdentityConfigKey: 'OIDC_ISSUER']
                    ]
                ]
            ],
            generatedValues    : [
                profile: 'outboxWorker'
            ]
        ]

        HelmDeploymentContext context = builder.build('dev', envCfg, [enabled: true], [repository: 'repo', imageTag: 'tag'])

        assertEquals(
            ['.ci/.generated-base-values-dev.json', '.ci/values-dev.yaml', '.ci/.generated-values-dev.json'],
            context.valuesFiles
        )
        assertTrue(steps.writes.containsKey('.ci/.generated-base-values-dev.json'))
        assertTrue(steps.writes['.ci/.generated-base-values-dev.json'].contains('"svc-feed-dev-secrets"'))
        assertTrue(steps.writes['.ci/.generated-base-values-dev.json'].contains('"api-dev.mereb.app"'))
        assertTrue(steps.writes.containsKey('.ci/.generated-values-dev.json'))
    }

    @Test
    void "keeps generated base first and overlay last after deploy defaults and extends are normalized"() {
        FakeSteps steps = new FakeSteps(existing: ['.ci/values-dev.yaml'])
        HelmDeploymentContextBuilder builder = new HelmDeploymentContextBuilder(steps)
        Map cfg = ConfigNormalizer.normalize([
            version: 1,
            image  : false,
            deploy : [
                defaults             : [
                    chart           : 'app-chart',
                    repo            : 'https://charts.leultewolde.com',
                    repoCredentialId: 'helm-chart-creds'
                ],
                generatedBaseDefaults: [
                    profile: 'apiService',
                    inputs : [
                        serviceName   : 'svc-feed',
                        containerPort : 4002,
                        routePrefix   : '/feed',
                        secretTemplates: [
                            DATABASE_URL: 'FEED_DATABASE_URL'
                        ]
                    ]
                ],
                dev                  : [
                    release            : 'svc-feed-dev',
                    namespace          : 'apps-dev',
                    valuesFiles        : ['.ci/values-dev.yaml'],
                    generatedBaseValues: [
                        inputs: [
                            configMapName: 'svc-feed-dev-config',
                            secretName   : 'svc-feed-dev-secrets',
                            tlsSecretName: 'feed-dev-tls'
                        ]
                    ]
                ],
                dev_outbox           : [
                    extends        : 'dev',
                    release        : 'svc-feed-dev-outbox',
                    smoke          : false,
                    generatedValues: [
                        profile: 'outboxWorker'
                    ]
                ]
            ]
        ], ['dev', 'dev_outbox'], '.ci/ci.mjc')

        HelmDeploymentContext context = builder.build('dev_outbox', cfg.deploy.environments.dev_outbox as Map, [enabled: true], [repository: 'repo', imageTag: 'tag'])

        assertEquals(
            ['.ci/.generated-base-values-dev_outbox.json', '.ci/values-dev.yaml', '.ci/.generated-values-dev_outbox.json'],
            context.valuesFiles
        )
        assertTrue(steps.writes['.ci/.generated-base-values-dev_outbox.json'].contains('"svc-feed-dev-secrets"'))
        assertTrue(steps.writes['.ci/.generated-values-dev_outbox.json'].contains('"outbox-relay"'))
    }

    private static class FakeSteps {
        final Set<String> existing
        final Map<String, String> writes = [:]

        FakeSteps(Map args = [:]) {
            this.existing = (args.existing ?: []) as Set
        }

        boolean fileExists(String path) {
            return existing.contains(path) || writes.containsKey(path)
        }

        void writeFile(Map args) {
            writes[args.file] = args.text
        }
    }
}
