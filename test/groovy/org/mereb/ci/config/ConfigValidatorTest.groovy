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

        Map stagedDeploy = [
            version : 1,
            image   : [repository: 'ghcr.io/mereb/api'],
            delivery: [mode: 'staged'],
            deploy  : [
                dev: [when: 'branch=main', autoPromote: true, approval: [message: 'Ship?']]
            ]
        ]
        def stagedDeployResult = ConfigValidator.validate(stagedDeploy)
        assertFalse(stagedDeployResult.hasErrors())
        assertTrue(stagedDeployResult.warnings.any { it.contains('deploy.dev.when') })
        assertTrue(stagedDeployResult.warnings.any { it.contains('deploy.dev.autoPromote') })
        assertTrue(stagedDeployResult.warnings.any { it.contains('deploy.dev.approval') })

        Map stagedMicrofrontend = [
            version      : 1,
            image        : false,
            delivery     : [mode: 'staged'],
            microfrontend: [
                environments: [
                    stg: [when: 'branch=main', approval: [message: 'Publish?']]
                ]
            ]
        ]
        def stagedMicrofrontendResult = ConfigValidator.validate(stagedMicrofrontend)
        assertFalse(stagedMicrofrontendResult.hasErrors())
        assertTrue(stagedMicrofrontendResult.warnings.any { it.contains('microfrontend.environments.stg.when') })
        assertTrue(stagedMicrofrontendResult.warnings.any { it.contains('microfrontend.environments.stg.approval') })
    }

    @Test
    void "accepts compatible explicit recipe override"() {
        Map raw = [
            version: 1,
            recipe : 'service',
            image  : [repository: 'ghcr.io/mereb/api'],
            deploy : [
                dev: [chart: 'infra/charts/api']
            ]
        ]

        def result = ConfigValidator.validate(raw)

        assertFalse(result.hasErrors())
    }

    @Test
    void "rejects incompatible explicit recipe override"() {
        Map raw = [
            version: 1,
            recipe : 'build',
            image  : [repository: 'ghcr.io/mereb/api']
        ]

        def result = ConfigValidator.validate(raw)

        assertTrue(result.hasErrors())
        assertTrue(result.errors.any { it.contains('recipe=build cannot enable image orchestration') })
    }

    @Test
    void "rejects unsupported mixed recipe shapes when no explicit recipe is set"() {
        Map raw = [
            version  : 1,
            image    : [repository: 'ghcr.io/mereb/api'],
            deploy   : [
                dev: [chart: 'infra/charts/api']
            ],
            terraform: [
                environments: [
                    dev: [displayName: 'DEV']
                ]
            ]
        ]

        def result = ConfigValidator.validate(raw)

        assertTrue(result.hasErrors())
        assertTrue(result.errors.any { it.contains('terraform environments cannot be combined') })
    }
}
