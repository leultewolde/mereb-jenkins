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
}
