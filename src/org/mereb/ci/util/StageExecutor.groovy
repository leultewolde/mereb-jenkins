package org.mereb.ci.util

import org.mereb.ci.credentials.CredentialHelper

/**
 * Runs Jenkins stages with optional environment variables and credential bindings.
 */
class StageExecutor implements Serializable {

    private final def steps
    private final CredentialHelper credentialHelper

    StageExecutor(def steps, CredentialHelper credentialHelper) {
        this.steps = steps
        this.credentialHelper = credentialHelper
    }

    void run(String name, List<String> envList = null, List<Map> credentials = null, Closure body) {
        List<String> normalizedEnv = envList ? new ArrayList<>(envList) : []
        List<Map> normalizedCreds = credentials ? new ArrayList<>(credentials) : []

        steps.stage(name) {
            Closure execute = {
                if (normalizedEnv && !normalizedEnv.isEmpty()) {
                    steps.withEnv(normalizedEnv) {
                        body()
                    }
                } else {
                    body()
                }
            }
            credentialHelper.withOptionalCredentials(normalizedCreds, execute)
        }
    }
}
