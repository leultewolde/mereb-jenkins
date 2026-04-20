import org.junit.jupiter.api.Test
import org.mereb.ci.credentials.CredentialHelper
import org.mereb.ci.deploy.DeployPipeline

import static org.junit.jupiter.api.Assertions.*

class DeployPipelineTest {

    @Test
    void "uses checked-in values files and binds optional credentials for deploy and smoke"() {
        FakeSteps steps = new FakeSteps()
        steps.files['.ci/values-dev.yaml'] = 'image: {}'
        CredentialHelper credentialHelper = new CredentialHelper(steps)
        DeployPipeline pipeline = new DeployPipeline(steps, credentialHelper)

        Map envCfg = [
                displayName   : 'DEV',
                namespace     : 'apps-dev',
                release       : 'svc-feed-dev',
                chart         : 'app-chart',
                repo          : 'https://example.com/chart',
                rolloutTimeout: '5m',
                when          : '',
                smoke         : [url: 'https://api-dev.mereb.app/healthz'],
                credentials   : [[type: 'string', id: 'api-token', env: 'API_TOKEN']]
        ]

        pipeline.run([
                image : [enabled: true],
                deploy: [order: ['dev'], environments: [dev: envCfg]]
        ], [repository: 'registry.leultewolde.com/mereb/svc-feed', imageTag: 'main-abc123'])

        assertTrue(steps.withCredentialsBindings.any { bindings ->
            bindings.any { (it.variable ?: it.tokenVariable) == 'API_TOKEN' && it.credentialsId == 'api-token' }
        }, 'Expected explicit deploy credentials to be bound')
        assertEquals(['.ci/values-dev.yaml'], steps.helmDeployCalls[0].valuesFiles)
        assertEquals(1, steps.runSmokeCalls.size())
        assertEquals('secret-api-token', steps.runSmokeCalls[0].env.API_TOKEN.toString())
        assertTrue(steps.shScripts.any { it.contains("kubectl -n 'apps-dev' get deployment -l 'app.kubernetes.io/instance=svc-feed-dev' -o name") })
        assertTrue(steps.shScripts.any { it.contains("kubectl -n 'apps-dev' rollout restart 'deployment/svc-feed-dev'") })
        assertTrue(steps.shScripts.any { it.contains("kubectl -n 'apps-dev' rollout status 'deployment/svc-feed-dev' --timeout='5m'") })
        assertTrue(steps.shScripts.any { it.contains("kubectl -n 'apps-dev' get pods -l 'app.kubernetes.io/instance=svc-feed-dev'") })
    }

    @Test
    void "creates registry pull secret before helm deployment"() {
        FakeSteps steps = new FakeSteps()
        CredentialHelper credentialHelper = new CredentialHelper(steps)
        DeployPipeline pipeline = new DeployPipeline(steps, credentialHelper)

        Map envCfg = [
                displayName   : 'DEV',
                namespace     : 'apps-dev',
                release       : 'svc-feed-dev',
                chart         : 'app-chart',
                repo          : 'https://example.com/chart',
                rolloutTimeout: '5m',
                when          : ''
        ]

        pipeline.run([
                image : [
                        enabled     : true,
                        registryHost: 'registry.leultewolde.com',
                        push        : [
                                credentials: [
                                        id         : 'docker-registry-local',
                                        usernameEnv: 'DOCKER_USERNAME',
                                        passwordEnv: 'DOCKER_PASSWORD'
                                ]
                        ]
                ],
                deploy: [order: ['dev'], environments: [dev: envCfg]]
        ], [repository: 'registry.leultewolde.com/mereb/svc-feed', imageTag: 'main-abc123'])

        assertTrue(steps.withCredentialsBindings.any { bindings ->
            bindings.any { it.credentialsId == 'docker-registry-local' && it.usernameVariable == 'DOCKER_USERNAME' && it.passwordVariable == 'DOCKER_PASSWORD' }
        }, 'Expected docker registry credentials to be bound during deploy')
        assertTrue(steps.shScripts.any { it.contains("create secret docker-registry 'regcred'") })
        assertTrue(steps.shScripts.any { it.contains("--docker-server='registry.leultewolde.com'") })
        assertEquals(1, steps.helmDeployCalls.size())
    }

