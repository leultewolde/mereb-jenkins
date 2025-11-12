import org.junit.jupiter.api.Test
import org.mereb.ci.config.ConfigValidator

import static org.junit.jupiter.api.Assertions.*

class ConfigValidatorTest {

    @Test
    void "flags missing image repository"() {
        Map raw = [
            version: 1,
            image  : [enabled: true],
            deploy : [:]
        ]
        def result = ConfigValidator.validate(raw)
        assertTrue(result.hasErrors())
        assertTrue(result.errors.any { it.contains('image.repository') })
    }

    @Test
    void "warns when no build stages configured"() {
        Map raw = [
            version: 1,
            image  : [repository: 'ghcr.io/mereb/api'],
            deploy : [:]
        ]
        def result = ConfigValidator.validate(raw)
        assertFalse(result.hasErrors())
        assertTrue(result.hasWarnings())
    }
}
