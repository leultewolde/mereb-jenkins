package org.mereb.ci.util

/**
 * Centralizes approval gate wiring (message defaults, submitter lists, optional timeout) for the pipeline.
 */
class ApprovalHelper implements Serializable {

    private final def steps

    ApprovalHelper(def steps) {
        this.steps = steps
    }

    void request(Map approval, String defaultMessage, String defaultOk = 'Approve', int timeoutMinutes = 0) {
        if (!(approval instanceof Map) || approval.isEmpty()) {
            return
        }

        String message = approval.message ?: defaultMessage
        String ok = approval.ok ?: defaultOk
        String submitter = extractSubmitter(approval)

        Closure runInput = {
            if (submitter) {
                steps.input message: message, ok: ok, submitter: submitter
            } else {
                steps.input message: message, ok: ok
            }
        }

        if (timeoutMinutes > 0) {
            steps.timeout(time: timeoutMinutes, unit: 'MINUTES') {
                runInput()
            }
        } else {
            runInput()
        }
    }

    private String extractSubmitter(Map approval) {
        Object submitter = approval.submitter ?: approval.user ?: approval.users
        if (!submitter) {
            return null
        }
        if (submitter instanceof Collection) {
            return submitter.findAll { it }*.toString().join(',')
        }
        return submitter.toString()
    }
}
