import groovy.transform.Field
import org.mereb.ci.PipelineStateFactory
import org.mereb.ci.build.BuildStages
import org.mereb.ci.config.ConfigNormalizer
import org.mereb.ci.config.ConfigValidator
import org.mereb.ci.credentials.CredentialHelper
import org.mereb.ci.deploy.DeployPipeline
import org.mereb.ci.docker.DockerPipeline
import org.mereb.ci.release.ReleaseFlow
import org.mereb.ci.release.ReleaseOrchestrator
import org.mereb.ci.terraform.TerraformPipeline
import org.mereb.ci.util.PipelineHelper
import org.mereb.ci.verbs.VerbRunner

import static org.mereb.ci.util.PipelineUtils.*

@Field final String PRIMARY_CONFIG = '.ci/ci.yml'
@Field final String LEGACY_CONFIG  = 'ci.yml'
@Field final List<String> DEFAULT_ENV_ORDER = ['dev', 'stg', 'prd', 'prod']

def call(Map args = [:]) {
    Map rawCfg = [:]
    List<String> baseEnv = []
    String configPath = (args?.configPath ?: '').toString().trim()
    PipelineHelper pipelineHelper = new PipelineHelper(this)
    PipelineStateFactory stateFactory = new PipelineStateFactory(this, pipelineHelper)

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
        final String ws = pwd()
        if (!ws?.trim()) {
            error "Workspace path is empty"
        }

        final def pipelineState = stateFactory.create(cfg, baseEnv, ws)
        final Map<String, String> state = pipelineState.state
        CredentialHelper credentialHelper = new CredentialHelper(this)
        VerbRunner verbRunner = new VerbRunner(this)
        BuildStages buildStages = new BuildStages(this, verbRunner.&run, credentialHelper)
        DockerPipeline dockerPipeline = new DockerPipeline(this)
        ReleaseFlow releaseFlow = new ReleaseFlow(this, credentialHelper, verbRunner.&run)
        TerraformPipeline terraformPipeline = new TerraformPipeline(this, credentialHelper, pipelineHelper.&awaitApproval)
        DeployPipeline deployPipeline = new DeployPipeline(this, credentialHelper)
        ReleaseOrchestrator releaseOrchestrator = new ReleaseOrchestrator(
            this,
            terraformPipeline,
            releaseFlow.&handleRelease,
            releaseFlow.&runReleaseStages,
            releaseFlow.&publishRelease
        )
        List<String> exportedEnv = pipelineState.exportedEnv

        try {
            if (cfg.requiresGradleHome) {
                sh "mkdir -p '${ws}/.gradle'"
            }

            withEnv(exportedEnv) {
                checkout scm
                sh 'chmod +x ./gradlew || true'

                buildStages.runBuildStages(cfg.buildStages)
                buildStages.runMatrix(cfg.matrix)

                dockerPipeline.run(cfg, state)

                releaseOrchestrator.execute(cfg, state) { Closure afterEnv ->
                    deployPipeline.run(cfg, state, afterEnv)
                }
            }
        } finally {
            pipelineHelper.cleanupWorkspace(ws)
        }
    }

    if (agent.docker && agent.label) {
        node(agent.label) {
            checkout scm
            docker.image(agent.docker).inside {
                runCore()
            }
        }
    } else if (agent.docker) {
        node {
            checkout scm
            docker.image(agent.docker).inside {
                runCore()
            }
        }
    } else if (agent.label) {
        node(agent.label) {
            checkout scm
            runCore()
        }
    } else {
        node {
            checkout scm
            runCore()
        }
    }
}
