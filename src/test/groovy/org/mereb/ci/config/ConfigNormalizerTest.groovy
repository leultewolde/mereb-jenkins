import org.junit.jupiter.api.Test
import org.mereb.ci.config.ConfigNormalizer

import static org.junit.jupiter.api.Assertions.*

class ConfigNormalizerTest {

    @Test
    void "normalizes pnpm preset into concrete stages"() {
        Map raw = [
            version: 1,
            preset : 'pnpm',
            build  : [:],
            pnpm   : [packageDir: 'apps/web'],
            image  : [repository: 'ghcr.io/mereb/web'],
            deploy : [:],
            terraform: [:],
            release: [:]
        ]

        Map cfg = ConfigNormalizer.normalize(raw, ['dev', 'stg', 'prd'], '.ci/ci.yml')

        assertFalse(cfg.requiresGradleHome)
        assertEquals('pnpm', cfg.preset.toLowerCase())
        assertTrue(cfg.buildStages*.name.contains('Install dependencies'))
        assertEquals('apps/web', cfg.buildStages.find { it.name == 'Install dependencies' }?.env?.PNPM_PACKAGE_DIR)
    }

    @Test
    void "detects gradle stages for needsGradleHome"() {
        List<Map> stages = [
            [name: 'Unit Tests', verb: 'gradle.test']
        ]
        assertTrue(ConfigNormalizer.needsGradleHome('java-gradle', []))
        assertTrue(ConfigNormalizer.needsGradleHome('node', stages))
        assertFalse(ConfigNormalizer.needsGradleHome('node', [[name: 'Lint', verb: 'node.lint']]))
    }

    @Test
    void "normalizes helm repo credentials"() {
        Map raw = [
            version: 1,
            image  : false,
            deploy : [
                dev: [
                    chart            : 'infra/charts/app',
                    repoCredentialId : 'helm-creds'
                ]
            ]
        ]

        Map cfg = ConfigNormalizer.normalize(raw, ['dev'], '.ci/ci.yml')

        Map repoCreds = cfg.deploy.environments.dev.repoCredentials
        assertEquals('helm-creds', repoCreds.id)
        assertEquals('HELM_REPO_USERNAME', repoCreds.usernameEnv)
        assertEquals('HELM_REPO_PASSWORD', repoCreds.passwordEnv)
    }
}
