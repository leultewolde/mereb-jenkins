import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class CiV1PipelineJenkinsTest extends BasePipelineTest {

    List<String> stages = []
    List<List<String>> withEnvCalls = []
    Map envVars = [:]
    Map currentBuild = [result: null]

    @BeforeEach
    void setup() {
        super.setUp()
        envVars = [
            BRANCH_NAME: 'main',
            BUILD_NUMBER: '42',
            CHANGE_ID: '',
            HOME: ''
        ]
        binding.setVariable('env', envVars)
        binding.setVariable('scm', [:])
        binding.setVariable('currentBuild', currentBuild)

        helper.registerAllowedMethod('node', [Closure]) { Closure body -> body() }
        helper.registerAllowedMethod('node', [String, Closure]) { String label, Closure body -> body() }
        helper.registerAllowedMethod('checkout', [Map]) { }
        helper.registerAllowedMethod('checkout', [Object]) { }
        helper.registerAllowedMethod('fileExists', [String]) { String path -> path == '.ci/ci.yml' }
        helper.registerAllowedMethod('readYaml', [Map]) { Map args ->
            [
                version: 1,
                preset : 'node',
                build  : [stages: []],
                image  : false,
                deploy : [:],
                terraform: [:],
                release: [:]
            ]
        }
        helper.registerAllowedMethod('pwd', []) { '/workspace' }
        helper.registerAllowedMethod('sh', [String]) { String cmd -> cmd }
        helper.registerAllowedMethod('sh', [Map]) { Map args ->
            if (args.script?.contains('git rev-parse HEAD')) {
                return 'abcdef1234567890abcdef1234567890abcdef12'
            }
            ''
        }
        helper.registerAllowedMethod('withEnv', [List, Closure]) { List envList, Closure body ->
            withEnvCalls << envList.collect { it.toString() }
            body()
        }
        helper.registerAllowedMethod('withCredentials', [List, Closure]) { List creds, Closure body -> body() }
        helper.registerAllowedMethod('usernamePassword', [Map]) { Map m -> m }
        helper.registerAllowedMethod('string', [Map]) { Map m -> m }
        helper.registerAllowedMethod('file', [Map]) { Map m -> m }
        helper.registerAllowedMethod('stage', [String, Closure]) { String name, Closure body ->
            stages << name
            body()
        }
        helper.registerAllowedMethod('cleanWs', [Map]) { }
        helper.registerAllowedMethod('deleteDir', []) { }
        helper.registerAllowedMethod('dir', [String, Closure]) { String path, Closure body -> body() }
        helper.registerAllowedMethod('helmDeploy', [Map]) { Map args -> }
        helper.registerAllowedMethod('runSmoke', [Map]) { Map args -> }
        helper.registerAllowedMethod('input', [Map]) { Map args -> }
        helper.registerAllowedMethod('timeout', [Map, Closure]) { Map m, Closure body -> body() }
    }

    @Test
    void "ciV1 loads and runs with minimal config"() {
        def script = loadScript('vars/ciV1.groovy')

        script.call(configPath: '.ci/ci.yml')

        assertFalse(withEnvCalls.isEmpty())
        assertTrue(withEnvCalls.flatten().contains('HOME=/workspace'))
    }

    @Test
    void "ciV1 skips direct non-main branch builds"() {
        envVars.BRANCH_NAME = 'feature/login'
        def script = loadScript('vars/ciV1.groovy')

        script.call(configPath: '.ci/ci.yml')

        assertEquals('NOT_BUILT', currentBuild.result)
        assertTrue(withEnvCalls.isEmpty())
        assertTrue(stages.isEmpty())
    }

    @Test
    void "ciV1 skips staged tag builds"() {
        envVars.TAG_NAME = 'v1.2.3'
        helper.registerAllowedMethod('readYaml', [Map]) { Map args ->
            [
                version : 1,
                preset  : 'node',
                build   : [stages: []],
                image   : false,
                deploy  : [:],
                terraform: [:],
                release : [:],
                delivery: [mode: 'staged']
            ]
        }
        def script = loadScript('vars/ciV1.groovy')

        script.call(configPath: '.ci/ci.yml')

        assertEquals('NOT_BUILT', currentBuild.result)
        assertTrue(withEnvCalls.isEmpty())
        assertTrue(stages.isEmpty())
    }
}
