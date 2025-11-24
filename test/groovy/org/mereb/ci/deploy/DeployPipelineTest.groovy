import org.junit.jupiter.api.Test
import org.mereb.ci.credentials.CredentialHelper
import org.mereb.ci.deploy.DeployPipeline
import org.mereb.ci.deploy.ValuesTemplateRenderer

import static org.junit.jupiter.api.Assertions.*

class DeployPipelineTest {

    @Test
    void "renders values templates with credentials bound for vault tokens"() {
        FakeSteps steps = new FakeSteps()
        StubRenderer renderer = new StubRenderer(steps)
        CredentialHelper credentialHelper = new CredentialHelper(steps)
        DeployPipeline pipeline = new DeployPipeline(steps, credentialHelper, renderer)

        Map envCfg = [
                displayName    : 'DEV',
                namespace      : 'apps-dev',
                release        : 'svc-feed-dev',
                chart          : 'app-chart',
                repo           : 'https://example.com/chart',
                when           : '',
                valuesTemplates: [[
                        template: 'templates/values-secret.yaml.tpl',
                        output  : '.ci/.rendered/values-dev.secret.yaml'
                ]],
                credentials    : [[type: 'string', id: 'vault-credentials', env: 'VAULT_TOKEN']]
        ]

        pipeline.run([
                image : [enabled: false],
                deploy: [order: ['dev'], environments: [dev: envCfg]]
        ], [:])

        assertEquals(1, renderer.renderCalls)
        assertEquals('.ci/.rendered/values-dev.secret.yaml', renderer.lastOutput)
        assertTrue(steps.withCredentialsBindings.any { bindings ->
            bindings.any { (it.variable ?: it.tokenVariable) == 'VAULT_TOKEN' && it.credentialsId == 'vault-credentials' }
        }, 'Expected vault credentials to be bound during template rendering')
    }

    private static class StubRenderer extends ValuesTemplateRenderer {
        final FakeSteps stepsRef
        int renderCalls = 0
        String lastOutput

        StubRenderer(FakeSteps steps) {
            super(steps)
            this.stepsRef = steps
        }

        @Override
        List<String> render(String envName, Map envCfg) {
            renderCalls++
            if (!stepsRef.env.VAULT_TOKEN) {
                throw new RuntimeException("VAULT_TOKEN missing during render")
            }
            lastOutput = envCfg.valuesTemplates?.first()?.output ?: '.ci/.rendered/out.yaml'
            stepsRef.writes[lastOutput] = 'rendered'
            return [lastOutput]
        }
    }

    private static class FakeSteps {
        final Map env = [BRANCH_NAME: 'main', CHANGE_ID: '']
        final Map<String, String> files = [:]
        final Map<String, String> writes = [:]
        final List<List<Map>> withCredentialsBindings = []
        final List<String> shScripts = []

        void echo(String msg) {}

        boolean fileExists(String path) {
            return files.containsKey(path) || writes.containsKey(path)
        }

        String readFile(String path) {
            return files[path] ?: ''
        }

        void writeFile(Map args) {
            writes[args.file] = args.text
        }

        void stage(String name, Closure body) {
            body?.call()
        }

        void helmDeploy(Map args) {}

        void withCredentials(List bindings, Closure body) {
            withCredentialsBindings << bindings
            bindings.each { Map binding ->
                String varName = binding.variable ?: binding.usernameVariable ?: binding.passwordVariable ?: binding.tokenVariable
                if (varName) {
                    env[varName] = "secret-${binding.credentialsId ?: binding.id}"
                }
            }
            body?.call()
        }

        String sh(Map args) {
            if (args.returnStdout) {
                def matcher = args.script =~ /printenv\s+([A-Za-z0-9_]+)/
                if (matcher.find()) {
                    String key = matcher[0][1]
                    return (env[key] ?: '') + '\n'
                }
                return ''
            }
            shScripts << (args.script ?: '')
            return ''
        }

        void error(String msg) {
            throw new RuntimeException(msg)
        }
    }
}
