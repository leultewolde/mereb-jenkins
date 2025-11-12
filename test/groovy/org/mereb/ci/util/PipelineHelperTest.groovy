import org.junit.jupiter.api.Test
import org.mereb.ci.util.PipelineHelper

import static org.junit.jupiter.api.Assertions.*

class PipelineHelperTest {

    @Test
    void "prefers primary config path when present"() {
        FakeSteps steps = new FakeSteps(existing: ['.ci/ci.yml'])
        PipelineHelper helper = new PipelineHelper(steps)

        assertEquals('.ci/ci.yml', helper.locateConfig('.ci/ci.yml', 'ci.yml'))
        assertEquals(['.ci/ci.yml'], steps.fileChecks)
    }

    @Test
    void "falls back to legacy config"() {
        FakeSteps steps = new FakeSteps(existing: ['ci.yml'])
        PipelineHelper helper = new PipelineHelper(steps)

        assertEquals('ci.yml', helper.locateConfig('.ci/ci.yml', 'ci.yml'))
        assertEquals(['.ci/ci.yml', 'ci.yml'], steps.fileChecks)
    }

    @Test
    void "computes home directory from env"() {
        FakeSteps steps = new FakeSteps(env: [HOME: '/tmp/custom'])
        PipelineHelper helper = new PipelineHelper(steps)

        assertEquals('/tmp/custom', helper.determineHome('/workspace'))
    }

    @Test
    void "uses workspace when HOME empty"() {
        FakeSteps steps = new FakeSteps(env: [:])
        PipelineHelper helper = new PipelineHelper(steps)

        assertEquals('/workspace', helper.determineHome('/workspace'))
    }

    @Test
    void "returns cached commit when available"() {
        FakeSteps steps = new FakeSteps(env: [GIT_COMMIT: 'abc123'])
        PipelineHelper helper = new PipelineHelper(steps)

        assertEquals('abc123', helper.resolveCommitSha())
        assertFalse(steps.shCalled)
    }

    @Test
    void "shells out when commit missing"() {
        FakeSteps steps = new FakeSteps(env: [:], shResult: 'deadbeef\n')
        PipelineHelper helper = new PipelineHelper(steps)

        assertEquals('deadbeef', helper.resolveCommitSha())
        assertTrue(steps.shCalled)
    }

    @Test
    void "skips approval when not configured"() {
        FakeSteps steps = new FakeSteps()
        new PipelineHelper(steps).awaitApproval(null, 'Approve?')

        assertTrue(steps.inputs.isEmpty())
    }

    @Test
    void "requests approval with optional submitter"() {
        FakeSteps steps = new FakeSteps()
        Map approval = [message: 'Go live?', ok: 'Ship', submitter: 'ops']

        new PipelineHelper(steps).awaitApproval(approval, 'Approve?')

        assertEquals([[message: 'Go live?', ok: 'Ship', submitter: 'ops']], steps.inputs)
    }

    @Test
    void "prefers cleanWs when available"() {
        FakeSteps steps = new FakeSteps()
        PipelineHelper helper = new PipelineHelper(steps)

        helper.cleanupWorkspace('/tmp/ws')

        assertEquals(1, steps.cleanWsCalls.size())
        assertEquals(0, steps.deleteDirCalls)
    }

    @Test
    void "falls back to deleteDir when cleanWs missing"() {
        FakeSteps steps = new FakeSteps(cleanWsBehavior: 'missing')
        PipelineHelper helper = new PipelineHelper(steps)

        helper.cleanupWorkspace('/tmp/ws')

        assertEquals(['Cleaning workspace...', 'cleanWs step unavailable; falling back to deleteDir()'], steps.echoes)
        assertEquals(['/tmp/ws'], steps.dirCalls)
        assertEquals(1, steps.deleteDirCalls)
    }

    @Test
    void "logs error when cleanup fails"() {
        FakeSteps steps = new FakeSteps(cleanWsBehavior: 'error')
        PipelineHelper helper = new PipelineHelper(steps)

        helper.cleanupWorkspace('')

        assertTrue(steps.echoes.last().startsWith('Workspace cleanup failed:'))
    }

    private static class FakeSteps {
        final List<String> fileChecks = []
        final Map env
        final Set<String> existing
        final String shResult
        boolean shCalled
        final List<Map> inputs = []
        final List<String> echoes = []
        final List<Map> cleanWsCalls = []
        final List<String> dirCalls = []
        int deleteDirCalls = 0
        final String cleanWsBehavior

        FakeSteps(Map args = [:]) {
            this.env = args.env ?: [:]
            this.existing = (args.existing ?: []) as Set
            this.shResult = args.shResult ?: ''
            this.cleanWsBehavior = args.cleanWsBehavior ?: 'success'
        }

        boolean fileExists(String path) {
            fileChecks << path
            return existing.contains(path)
        }

        String sh(Map args) {
            shCalled = true
            return shResult
        }

        void input(Map args) {
            inputs << args
        }

        void echo(String msg) {
            echoes << msg
        }

        void cleanWs(Map args) {
            if ('missing'.equals(cleanWsBehavior)) {
                throw new MissingMethodException('cleanWs', FakeSteps, null)
            }
            if ('error'.equals(cleanWsBehavior)) {
                throw new RuntimeException('unable to clean')
            }
            cleanWsCalls << args
        }

        void dir(String path, Closure body) {
            dirCalls << path
            body?.call()
        }

        void deleteDir() {
            deleteDirCalls++
        }
    }
}
