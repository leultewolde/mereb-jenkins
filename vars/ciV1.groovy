import groovy.transform.Field
import org.mereb.ci.PipelineStateFactory
import org.mereb.ci.build.BuildStages
import org.mereb.ci.config.ConfigNormalizer
import org.mereb.ci.config.ConfigValidator
import org.mereb.ci.credentials.CredentialHelper
import org.mereb.ci.deploy.DeployPipeline
import org.mereb.ci.docker.DockerPipeline
import org.mereb.ci.mfe.MicrofrontendPipeline
import org.mereb.ci.release.ReleaseFlow
import org.mereb.ci.release.ReleaseOrchestrator
import org.mereb.ci.terraform.TerraformPipeline
import org.mereb.ci.util.ApprovalHelper
import org.mereb.ci.util.PipelineHelper
import org.mereb.ci.util.StageExecutor
import org.mereb.ci.verbs.VerbRunner
import org.mereb.ci.ai.AiFactory
import groovy.json.JsonOutput

import static org.mereb.ci.util.PipelineUtils.*
import java.util.UUID

@Field final String PRIMARY_CONFIG = '.ci/ci.yml'
@Field final String LEGACY_CONFIG  = 'ci.yml'
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
            configPath = pipelineHelper.locateConfig(PRIMARY_CONFIG, LEGACY_CONFIG)
            if (!configPath) {
                error "Pipeline configuration not found. Add ${PRIMARY_CONFIG}"
            }
            if (configPath == LEGACY_CONFIG) {
                echo "[DEPRECATION] Found legacy pipeline file '${LEGACY_CONFIG}'. Support will be removed in December 2024 — migrate to ${PRIMARY_CONFIG}."
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
        error "Invalid ${PRIMARY_CONFIG}: ${validation.errors.join('; ')}"
    }

    Map cfg = ConfigNormalizer.normalize(rawCfg, DEFAULT_ENV_ORDER, PRIMARY_CONFIG)
    Map agent = cfg.agent

    def runCore = {
        deleteDir()
        unstash workspaceStash
        final String ws = pwd()
        if (!ws?.trim()) {
            error "Workspace path is empty"
        }

        final def pipelineState = stateFactory.create(cfg, baseEnv, ws)
        final Map<String, String> state = pipelineState.state
        CredentialHelper credentialHelper = new CredentialHelper(this)
        VerbRunner verbRunner = new VerbRunner(this)
        Closure runVerb = { String spec -> verbRunner.run(spec) }
        BuildStages buildStages = new BuildStages(this, runVerb, credentialHelper)
        DockerPipeline dockerPipeline = new DockerPipeline(this)
        ApprovalHelper releaseApprovalHelper = new ApprovalHelper(this)
        StageExecutor stageExecutor = new StageExecutor(this, credentialHelper)
        Closure approvalHandler = { Map approvalCfg, String message -> pipelineHelper.awaitApproval(approvalCfg, message) }
        ReleaseFlow releaseFlow = new ReleaseFlow(this, credentialHelper, runVerb, releaseApprovalHelper, stageExecutor)
        TerraformPipeline terraformPipeline = new TerraformPipeline(this, credentialHelper, stageExecutor, approvalHandler)
        DeployPipeline deployPipeline = new DeployPipeline(this, credentialHelper)
        MicrofrontendPipeline microfrontendPipeline = new MicrofrontendPipeline(this, credentialHelper, approvalHandler, stageExecutor)
        Closure handleRelease = { Map releaseCfg, Map currentState -> releaseFlow.handleRelease(releaseCfg, currentState) }
        Closure runMicrofrontends = { Map mfeCfg, Map mfeState, Closure tagCallback = null, Closure releaseCallback = null, Map releaseCfg = null ->
            microfrontendPipeline.run(mfeCfg, mfeState, tagCallback, releaseCallback, releaseCfg)
        }
        Closure runReleaseStages = { List<Map> stages -> releaseFlow.runReleaseStages(stages) }
        Closure publishRelease = { Map releaseCfg, Map releaseState -> releaseFlow.publishRelease(releaseCfg, releaseState) }
        ReleaseOrchestrator releaseOrchestrator = new ReleaseOrchestrator(
            this,
            terraformPipeline,
            handleRelease,
            runMicrofrontends,
            runReleaseStages,
            publishRelease
        )
        List<String> exportedEnv = pipelineState.exportedEnv
        def aiClient = AiFactory.create(cfg.ai, this)

        try {
            if (cfg.requiresGradleHome) {
                sh "mkdir -p '${ws}/.gradle'"
            }

            withEnv(exportedEnv) {
                if (fileExists('gradlew')) {
                    sh 'chmod +x ./gradlew'
                }

                buildStages.runBuildStages(cfg.buildStages)
                buildStages.runMatrix(cfg.matrix)

                echo "AI: starting suggestion fetch (provider=${(cfg.ai?.provider ?: 'none')})"
                def aiSuggestion = aiClient.suggest([state: state, config: cfg, env: env])
                if (aiSuggestion?.hasData()) {
                    echo "AI: suggestion received (bumpTypes=${aiSuggestion.bumpTypes?.keySet() ?: 'none'}, hasChangeset=${aiSuggestion.changeset?.trim() ? 'yes' : 'no'})"
                    if (aiSuggestion.changeset?.trim()) {
                        sh 'mkdir -p .ci'
                        writeFile file: '.ci/ai-changeset.md', text: aiSuggestion.changeset
                        exportedEnv << "AI_CHANGESET_PATH=${ws}/.ci/ai-changeset.md"
                        exportedEnv << "AI_CHANGESET=${aiSuggestion.changeset}"
                    }
                    if (aiSuggestion.bumpTypes && !aiSuggestion.bumpTypes.isEmpty()) {
                        exportedEnv << "AI_BUMP_TYPES=${JsonOutput.toJson(aiSuggestion.bumpTypes)}"
                    }
                } else {
                    echo "AI: no suggestion returned"
                }

                dockerPipeline.run(cfg, state)

                releaseOrchestrator.execute(cfg, state) { Closure afterEnv ->
                    deployPipeline.run(cfg, state, afterEnv)
                }
            }
        } catch (Throwable err) {
            releaseFlow.cleanupAutoTag(cfg.release)
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
