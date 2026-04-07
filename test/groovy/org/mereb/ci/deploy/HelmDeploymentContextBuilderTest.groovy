import org.junit.jupiter.api.Test
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
