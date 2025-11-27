package org.mereb.ci.release

import org.mereb.ci.ReleaseCoordinator

/**
 * Glues together release automation and deferred Terraform execution.
 */
class ReleaseOrchestrator implements Serializable {

    private final def steps
    private final def terraformPipeline
    private final Closure handleRelease
    private final Closure runMicrofrontends
    private final Closure runReleaseStages
    private final Closure publishRelease

    ReleaseOrchestrator(def steps,
                        def terraformPipeline,
                        Closure handleRelease,
                        Closure runMicrofrontends,
                        Closure runReleaseStages,
                        Closure publishRelease) {
        this.steps = steps
        this.terraformPipeline = terraformPipeline
        this.handleRelease = handleRelease
        this.runMicrofrontends = runMicrofrontends
        this.runReleaseStages = runReleaseStages
        this.publishRelease = publishRelease
    }

    void execute(Map cfg, Map state, Closure deployCallback) {
        if (cfg.microfrontend?.enabled) {
            runMicrofrontends?.call(
                cfg.microfrontend,
                state,
                { releaseCfg, currentState -> handleRelease?.call(cfg.release, currentState) },
                { releaseCfg, currentState -> publishRelease?.call(cfg.release, currentState) },
                cfg.release
            )
            runReleaseStages?.call(cfg.releaseStages)
            return
        }

        List<String> deferredTerraform = terraformPipeline.run(cfg.terraform, null, true)
        boolean deferredTerraformRan = false

        Closure runDeferredTerraform = {
            if (deferredTerraformRan) {
                return
            }
            if (steps.env.RELEASE_TAG?.trim() && deferredTerraform && !deferredTerraform.isEmpty()) {
                steps.env.TAG_NAME = steps.env.RELEASE_TAG
                steps.echo "Running deferred Terraform environments after creating tag ${steps.env.RELEASE_TAG}"
                terraformPipeline.run(cfg.terraform, deferredTerraform, false)
                deferredTerraformRan = true
            }
        }

        List<String> deployOrder = (cfg.deploy?.order instanceof List) ? (cfg.deploy.order as List).collect { it?.toString() } : []
        ReleaseCoordinator releaseCoordinator = new ReleaseCoordinator(
            cfg.release?.autoTag,
            deployOrder,
            { handleRelease?.call(cfg.release, state) },
            {
                runMicrofrontends?.call(cfg.microfrontend, state)
                runReleaseStages?.call(cfg.releaseStages)
            },
            { runDeferredTerraform() }
        )

        releaseCoordinator.runIfEager()
        runDeferredTerraform()

        Closure afterEnvCallback = releaseCoordinator.afterEnvironmentHook()
        deployCallback?.call(afterEnvCallback)

        runDeferredTerraform()
        releaseCoordinator.ensureReleaseStagesRan()
        publishRelease?.call(cfg.release, state)
    }
}
