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
                rolloutTimeout : '5m',
                when           : '',
                valuesTemplates: [[
                        template: 'templates/values-secret.yaml.tpl',
                        output  : '.ci/.rendered/values-dev.secret.yaml',
                        vault   : [url: 'vault.dev']
                ]],
                credentials    : [[type: 'string', id: 'vault-credentials', env: 'VAULT_TOKEN']]
        ]

        pipeline.run([
                image : [enabled: true],
                deploy: [order: ['dev'], environments: [dev: envCfg]]
        ], [repository: 'registry.leultewolde.com/mereb/svc-feed', imageTag: 'main-abc123'])

        assertEquals(1, renderer.renderCalls)
        assertEquals('.ci/.rendered/values-dev.secret.yaml', renderer.lastOutput)
        assertTrue(steps.withCredentialsBindings.any { bindings ->
            bindings.any { (it.variable ?: it.tokenVariable) == 'VAULT_TOKEN' && it.credentialsId == 'vault-credentials' }
        }, 'Expected vault credentials to be bound during template rendering')
        assertTrue(steps.withEnvCalls.any { it.VAULT_ADDR == 'https://vault.dev' }, 'Expected VAULT_ADDR to be set during render')
        assertTrue(steps.shScripts.any { it.contains("kubectl -n 'apps-dev' get deployment -l 'app.kubernetes.io/instance=svc-feed-dev' -o name") })
        assertTrue(steps.shScripts.any { it.contains("kubectl -n 'apps-dev' rollout restart 'deployment/svc-feed-dev'") })
        assertTrue(steps.shScripts.any { it.contains("kubectl -n 'apps-dev' rollout status 'deployment/svc-feed-dev' --timeout='5m'") })
        assertTrue(steps.shScripts.any { it.contains("kubectl -n 'apps-dev' get pods -l 'app.kubernetes.io/instance=svc-feed-dev'") })
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
        final List<Map<String, String>> withEnvCalls = []

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

        void withEnv(List<String> vars, Closure body) {
            Map<String, String> snapshot = [:]
            vars.each { String entry ->
                if (!entry) return
                def parts = entry.split('=', 2)
                if (parts.length == 2) {
                    String key = parts[0]
                    snapshot[key] = env[key]
                    env[key] = parts[1]
                }
            }
            withEnvCalls << vars.collectEntries { it.contains('=') ? [(it.split('=',2)[0]): it.split('=',2)[1]] : [:] }
            try {
                body?.call()
            } finally {
                snapshot.each { k, v ->
                    if (v == null) {
                        env.remove(k)
                    } else {
                        env[k] = v
                    }
                }
            }
        }

        String sh(def args) {
            if (args instanceof Map) {
                String script = (args.script ?: '').toString()
                shScripts << script
                if (args.returnStdout) {
                    def matcher = script =~ /printenv\s+([A-Za-z0-9_]+)/
                    if (matcher.find()) {
                        String key = matcher[0][1]
                        return (env[key] ?: '') + '\n'
                    }
                    if (script.contains("get deployment -l 'app.kubernetes.io/instance=svc-feed-dev' -o name")) {
                        return "deployment/svc-feed-dev\n"
                    }
                    if (script.contains("get statefulset -l 'app.kubernetes.io/instance=svc-feed-dev' -o name")) {
                        return ''
                    }
                    if (script.contains("get pods -l 'app.kubernetes.io/instance=svc-feed-dev' -o jsonpath")) {
                        return "registry.leultewolde.com/mereb/svc-feed:main-abc123\n"
                    }
                    return ''
                }
                return ''
            }
            shScripts << args.toString()
            return ''
        }

        void error(String msg) {
            throw new RuntimeException(msg)
        }
    }
}