    @Test
    void "skips live artifact verification when all workloads are scaled to zero"() {
        FakeSteps steps = new FakeSteps()
        CredentialHelper credentialHelper = new CredentialHelper(steps)
        DeployPipeline pipeline = new DeployPipeline(steps, credentialHelper)

        Map envCfg = [
                displayName   : 'DEV_OUTBOX',
                namespace     : 'apps-dev',
                release       : 'svc-messaging-dev-outbox',
                chart         : 'app-chart',
                repo          : 'https://example.com/chart',
                rolloutTimeout: '5m',
                when          : ''
        ]

        pipeline.run([
                image : [enabled: true],
                deploy: [order: ['dev_outbox'], environments: [dev_outbox: envCfg]]
        ], [repository: 'registry.leultewolde.com/mereb/svc-messaging', imageTag: 'main-abc123', imageDigest: 'sha256:deadbeef'])

        assertTrue(steps.shScripts.any { it.contains("get deployment -l 'app.kubernetes.io/instance=svc-messaging-dev-outbox' -o name") })
        assertTrue(steps.shScripts.any { it.contains("get 'deployment/svc-messaging-dev-outbox' -o jsonpath='{.spec.replicas}'") })
        assertFalse(steps.shScripts.any { it.contains("get pods -l 'app.kubernetes.io/instance=svc-messaging-dev-outbox'") })
    }

    @Test
    void "passes generated values overlays to helm deployments"() {
        FakeSteps steps = new FakeSteps()
        steps.files['.ci/values-dev.yaml'] = 'image: {}'
        CredentialHelper credentialHelper = new CredentialHelper(steps)
        DeployPipeline pipeline = new DeployPipeline(steps, credentialHelper)

        Map envCfg = [
                displayName    : 'DEV_OUTBOX',
                namespace      : 'apps-dev',
                release        : 'svc-feed-dev-outbox',
                chart          : 'app-chart',
                repo           : 'https://example.com/chart',
                rolloutTimeout : '5m',
                restartWorkloads: false,
                when           : '',
                valuesFiles    : ['.ci/values-dev.yaml'],
                generatedValues: [
                        profile: 'outboxWorker',
                        overlay: [
                                deploymentStrategy: [
                                        type         : 'RollingUpdate',
                                        rollingUpdate: [maxSurge: 0, maxUnavailable: 1]
                                ]
                        ]
                ]
        ]

        pipeline.run([
                image : [enabled: true],
                deploy: [order: ['dev_outbox'], environments: [dev_outbox: envCfg]]
        ], [repository: 'registry.leultewolde.com/mereb/svc-feed', imageTag: 'main-abc123'])

        assertEquals(['.ci/values-dev.yaml', '.ci/.generated-values-dev_outbox.json'], steps.helmDeployCalls[0].valuesFiles)
        assertTrue(steps.writes.containsKey('.ci/.generated-values-dev_outbox.json'))
        assertTrue(steps.writes['.ci/.generated-values-dev_outbox.json'].contains('"outbox-relay"'))
    }

    @Test
    void "passes generated base values before checked in values files"() {
        FakeSteps steps = new FakeSteps()
        steps.files['.ci/values-dev.yaml'] = 'configMap:\n  data:\n    PORT: \"4002\"\n'
        CredentialHelper credentialHelper = new CredentialHelper(steps)
        DeployPipeline pipeline = new DeployPipeline(steps, credentialHelper)

        Map envCfg = [
                displayName        : 'DEV',
                namespace          : 'apps-dev',
                release            : 'svc-feed-dev',
                chart              : 'app-chart',
                repo               : 'https://example.com/chart',
                rolloutTimeout     : '5m',
                restartWorkloads   : false,
                when               : '',
                valuesFiles        : ['.ci/values-dev.yaml'],
                generatedBaseValues: [
                        profile: 'apiService',
                        inputs : [
                                serviceName   : 'svc-feed',
                                containerPort : 4002,
                                routePrefix   : '/feed',
                                configMapName : 'svc-feed-dev-config',
                                secretName    : 'svc-feed-dev-secrets',
                                tlsSecretName : 'feed-dev-tls',
                                secretTemplates: [
                                        SPLUNK_HEC_TOKEN: 'SPLUNK_HEC_TOKEN'
                                ],
                                platformSecretTemplates: [
                                        DATABASE_URL   : 'FEED_DATABASE_URL'
                                ],
                                extraEnv      : [
                                        [name: 'OIDC_ISSUER', fromPlatformIdentityConfigKey: 'OIDC_ISSUER']
                                ]
                        ]
                ]
        ]

        pipeline.run([
                image : [enabled: true],
                deploy: [order: ['dev'], environments: [dev: envCfg]]
        ], [repository: 'registry.leultewolde.com/mereb/svc-feed', imageTag: 'main-abc123'])

        assertEquals(['.ci/.generated-base-values-dev.json', '.ci/values-dev.yaml'], steps.helmDeployCalls[0].valuesFiles)
        assertTrue(steps.writes.containsKey('.ci/.generated-base-values-dev.json'))
        assertTrue(steps.writes['.ci/.generated-base-values-dev.json'].contains('"svc-feed-dev-secrets"'))
        assertTrue(steps.writes['.ci/.generated-base-values-dev.json'].contains('"api-dev.mereb.app"'))
    }

