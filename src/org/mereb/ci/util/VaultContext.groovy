package org.mereb.ci.util

/**
 * Simple tuple combining a Vault endpoint and the credential bindings that should use it.
 */
class VaultContext implements Serializable {

    final String address
    final List<Map> bindings

    VaultContext(String address, List<Map> bindings) {
        this.address = address
        this.bindings = bindings ?: []
    }
}
