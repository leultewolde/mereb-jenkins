package org.mereb.ci.credentials

/**
 * Centralizes Jenkins credential binding maps so that every caller logs consistently.
 */
class CredentialHelper implements Serializable {

    private final def steps

    CredentialHelper(def steps) {
        this.steps = steps
    }

    List<Map> bindingsFor(Map cfg) {
        List<Map> bindings = []
        if (cfg?.kubeconfigCredential) {
            bindings << [$class: 'FileBinding', credentialsId: cfg.kubeconfigCredential, variable: (cfg.kubeconfigEnv ?: 'KUBECONFIG')]
        }
        List creds = cfg?.credentials instanceof List ? (cfg.credentials as List) : []
        creds.each { Object entry ->
            if (!(entry instanceof Map)) {
                return
            }
            Map cred = entry as Map
            String type = (cred.type ?: 'string').toString()
            switch (type) {
                case 'file':
                    bindings << [$class: 'FileBinding', credentialsId: cred.id, variable: cred.env ?: cred.variable ?: 'SECRET_FILE']
                    break
                case 'usernamePassword':
                    bindings << [$class: 'UsernamePasswordMultiBinding', credentialsId: cred.id, usernameVariable: cred.usernameEnv ?: 'USERNAME', passwordVariable: cred.passwordEnv ?: 'PASSWORD']
                    break
                default:
                    bindings << [$class: 'StringBinding', credentialsId: cred.id, variable: cred.env ?: 'SECRET']
                    break
            }
        }
        return bindings
    }

    void withOptionalCredentials(List<Map> bindings, Closure body) {
        if (bindings && !bindings.isEmpty()) {
            steps.withCredentials(bindings) {
                body()
            }
        } else {
            body()
        }
    }

    void withRepoCredentials(Object rawCfg, Closure body) {
        Map cfg = [:]
        if (rawCfg instanceof Map) {
            cfg.putAll(rawCfg as Map)
        }
        String id = (cfg.id ?: cfg.credentialsId ?: '').toString().trim()
        if (!id) {
            callWithOptionalArg(body, [:])
            return
        }
        String usernameEnv = (cfg.usernameEnv ?: 'HELM_REPO_USERNAME').toString()
        String passwordEnv = (cfg.passwordEnv ?: 'HELM_REPO_PASSWORD').toString()
        steps.withCredentials([
            steps.usernamePassword(credentialsId: id, usernameVariable: usernameEnv, passwordVariable: passwordEnv)
        ]) {
            Map creds = [
                username: steps.env[usernameEnv],
                password: steps.env[passwordEnv]
            ]
            callWithOptionalArg(body, creds)
        }
    }

    private void callWithOptionalArg(Closure body, Map arg) {
        if (!body) {
            return
        }
        try {
            body.call(arg ?: [:])
        } catch (MissingMethodException | IllegalArgumentException ignored) {
            body.call()
        }
    }
}
