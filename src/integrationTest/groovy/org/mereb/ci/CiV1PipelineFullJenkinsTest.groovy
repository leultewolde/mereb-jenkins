import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class CiV1PipelineFullJenkinsTest extends BasePipelineTest {

    List<String> stages
    List<Map> helmCalls
    List<Map> smokeCalls
    List<List<String>> withEnvValues
    Map envVars
    List<String> shCalls

    @BeforeEach
    void setup() {
        super.setUp()
        stages = []
        helmCalls = []
        smokeCalls = []
        withEnvValues = []
        shCalls = []
        envVars = [
            BRANCH_NAME: 'main',
            BUILD_NUMBER: '42',
            CHANGE_ID: '',
            HOME: '/home/jenkins'
        ]
        binding.setVariable('env', envVars)
        binding.setVariable('scm', [:])
        binding.setVariable('docker', [image: { String name -> [inside: { Closure body -> body() }] }])

        helper.registerAllowedMethod('node', [Closure]) { Closure body -> body() }
        helper.registerAllowedMethod('node', [String, Closure]) { String label, Closure body -> body() }
        helper.registerAllowedMethod('checkout', [Map]) { }
        helper.registerAllowedMethod('checkout', [Object]) { }
        helper.registerAllowedMethod('fileExists', [String]) { String path -> path == '.ci/ci.yml' || path.startsWith('.ci/values-dev') }
        helper.registerAllowedMethod('readYaml', [Map]) { Map args -> integrationConfig() }
        helper.registerAllowedMethod('pwd', []) { '/workspace' }
        helper.registerAllowedMethod('sh', [String]) { String script -> shCalls << script; respondToShell(script) }
        helper.registerAllowedMethod('sh', [Map]) { Map args -> String script = args.script ?: ''; shCalls << script; respondToShell(script) }
        helper.registerAllowedMethod('withEnv', [List, Closure]) { List envList, Closure body -> withEnvValues << envList.collect { it.toString() }; body() }
        helper.registerAllowedMethod('helmDeploy', [Map]) { Map args -> helmCalls << args }
        helper.registerAllowedMethod('runSmoke', [Map]) { Map args -> smokeCalls << args }
        helper.registerAllowedMethod('withCredentials', [List, Closure]) { List creds, Closure body -> body() }
        helper.registerAllowedMethod('usernamePassword', [Map]) { Map m -> m }
        helper.registerAllowedMethod('string', [Map]) { Map m -> m }
        helper.registerAllowedMethod('file', [Map]) { Map m -> m }
        helper.registerAllowedMethod('stage', [String, Closure]) { String name, Closure body -> stages << name; body() }
        helper.registerAllowedMethod('cleanWs', [Map]) { }
        helper.registerAllowedMethod('deleteDir', []) { }
        helper.registerAllowedMethod('dir', [String, Closure]) { String path, Closure body -> body() }
        helper.registerAllowedMethod('timeout', [Map, Closure]) { Map cfg, Closure body -> body() }
        helper.registerAllowedMethod('input', [Map]) { Map args -> }
    }

    @Test
    void "ciV1 runs docker build, terraform, deploy, and smoke"() {
        def script = loadScript('vars/ciV1.groovy')

        script.call(configPath: '.ci/ci.yml')

        assertTrue(stages.any { it.contains('Docker Build') })
        assertTrue(stages.contains('Deploy Dev'))
        assertTrue(stages.any { it.contains('Terraform DEV') })
        assertFalse(helmCalls.isEmpty())
        Map helmArgs = helmCalls.first()
        assertEquals('ghcr.io/mereb/app', helmArgs.release)
        assertEquals('main-abcdef123456', helmArgs.set['image.tag'])
        assertEquals('Dev', smokeCalls.first().environment)
        assertTrue(withEnvValues.flatten().any { it.startsWith('IMAGE_TAG=main-abcdef123456') })
    }

    private Map integrationConfig() {
        return [
            version: 1,
            build  : [stages: []],
            preset : 'node',
            image  : [
                enabled    : true,
                repository : 'ghcr.io/mereb/app',
                tagStrategy: 'branch-sha'
            ],
            deploy : [
                order       : ['dev'],
                dev         : [
                    displayName: 'Dev',
                    release    : 'ghcr.io/mereb/app',
                    namespace  : 'apps',
                    chart      : 'infra/charts/app',
                    when       : '!pr',
                    valuesFiles: [],
                    smoke      : [
                        url        : 'https://dev.api.local/ping',
                        environment: 'Dev smoke'
                    ]
                ]
            ],
            terraform: [
                environments: [
                    dev: [
                        displayName: 'DEV',
                        when       : '!pr'
                    ]
                ]
            ],
            release: [
                autoTag: [enabled: true]
            ]
        ]
    }

    private Object respondToShell(String script) {
        if (script.contains('git rev-parse HEAD')) {
            return 'abcdef1234567890abcdef1234567890abcdef12'
        }
        if (script.contains('git status --porcelain')) {
            return ''
        }
        if (script.contains('git describe --tags')) {
            return ''
        }
        if (script.contains('git fetch --tags')) {
            return ''
        }
        if (script.contains('git tag --list')) {
            return ''
        }
        if (script.contains('git rev-parse --quiet --verify')) {
            return ''
        }
        if (script.contains('command -v terraform')) {
            return 'yes'
        }
        if (script.contains('git remote get-url')) {
            return 'https://github.com/mereb/app.git'
        }
        if (script.contains('git config user.name') || script.contains('git config user.email')) {
            return ''
        }
        if (script.contains('git push')) {
            return ''
        }
        return ''
    }
}
