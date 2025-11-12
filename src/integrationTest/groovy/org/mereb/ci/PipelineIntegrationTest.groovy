import org.junit.jupiter.api.Test
import org.mereb.ci.credentials.CredentialHelper
import org.mereb.ci.deploy.DeployPipeline

import static org.junit.jupiter.api.Assertions.*

class PipelineIntegrationTest {

    @Test
    void "deploy pipeline integrates helm and smoke helpers"() {
        FakeSteps steps = new FakeSteps()
        CredentialHelper credentialHelper = new CredentialHelper(steps)
        DeployPipeline deployPipeline = new DeployPipeline(steps, credentialHelper)

        Map cfg = [
            image : [enabled: true, repository: 'ghcr.io/mereb/api'],
            deploy: [
                order       : ['dev'],
                environments: [
                    dev: [
                        displayName: 'Dev',
                        release    : 'api-dev',
                        namespace  : 'apps',
                        chart      : 'infra/charts/api',
                        when       : '!pr',
                        valuesFiles: ['values-dev.yaml'],
                        smoke      : [url: 'https://dev.api.local/ping']
                    ]
                ]
            ]
        ]

        Map state = [
            repository: 'ghcr.io/mereb/api',
            imageTag  : 'main-abc123',
            imageRef  : 'ghcr.io/mereb/api:main-abc123'
        ]

        steps.existingFiles << 'values-dev.yaml'

        deployPipeline.run(cfg, state, null)

        assertTrue(steps.stages.contains('Deploy Dev'))
        assertTrue(steps.stages.contains('Smoke Dev'))
        assertEquals(1, steps.helmCommands.size())
        assertTrue(steps.helmCommands.first().contains("--set 'image.tag=main-abc123'"))
        assertEquals('https://dev.api.local/ping', steps.smokeUrls.first())
    }

    private static class FakeSteps {
        Map env = [BRANCH_NAME: 'main']
        List<String> stages = []
        List<String> helmCommands = []
        List<String> smokeUrls = []
        Set<String> existingFiles = [] as Set

        void stage(String name, Closure body) {
            stages << name
            body()
        }

        void echo(String msg) {
            // no-op
        }

        boolean fileExists(String path) {
            return existingFiles.contains(path)
        }

        void helmDeploy(Map args) {
            new org.mereb.ci.helm.HelmDeployer(this).deploy(args)
        }

        void runSmoke(Map args) {
            smokeUrls << args.url
        }

        void sh(String script) {
            helmCommands << script
        }

        void withEnv(List<String> envList, Closure body) {
            body()
        }

        void withCredentials(List creds, Closure body) {
            body()
        }

        def usernamePassword(Map args) { args }
        def file(Map args) { args }
        def string(Map args) { args }

        void input(Map args) {
            // approvals not part of test
        }
    }
}
