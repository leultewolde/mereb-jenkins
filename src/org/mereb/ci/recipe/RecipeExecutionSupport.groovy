package org.mereb.ci.recipe

import org.mereb.ci.Helpers
import org.mereb.ci.ReleaseCoordinator
import org.mereb.ci.delivery.DeliveryPolicy
import org.mereb.ci.docker.DockerPipeline
import org.mereb.ci.release.ReleaseFlow

/**
 * Shared sequencing helpers used by multiple recipe executors.
 */
class RecipeExecutionSupport implements Serializable {

    static void maybeRunEagerAutoTag(def steps, Map cfg, Map state, ReleaseFlow releaseFlow) {
        Map autoTagCfg = cfg?.release?.autoTag instanceof Map ? (cfg.release.autoTag as Map) : [:]
        boolean autoTagEnabled = autoTagCfg.enabled as Boolean
        String autoTagWhen = (autoTagCfg.when ?: '!pr').toString()
        boolean canEagerTag = (cfg?.image?.enabled as Boolean) && !autoTagCfg.afterEnvironment
        DeliveryPolicy deliveryPolicy = cfg?.delivery?.policy instanceof DeliveryPolicy ? (cfg.delivery.policy as DeliveryPolicy) : null

        boolean shouldAutoTag
        if (deliveryPolicy?.isStagedMode()) {
            shouldAutoTag = canEagerTag && autoTagEnabled && deliveryPolicy.shouldAutoTag() && !(steps.env.TAG_NAME?.trim())
        } else {
            shouldAutoTag = canEagerTag && autoTagEnabled && Helpers.matchCondition(autoTagWhen, steps.env) &&
                !(steps.env.TAG_NAME?.trim())
        }

        if (!shouldAutoTag) {
            return
        }

        releaseFlow.handleRelease(cfg.release, state)
        if (steps.env.TAG_NAME?.trim()) {
            state.tagName = steps.env.TAG_NAME.trim()
            state.imageTag = DockerPipeline.computeImageTag(cfg.image, state)
            state.imageRef = "${cfg.image.repository}:${state.imageTag}"
            cfg.release?.autoTag?.put('enabled', false)
        }
    }

    static ReleaseCoordinator releaseCoordinator(Map cfg,
                                                 List<String> deployOrder,
                                                 Closure autoTagAction,
                                                 Closure releaseStagesAction,
                                                 Closure deferredTerraformAction) {
        return new ReleaseCoordinator(
            cfg?.release?.autoTag,
            deployOrder,
            autoTagAction,
            releaseStagesAction,
            deferredTerraformAction
        )
    }
}
