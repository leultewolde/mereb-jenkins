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
            String id = (cred.id ?: cred.credentialsId ?: '').toString()
            String envVar = (cred.env ?: cred.variable ?: '').toString()
            String lowerType = type.toLowerCase()
            switch (lowerType) {
                case 'vault':
                case 'vaulttoken':
                    bindings << vaultTokenBinding(id, envVar)
                    break
                case 'file':
                    bindings << [$class: 'FileBinding', credentialsId: id, variable: envVar ?: 'SECRET_FILE']
                    break
                case 'usernamePassword':
                case 'usernamepassword':
                    bindings << [$class: 'UsernamePasswordMultiBinding', credentialsId: id, usernameVariable: cred.usernameEnv ?: 'USERNAME', passwordVariable: cred.passwordEnv ?: 'PASSWORD']
                    break
                default:
                    if (shouldUseVaultTokenFallback(lowerType, envVar, id)) {
                        bindings << vaultTokenBinding(id, envVar)
                    } else {
                        bindings << [$class: 'StringBinding', credentialsId: id, variable: envVar ?: 'SECRET']
                    }
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
        if (!body) {
            return
        }
        Map cfg = [:]
        if (rawCfg instanceof Map) {
            cfg.putAll(rawCfg as Map)
        }
        String id = (cfg.id ?: cfg.credentialsId ?: '').toString().trim()
        if (!id) {
            body.call([:])
            return
        }
        String usernameEnv = (cfg.usernameEnv ?: 'HELM_REPO_USERNAME').toString()
        String passwordEnv = (cfg.passwordEnv ?: 'HELM_REPO_PASSWORD').toString()
        steps.withCredentials([
            steps.usernamePassword(credentialsId: id, usernameVariable: usernameEnv, passwordVariable: passwordEnv)
        ]) {
            String username = readEnvValue(usernameEnv)
            String password = readEnvValue(passwordEnv)
            Map creds = [
                username: username,
                password: password
            ]
            body.call(creds)
        }
    }

    private String readEnvValue(String name) {
        if (!name?.trim()) {
            return ''
        }
        String safeName = name.replaceAll(/[^A-Za-z0-9_]/, '')
        if (!safeName) {
            return ''
        }
        String script = "printenv ${safeName} || true"
        return steps.sh(script: script, returnStdout: true).trim()
    }

    private static Map vaultTokenBinding(String id, String envVar) {
        String variable = envVar ?: 'VAULT_TOKEN'
        return [$class: 'VaultTokenCredentialBinding', credentialsId: id, tokenVariable: variable]
    }

    private static boolean shouldUseVaultTokenFallback(String lowerType, String envVar, String id) {
        if ('string'.equals(lowerType)) {
            if (envVar?.toUpperCase()?.contains('VAULT_TOKEN')) {
                return true
            }
            if (id?.toLowerCase()?.contains('vault')) {
                return true
            }
        }
        return false
    }
}
