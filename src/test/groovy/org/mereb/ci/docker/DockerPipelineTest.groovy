import org.junit.jupiter.api.Test
import org.mereb.ci.docker.DockerPipeline

import static org.junit.jupiter.api.Assertions.*

class DockerPipelineTest {

    @Test
    void "prefers explicit tag templates"() {
        Map image = [
            enabled    : true,
            repository : 'ghcr.io/mereb/api',
            tagTemplate: '{{branchSlug}}-{{buildNumber}}'
        ]
        Map state = [
            branch        : 'feature/add-login',
            branchSanitized: 'feature-add-login',
            commit        : '0123456789abcdef0123456789abcdef01234567',
            commitShort   : '0123456789ab',
            buildNumber   : '42'
        ]

        assertEquals('feature-add-login-42', DockerPipeline.computeImageTag(image, state))
    }

    @Test
    void "falls back to branch and sha"() {
        Map image = [
            enabled    : true,
            repository : 'ghcr.io/mereb/api',
            tagStrategy: 'branch-sha'
        ]
        Map state = [
            branch        : 'main',
            branchSanitized: 'main',
            commit        : 'fedcba9876543210fedcba9876543210fedcba98',
            commitShort   : 'fedcba987654'
        ]

        assertEquals('main-fedcba987654', DockerPipeline.computeImageTag(image, state))
    }
}
