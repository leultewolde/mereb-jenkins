import org.junit.jupiter.api.Test
import org.mereb.ci.build.PnpmPreset

import static org.junit.jupiter.api.Assertions.*

class PnpmPresetTest {

    @Test
    void "creates tasks with filter flags"() {
        Map cfg = [
            packageDir : 'apps/mobile',
            packageName: 'mobile',
            tasks      : [[type: 'test']]
        ]

        List<Map> stages = PnpmPreset.buildStages(cfg)

        assertEquals(4, stages.size())
        Map taskStage = stages.last()
        assertEquals('Test', taskStage.name)
        assertEquals('true', taskStage.env.PNPM_USE_FILTER)
        assertEquals('mobile', taskStage.env.PNPM_PACKAGE_NAME)
        assertTrue(taskStage.sh.contains('pnpm'))
    }

    @Test
    void "skips stage generation when config missing"() {
        List<Map> stages = PnpmPreset.buildStages(null)
        assertTrue(stages.isEmpty())
    }

    @Test
    void "install stage falls back to non frozen install when lockfile missing"() {
        List<Map> stages = PnpmPreset.buildStages([:])
        Map installStage = stages.find { it.name == 'Install dependencies' }

        assertNotNull(installStage, 'expected install stage to exist')
        String script = installStage.sh
        assertTrue(script.contains('LOCK_EXISTS="true"'))
        assertTrue(script.contains('Falling back to --no-frozen-lockfile because the lockfile is missing.'))
    }
}
