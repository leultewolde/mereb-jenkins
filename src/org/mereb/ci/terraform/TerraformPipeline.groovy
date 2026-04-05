package org.mereb.ci.terraform

import org.mereb.ci.Helpers
import org.mereb.ci.credentials.CredentialHelper
import org.mereb.ci.util.StageExecutor

import static org.mereb.ci.util.PipelineUtils.mapToEnvList
import static org.mereb.ci.util.PipelineUtils.resolveEnvVar
import static org.mereb.ci.util.PipelineUtils.shellEscape

/**
 * Wraps terraform init/plan/apply automation, including deferred environments.
 */
class TerraformPipeline implements Serializable {

    private final def steps
    private final CredentialHelper credentialHelper
    private final StageExecutor stageExecutor
    private final Closure approvalHandler
    private final TerraformVerifier verifier

    TerraformPipeline(def steps, CredentialHelper credentialHelper, StageExecutor stageExecutor, Closure approvalHandler) {
        this.steps = steps
        this.credentialHelper = credentialHelper
        this.stageExecutor = stageExecutor ?: new StageExecutor(steps, credentialHelper)
        this.approvalHandler = approvalHandler
        this.verifier = new TerraformVerifier(steps)
    }

    List<String> run(Map tfCfg, List<String> overrideOrder = null, boolean captureDeferred = false, Closure afterEnvCallback = null) {
        List<String> deferred = []
        if (!tfCfg?.enabled) return deferred
        Map envs = tfCfg.environments ?: [:]
        if (!envs || envs.isEmpty()) return deferred

        List<String> order = overrideOrder ?: (tfCfg.order ?: [])
        if (!order || order.isEmpty()) {
            order = envs.keySet().collect { it.toString() }
        }
        String basePath = (tfCfg.path ?: 'infra/platform/terraform').toString()
        String binary = resolveTerraformBinary(tfCfg)
        List<String> globalEnv = mapToEnvList(tfCfg.env)
        String pluginCacheDir = resolvePluginCacheDir(tfCfg)
        if (pluginCacheDir) {
            globalEnv << "TF_PLUGIN_CACHE_DIR=${pluginCacheDir}"
        }
        Map<String, String> globalBackend = tfCfg.backend ?: [:]
        List<String> completed = []

        order.each { String envName ->
            Map envCfg = envs[envName]
            if (!envCfg) {
                return
            }
            if (!Helpers.matchCondition(envCfg.when as String, steps.env)) {
                if (captureDeferred && shouldDeferTerraform(envCfg.when as String)) {
                    steps.echo "Queue terraform '${envCfg.displayName}' for deferred execution — condition '${envCfg.when}' not yet met"
                    deferred << envName
                } else {
                    steps.echo "Skip terraform '${envCfg.displayName}' — condition '${envCfg.when}' not met"
                }
                return
            }

            List<String> envList = []
            envList.addAll(globalEnv)
            envList.addAll(mapToEnvList(envCfg.env))
            String vaultAddress = resolveVaultAddress(tfCfg, envCfg)

            Map<String, String> backend = [:]
            backend.putAll(globalBackend ?: [:])
            backend.putAll(envCfg.backend ?: [:])

            List<Map> bindings = credentialHelper.bindingsFor(envCfg, vaultAddress)
            Closure rollout = {
                stageExecutor.run("Terraform ${envCfg.displayName}", envList, bindings) {
                    steps.dir(basePath) {
                        if (pluginCacheDir) {
                            steps.sh "mkdir -p ${shellEscape(pluginCacheDir)}"
                        }
                        Map<String, String> combinedVars = [:]
                        combinedVars.putAll(envCfg.vars ?: [:])
                        String kubeEnvVar = envCfg.kubeconfigEnv ?: 'KUBECONFIG'
                        String kubePath = resolveEnvVar(steps, kubeEnvVar)
                        if (kubePath?.trim() && !combinedVars.containsKey('kubeconfig_path')) {
                            combinedVars['kubeconfig_path'] = kubePath.trim()
                        }
                        if (envCfg.kubeContext && !combinedVars.containsKey('kube_context')) {
                            combinedVars['kube_context'] = envCfg.kubeContext
                        }

                        Closure commands = {
                            if (!envList.isEmpty()) {
                                steps.withEnv(envList) {
                                    runTerraformCommands(binary, tfCfg, envCfg, backend, combinedVars)
                                }
                            } else {
                                runTerraformCommands(binary, tfCfg, envCfg, backend, combinedVars)
                            }
                        }
                        String envPath = envCfg.path ?: '.'
                        if (envPath && envPath != '.' && envPath != './') {
                            steps.dir(envPath) {
                                commands()
                            }
                        } else {
                            commands()
                        }
                    }
                }

                if (envCfg.verify?.resources) {
                    stageExecutor.run("Verify ${envCfg.displayName}", envList, bindings) {
                        verifier.run(envCfg)
                    }
                }

                Map smoke = envCfg.smoke ?: [:]
                if (smoke.url || smoke.script || smoke.command) {
                    steps.stage("Smoke ${envCfg.displayName}") {
                        Map payload = [:]
                        payload.putAll(smoke)
                        payload.environment = envCfg.displayName
                        List<Map> smokeBindings = credentialHelper.bindingsFor(envCfg, vaultAddress)
                        Closure smokeRun = {
                            steps.runSmoke(payload)
                        }
                        credentialHelper.withOptionalCredentials(smokeBindings, smokeRun)
                    }
                }
            }

            withTerraformLock(envCfg, rollout)

            completed << envName
        }

        if (afterEnvCallback && !completed.isEmpty()) {
            completed.each { String envName ->
                afterEnvCallback.call(envName)
            }
        }

        return deferred
    }

