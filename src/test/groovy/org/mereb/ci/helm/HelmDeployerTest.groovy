import org.junit.jupiter.api.Test
import org.mereb.ci.helm.HelmDeployer

import static org.junit.jupiter.api.Assertions.*

class HelmDeployerTest {

    @Test
    void "builds helm command with repo and values"() {
        FakeSteps steps = new FakeSteps(existingFiles: ['values.yaml'])
        HelmDeployer deployer = new HelmDeployer(steps)

        deployer.deploy([
            release    : 'web',
            chart      : 'infra/charts/web',
            namespace  : 'apps',
            repo       : 'https://charts.local',
            version    : '1.2.3',
            valuesFiles: ['values.yaml', 'missing.yaml'],
            set        : [imageTag: 'abc'],
            setString  : [replicas: '2'],
            setFile    : [secret: 'file.env'],
            kubeconfig : '/tmp/kube'
        ])

        assertEquals(1, steps.withEnvCalls.size())
        assertEquals(['KUBECONFIG=/tmp/kube'], steps.withEnvCalls.first())
        String command = steps.shCalls.first()
        assertTrue(command.contains("helm upgrade --install 'web' 'infra/charts/web'"))
        assertTrue(command.contains("-f 'values.yaml'"))
        assertFalse(command.contains("missing.yaml"))
        assertTrue(command.contains("--set 'imageTag=abc'"))
        assertTrue(command.contains("--set-string 'replicas=2'"))
        assertTrue(command.contains("--set-file 'secret=file.env'"))
    }

    @Test
    void "errors when required args missing"() {
        FakeSteps steps = new FakeSteps()
        HelmDeployer deployer = new HelmDeployer(steps)

        RuntimeException ex = assertThrows(RuntimeException) {
            deployer.deploy([chart: 'infra'])
        }
        assertTrue(ex.message.contains('release'))
    }

    private static class FakeSteps {
        final Set<String> existingFiles
        final List<String> shCalls = []
        final List<List<String>> withEnvCalls = []

        FakeSteps(Map args = [:]) {
            this.existingFiles = (args.existingFiles ?: []) as Set
        }

        void sh(String script) {
            shCalls << script
        }

        boolean fileExists(String path) {
            return existingFiles.contains(path)
        }

        void echo(String msg) {
            // no-op
        }

        void error(String msg) {
            throw new RuntimeException(msg)
        }

        void withEnv(List<String> env, Closure body) {
            withEnvCalls << env.collect { it.toString() }
            body()
        }
    }
}
