import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test
import org.mereb.ci.deploy.GeneratedValuesRenderer

import static org.junit.jupiter.api.Assertions.*

class GeneratedValuesRendererTest {

    @Test
    void "renders the outbox profile with overlay merged on top"() {
        FakeSteps steps = new FakeSteps()
        GeneratedValuesRenderer renderer = new GeneratedValuesRenderer(steps)

        String path = renderer.render('prd_outbox', [
            profile: 'outboxWorker',
            overlay: [
                deploymentStrategy: [
                    type         : 'RollingUpdate',
                    rollingUpdate: [maxSurge: 0, maxUnavailable: 1]
                ],
                resources         : [
                    requests: [cpu: '50m']
                ]
            ]
        ])

        assertEquals('.ci/.generated-values-prd_outbox.json', path)
        Map parsed = new JsonSlurper().parseText(steps.writes[path]) as Map
        assertEquals('outbox-relay', parsed.podLabels['mereb.dev/workload-role'])
        assertEquals(['node', 'dist/outboxRelayWorker.js'], parsed.image.args)
        assertEquals(false, parsed.service.enabled)
        assertEquals([], parsed.service.ports)
        assertEquals('RollingUpdate', parsed.deploymentStrategy.type)
        assertEquals(0, parsed.deploymentStrategy.rollingUpdate.maxSurge)
        assertEquals('50m', parsed.resources.requests.cpu)
    }

    @Test
    void "rejects unsupported generated values profiles"() {
        FakeSteps steps = new FakeSteps()
        GeneratedValuesRenderer renderer = new GeneratedValuesRenderer(steps)

        IllegalArgumentException ex = assertThrows(IllegalArgumentException) {
            renderer.render('dev', [profile: 'unknown'])
        }

        assertTrue(ex.message.contains('Unsupported generatedValues profile'))
    }

    private static class FakeSteps {
        final Map<String, String> writes = [:]

        void writeFile(Map args) {
            writes[args.file] = args.text
        }
    }
}
