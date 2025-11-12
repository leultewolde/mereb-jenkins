package org.mereb.ci.util

/**
 * Wraps Jenkins step helpers so vars/ciV1.groovy can stay focused on orchestration.
 */
class PipelineHelper implements Serializable {

    private final def steps

    PipelineHelper(def steps) {
        this.steps = steps
    }

    String locateConfig(String primary, String legacy) {
        if (steps.fileExists(primary)) {
            return primary
        }
        if (steps.fileExists(legacy)) {
            return legacy
        }
        return null
    }

    String determineHome(String workspace) {
        String home = steps.env?.HOME?.trim()
        if (home && home != '/') {
            return home
        }
        return workspace
    }

    String resolveCommitSha() {
        String sha = steps.env?.GIT_COMMIT?.trim()
        if (sha) {
            return sha
        }
        return steps.sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
    }

    void awaitApproval(Map approval, String defaultMessage) {
        if (!approval || approval.isEmpty()) {
            return
        }
        String message = approval.message ?: defaultMessage
        String ok = approval.ok ?: 'Approve'
        if (approval.submitter) {
            steps.input message: message, ok: ok, submitter: approval.submitter.toString()
        } else {
            steps.input message: message, ok: ok
        }
    }

    void cleanupWorkspace(String workspace) {
        try {
            steps.echo 'Cleaning workspace...'
            steps.cleanWs deleteDirs: true, disableDeferredWipeout: true, notFailBuild: true
        } catch (MissingMethodException | NoSuchMethodError ignore) {
            steps.echo 'cleanWs step unavailable; falling back to deleteDir()'
            if (workspace?.trim()) {
                steps.dir(workspace) {
                    steps.deleteDir()
                }
            } else {
                steps.deleteDir()
            }
        } catch (Throwable t) {
            steps.echo "Workspace cleanup failed: ${t.message}"
        }
    }
}
