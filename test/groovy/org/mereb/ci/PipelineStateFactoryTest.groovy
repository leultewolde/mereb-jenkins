import org.junit.jupiter.api.Test
import org.mereb.ci.PipelineStateFactory
import org.mereb.ci.util.PipelineHelper

import static org.junit.jupiter.api.Assertions.*

class PipelineStateFactoryTest {

    @Test
    void "builds state and env exports for image-enabled service"() {
        FakeSteps steps = new FakeSteps([
            BRANCH_NAME: 'feature/login',
            BUILD_NUMBER: '42',
            TAG_NAME: '',
            HOME: '/home/jenkins',
            GIT_COMMIT: '0123456789abcdef0123456789abcdef01234567'
        ])
        PipelineHelper helper = new PipelineHelper(steps)
        PipelineStateFactory factory = new PipelineStateFactory(steps, helper)

        Map cfg = [
            requiresGradleHome: true,
            image: [
                enabled    : true,
                repository : 'ghcr.io/mereb/api',
                tagStrategy: 'branch-sha'
            ]
        ]

        def ctx = factory.create(cfg, ['FOO=bar'], '/workspace')

        assertEquals('0123456789abcdef0123456789abcdef01234567', ctx.state.commit)
        assertEquals('0123456789ab', ctx.state.commitShort)
        assertEquals('feature/login', ctx.state.branch)
        assertEquals('feature-login', ctx.state.branchSanitized)
        assertEquals('', ctx.state.changeId)
        assertEquals('false', ctx.state.isPr)
        assertEquals('ghcr.io/mereb/api:feature-login-0123456789ab', ctx.state.imageRef)
        assertTrue(ctx.exportedEnv.contains("FOO=bar"))
        assertTrue(ctx.exportedEnv.contains("HOME=/home/jenkins"))
        assertTrue(ctx.exportedEnv.contains("GRADLE_USER_HOME=/workspace/.gradle"))
        assertTrue(ctx.exportedEnv.contains("IMAGE_REPOSITORY=ghcr.io/mereb/api"))
        assertTrue(ctx.exportedEnv.contains("IS_PR=false"))
    }

    @Test
    void "sets release tag when TAG_NAME present"() {
        FakeSteps steps = new FakeSteps([
            BRANCH_NAME: 'main',
            TAG_NAME: 'v1.2.3',
            HOME: '/home/jenkins',
            GIT_COMMIT: 'deadbeefdeadbeefdeadbeefdeadbeefdeadbeef'
        ])
        PipelineHelper helper = new PipelineHelper(steps)
        PipelineStateFactory factory = new PipelineStateFactory(steps, helper)

        Map cfg = [requiresGradleHome: false, image: [enabled: false]]

        def ctx = factory.create(cfg, [], '/ws')

        assertEquals('v1.2.3', steps.env.RELEASE_TAG)
        assertEquals('v1.2.3', ctx.state.tagName)
        assertFalse(ctx.exportedEnv.any { it.startsWith('IMAGE_') })
    }

    @Test
    void "exports pr state when change id is present"() {
        FakeSteps steps = new FakeSteps([
            BRANCH_NAME: 'PR-24',
            CHANGE_ID  : '24',
            HOME       : '/home/jenkins',
            GIT_COMMIT : 'cafebabecafebabecafebabecafebabecafebabe'
        ])
        PipelineHelper helper = new PipelineHelper(steps)
        PipelineStateFactory factory = new PipelineStateFactory(steps, helper)

        Map cfg = [requiresGradleHome: false, image: [enabled: false]]

        def ctx = factory.create(cfg, [], '/ws')

        assertEquals('24', ctx.state.changeId)
        assertEquals('true', ctx.state.isPr)
        assertTrue(ctx.exportedEnv.contains('CHANGE_ID=24'))
        assertTrue(ctx.exportedEnv.contains('IS_PR=true'))
    }

    private static class FakeSteps {
        Map env = [:]

        FakeSteps(Map initialEnv = [:]) {
            initialEnv.each { k, v -> env[k] = v }
        }

        String sh(Map args) {
            return ''
        }
    }
}