    boolean shouldDeferTerraform(String condition) {
        if (!condition) {
            return false
        }
        String trimmed = condition.trim()
        return trimmed.startsWith('tag=') || trimmed.contains(' tag=')
    }

    private void withTerraformLock(Map envCfg, Closure body) {
        String resource = envCfg?.lock?.resource?.toString()?.trim()
        if (!resource) {
            body.call()
            return
        }
        steps.echo "Waiting for Terraform lock resource '${resource}' before running ${envCfg.displayName}"
        try {
            steps.lock(resource: resource) {
                body.call()
            }
        } catch (MissingMethodException | NoSuchMethodError err) {
            steps.error("terraform lock: Jenkins 'lock' step is unavailable for resource '${resource}'. Install/configure the Lockable Resources plugin.")
        }
    }

    private String resolveVaultAddress(Map tfCfg, Map envCfg) {
        String raw = firstNonBlank(
            envVarFromMap(envCfg?.env, 'VAULT_ADDR'),
            vaultUrl(envCfg?.vault),
            envVarFromMap(tfCfg?.env, 'VAULT_ADDR'),
            vaultUrl(tfCfg?.vault),
            resolveEnvVar(steps, 'VAULT_ADDR')
        )
        return normalizeVaultAddress(raw)
    }

    private static String envVarFromMap(Object envCfg, String name) {
        if (!(envCfg instanceof Map) || !name) {
            return null
        }
        Object value = (envCfg as Map).get(name)
        return value == null ? null : value.toString()
    }

