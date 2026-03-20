import org.junit.jupiter.api.Test
import org.mereb.ci.credentials.CredentialHelper
import org.mereb.ci.terraform.TerraformPipeline
import org.mereb.ci.util.StageExecutor

import static org.junit.jupiter.api.Assertions.*

class TerraformPipelineTest {

    @Test
    void "runs verify stage before smoke and configures plugin cache"() {
        FakeSteps steps = new FakeSteps()
        FakeCredentialHelper credentialHelper = new FakeCredentialHelper(steps)
        TerraformPipeline pipeline = new TerraformPipeline(steps, credentialHelper, new StageExecutor(steps, credentialHelper), null)

        Map cfg = [
            enabled       : true,
            path          : 'terraform',
            pluginCacheDir: '.ci/tf-cache',
            environments  : [
                dev: [
                    displayName: 'DEV',
                    when       : '!pr',
                    path       : 'envs/dev',
                    planOut    : 'tfplan-dev',
                    verify     : [
                        timeout  : '90s',
                        resources: [
                            [kind: 'deployment', name: 'apollo-router', namespace: 'router-dev', wait: 'available']
                        ]
                    ],
                    smoke      : [script: 'echo smoke']
                ]
            ]
        ]

        pipeline.run(cfg)

        assertEquals(['Terraform DEV', 'Verify DEV', 'Smoke DEV'], steps.stageNames)
        assertTrue(steps.lockCalls.isEmpty())
        assertTrue(steps.envs.flatten().any { it == 'TF_PLUGIN_CACHE_DIR=/workspace/.ci/tf-cache' })
        assertTrue(steps.shCalls.any { it.contains("mkdir -p '/workspace/.ci/tf-cache'") })
        assertTrue(steps.shCalls.any { it.contains("'terraform' plan") })
        assertTrue(steps.shCalls.any { it.contains('kubectl') && it.contains('wait') && it.contains('Available') })
        assertEquals(['DEV'], steps.smokeCalls*.environment)
    }

    @Test
    void "fails verify when selector target is missing"() {
        FakeSteps steps = new FakeSteps()
        steps.selectorOutput = ''
        FakeCredentialHelper credentialHelper = new FakeCredentialHelper(steps)
        TerraformPipeline pipeline = new TerraformPipeline(steps, credentialHelper, new StageExecutor(steps, credentialHelper), null)

        Map cfg = [
            enabled     : true,
            path        : 'terraform',
            environments: [
                dev: [
                    displayName: 'DEV',
                    when       : '!pr',
                    verify     : [
                        resources: [
                            [kind: 'pod', selector: 'app=test', namespace: 'apps-dev', wait: 'exists']
                        ]
                    ]
                ]
            ]
        ]

        RuntimeException ex = assertThrows(RuntimeException) {
            pipeline.run(cfg)
        }

        assertTrue(ex.message.contains('expected resource missing'))
    }

    @Test
    void "runs pre-plan hooks after init and workspace selection"() {
        FakeSteps steps = new FakeSteps()
        FakeCredentialHelper credentialHelper = new FakeCredentialHelper(steps)
        TerraformPipeline pipeline = new TerraformPipeline(steps, credentialHelper, new StageExecutor(steps, credentialHelper), null)

        Map cfg = [
            enabled     : true,
            path        : 'terraform',
            environments: [
                dev: [
                    displayName: 'DEV',
                    when       : '!pr',
                    workspace  : 'dev',
                    prePlan    : [
                        "terraform state rm 'old.addr' || true",
                        "terraform state rm 'older.addr' || true"
                    ]
                ]
            ]
        ]

        pipeline.run(cfg)

        int initIndex = steps.shCalls.findIndexOf { it.contains("'terraform' init") }
        int workspaceIndex = steps.shCalls.findIndexOf { it.contains("workspace select 'dev' || 'terraform' workspace new 'dev'") }
        int firstHookIndex = steps.shCalls.findIndexOf { it == "terraform state rm 'old.addr' || true" }
        int secondHookIndex = steps.shCalls.findIndexOf { it == "terraform state rm 'older.addr' || true" }
        int planIndex = steps.shCalls.findIndexOf { it.contains("'terraform' plan") }

        assertTrue(initIndex >= 0)
        assertTrue(workspaceIndex > initIndex)
        assertTrue(firstHookIndex > workspaceIndex)
        assertTrue(secondHookIndex > firstHookIndex)
        assertTrue(planIndex > secondHookIndex)
    }

