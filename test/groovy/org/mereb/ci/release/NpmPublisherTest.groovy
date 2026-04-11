import org.junit.jupiter.api.Test
import org.mereb.ci.release.NpmPublisher

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class NpmPublisherTest {

    @Test
    void "preserves registry path in npm auth config"() {
        FakeSteps steps = new FakeSteps()

        new NpmPublisher(steps).publish([registry: 'https://registry.example.com/npm/private/'])

        assertTrue(steps.script.contains("printf '//%s/:_authToken=%s"))
        assertTrue(steps.script.contains('"${REGISTRY_AUTH_PATH}" "${NPM_TOKEN}"'))
        assertTrue(steps.script.contains('REGISTRY_AUTH_PATH="${REGISTRY_HOST%/}"'))
        assertTrue(steps.script.contains('REGISTRY_HOST="${REGISTRY_AUTH_PATH%%/*}"'))
    }

    @Test
    void "adds npmjs scoped publish guidance"() {
        FakeSteps steps = new FakeSteps()

        new NpmPublisher(steps).publish()

        assertTrue(steps.script.contains('NPM_WHOAMI="$(npm whoami --registry "${REGISTRY_URL}" 2>/dev/null || true)"'))
        assertTrue(steps.script.contains('npmjs rejected the scoped package publish for ${PACKAGE_NAME}.'))
        assertTrue(steps.script.contains('Verify the Jenkins NPM_TOKEN credential can publish packages for ${PACKAGE_SCOPE} on npmjs.com.'))
    }

    @Test
    void "skips env file sourcing when disabled"() {
        FakeSteps steps = new FakeSteps()

        new NpmPublisher(steps).publish([loadEnvFile: false, envFile: '.ci/custom-env.sh'])

        assertFalse(steps.script.contains("if [ -f '.ci/custom-env.sh' ]; then"))
        assertFalse(steps.script.contains(". '.ci/custom-env.sh'"))
    }

    private static class FakeSteps {
        Map env = [:]
        String script = ''

        void sh(Map args) {
            script = args.script ?: ''
        }
    }
}
