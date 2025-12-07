import org.junit.jupiter.api.Test
import org.mereb.ci.credentials.CredentialHelper
import org.mereb.ci.util.VaultCredentialHelper

import static org.junit.jupiter.api.Assertions.*

class VaultCredentialHelperTest {

    @Test
    void "prefers pipeline VAULT_ADDR"() {
        FakeSteps steps = new FakeSteps(env: [VAULT_ADDR: 'https://vault.example.com/'])
        VaultCredentialHelper helper = new VaultCredentialHelper(steps, new CredentialHelper(steps))

        assertEquals('https://vault.example.com', helper.resolveVaultAddress([:]))
    }

    @Test
    void "falls back to config vault url"() {
        FakeSteps steps = new FakeSteps()
        VaultCredentialHelper helper = new VaultCredentialHelper(steps, new CredentialHelper(steps))
        Map envCfg = [vault: [url: 'vault.internal']]

        assertEquals('https://vault.internal', helper.resolveVaultAddress(envCfg))
    }

    @Test
    void "uses values templates when vault config missing"() {
        FakeSteps steps = new FakeSteps()
        VaultCredentialHelper helper = new VaultCredentialHelper(steps, new CredentialHelper(steps))
        Map envCfg = [valuesTemplates: [[vault: [baseUrl: 'vault-template.local/']]]]

        assertEquals('https://vault-template.local', helper.resolveVaultAddress(envCfg))
    }

    @Test
    void "withVaultEnv sets VAULT_ADDR when available"() {
        FakeSteps steps = new FakeSteps()
        VaultCredentialHelper helper = new VaultCredentialHelper(steps, new CredentialHelper(steps))
        boolean executed = false

        helper.withVaultEnv('https://vault.example.com', {
            executed = true
        })

        assertTrue(executed)
        assertEquals(1, steps.envCalls.size())
        assertEquals(1, steps.envCalls.size())
        assertEquals(1, steps.envCalls[0].size())
        assertEquals('VAULT_ADDR=https://vault.example.com', steps.envCalls[0][0].toString())

        helper.withVaultEnv('', {
            executed = false
        })
        assertFalse(executed)
        assertEquals(1, steps.envCalls.size())
    }

    private static class FakeSteps {
        final Map env
        final List<List<String>> envCalls = []

        FakeSteps(Map args = [:]) {
            this.env = args.env ?: [:]
        }

        void withEnv(List<String> envList, Closure body) {
            envCalls << new ArrayList<>(envList)
            body?.call()
        }
    }
}
