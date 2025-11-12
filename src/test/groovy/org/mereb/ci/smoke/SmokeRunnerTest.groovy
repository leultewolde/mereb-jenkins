import org.junit.jupiter.api.Test
import org.mereb.ci.smoke.SmokeRunner

import static org.junit.jupiter.api.Assertions.*

class SmokeRunnerTest {

    @Test
    void "retries curl smoke and respects timeout"() {
        FakeSteps steps = new FakeSteps()
        steps.failFirst = true
        SmokeRunner runner = new SmokeRunner(steps)

        runner.run([
            name   : 'Health',
            url    : 'https://api.local/health',
            retries: 1,
            headers: [Authorization: 'Bearer token'],
            timeout: '5s'
        ])

        assertEquals(2, steps.shCalls.size())
        assertEquals(1, steps.sleepCalls)
        assertTrue(steps.timeoutCalled)
        assertTrue(steps.shCalls.first().contains('curl'))
    }

    @Test
    void "runs shell script smoke"() {
        FakeSteps steps = new FakeSteps()
        SmokeRunner runner = new SmokeRunner(steps)

        runner.run([script: 'npm run smoke'])

        assertEquals('npm run smoke', steps.shCalls.first())
    }

    @Test
    void "errors when config missing"() {
        FakeSteps steps = new FakeSteps()
        SmokeRunner runner = new SmokeRunner(steps)

        RuntimeException ex = assertThrows(RuntimeException) {
            runner.run([:])
        }
        assertTrue(ex.message.contains('runSmoke'))
    }

    private static class FakeSteps {
        List<String> shCalls = []
        boolean timeoutCalled = false
        int sleepCalls = 0
        boolean failFirst = false
        int attempt = 0

        void echo(String msg) {
            // no-op
        }

        void sh(String script) {
            attempt++
            shCalls << script
            if (failFirst && attempt == 1) {
                throw new RuntimeException('boom')
            }
        }

        void timeout(Map args, Closure body) {
            timeoutCalled = true
            body()
        }

        void sleep(Map args) {
            sleepCalls++
        }

        void error(String msg) {
            throw new RuntimeException(msg)
        }
    }
}