    private static String vaultUrl(Object vaultCfg) {
        if (!(vaultCfg instanceof Map)) {
            return null
        }
        Object value = (vaultCfg as Map).get('url') ?: (vaultCfg as Map).get('baseUrl')
        return value == null ? null : value.toString()
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value?.trim()) {
                return value.trim()
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

    private void runTerraformCommands(String binary, Map tfCfg, Map envCfg, Map backend, Map vars) {
        String planOut = envCfg.planOut
        try {
            runTerraformInit(binary, tfCfg.initArgs, envCfg.initArgs, backend)
            if (envCfg.workspace) {
                selectTerraformWorkspace(binary, envCfg.workspace.toString())
            }
            runTerraformPrePlanHooks(envCfg.prePlan)
            runTerraformPlan(binary, tfCfg.planArgs, envCfg.planArgs, envCfg.varFiles, vars, planOut)
            if (envCfg.apply as Boolean) {
                runTerraformApply(binary, tfCfg.applyArgs, envCfg.applyArgs, planOut, envCfg.autoApply as Boolean)
            } else {
                steps.echo "Terraform apply skipped for ${envCfg.displayName}"
            }
        } finally {
            cleanupTerraformArtifacts(planOut)
        }
    }

    private void runTerraformInit(String binary, List<String> globalArgs, List<String> envArgs, Map backend) {
        List<String> cmd = []
        cmd << shellEscape(binary)
        cmd << 'init'
        (globalArgs ?: []).each { cmd << it.toString() }
        (envArgs ?: []).each { cmd << it.toString() }
        (backend ?: [:]).each { k, v ->
            cmd << "-backend-config=${shellEscape("${k}=${v}")}"
        }
        steps.sh cmd.join(' ')
    }

    private void selectTerraformWorkspace(String binary, String workspace) {
        if (!workspace?.trim()) {
            return
        }
        String bin = shellEscape(binary)
        String ws = shellEscape(workspace)
        String selectCmd = "${bin} workspace select ${ws} || ${bin} workspace new ${ws}"
        steps.sh selectCmd
    }

    private void runTerraformPlan(String binary, List<String> globalArgs, List<String> envArgs, List<String> varFiles, Map vars, String planOut) {
        List<String> cmd = []
        cmd << shellEscape(binary)
        cmd << 'plan'
        (globalArgs ?: []).each { cmd << it.toString() }
        (envArgs ?: []).each { cmd << it.toString() }
        (varFiles ?: []).each { file ->
            cmd << "-var-file=${shellEscape(file.toString())}"
        }
        (vars ?: [:]).each { k, v ->
            cmd << "-var=${shellEscape("${k}=${v}")}"
        }
        if (planOut) {
            cmd << "-out=${shellEscape(planOut)}"
        }
        steps.sh cmd.join(' ')
    }

    private void runTerraformPrePlanHooks(List<String> commands) {
        List<String> hooks = (commands ?: []).findAll { it?.trim() }
        hooks.each { String command ->
            steps.sh command
        }
    }

    private void runTerraformApply(String binary, List<String> globalArgs, List<String> envArgs, String planOut, boolean autoApprove) {
        List<String> cmd = []
        cmd << shellEscape(binary)
        cmd << 'apply'
        (globalArgs ?: []).each { cmd << it.toString() }
        (envArgs ?: []).each { cmd << it.toString() }
        if (autoApprove && !cmd.any { it.contains('-auto-approve') }) {
            cmd << '-auto-approve'
        }
        if (planOut) {
            cmd << shellEscape(planOut)
        }
        steps.sh cmd.join(' ')
    }

    private void cleanupTerraformArtifacts(String planOut) {
        if (planOut?.trim()) {
            steps.sh "rm -f ${shellEscape(planOut)}"
        }
        steps.sh 'rm -rf .terraform'
        if (steps.fileExists('.terraform.lock.hcl')) {
            steps.sh 'git checkout -- .terraform.lock.hcl || true'
        }
    }

    private String resolveTerraformBinary(Map tfCfg) {
        String binary = (tfCfg.binary ?: 'terraform').toString()
        if (!binary?.trim()) {
            binary = 'terraform'
        }
        if (binary.startsWith('/')) {
            return binary
        }
        if (binary.contains('/') || binary.contains('\\')) {
            String ws = steps.pwd()
            return "${ws.replaceAll(/\/+$/, '')}/${binary}"
        }
        if (tfCfg.autoInstall as Boolean) {
            if (terraformExecutableExists(binary)) {
                return binary
            }
            return installTerraformBinary(tfCfg)
        }
        return binary
    }

    private boolean terraformExecutableExists(String binary) {
        String script
        if (!binary) {
            return false
        }
        if (binary.contains('/') || binary.contains('\\')) {
            script = "[ -x ${shellEscape(binary)} ] && echo yes || echo no"
        } else {
            script = "command -v ${shellEscape(binary)} >/dev/null 2>&1 && echo yes || echo no"
        }
        return steps.sh(script: script, returnStdout: true).trim() == 'yes'
    }

    private String installTerraformBinary(Map tfCfg) {
        String installDir = resolveTerraformInstallDir(tfCfg)
        String targetPath = "${installDir}/terraform"
        if (terraformExecutableExists(targetPath)) {
            return targetPath
        }

        String version = (tfCfg.version ?: '1.6.6').toString()
        Map platform = detectTerraformPlatform()
        String archiveUrl = "https://releases.hashicorp.com/terraform/${version}/terraform_${version}_${platform.os}_${platform.arch}.zip"
        String zipPath = "${installDir}/terraform_${version}.zip"

        steps.echo "Terraform binary not found. Installing Terraform ${version} to ${targetPath}"
        steps.sh "mkdir -p ${shellEscape(installDir)}"
        steps.sh """
            set -e
            curl -fsSL ${shellEscape(archiveUrl)} -o ${shellEscape(zipPath)}
            unzip -o ${shellEscape(zipPath)} -d ${shellEscape(installDir)}
            rm -f ${shellEscape(zipPath)}
            chmod +x ${shellEscape(targetPath)}
        """
        return targetPath
    }

    private String resolveTerraformInstallDir(Map tfCfg) {
        String dir = (tfCfg.installDir ?: '.ci/bin').toString()
        if (dir.startsWith('/')) {
            return dir
        }
        String ws = steps.pwd()
        return "${ws.replaceAll(/\/+$/, '')}/${dir}"
    }

    private String resolvePluginCacheDir(Map tfCfg) {
        String dir = (tfCfg.pluginCacheDir ?: '').toString().trim()
        if (!dir) {
            return ''
        }
        if (dir.startsWith('/')) {
            return dir
        }
        String ws = steps.pwd()
        return "${ws.replaceAll(/\/+$/, '')}/${dir}"
    }

    private Map detectTerraformPlatform() {
        String os = System.getProperty('os.name')?.toLowerCase() ?: ''
        String arch = System.getProperty('os.arch')?.toLowerCase() ?: ''

        String osLabel
        if (os.contains('linux')) {
            osLabel = 'linux'
        } else if (os.contains('mac') || os.contains('darwin')) {
            osLabel = 'darwin'
        } else if (os.contains('windows')) {
            osLabel = 'windows'
        } else {
            throw new RuntimeException("Unsupported operating system '${os}' for Terraform auto-install.")
        }

        String archLabel
        switch (arch) {
            case ['amd64', 'x86_64']:
                archLabel = 'amd64'
                break
            case ['arm64', 'aarch64']:
                archLabel = 'arm64'
                break
            default:
                throw new RuntimeException("Unsupported architecture '${arch}' for Terraform auto-install.")
        }

        return [os: osLabel, arch: archLabel]
    }
}
