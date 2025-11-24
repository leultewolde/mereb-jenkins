import org.junit.jupiter.api.Test
import org.mereb.ci.credentials.CredentialHelper

import static org.junit.jupiter.api.Assertions.*

class CredentialHelperTest {

    @Test
    void "uses vault token binding when type is vaultToken"() {
        CredentialHelper helper = new CredentialHelper(null)

        List<Map> bindings = helper.bindingsFor([credentials: [[type: 'vaultToken', id: 'vault-cred', env: 'VAULT_TOKEN']]], 'https://vault.leultewolde.com')

        assertEquals(1, bindings.size())
        assertEquals('VaultTokenCredentialBinding', bindings[0].$class)
        assertEquals('VAULT_TOKEN', bindings[0].tokenVariable)
        assertEquals('vault-cred', bindings[0].credentialsId)
        assertEquals('https://vault.leultewolde.com', bindings[0].vaultAddr)
    }

    @Test
    void "falls back to vault token binding when env is VAULT_TOKEN even if type is string"() {
        CredentialHelper helper = new CredentialHelper(null)

        List<Map> bindings = helper.bindingsFor([credentials: [[type: 'string', id: 'vault-credentials', env: 'VAULT_TOKEN']]], 'https://vault.leultewolde.com')

        assertEquals(1, bindings.size())
        assertEquals('VaultTokenCredentialBinding', bindings[0].$class)
        assertEquals('VAULT_TOKEN', bindings[0].tokenVariable)
        assertEquals('vault-credentials', bindings[0].credentialsId)
        assertEquals('https://vault.leultewolde.com', bindings[0].vaultAddr)
    }

    @Test
    void "keeps string binding for non-vault secrets"() {
        CredentialHelper helper = new CredentialHelper(null)

        List<Map> bindings = helper.bindingsFor([credentials: [[type: 'string', id: 'api-key', env: 'API_KEY']]])

        assertEquals(1, bindings.size())
        assertEquals('StringBinding', bindings[0].$class)
        assertEquals('API_KEY', bindings[0].variable)
    }
}
