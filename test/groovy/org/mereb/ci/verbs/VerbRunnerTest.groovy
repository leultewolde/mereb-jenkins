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
    void "passes loadEnvFile through pnpm publish verb"() {
        FakeSteps steps = new FakeSteps()

        new VerbRunner(steps).run('pnpm.publish loadEnvFile=false envFile=.ci/custom-env.sh')

        assertTrue(steps.calls.last().contains('label:Publish npm package from .'))
        assertFalse(steps.calls.last().contains(". '.ci/custom-env.sh'"))
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

    @Test
    void "triggers downstream Jenkins build via jenkins build verb with debounce controls"() {
        FakeSteps steps = new FakeSteps()
        new VerbRunner(steps).run('jenkins.build job=graphos-promote-prd wait=false propagate=false quietPeriod=300')

        assertEquals(1, steps.buildCalls.size())
        assertEquals('graphos-promote-prd', steps.buildCalls[0].job)
        assertEquals(false, steps.buildCalls[0].wait)
        assertEquals(false, steps.buildCalls[0].propagate)
        assertEquals(300, steps.buildCalls[0].quietPeriod)
    }

    @Test
    void "jenkins build verb defaults wait and propagate to true"() {
        FakeSteps steps = new FakeSteps()
        new VerbRunner(steps).run('jenkins.build job=graphos-promote-prd')

        assertEquals(1, steps.buildCalls.size())
        assertEquals(true, steps.buildCalls[0].wait)
        assertEquals(true, steps.buildCalls[0].propagate)
        assertFalse(steps.buildCalls[0].containsKey('quietPeriod'))
    }

    private static class FakeSteps {
        List<String> calls = []
        Map env = [:]
        List<Map> buildCalls = []

        void sh(Object script) {
            calls << "sh:${script}".toString()
        }

        void sh(Map args) {
            calls << "sh:label:${args.label};script:${args.script}".toString()
        }

        void error(String message) {
            throw new RuntimeException(message)
        }

        void build(Map args) {
            buildCalls << new LinkedHashMap(args)
        }
    }
}
