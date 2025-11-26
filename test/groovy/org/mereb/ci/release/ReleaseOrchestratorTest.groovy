import org.junit.jupiter.api.Test
import org.mereb.ci.release.ReleaseOrchestrator

import static org.junit.jupiter.api.Assertions.*

class ReleaseOrchestratorTest {

    @Test
    void "runs deferred terraform once release tag exists"() {
        FakeSteps steps = new FakeSteps()
        FakeTerraformPipeline terraform = new FakeTerraformPipeline()
        int handleCalled = 0
        int stagesCalled = 0
        int publishCalled = 0
        int microCalled = 0

        ReleaseOrchestrator orchestrator = new ReleaseOrchestrator(
            steps,
            terraform,
            { releaseCfg, state ->
                handleCalled++
                steps.env.RELEASE_TAG = 'v1.0.0'
            },
            { cfg, state -> microCalled++ },
            { stages -> stagesCalled++ },
            { releaseCfg, state -> publishCalled++ }
        )

        Map cfg = [
            terraform: [:],
            release  : [autoTag: [enabled: true]],
            releaseStages: []
        ]

        orchestrator.execute(cfg, [:]) { Closure afterEnv ->
            afterEnv?.call('dev')
        }

        assertEquals(2, terraform.calls.size())
        assertEquals([null, ['prd']], terraform.calls*.order)
        assertEquals('v1.0.0', steps.env.TAG_NAME)
        assertEquals(1, handleCalled)
        assertEquals(1, microCalled)
        assertEquals(1, stagesCalled)
        assertEquals(1, publishCalled)
    }

    @Test
    void "provides after-environment callback when auto-tag gated"() {
        FakeSteps steps = new FakeSteps()
        FakeTerraformPipeline terraform = new FakeTerraformPipeline()
        int handleCalled = 0

        ReleaseOrchestrator orchestrator = new ReleaseOrchestrator(
            steps,
            terraform,
            { releaseCfg, state -> handleCalled++ },
            { cfg, state -> },
            { stages -> },
            { releaseCfg, state -> }
        )

        Map cfg = [
            terraform: [:],
            release  : [autoTag: [enabled: true, afterEnvironment: 'stg']],
            releaseStages: []
        ]

        Closure recorded = null
        orchestrator.execute(cfg, [:]) { Closure afterEnv ->
            recorded = afterEnv
        }

        assertNotNull(recorded)
        assertEquals(0, handleCalled)
        recorded.call('stg')
        assertEquals(1, handleCalled)
    }

    private static class FakeSteps {
        Map env = [:]
        List<String> echoes = []

        void echo(String msg) {
            echoes << msg
        }
    }

    private static class FakeTerraformPipeline {
        List<Map> calls = []
        List<String> deferred = ['prd']

        List<String> run(Map cfg, List<String> overrideOrder, boolean capture) {
            calls << [order: overrideOrder, capture: capture]
            return capture ? deferred : []
        }
    }
}