    @Test
    void "emits kubernetes diagnostics when rollout status fails"() {
        FakeSteps steps = new FakeSteps()
        steps.failScriptContains = "rollout status 'deployment/svc-feed-dev' --timeout='5m'"
        steps.failure = new RuntimeException("rollout failed")
        CredentialHelper credentialHelper = new CredentialHelper(steps)
        DeployPipeline pipeline = new DeployPipeline(steps, credentialHelper)

        Map envCfg = [
                displayName    : 'DEV',
                namespace      : 'apps-dev',
                release        : 'svc-feed-dev',
                chart          : 'https://example.com/chart',
                repo           : 'https://example.com/chart',
                rolloutTimeout : '5m',
                when           : ''
        ]

        RuntimeException ex = assertThrows(RuntimeException) {
            pipeline.run([
                    image : [enabled: true],
                    deploy: [order: ['dev'], environments: [dev: envCfg]]
            ], [repository: 'registry.leultewolde.com/mereb/svc-feed', imageTag: 'main-abc123'])
        }

        assertEquals('rollout failed', ex.message)
        assertTrue(steps.shScripts.any { it.contains("get deployment,statefulset -l 'app.kubernetes.io/instance=svc-feed-dev' -o wide") })
        assertTrue(steps.shScripts.any { it.contains("get pods -l 'app.kubernetes.io/instance=svc-feed-dev' -o wide") })
        assertTrue(steps.shScripts.any { it.contains("get events --sort-by=.lastTimestamp") })
        assertTrue(steps.shScripts.any { it.contains("describe 'deployment/svc-feed-dev'") })
        assertTrue(steps.shScripts.any { it.contains("describe pod 'svc-feed-dev-abc'") })
        assertTrue(steps.shScripts.any { it.contains("logs 'svc-feed-dev-abc' --all-containers=true --tail=200") })
    }

    @Test
    void "runs post deploy stages after smoke with deploy context env"() {
        FakeSteps steps = new FakeSteps()
        CredentialHelper credentialHelper = new CredentialHelper(steps)
        DeployPipeline pipeline = new DeployPipeline(steps, credentialHelper)

        Map envCfg = [
                name            : 'dev',
                displayName     : 'DEV',
                namespace       : 'apps-dev',
                release         : 'svc-profile-dev',
                chart           : 'app-chart',
                repo            : 'https://example.com/chart',
                rolloutTimeout  : '5m',
                restartWorkloads: false,
                when            : '',
                smoke           : [url: 'https://api-dev.mereb.app/profile/healthz'],
                postDeployStages: [[
                        name       : 'Publish GraphOS subgraph',
                        sh         : './scripts/graphos/publish-subgraph.sh',
                        env        : [GRAPHOS_VARIANT: 'mereb-supergraph@dev'],
                        credentials: [[type: 'string', id: 'graphos-rover-api-key', env: 'ROVER_APOLLO_KEY']]
                ]]
        ]

        pipeline.run([
                image : [enabled: true],
                deploy: [order: ['dev'], environments: [dev: envCfg]]
        ], [repository: 'registry.leultewolde.com/mereb/svc-profile', imageTag: 'main-abc123', imageRef: 'registry.leultewolde.com/mereb/svc-profile:main-abc123'])

        assertEquals(1, steps.runSmokeCalls.size())
        int smokeIndex = steps.stageCalls.indexOf('Smoke DEV')
        int postDeployIndex = steps.stageCalls.indexOf('Publish GraphOS subgraph')
        assertTrue(smokeIndex >= 0)
        assertTrue(postDeployIndex > smokeIndex)
        assertTrue(steps.withCredentialsBindings.any { bindings ->
            bindings.any { (it.variable ?: it.tokenVariable) == 'ROVER_APOLLO_KEY' && it.credentialsId == 'graphos-rover-api-key' }
        })
        assertTrue(steps.withEnvCalls.any { envMap ->
            envMap.DEPLOY_ENV == 'dev' &&
                envMap.DEPLOY_NAMESPACE == 'apps-dev' &&
                envMap.DEPLOY_RELEASE == 'svc-profile-dev' &&
                envMap.GRAPHOS_VARIANT == 'mereb-supergraph@dev'
        })
        assertTrue(steps.shScripts.contains('./scripts/graphos/publish-subgraph.sh'))
    }

