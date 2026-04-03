import groovy.transform.Field
import org.mereb.ci.PipelineStateFactory
import org.mereb.ci.config.ConfigNormalizer
import org.mereb.ci.config.ConfigValidator
import org.mereb.ci.delivery.DeliveryPolicy
import org.mereb.ci.util.PipelineHelper
import org.mereb.ci.recipe.CommonPipelineExecutor
import org.mereb.ci.recipe.RecipeContext
import org.mereb.ci.recipe.RecipeExecutorFactory
import org.mereb.ci.recipe.RecipeResolver
import org.mereb.ci.recipe.RecipeServicesFactory
import org.mereb.ci.recipe.RecipeType

import static org.mereb.ci.util.PipelineUtils.*
import java.util.UUID

@Field final String PRIMARY_CONFIG = '.ci/ci.mjc'
@Field final List<String> LEGACY_CONFIGS = ['.ci/ci.yml', 'ci.yml']
@Field final List<String> SUPPORTED_CONFIGS = [PRIMARY_CONFIG] + LEGACY_CONFIGS
@Field final List<String> DEFAULT_ENV_ORDER = ['dev', 'stg', 'prd', 'prod']

def call(Map args = [:]) {
    Map rawCfg = [:]
    List<String> baseEnv = []
    String configPath = (args?.configPath ?: '').toString().trim()
    PipelineHelper pipelineHelper = new PipelineHelper(this)
    PipelineStateFactory stateFactory = new PipelineStateFactory(this, pipelineHelper)
    String workspaceStash = "ciV1-workspace-${UUID.randomUUID().toString()}"

    node(args?.bootstrapLabel ?: '') {
        checkout scm
        if (configPath) {
            if (!fileExists(configPath)) {
                error "Pipeline configuration '${configPath}' not found."
            }
        } else {
            configPath = pipelineHelper.locateConfig(SUPPORTED_CONFIGS)
            if (!configPath) {
                error "Pipeline configuration not found. Add ${PRIMARY_CONFIG} (YAML)."
            }
            if (LEGACY_CONFIGS.contains(configPath)) {
                echo "ciV1 config notice: using legacy pipeline file '${configPath}'. Prefer ${PRIMARY_CONFIG} for new configs."
            }
        }
        rawCfg = readYaml(file: configPath) ?: [:]
        int version = (rawCfg.version ?: 1) as Integer
        if (version != 1) {
            error "Pipeline config version must be 1 (found ${version})"
        }
        baseEnv = (rawCfg.env ?: [:]).collect { k, v -> "${k}=${v}" }
        stash name: workspaceStash, includes: '**/*', useDefaultExcludes: false
    }

    def validation = ConfigValidator.validate(rawCfg)
    if (validation.hasWarnings()) {
        validation.warnings.each { warn ->
            echo "ciV1 config warning: ${warn}"
        }
    }
    if (validation.hasErrors()) {
        error "Invalid pipeline configuration '${configPath ?: PRIMARY_CONFIG}': ${validation.errors.join('; ')}"
    }

    Map cfg = ConfigNormalizer.normalize(rawCfg, DEFAULT_ENV_ORDER, configPath ?: PRIMARY_CONFIG)
    Map deliveryEnv = [
        BRANCH_NAME: env.BRANCH_NAME ?: '',
        CHANGE_ID  : env.CHANGE_ID ?: '',
        TAG_NAME   : env.TAG_NAME ?: ''
    ]
    DeliveryPolicy deliveryPolicy = new DeliveryPolicy(cfg.delivery, deliveryEnv)
    cfg.delivery.policy = deliveryPolicy
    Map agent = cfg.agent

    if (!deliveryPolicy.shouldRunPipeline()) {
        echo deliveryPolicy.skipReason()
        try {
            currentBuild.result = 'NOT_BUILT'
        } catch (Throwable ignored) {
            echo 'Unable to set currentBuild.result to NOT_BUILT in this context.'
        }
        return
    }

    def runCore = {
        deleteDir()
        unstash workspaceStash
        final String ws = pwd()
        if (!ws?.trim()) {
            error "Workspace path is empty"
        }

        final def pipelineState = stateFactory.create(cfg, baseEnv, ws)
        final Map<String, String> state = pipelineState.state
        List<String> exportedEnv = pipelineState.exportedEnv
        RecipeType recipeType = RecipeResolver.resolveNormalized(cfg)
        def services = RecipeServicesFactory.create(this, pipelineHelper, cfg)
        def commonExecutor = new CommonPipelineExecutor(this)
        def recipeExecutor = RecipeExecutorFactory.create(recipeType)
        def recipeContext = new RecipeContext(this, cfg, state, exportedEnv, recipeType, services, ws)

        try {
            echo "ciV1 recipe: ${recipeType.value}"

            withEnv(exportedEnv) {
                commonExecutor.run(recipeContext)
                recipeExecutor.execute(recipeContext)
            }
        } catch (Throwable err) {
            services.releaseFlow.cleanupAutoTag(cfg.release)
            throw err
        } finally {
            pipelineHelper.cleanupWorkspace(ws)
        }
    }

    if (agent.docker && agent.label) {
        node(agent.label) {
            docker.image(agent.docker).inside {
                runCore()
            }
        }
    } else if (agent.docker) {
        node {
            docker.image(agent.docker).inside {
                runCore()
            }
        }
    } else if (agent.label) {
        node(agent.label) {
            runCore()
        }
    } else {
        node {
            runCore()
        }
    }
}
