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
        assertEquals('image', cfg.recipe)
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
        assertEquals('microfrontend', cfg.recipe)
        assertEquals(['dev', 'stg'], cfg.microfrontend.order)
        assertEquals('cdn-dev', cfg.microfrontend.environments.dev.bucket)
        assertEquals('https://cdn-stg.example.com', cfg.microfrontend.environments.stg.publicBase)
        assertEquals('mfe-admin', cfg.microfrontend.name)
    }

    @Test
    void "preserves explicit recipe override when config is compatible"() {
        Map raw = [
            version: 1,
            recipe : 'package',
            image  : false,
            build  : [:],
            release: [
                autoTag: [enabled: true]
            ]
        ]

        Map cfg = ConfigNormalizer.normalize(raw, ['dev'], '.ci/ci.yml')

        assertEquals('package', cfg.requestedRecipe)
        assertEquals('package', cfg.recipe)
    }

    @Test
    void "normalizes delivery mode configuration"() {
        Map raw = [
            version : 1,
            build   : [:],
            image   : [repository: 'ghcr.io/example/app'],
            deploy  : [:],
            release : [:],
            delivery: [
                mode      : 'staged',
                mainBranch: 'trunk',
                pr        : [deployToStg: true]
            ]
        ]

        Map cfg = ConfigNormalizer.normalize(raw, ['dev'], '.ci/ci.yml')

        assertEquals('staged', cfg.delivery.mode)
        assertEquals('trunk', cfg.delivery.mainBranch)
        assertTrue(cfg.delivery.pr.deployToStg)
    }

    @Test
    void "normalizes terraform plugin cache, lock, and verify resources"() {
        Map raw = [
            version  : 1,
            image    : false,
            terraform: [
                pluginCacheDir: '.ci/tf-cache',
                environments  : [
                    dev: [
                        prePlan: [
                            "terraform state rm 'module.platform_stack.kubernetes_manifest.kong_cors_plugin' || true"
                        ],
                        lock  : [
                            resource: 'infra-platform-dev'
                        ],
                        verify: [
                            timeout  : '120s',
                            resources: [
                                [kind: 'deployment', name: 'apollo-router', namespace: 'router-dev', wait: 'available'],
                                [kind: 'pod', selector: 'app=api', namespace: 'apps-dev', wait: 'ready', optional: true]
                            ]
                        ]
                    ]
                ]
            ]
        ]

        Map cfg = ConfigNormalizer.normalize(raw, ['dev'], '.ci/ci.yml')

        assertEquals('.ci/tf-cache', cfg.terraform.pluginCacheDir)
        assertEquals("terraform state rm 'module.platform_stack.kubernetes_manifest.kong_cors_plugin' || true", cfg.terraform.environments.dev.prePlan[0])
        assertEquals('infra-platform-dev', cfg.terraform.environments.dev.lock.resource)
        assertEquals('120s', cfg.terraform.environments.dev.verify.timeout)
        assertEquals('deployment', cfg.terraform.environments.dev.verify.resources[0].kind)
        assertEquals('apollo-router', cfg.terraform.environments.dev.verify.resources[0].name)
        assertEquals('app=api', cfg.terraform.environments.dev.verify.resources[1].selector)
        assertTrue(cfg.terraform.environments.dev.verify.resources[1].optional)
    }
}
