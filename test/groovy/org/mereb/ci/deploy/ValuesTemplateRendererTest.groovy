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

    @Test
    void "uses env-level vault defaults when template vars specify vault path"() {
        FakeSteps steps = new FakeSteps(
                existing: ['templates/values-secret.yaml.tpl'] as Set,
                files: ['templates/values-secret.yaml.tpl': 'db: {{ DATABASE_URL }}']
        )
        ValuesTemplateRenderer renderer = new ValuesTemplateRenderer(steps)
        renderer.metaClass.fetchVaultValue = { String placeholder, Map cfg ->
            return "${cfg.url}:${cfg.path}:${cfg.field}"
        }

        List<String> outputs = renderer.render('dev', [
                vault: [url: 'https://vault.leultewolde.com', tokenEnv: 'VAULT_TOKEN'],
                valuesTemplates: [[
                        template: 'templates/values-secret.yaml.tpl',
                        output  : 'out/secret.yaml',
                        vars    : [
                                DATABASE_URL: [vaultPath: 'kv/data/apps/dev', vaultField: 'DATABASE_URL']
                        ]
                ]]
        ])

        assertEquals(['out/secret.yaml'], outputs)
        assertEquals('db: https://vault.leultewolde.com:kv/data/apps/dev:DATABASE_URL', steps.written['out/secret.yaml'])
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
                return 'token\n'
            }
            shScripts << (args.script ?: '').toString()
            return ''
        }
    }
}
