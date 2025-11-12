package org.mereb.ci.helm

import static org.mereb.ci.util.PipelineUtils.shellEscape

/**
 * Builds and executes helm upgrade/install commands so the vars entrypoint stays slim.
 */
class HelmDeployer implements Serializable {

    private final def steps

    HelmDeployer(def steps) {
        this.steps = steps
    }

    void deploy(Map args = [:]) {
        String release = requireArg(args, 'release')
        String chart = requireArg(args, 'chart')
        String namespace = (args.namespace ?: 'default').toString()
        String timeout = (args.timeout ?: '10m').toString()
        boolean wait = args.containsKey('wait') ? args.wait as Boolean : true
        boolean atomic = args.containsKey('atomic') ? args.atomic as Boolean : true
        String repo = args.repo ? args.repo.toString() : null
        String version = args.version ? args.version.toString() : null
        String kubeContext = args.kubeContext ? args.kubeContext.toString() : null
        String kubeconfig = args.kubeconfig ? args.kubeconfig.toString() : null
        String repoUsername = args.repoUsername ? args.repoUsername.toString() : null
        String repoPassword = args.repoPassword ? args.repoPassword.toString() : null
        List valuesFiles = normalizeList(args.valuesFiles)
        Map setMap = args.set instanceof Map ? args.set : [:]
        Map setStringMap = args.setString instanceof Map ? args.setString : [:]
        Map setFileMap = args.setFile instanceof Map ? args.setFile : [:]
        List extraArgs = normalizeList(args.extraArgs)

        List<String> cmd = []
        cmd << "helm upgrade --install ${shellEscape(release)} ${shellEscape(chart)}"
        cmd << "--namespace ${shellEscape(namespace)}"
        cmd << '--create-namespace'
        cmd << "--timeout ${shellEscape(timeout)}"
        if (wait) {
            cmd << '--wait'
        }
        if (atomic) {
            cmd << '--atomic'
        }
        if (repo) {
            cmd << "--repo ${shellEscape(repo)}"
        }
        if (version) {
            cmd << "--version ${shellEscape(version)}"
        }
        if (repoUsername) {
            cmd << "--username ${shellEscape(repoUsername)}"
        }
        if (repoPassword) {
            cmd << "--password ${shellEscape(repoPassword)}"
            cmd << '--pass-credentials'
        }
        if (kubeContext) {
            cmd << "--kube-context ${shellEscape(kubeContext)}"
        }

        valuesFiles.each { file ->
            if (!file) {
                return
            }
            String path = file.toString()
            if (steps.fileExists(path)) {
                cmd << "-f ${shellEscape(path)}"
            } else {
                steps.echo "helmDeploy: values file '${path}' not found; skipping."
            }
        }

        setMap.each { k, v ->
            if (k && v != null) {
                cmd << "--set ${shellEscape("${k}=${v}")}"
            }
        }

        setStringMap.each { k, v ->
            if (k && v != null) {
                cmd << "--set-string ${shellEscape("${k}=${v}")}"
            }
        }

        setFileMap.each { k, v ->
            if (k && v != null) {
                cmd << "--set-file ${shellEscape("${k}=${v}")}"
            }
        }

        extraArgs.each { arg ->
            if (arg) {
                cmd << arg.toString()
            }
        }

        Closure runner = {
            steps.sh cmd.join(' ')
        }

        if (kubeconfig) {
            steps.withEnv(["KUBECONFIG=${kubeconfig}"]) {
                runner()
            }
        } else {
            runner()
        }
    }

    private static List normalizeList(Object raw) {
        if (raw instanceof List) {
            return raw
        }
        if (raw == null) {
            return []
        }
        return [raw]
    }

    private String requireArg(Map args, String key) {
        def value = args[key]
        if (!value) {
            steps.error "helmDeploy: missing required argument '${key}'"
        }
        return value.toString()
    }
}
