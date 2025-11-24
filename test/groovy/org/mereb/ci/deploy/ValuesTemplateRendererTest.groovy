import org.junit.jupiter.api.Test
import org.mereb.ci.deploy.ValuesTemplateRenderer

import static org.junit.jupiter.api.Assertions.*

class ValuesTemplateRendererTest {

    @Test
    void "renders template and creates parent directory"() {
        FakeSteps steps = new FakeSteps(
                existing: ['templates/base.yaml'] as Set,
                files: ['templates/base.yaml': 'foo: {{ value }}']
        )

        ValuesTemplateRenderer renderer = new ValuesTemplateRenderer(steps)
        List<String> outputs = renderer.render('dev', [valuesTemplates: [[template: 'templates/base.yaml', output: 'out/dev.yaml', vars: [value: '123']]]])

        assertEquals(['out/dev.yaml'], outputs)
        assertEquals(["mkdir -p 'out'"], steps.shScripts)
        assertEquals('foo: 123', steps.written['out/dev.yaml'])
    }

    @Test
    void "skips empty template list"() {
        FakeSteps steps = new FakeSteps()
        ValuesTemplateRenderer renderer = new ValuesTemplateRenderer(steps)

        assertTrue(renderer.render('dev', [:]).isEmpty())
    }

    private static class FakeSteps {
        final Set<String> existing
        final Map<String, String> files
        final Map<String, String> written = [:]
        final List<String> shScripts = []

        FakeSteps(Map args = [:]) {
            this.existing = (args.existing ?: [] as Set) as Set
            this.files = args.files ?: [:]
        }

        boolean fileExists(String path) {
            existing.contains(path)
        }

        String readFile(String path) {
            files[path] ?: ''
        }

        void writeFile(Map args) {
            written[args.file] = args.text
        }

        void error(String msg) {
            throw new RuntimeException(msg)
        }

        String sh(Map args) {
            if (args.returnStdout) {
                return ''
            }
            shScripts << (args.script ?: '').toString()
            return ''
        }
    }
}
