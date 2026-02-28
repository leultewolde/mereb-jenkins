import org.junit.jupiter.api.Test
import org.mereb.ci.delivery.DeliveryPolicy

import static org.junit.jupiter.api.Assertions.*

class DeliveryPolicyTest {

    @Test
    void "staged mode deploys grouped stage environments from main"() {
        DeliveryPolicy policy = new DeliveryPolicy(
            [mode: 'staged', mainBranch: 'main', pr: [deployToStg: false]],
            [BRANCH_NAME: 'main', CHANGE_ID: '', TAG_NAME: '']
        )

        assertTrue(policy.shouldRunPipeline())
        assertTrue(policy.shouldDeployEnvironment('dev'))
        assertTrue(policy.shouldDeployEnvironment('dev_outbox'))
        assertTrue(policy.shouldDeployEnvironment('stg'))
        assertTrue(policy.shouldDeployEnvironment('prd_outbox'))
    }

    @Test
    void "staged mode pr only deploys dev unless explicitly allowed to reach staging"() {
        DeliveryPolicy strict = new DeliveryPolicy(
            [mode: 'staged', pr: [deployToStg: false]],
            [BRANCH_NAME: 'PR-12', CHANGE_ID: '12', TAG_NAME: '']
        )
        DeliveryPolicy permissive = new DeliveryPolicy(
            [mode: 'staged', pr: [deployToStg: true]],
            [BRANCH_NAME: 'PR-12', CHANGE_ID: '12', TAG_NAME: '']
        )

        assertTrue(strict.shouldDeployEnvironment('dev'))
        assertFalse(strict.shouldDeployEnvironment('stg'))
        assertFalse(strict.shouldDeployEnvironment('prd'))
        assertTrue(permissive.shouldDeployEnvironment('stg'))
    }

    @Test
    void "staged mode skips standalone tag builds"() {
        DeliveryPolicy policy = new DeliveryPolicy(
            [mode: 'staged'],
            [BRANCH_NAME: 'main', CHANGE_ID: '', TAG_NAME: 'v1.2.3']
        )

        assertFalse(policy.shouldRunPipeline())
        assertTrue(policy.skipReason().contains('staged tag build'))
    }
}
