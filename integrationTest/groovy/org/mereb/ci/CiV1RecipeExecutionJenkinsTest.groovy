import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class CiV1RecipeExecutionJenkinsTest extends BasePipelineTest {

    Map config
    List<String> stages
    List<Map> helmCalls
    List<Map> smokeCalls
    List<List<String>> withEnvValues
    Map envVars
    List<String> shCalls
    List<String> echoes
    Map currentBuild

    @BeforeEach
    void setup() {
        super.setUp()
        stages = []
        helmCalls = []
        smokeCalls = []
        withEnvValues = []
        shCalls = []
        echoes = []
        config = [:]
        envVars = [
            BRANCH_NAME : 'main',
            BUILD_NUMBER: '42',
            CHANGE_ID   : '',
            HOME        : '/home/jenkins'
        ]
        currentBuild = [result: null]
        binding.setVariable('env', envVars)
        binding.setVariable('scm', [:])
        binding.setVariable('currentBuild', currentBuild)
        binding.setVariable('docker', [image: { String name -> [inside: { Closure body -> body() }] }])

        helper.registerAllowedMethod('node', [Closure]) { Closure body -> body() }
        helper.registerAllowedMethod('node', [String, Closure]) { String label, Closure body -> body() }
        helper.registerAllowedMethod('checkout', [Map]) { }
        helper.registerAllowedMethod('checkout', [Object]) { }
        helper.registerAllowedMethod('fileExists', [String]) { String path -> path == '.ci/ci.yml' || path.startsWith('.ci/values-dev') }
        helper.registerAllowedMethod('readYaml', [Map]) { Map args -> config }
        helper.registerAllowedMethod('pwd', []) { '/workspace' }
        helper.registerAllowedMethod('echo', [String]) { String msg -> echoes << msg }
        helper.registerAllowedMethod('sh', [String]) { String script -> shCalls << script; respondToShell(script) }
        helper.registerAllowedMethod('sh', [Map]) { Map args ->
            String script = args.script ?: ''
            shCalls << script
            if (args.returnStatus) {
                return respondStatus(script)
            }
            respondToShell(script)
        }
        helper.registerAllowedMethod('withEnv', [List, Closure]) { List envList, Closure body ->
            withEnvValues << envList.collect { it.toString() }
            body()
        }
        helper.registerAllowedMethod('helmDeploy', [Map]) { Map args -> helmCalls << args }
        helper.registerAllowedMethod('runSmoke', [Map]) { Map args -> smokeCalls << args }
        helper.registerAllowedMethod('withCredentials', [List, Closure]) { List creds, Closure body ->
            creds.each { Map binding ->
                if (binding.variable) {
                    envVars[binding.variable] = "secret-${binding.credentialsId ?: 'cred'}"
                }
                if (binding.usernameVariable) {
                    envVars[binding.usernameVariable] = "user-${binding.credentialsId ?: 'cred'}"
                }
                if (binding.passwordVariable) {
                    envVars[binding.passwordVariable] = "pass-${binding.credentialsId ?: 'cred'}"
                }
                if (binding.tokenVariable) {
                    envVars[binding.tokenVariable] = "secret-${binding.credentialsId ?: 'cred'}"
                }
            }
            body()
        }
        helper.registerAllowedMethod('usernamePassword', [Map]) { Map m -> m }
        helper.registerAllowedMethod('string', [Map]) { Map m -> m }
        helper.registerAllowedMethod('file', [Map]) { Map m -> m }
        helper.registerAllowedMethod('writeFile', [Map]) { Map args -> }
        helper.registerAllowedMethod('archiveArtifacts', [Map]) { Map args -> }
        helper.registerAllowedMethod('stage', [String, Closure]) { String name, Closure body -> stages << name; body() }
        helper.registerAllowedMethod('cleanWs', [Map]) { }
        helper.registerAllowedMethod('deleteDir', []) { }
        helper.registerAllowedMethod('dir', [String, Closure]) { String path, Closure body -> body() }
        helper.registerAllowedMethod('timeout', [Map, Closure]) { Map cfg, Closure body -> body() }
        helper.registerAllowedMethod('input', [Map]) { Map args -> }
        helper.registerAllowedMethod('parallel', [Map]) { Map branches ->
            branches.values().each { Closure branch -> branch() }
        }
    }

    @Test
    void "ciV1 runs the package recipe"() {
        config = [
            version      : 1,
            image        : false,
            build        : [stages: [[name: 'Build package', sh: 'echo build']]],
            releaseStages: [[name: 'Publish package', sh: 'echo publish']],
            release      : [
                autoTag: [enabled: true],
                github : [enabled: true, credentialId: 'github-credentials']
            ]
        ]

        loadScript('vars/ciV1.groovy').call(configPath: '.ci/ci.yml')

        assertTrue(echoes.any { it.contains('ciV1 recipe: package') })
        assertTrue(stages.contains('Publish package'))
        assertTrue(stages.contains('Create Release Tag'))
        assertTrue(stages.contains('GitHub Release'))
        assertFalse(stages.any { it.contains('Docker Build') })
    }

    @Test
    void "ciV1 runs the microfrontend recipe"() {
        config = [
            version       : 1,
            delivery      : [mode: 'staged'],
            image         : false,
            build         : [stages: [[name: 'Build remote', sh: 'echo build']]],
            microfrontend : [
                name        : 'mfe-admin',
                distDir     : 'dist',
                manifestScript: 'scripts/update-manifest.js',
                checkScript : 'scripts/check-remote-entry.js',
                aws         : [
                    credential: [id: 'minio-credentials']
                ],
                order       : ['dev', 'stg', 'prd'],
                environments: [
                    dev: [displayName: 'Dev CDN', bucket: 'cdn-dev', publicBase: 'https://cdn-dev.mereb.app'],
                    stg: [displayName: 'Staging CDN', bucket: 'cdn-stg', publicBase: 'https://cdn-stg.mereb.app'],
                    prd: [displayName: 'Prod CDN', bucket: 'cdn', publicBase: 'https://cdn.mereb.app']
                ]
            ],
            release       : [
                autoTag: [enabled: true],
                github : [enabled: true, credentialId: 'github-credentials']
            ]
        ]

        loadScript('vars/ciV1.groovy').call(configPath: '.ci/ci.yml')

        assertTrue(echoes.any { it.contains('ciV1 recipe: microfrontend') })
        assertTrue(stages.any { it.contains('MFE Dev CDN Deploy') })
        assertTrue(stages.any { it.contains('MFE Staging CDN Deploy') })
        assertTrue(stages.any { it.contains('MFE Prod CDN Deploy') })
        assertTrue(stages.contains('Create Release Tag'))
        assertTrue(stages.contains('GitHub Release'))
    }

    @Test
    void "ciV1 runs the terraform recipe"() {
        config = [
            version  : 1,
            image    : false,
            build    : [stages: [[name: 'Validate terraform', sh: 'echo validate']]],
            terraform: [
                environments: [
                    dev: [displayName: 'DEV', when: '!pr']
                ]
            ],
            release  : [
                autoTag: [enabled: true],
                github : [enabled: true, credentialId: 'github-credentials']
            ]
        ]

        loadScript('vars/ciV1.groovy').call(configPath: '.ci/ci.yml')

        assertTrue(echoes.any { it.contains('ciV1 recipe: terraform') })
        assertTrue(stages.any { it.contains('Terraform DEV') })
        assertTrue(stages.contains('Create Release Tag'))
        assertTrue(stages.contains('GitHub Release'))
        assertFalse(stages.any { it.contains('Deploy ') })
    }

    @Test
    void "ciV1 runs the image recipe"() {
        config = [
            version: 1,
            build  : [stages: [[name: 'Skip build', sh: 'echo skip']]],
            image  : [
                enabled    : true,
                repository : 'ghcr.io/mereb/base',
                tagTemplate: '{{tagName}}'
            ],
            release: [
                autoTag: [enabled: true],
                github : [enabled: true, credentialId: 'github-credentials']
            ]
        ]

        loadScript('vars/ciV1.groovy').call(configPath: '.ci/ci.yml')

        assertTrue(echoes.any { it.contains('ciV1 recipe: image') })
        assertTrue(stages.contains('Docker Build'))
        assertTrue(stages.contains('GitHub Release'))
        assertFalse(stages.any { it.contains('Deploy ') })
    }

    @Test
    void "ciV1 runs the build recipe"() {
        config = [
            version: 1,
            image  : false,
            build  : [stages: [[name: 'Run e2e', sh: 'echo test']]]
        ]

        loadScript('vars/ciV1.groovy').call(configPath: '.ci/ci.yml')

        assertTrue(echoes.any { it.contains('ciV1 recipe: build') })
        assertTrue(stages.contains('Run e2e'))
        assertFalse(stages.any { it.contains('Docker Build') })
        assertFalse(stages.any { it.contains('GitHub Release') })
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
        if (script.contains('git ls-remote --tags --refs')) {
            return ''
        }
        if (script.contains("git ls-remote 'origin' refs/tags/")) {
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
        if (script.contains("docker inspect --format='{{index .RepoDigests 0}}'")) {
            return "ghcr.io/mereb/app@sha256:abcdef1234567890\n"
        }
        return ''
    }

    private int respondStatus(String script) {
        if (script.contains('git push')) {
            return 0
        }
        return 0
    }
}
