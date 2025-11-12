package org.mereb.ci

/**
 * Coordinates release automation (auto-tagging, deferred Terraform, release stages)
 * so the main pipeline can stay declarative.
 */
class ReleaseCoordinator implements Serializable {

    private final Map autoTagCfg
    private final List<String> deployOrder
    private final Closure autoTagAction
    private final Closure releaseStagesAction
    private final Closure deferredTerraformAction

    private final boolean autoTagEnabled
    private final String afterEnvironment

    private boolean releaseFlowTriggered = false
    private boolean releaseStagesRan = false

    ReleaseCoordinator(Map autoTagCfg,
                       List<String> deployOrder,
                       Closure autoTagAction,
                       Closure releaseStagesAction,
                       Closure deferredTerraformAction) {
        this.autoTagCfg = autoTagCfg ?: [:]
        this.deployOrder = (deployOrder ?: []).collect { it?.toString()?.trim() ?: '' }
        this.autoTagAction = autoTagAction
        this.releaseStagesAction = releaseStagesAction
        this.deferredTerraformAction = deferredTerraformAction
        this.autoTagEnabled = this.autoTagCfg.enabled as Boolean
        String rawAfter = (this.autoTagCfg.afterEnvironment ?: '').toString().trim().toLowerCase()
        if (!rawAfter && this.deployOrder && !this.deployOrder.isEmpty()) {
            rawAfter = this.deployOrder.last().toLowerCase()
        }
        this.afterEnvironment = rawAfter
    }

    /**
     * Immediately run the release flow when no environment gate is configured.
     */
    void runIfEager() {
        if (autoTagEnabled && !afterEnvironment) {
            runReleaseFlow()
        }
    }

    /**
     * Generates a callback that should run after each environment deploy to trigger gated releases.
     */
    Closure afterEnvironmentHook() {
        if (!autoTagEnabled || !afterEnvironment) {
            return null
        }
        final String target = afterEnvironment
        return { String envName ->
            String normalized = (envName ?: '').toString().trim().toLowerCase()
            if (normalized == target) {
                runReleaseFlow()
            }
        }
    }

    /**
     * Ensures release stages have executed exactly once, even if no auto-tag occurred.
     */
    void ensureReleaseStagesRan() {
        runReleaseStages()
    }

    /**
     * Exposed for scenarios where callers need to force the release flow.
     */
    void triggerNow() {
        runReleaseFlow()
    }

    boolean hasReleaseRun() {
        return releaseFlowTriggered
    }

    private void runReleaseFlow() {
        if (releaseFlowTriggered) {
            return
        }
        releaseFlowTriggered = true
        autoTagAction?.call()
        deferredTerraformAction?.call()
        runReleaseStages()
    }

    private void runReleaseStages() {
        if (releaseStagesRan) {
            return
        }
        releaseStagesAction?.call()
        releaseStagesRan = true
    }
}
