package org.mereb.ci.terraform

import static org.mereb.ci.util.PipelineUtils.resolveEnvVar
import static org.mereb.ci.util.PipelineUtils.shellEscape

/**
 * Verifies post-apply Kubernetes resources declared in ci config.
 */
class TerraformVerifier implements Serializable {

    private final def steps

    TerraformVerifier(def steps) {
        this.steps = steps
    }

    void run(Map envCfg) {
        Map verifyCfg = envCfg?.verify instanceof Map ? (envCfg.verify as Map) : [:]
        List<Map> resources = verifyCfg.resources instanceof List ? (verifyCfg.resources as List<Map>) : []
        if (!resources || resources.isEmpty()) {
            return
        }
        String defaultTimeout = (verifyCfg.timeout ?: '180s').toString()
        String kubectlBase = buildKubectlBase(envCfg)

        resources.each { Map resource ->
            verifyResource(kubectlBase, resource, defaultTimeout)
        }
    }

    private void verifyResource(String kubectlBase, Map resource, String defaultTimeout) {
        String kind = resource.kind?.toString()?.trim()
        String name = resource.name?.toString()?.trim()
        String selector = resource.selector?.toString()?.trim()
        String namespace = resource.namespace?.toString()?.trim()
        String waitMode = (resource.wait ?: 'exists').toString().trim().toLowerCase()
        String timeout = (resource.timeout ?: defaultTimeout ?: '180s').toString().trim()
        boolean optional = resource.optional as Boolean

        if (!kind || (!name && !selector)) {
            steps.error("terraform verify: each resource requires 'kind' plus 'name' or 'selector'")
        }

        String getCmd = buildGetCommand(kubectlBase, kind, name, selector, namespace)
        String description = describe(kind, name, selector, namespace)
        String existing = steps.sh(script: getCmd, returnStdout: true).trim()
        if (!existing) {
            if (optional) {
                steps.echo("terraform verify: optional resource not present, skipping ${description}")
                return
            }
            steps.error("terraform verify: expected resource missing: ${description}")
        }

        switch (waitMode) {
            case 'exists':
                break
            case 'ready':
                steps.sh "${buildWaitCommand(kubectlBase, kind, name, selector, namespace)} --for=condition=Ready --timeout=${shellEscape(timeout)}"
                break
            case 'available':
                steps.sh "${buildWaitCommand(kubectlBase, kind, name, selector, namespace)} --for=condition=Available --timeout=${shellEscape(timeout)}"
                break
            case 'complete':
                steps.sh "${buildWaitCommand(kubectlBase, kind, name, selector, namespace)} --for=condition=Complete --timeout=${shellEscape(timeout)}"
                break
            default:
                steps.error("terraform verify: unsupported wait mode '${waitMode}' for ${description}")
        }
    }

    private String buildKubectlBase(Map envCfg) {
        List<String> parts = ['kubectl']
        String kubeconfigEnv = (envCfg?.kubeconfigEnv ?: 'KUBECONFIG').toString()
        String kubeconfigPath = resolveEnvVar(steps, kubeconfigEnv)?.trim()
        if (kubeconfigPath) {
            parts << "--kubeconfig"
            parts << shellEscape(kubeconfigPath)
        }
        String kubeContext = envCfg?.kubeContext?.toString()?.trim()
        if (kubeContext) {
            parts << "--context"
            parts << shellEscape(kubeContext)
        }
        return parts.join(' ')
    }

    private static String buildGetCommand(String kubectlBase, String kind, String name, String selector, String namespace) {
        List<String> parts = [kubectlBase]
        if (namespace) {
            parts << "-n"
            parts << shellEscape(namespace)
        }
        parts << 'get'
        parts << shellEscape(kind)
        if (name) {
            parts << shellEscape(name)
        }
        if (selector) {
            parts << '-l'
            parts << shellEscape(selector)
        }
        parts << '-o'
        parts << 'name'
        parts << '--ignore-not-found'
        return parts.join(' ')
    }

    private static String buildWaitCommand(String kubectlBase, String kind, String name, String selector, String namespace) {
        List<String> parts = [kubectlBase]
        if (namespace) {
            parts << "-n"
            parts << shellEscape(namespace)
        }
        parts << 'wait'
        parts << shellEscape(kind)
        if (name) {
            parts << shellEscape(name)
        }
        if (selector) {
            parts << '-l'
            parts << shellEscape(selector)
        }
        return parts.join(' ')
    }

    private static String describe(String kind, String name, String selector, String namespace) {
        List<String> parts = [kind]
        if (name) {
            parts << name
        }
        if (selector) {
            parts << "selector=${selector}"
        }
        if (namespace) {
            parts << "namespace=${namespace}"
        }
        return parts.join(' ')
    }
}
