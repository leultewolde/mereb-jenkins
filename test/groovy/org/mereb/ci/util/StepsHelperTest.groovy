import org.junit.jupiter.api.Test
import org.mereb.ci.util.StepsHelper

import static org.junit.jupiter.api.Assertions.*

class StepsHelperTest {

    @Test
    void "configures pnpm in node context"() {
        FakeSteps steps = new FakeSteps()
        StepsHelper helper = new StepsHelper(steps)

        helper.withPnpm { steps.marker++ }

        assertEquals(1, steps.nodejsCalls)
        assertEquals(1, steps.marker)
        assertTrue(steps.shCalls.any { it.contains('corepack enable') })
    }

    @Test
    void "performs docker login with default registry"() {
        FakeSteps steps = new FakeSteps()
        StepsHelper helper = new StepsHelper(steps)

        helper.dockerLogin([:])

        assertTrue(steps.withCredsCalled)
        assertTrue(steps.shCalls.last().contains('docker login'))
    }

    @Test
    void "wraps approval gate"() {
        FakeSteps steps = new FakeSteps()
        StepsHelper helper = new StepsHelper(steps)

        helper.approvalGate('Go?')

        assertTrue(steps.timeoutCalled)
        assertEquals('Go?', steps.inputArgs.message)
    }

    private static class FakeSteps {
        int nodejsCalls = 0
        int marker = 0
        List<String> shCalls = []
        boolean withCredsCalled = false
        boolean timeoutCalled = false
        Map inputArgs = [:]
        Map env = [:]

        void nodejs(String label, Closure body) {
            nodejsCalls++
            body()
        }

        void sh(String script) {
            shCalls << script
        }

        void withCredentials(List creds, Closure body) {
            withCredsCalled = true
            body()
        }

        def usernamePassword(Map args) {
            return args
        }

        def file(Map args) {
            return args
        }

        def string(Map args) {
            return args
        }

        void timeout(Map args, Closure body) {
            timeoutCalled = true
            body()
        }

        void input(Map args) {
            inputArgs = args
        }
    }
}
