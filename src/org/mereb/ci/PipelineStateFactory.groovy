package org.mereb.ci

import org.mereb.ci.docker.DockerPipeline
import org.mereb.ci.util.PipelineHelper

import static org.mereb.ci.util.PipelineUtils.boolString
import static org.mereb.ci.util.PipelineUtils.sanitizeBranch

/**
 * Produces the pipeline state map and exported env list so ciV1 can stay declarative.
 */
class PipelineStateFactory implements Serializable {

    private final def steps
    private final PipelineHelper helper

    PipelineStateFactory(def steps, PipelineHelper helper) {
        this.steps = steps
        this.helper = helper
    }

    StateContext create(Map cfg, List<String> baseEnv, String workspace) {
        Map<String, String> state = [:]
        String commit = helper.resolveCommitSha()
        state.commit = commit
        state.commitShort = commit?.take(12) ?: ''
        state.branch = steps.env.BRANCH_NAME ?: ''
        state.branchSanitized = sanitizeBranch(state.branch)
        state.changeId = steps.env.CHANGE_ID ?: ''
        state.isPr = boolString(state.changeId?.trim() ? true : false)
        state.buildNumber = (steps.env.BUILD_NUMBER ?: '').toString()
        state.tagName = steps.env.TAG_NAME ?: ''
        state.repository = ''
        state.imageTag = ''
        state.imageDigest = ''
        state.imageRef = ''

        if ((steps.env.TAG_NAME ?: '').trim()) {
            steps.env.RELEASE_TAG = steps.env.TAG_NAME
        }

        if (cfg?.image?.enabled) {
            state.repository = cfg.image.repository
            state.imageTag = DockerPipeline.computeImageTag(cfg.image, state)
            state.imageRef = "${cfg.image.repository}:${state.imageTag}".toString()
        }

        List<String> exportedEnv = []
        exportedEnv.addAll(baseEnv ?: [])
        exportedEnv << "HOME=${helper.determineHome(workspace)}".toString()
        if (cfg.requiresGradleHome) {
            exportedEnv << "GRADLE_USER_HOME=${workspace}/.gradle".toString()
        }
        if (cfg.image?.enabled) {
            exportedEnv << "IMAGE_REPOSITORY=${cfg.image.repository}".toString()
            exportedEnv << "IMAGE_TAG=${state.imageTag}".toString()
            exportedEnv << "IMAGE_REF=${state.imageRef}".toString()
        }
        if (state.changeId?.trim()) {
            exportedEnv << "CHANGE_ID=${state.changeId}".toString()
        }
        exportedEnv << "IS_PR=${state.isPr}".toString()

        return new StateContext(state, exportedEnv)
    }

    static class StateContext implements Serializable {
        final Map<String, String> state
        final List<String> exportedEnv

        StateContext(Map<String, String> state, List<String> exportedEnv) {
            this.state = state ?: [:]
            this.exportedEnv = exportedEnv ?: []
        }
    }
}
