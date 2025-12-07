package org.mereb.ci.util

import org.mereb.ci.credentials.CredentialHelper

/**
 * Wraps Vault-specific credential resolution so every caller picks the same VAULT_ADDR and bindings.
 */
class VaultCredentialHelper implements Serializable {

    private final def steps
    private final CredentialHelper credentialHelper

    VaultCredentialHelper(def steps, CredentialHelper credentialHelper) {
        this.steps = steps
        this.credentialHelper = credentialHelper
    }

    VaultContext prepare(Map cfg) {
        String address = resolveVaultAddress(cfg)
        List<Map> bindings = credentialHelper.bindingsFor(cfg, address)
        return new VaultContext(address, bindings)
    }

    void withVaultEnv(String vaultAddress, Closure body) {
        if (vaultAddress?.trim()) {
            steps.withEnv(["VAULT_ADDR=${vaultAddress}"], body)
        } else {
            body()
        }
    }

    String resolveVaultAddress(Map envCfg) {
        String envAddr = (steps?.env?.VAULT_ADDR ?: '')?.toString()?.trim()
        if (envAddr) {
            String normalized = normalizeVaultAddress(envAddr)
            if (normalized) {
                return normalized
            }
        }
        if (envCfg?.vault instanceof Map) {
            String url = (envCfg.vault.url ?: envCfg.vault.baseUrl ?: '').toString().trim()
            String normalized = normalizeVaultAddress(url)
            if (normalized) {
                return normalized
            }
        }
        if (envCfg?.valuesTemplates instanceof List) {
            for (Object entry : envCfg.valuesTemplates) {
                if (!(entry instanceof Map)) {
                    continue
                }
                Map templateCfg = entry as Map
                Map vault = templateCfg.vault instanceof Map ? templateCfg.vault as Map : [:]
                String url = (vault.url ?: vault.baseUrl ?: '').toString().trim()
                String normalized = normalizeVaultAddress(url)
                if (normalized) {
                    return normalized
                }
            }
        }
        return null
    }

    private static String normalizeVaultAddress(String raw) {
        if (!raw?.trim()) {
            return null
        }
        String addr = raw.trim()
        if (!addr.contains('://')) {
            addr = "https://${addr}"
        }
        return addr.replaceAll(/\/+$/, '')
    }

    static class VaultContext implements Serializable {
        final String address
        final List<Map> bindings

        VaultContext(String address, List<Map> bindings) {
            this.address = address
            this.bindings = bindings ?: []
        }
    }
}
