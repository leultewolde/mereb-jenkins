import org.junit.jupiter.api.Test
import org.mereb.ci.credentials.CredentialHelper
import org.mereb.ci.util.StageExecutor

import static org.junit.jupiter.api.Assertions.*

class StageExecutorTest {

    @Test
    void "runs stage with env and credentials"() {
        FakeStageSteps steps = new FakeStageSteps()
        FakeCredentialHelper credentialHelper = new FakeCredentialHelper()
        StageExecutor executor = new StageExecutor(steps, credentialHelper)

        List<String> events = []
        executor.run('Build', ['FOO=1'], [[id: 'auth']], { events << 'ran' })

        assertEquals(['Build'], steps.stageNames)
        assertEquals([['FOO=1']], steps.envs)
        assertEquals([[[id: 'auth']]], credentialHelper.capturedBindings)
        assertEquals(['ran'], events)
    }

    @Test
    void "runs stage without env when none provided"() {
        FakeStageSteps steps = new FakeStageSteps()
        FakeCredentialHelper credentialHelper = new FakeCredentialHelper()
        StageExecutor executor = new StageExecutor(steps, credentialHelper)

        executor.run('Lint', null, null, {})

        assertEquals(['Lint'], steps.stageNames)
        assertTrue(steps.envs.isEmpty())
        assertEquals([[]], credentialHelper.capturedBindings)
    }

    private static class FakeStageSteps {
        final List<String> stageNames = []
        final List<List<String>> envs = []

        void stage(String name, Closure body) {
            stageNames << name
            body?.call()
        }

        void withEnv(List<String> envList, Closure body) {
            envs << new ArrayList<>(envList)
            body?.call()
        }
    }

    private static class FakeCredentialHelper extends CredentialHelper {
        final List<List<Map>> capturedBindings = []

        FakeCredentialHelper() {
            super(new Object())
        }

        @Override
        void withOptionalCredentials(List<Map> bindings, Closure body) {
            capturedBindings << (bindings ?: [])
            body()
        }
    }
}