    @Test
    void "wraps terraform verify and smoke in the configured Jenkins lock"() {
        FakeSteps steps = new FakeSteps()
        FakeCredentialHelper credentialHelper = new FakeCredentialHelper(steps)
        TerraformPipeline pipeline = new TerraformPipeline(steps, credentialHelper, new StageExecutor(steps, credentialHelper), null)

        Map cfg = [
            enabled     : true,
            path        : 'terraform',
            environments: [
                stg: [
                    displayName: 'STG',
                    when       : '!pr',
                    lock       : [resource: 'infra-platform-stg'],
                    verify     : [
                        resources: [
                            [kind: 'deployment', name: 'apollo-router', namespace: 'router-stg', wait: 'available']
                        ]
                    ],
                    smoke      : [script: 'echo smoke']
                ]
            ]
        ]

        List<String> callbackEvents = []
        List<Integer> callbackLockDepths = []
        pipeline.run(cfg, null, false) { String envName ->
            callbackEvents << envName
            callbackLockDepths << steps.activeLocks
        }

        assertEquals(['infra-platform-stg'], steps.lockCalls)
        assertEquals(['Terraform STG', 'Verify STG', 'Smoke STG'], steps.stageNames)
        assertEquals(['stg'], callbackEvents)
        assertEquals(1, steps.stageLockDepths['Terraform STG'])
        assertEquals(1, steps.stageLockDepths['Verify STG'])
        assertEquals(1, steps.stageLockDepths['Smoke STG'])
        assertEquals([0], callbackLockDepths)
    }

    private static class FakeSteps {
        Map env = [BRANCH_NAME: 'main', CHANGE_ID: '', TAG_NAME: '', HOME: '/home/jenkins']
        List<String> stageNames = []
        List<List<String>> envs = []
        List<String> shCalls = []
        List<Map> smokeCalls = []
        List<String> lockCalls = []
        Map<String, Integer> stageLockDepths = [:]
        int activeLocks = 0
        String selectorOutput = 'deployment/apollo-router'

        void stage(String name, Closure body) {
            stageNames << name
            stageLockDepths[name] = activeLocks
            body()
        }

        void lock(Map args, Closure body) {
            String resource = (args.resource ?: '').toString()
            lockCalls << resource
            activeLocks++
            body()
            activeLocks--
        }

        void withEnv(List<String> envList, Closure body) {
            envs << envList.collect { it.toString() }
            body()
        }

        void withCredentials(List creds, Closure body) {
            body()
        }

        void dir(String path, Closure body) {
            body()
        }

        String pwd() {
            return '/workspace'
        }

        Object sh(String script) {
            shCalls << script
            ''
        }

        Object sh(Map args) {
            String script = (args.script ?: '').toString()
            shCalls << script
            if (args.returnStdout) {
                if (script.contains('command -v terraform')) {
                    return 'yes'
                }
                if (script.contains("get 'pod'") && script.contains("-l 'app=test'")) {
                    return selectorOutput
                }
                return 'resource/found'
            }
            return ''
        }

        boolean fileExists(String path) {
            false
        }

        void runSmoke(Map args) {
            smokeCalls << args
        }

        void echo(String message) {
            // no-op
        }

        def usernamePassword(Map args) { args }
        def file(Map args) { args }
        def string(Map args) { args }

        void error(String message) {
            throw new RuntimeException(message)
        }
    }

    private static class FakeCredentialHelper extends CredentialHelper {
        private final def steps

        FakeCredentialHelper(def steps) {
            super(steps)
            this.steps = steps
        }

        @Override
        void withOptionalCredentials(List<Map> bindings, Closure body) {
            body()
        }
    }
}
