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

    @Test
    void "rejects invalid delivery mode and warns about staged overrides"() {
        Map invalid = [
            version : 1,
            image   : [repository: 'ghcr.io/mereb/api'],
            delivery: [mode: 'unknown'],
            deploy  : [:]
        ]
        def invalidResult = ConfigValidator.validate(invalid)
        assertTrue(invalidResult.hasErrors())
        assertTrue(invalidResult.errors.any { it.contains('delivery.mode') })

        Map staged = [
            version : 1,
            image   : [repository: 'ghcr.io/mereb/api'],
            delivery: [mode: 'staged'],
            deploy  : [
                dev: [when: 'branch=main', autoPromote: true, approval: [message: 'Ship?']]
            ],
            microfrontend: [
                environments: [
                    stg: [when: 'branch=main', approval: [message: 'Publish?']]
                ]
            ]
        ]
        def stagedResult = ConfigValidator.validate(staged)
        assertFalse(stagedResult.hasErrors())
        assertTrue(stagedResult.warnings.any { it.contains('deploy.dev.when') })
        assertTrue(stagedResult.warnings.any { it.contains('deploy.dev.autoPromote') })
        assertTrue(stagedResult.warnings.any { it.contains('deploy.dev.approval') })
        assertTrue(stagedResult.warnings.any { it.contains('microfrontend.environments.stg.when') })
        assertTrue(stagedResult.warnings.any { it.contains('microfrontend.environments.stg.approval') })
    }
}