    private static class FakeSteps {
        final Map env = [BRANCH_NAME: 'main', CHANGE_ID: '']
        final Map<String, String> files = [:]
        final Map<String, String> writes = [:]
        final List<List<Map>> withCredentialsBindings = []
        final List<String> shScripts = []
        final List<Map> helmDeployCalls = []
        final List<Map> runSmokeCalls = []
        final List<Map<String, String>> withEnvCalls = []
        final List<String> stageCalls = []
        String failScriptContains
        RuntimeException failure

        void echo(String msg) {}

        boolean fileExists(String path) {
            return files.containsKey(path) || writes.containsKey(path)
        }

        String readFile(String path) {
            return files[path] ?: ''
        }

        void writeFile(Map args) {
            writes[args.file] = args.text
        }

        void stage(String name, Closure body) {
            stageCalls << name
            body?.call()
        }

        void helmDeploy(Map args) {
            helmDeployCalls << new LinkedHashMap(args)
        }

        void runSmoke(Map args) {
            runSmokeCalls << [
                    args: new LinkedHashMap(args),
                    env : new LinkedHashMap(env)
            ]
        }

        Map usernamePassword(Map args) {
            return new LinkedHashMap(args)
        }

        void withCredentials(List bindings, Closure body) {
            withCredentialsBindings << bindings
            bindings.each { Map binding ->
                String varName = binding.variable ?: binding.usernameVariable ?: binding.passwordVariable ?: binding.tokenVariable
                if (varName) {
                    env[varName] = "secret-${binding.credentialsId ?: binding.id}"
                }
            }
            body?.call()
        }

        void withEnv(List<String> vars, Closure body) {
            Map<String, String> snapshot = [:]
            vars.each { String entry ->
                if (!entry) return
                def parts = entry.split('=', 2)
                if (parts.length == 2) {
                    String key = parts[0]
                    snapshot[key] = env[key]
                    env[key] = parts[1]
                }
            }
            withEnvCalls << vars.collectEntries { it.contains('=') ? [(it.split('=',2)[0]): it.split('=',2)[1]] : [:] }
            try {
                body?.call()
            } finally {
                snapshot.each { k, v ->
                    if (v == null) {
                        env.remove(k)
                    } else {
                        env[k] = v
                    }
                }
            }
        }

        String sh(def args) {
            if (args instanceof Map) {
                String script = (args.script ?: '').toString()
                shScripts << script
                if (args.returnStdout) {
                    def matcher = script =~ /printenv\s+([A-Za-z0-9_]+)/
                    if (matcher.find()) {
                        String key = matcher[0][1]
                        return (env[key] ?: '') + '\n'
                    }
                    if (script.contains("get deployment -l 'app.kubernetes.io/instance=svc-feed-dev' -o name")) {
                        return "deployment/svc-feed-dev\n"
                    }
                    if (script.contains("get deployment -l 'app.kubernetes.io/instance=svc-messaging-dev-outbox' -o name")) {
                        return "deployment/svc-messaging-dev-outbox\n"
                    }
                    if (script.contains("get statefulset -l 'app.kubernetes.io/instance=svc-feed-dev' -o name")) {
                        return ''
                    }
                    if (script.contains("get statefulset -l 'app.kubernetes.io/instance=svc-messaging-dev-outbox' -o name")) {
                        return ''
                    }
                    if (script.contains("get pods -l 'app.kubernetes.io/instance=svc-feed-dev'") && script.contains(".metadata.name")) {
                        return "svc-feed-dev-abc\n"
                    }
                    if (script.contains("get pods -l 'app.kubernetes.io/instance=svc-feed-dev' -o jsonpath")) {
                        return "registry.leultewolde.com/mereb/svc-feed:main-abc123\n"
                    }
                    if (script.contains("get 'deployment/svc-messaging-dev-outbox' -o jsonpath='{.spec.replicas}'")) {
                        return "0\n"
                    }
                    return ''
                }
                return ''
            }
            String script = args.toString()
            shScripts << script
            if (failScriptContains && script.contains(failScriptContains)) {
                throw (failure ?: new RuntimeException("script failed"))
            }
            return ''
        }

        void error(String msg) {
            throw new RuntimeException(msg)
        }
    }
}
