import org.junit.jupiter.api.Test
import org.mereb.ci.recipe.RecipeResolver
import org.mereb.ci.recipe.RecipeType

import static org.junit.jupiter.api.Assertions.assertEquals

class RecipeResolverTest {

    @Test
    void "resolves package recipe from release automation"() {
        assertEquals(RecipeType.PACKAGE, RecipeResolver.resolveRaw([
            version      : 1,
            image        : false,
            releaseStages: [[name: 'Publish package', sh: 'echo publish']]
        ]))
    }

    @Test
    void "resolves service recipe from deploy plus image orchestration"() {
        assertEquals(RecipeType.SERVICE, RecipeResolver.resolveRaw([
            version: 1,
            image  : [repository: 'ghcr.io/mereb/api'],
            deploy : [dev: [chart: 'infra/charts/api']]
        ]))
    }

    @Test
    void "resolves microfrontend recipe from microfrontend environments"() {
        assertEquals(RecipeType.MICROFRONTEND, RecipeResolver.resolveRaw([
            version       : 1,
            image         : false,
            microfrontend : [
                environments: [
                    dev: [bucket: 'cdn-dev']
                ]
            ]
        ]))
    }

    @Test
    void "resolves terraform recipe from terraform environments"() {
        assertEquals(RecipeType.TERRAFORM, RecipeResolver.resolveRaw([
            version  : 1,
            image    : false,
            terraform: [
                environments: [
                    dev: [displayName: 'DEV']
                ]
            ]
        ]))
    }

    @Test
    void "resolves image recipe from image orchestration only"() {
        assertEquals(RecipeType.IMAGE, RecipeResolver.resolveRaw([
            version: 1,
            image  : [repository: 'ghcr.io/mereb/base']
        ]))
    }

    @Test
    void "resolves build recipe when no orchestration section is configured"() {
        assertEquals(RecipeType.BUILD, RecipeResolver.resolveRaw([
            version: 1,
            image  : false,
            build  : [stages: [[name: 'Smoke', sh: 'echo hi']]]
        ]))
    }

    @Test
    void "uses explicit recipe override when present"() {
        assertEquals(RecipeType.BUILD, RecipeResolver.resolveRaw([
            version: 1,
            recipe : 'build',
            image  : [repository: 'ghcr.io/mereb/base']
        ]))
    }
}
