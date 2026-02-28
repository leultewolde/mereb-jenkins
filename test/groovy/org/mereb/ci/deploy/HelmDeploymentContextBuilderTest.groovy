import org.junit.jupiter.api.Test
import org.mereb.ci.credentials.CredentialHelper
import org.mereb.ci.deploy.HelmDeploymentContext
import org.mereb.ci.deploy.HelmDeploymentContextBuilder
import org.mereb.ci.deploy.ValuesTemplateRenderer
import org.mereb.ci.util.VaultContext
import org.mereb.ci.util.VaultCredentialHelper

import static org.junit.jupiter.api.Assertions.*

class HelmDeploymentContextBuilderTest {

    @Test
    void "includes default values file plus rendered templates"() {
        FakeSteps steps = new FakeSteps(existing: ['.ci/values-dev.yaml'])
        FakeRenderer renderer = new FakeRenderer(['generated.yaml'])
        CredentialHelper credentialHelper = new CredentialHelper(steps)
        VaultCredentialHelper vaultHelper = new VaultCredentialHelper(steps, credentialHelper)
        HelmDeploymentContextBuilder builder = new HelmDeploymentContextBuilder(steps, renderer, credentialHelper, vaultHelper)

        Map envCfg = [displayName: 'Dev', valuesFiles: [], set: [foo: 'bar']]
        HelmDeploymentContext context = builder.build('dev', envCfg, [enabled: true], [repository: 'repo', imageTag: 'tag', imageDigest: 'sha256:abc'], new VaultContext(null, []))

        assertEquals(['.ci/values-dev.yaml', 'generated.yaml'], context.valuesFiles)
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

        void withEnv(List<String> env, Closure body) {
            body?.call()
        }

        void withCredentials(List<Map> bindings, Closure body) {
            body?.call()
        }
    }

    private static class FakeRenderer extends ValuesTemplateRenderer {
        private final List<String> templates

        FakeRenderer(List<String> templates) {
            super(new FakeSteps())
            this.templates = templates
        }

        @Override
        List<String> render(String envName, Map envCfg) {
            return templates
        }
    }
}
