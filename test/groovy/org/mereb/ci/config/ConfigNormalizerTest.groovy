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

    @Test
    void "normalizes release autoTag credentialId into credential map"() {
        Map raw = [
            version: 1,
            build  : [:],
            image  : [repository: 'ghcr.io/example/app'],
            deploy : [:],
            terraform: [:],
            release: [
                autoTag: [
                    enabled     : true,
                    credentialId: 'github-credentials',
                    usernameEnv : 'TAG_USER',
                    passwordEnv : 'TAG_PASS'
                ]
            ]
        ]

        Map cfg = ConfigNormalizer.normalize(raw, ['dev'], '.ci/ci.yml')

        Map autoTag = cfg.release.autoTag
        assertTrue(autoTag.enabled)
        assertEquals('github-credentials', autoTag.credential.id)
        assertEquals('TAG_USER', autoTag.credential.usernameEnv)
        assertEquals('TAG_PASS', autoTag.credential.passwordEnv)
    }

    @Test
    void "strips protocols from registry inputs"() {
        Map raw = [
            version: 1,
            build  : [:],
            image  : [
                repository: 'https://registry.leultewolde.com/apps/svc-feed',
                registry  : 'https://registry.leultewolde.com/'
            ],
            deploy : [:],
            terraform: [:],
            release: [:]
        ]

        Map cfg = ConfigNormalizer.normalize(raw, ['dev'], '.ci/ci.yml')

        assertEquals('registry.leultewolde.com/apps/svc-feed', cfg.image.repository)
        assertEquals('registry.leultewolde.com', cfg.image.registryHost)
    }

    @Test
    void "normalizes microfrontend environments with default order"() {
        Map raw = [
            version       : 1,
            build         : [:],
            image         : false,
            deploy        : [:],
            terraform     : [:],
            release       : [:],
            microfrontend : [
                name        : 'mfe-admin',
                distDir     : 'dist',
                manifestScript: 'scripts/update-manifest.js',
                checkScript : 'scripts/check-remote-entry.js',
                aws         : [
                    endpoint      : 'https://minio.example.com',
                    credential    : [id: 'minio-credentials']
                ],
                environments: [
                    dev: [
                        bucket    : 'cdn-dev',
                        publicBase: 'https://cdn-dev.example.com',
                        when      : 'branch=main & !pr'
                    ],
                    stg: [
                        bucket    : 'cdn-stg',
                        publicBase: 'https://cdn-stg.example.com',
                        approval  : [message: 'Approve staging publish?']
                    ]
                ]
            ]
        ]

        Map cfg = ConfigNormalizer.normalize(raw, ['dev', 'stg', 'prd'], '.ci/ci.yml')

        assertTrue(cfg.microfrontend.enabled)
        assertEquals(['dev', 'stg'], cfg.microfrontend.order)
        assertEquals('cdn-dev', cfg.microfrontend.environments.dev.bucket)
        assertEquals('https://cdn-stg.example.com', cfg.microfrontend.environments.stg.publicBase)
        assertEquals('mfe-admin', cfg.microfrontend.name)
    }
}
