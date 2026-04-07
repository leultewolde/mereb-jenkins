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

    private static class FakeSteps {
        final Set<String> existing

        FakeSteps(Map args = [:]) {
            this.existing = (args.existing ?: []) as Set
        }

        boolean fileExists(String path) {
            return existing.contains(path)
        }
    }
}
