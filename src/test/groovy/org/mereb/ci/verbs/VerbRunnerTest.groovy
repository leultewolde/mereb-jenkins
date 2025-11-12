import org.junit.jupiter.api.Test
import org.mereb.ci.verbs.VerbRunner

import static org.junit.jupiter.api.Assertions.*

class VerbRunnerTest {

    @Test
    void "runs shell commands directly"() {
        FakeSteps steps = new FakeSteps()
        new VerbRunner(steps).run('sh echo 123')

        assertEquals(['sh:echo 123'], steps.calls)
    }

    @Test
    void "invokes predefined node verbs"() {
        FakeSteps steps = new FakeSteps()
        new VerbRunner(steps).run('node.install')

        assertEquals(['sh:npm ci'], steps.calls)
    }

    @Test
    void "renders docker build arguments"() {
        FakeSteps steps = new FakeSteps()
        new VerbRunner(steps).run("docker.build tag=ghcr.io/mereb/api:dev file=services/api")

        assertEquals(["sh:docker build -t 'ghcr.io/mereb/api:dev' 'services/api'"], steps.calls)
    }

    @Test
    void "throws for unknown verbs"() {
        FakeSteps steps = new FakeSteps()
        def runner = new VerbRunner(steps)

        def error = assertThrows(RuntimeException) {
            runner.run('unknown.action')
        }
        assertTrue(error.message.contains('Unknown verb'))
    }

    private static class FakeSteps {
        List<String> calls = []

        void sh(Object script) {
            calls << "sh:${script}".toString()
        }

        void error(String message) {
            throw new RuntimeException(message)
        }
    }
}
