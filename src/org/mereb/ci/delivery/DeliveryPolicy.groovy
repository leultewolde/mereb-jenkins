package org.mereb.ci.delivery

/**
 * Freezes delivery behavior for the lifetime of a pipeline run so later env mutations
 * (for example setting TAG_NAME after auto-tagging) do not change trigger classification.
 */
class DeliveryPolicy implements Serializable {

    private final String mode
    private final String mainBranch
    private final boolean prDeployToStg
    private final String branchName
    private final String changeId
    private final String triggerTagName

    DeliveryPolicy(Map deliveryCfg = [:], Map envCtx = [:]) {
        Map cfg = deliveryCfg instanceof Map ? deliveryCfg : [:]
        Map prCfg = cfg.pr instanceof Map ? (cfg.pr as Map) : [:]
        this.mode = ((cfg.mode ?: 'custom').toString().trim().toLowerCase() ?: 'custom')
        this.mainBranch = (cfg.mainBranch ?: 'main').toString().trim() ?: 'main'
        this.prDeployToStg = prCfg.deployToStg as Boolean
        this.branchName = envCtx?.get('BRANCH_NAME')?.toString()
        this.changeId = envCtx?.get('CHANGE_ID')?.toString()
        this.triggerTagName = envCtx?.get('TAG_NAME')?.toString()
    }

    boolean isStagedMode() {
        return 'staged'.equalsIgnoreCase(mode)
    }

    boolean isCustomMode() {
        return !isStagedMode()
    }

    boolean isPrBuild() {
        return changeId?.trim()
    }

    boolean isTagBuild() {
        return triggerTagName?.trim()
    }

    boolean isMainBranchBuild() {
        return !isPrBuild() && !isTagBuild() && mainBranch == branchName
    }

    boolean shouldRunPipeline() {
        if (isPrBuild() || isMainBranchBuild()) {
            return true
        }
        return isCustomMode() && isTagBuild()
    }

    boolean shouldPushImage() {
        return isStagedMode() && !isTagBuild() && (isPrBuild() || isMainBranchBuild())
    }

    boolean shouldAutoTag() {
        return isStagedMode() && isMainBranchBuild()
    }

    boolean shouldDeployEnvironment(String envName) {
        if (!isStagedMode()) {
            return false
        }
        String stage = stageKey(envName)
        if (!stage || isTagBuild()) {
            return false
        }
        if (isPrBuild()) {
            if ('dev'.equals(stage)) {
                return true
            }
            return 'stg'.equals(stage) && prDeployToStg
        }
        if (isMainBranchBuild()) {
            return stage in ['dev', 'stg', 'prd', 'prod']
        }
        return false
    }

    boolean shouldPublishMicrofrontendEnvironment(String envName) {
        return shouldDeployEnvironment(envName)
    }

    String skipReason() {
        if (isStagedMode() && isTagBuild()) {
            return "Skipping staged tag build '${triggerTagName}' because staged delivery releases from the main branch pipeline."
        }
        return "Skipping build for branch '${branchName ?: 'unknown'}'. Only PRs, '${mainBranch}', and custom-mode tag builds run."
    }

    private static String normalizeEnvironment(String envName) {
        String normalized = envName?.toString()?.trim()?.toLowerCase()
        if (!normalized) {
            return ''
        }
        switch (normalized) {
            case 'stage':
            case 'staging':
                return 'stg'
            case 'production':
                return 'prod'
            default:
                return normalized
        }
    }

    private static String stageKey(String envName) {
        String normalized = normalizeEnvironment(envName)
        if (!normalized) {
            return ''
        }
        if (normalized in ['dev', 'stg', 'prd', 'prod']) {
            return normalized
        }
        if (normalized.startsWith('dev_') || normalized.startsWith('dev-')) {
            return 'dev'
        }
        if (normalized.startsWith('stg_') || normalized.startsWith('stg-') ||
            normalized.startsWith('stage_') || normalized.startsWith('stage-') ||
            normalized.startsWith('staging_') || normalized.startsWith('staging-')) {
            return 'stg'
        }
        if (normalized.startsWith('prd_') || normalized.startsWith('prd-')) {
            return 'prd'
        }
        if (normalized.startsWith('prod_') || normalized.startsWith('prod-') ||
            normalized.startsWith('production_') || normalized.startsWith('production-')) {
            return 'prod'
        }
        return normalized
    }
}
