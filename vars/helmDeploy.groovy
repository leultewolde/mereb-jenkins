import com.cloudbees.groovy.cps.NonCPS

def call(Map args = [:]) {
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
    List valuesFiles = args.valuesFiles instanceof List ? args.valuesFiles : (args.valuesFiles ? [args.valuesFiles] : [])
    Map setMap = args.set instanceof Map ? args.set : [:]
    Map setStringMap = args.setString instanceof Map ? args.setString : [:]
    Map setFileMap = args.setFile instanceof Map ? args.setFile : [:]
    List extraArgs = args.extraArgs instanceof List ? args.extraArgs : (args.extraArgs ? [args.extraArgs] : [])

    List<String> cmd = []
    cmd << "helm upgrade --install ${q(release)} ${q(chart)}"
    cmd << "--namespace ${q(namespace)}"
    cmd << "--create-namespace"
    cmd << "--timeout ${q(timeout)}"
    if (wait) {
        cmd << "--wait"
    }
    if (atomic) {
        cmd << "--atomic"
    }
    if (repo) {
        cmd << "--repo ${q(repo)}"
    }
    if (version) {
        cmd << "--version ${q(version)}"
    }
    if (kubeContext) {
        cmd << "--kube-context ${q(kubeContext)}"
    }

    valuesFiles.each { file ->
        if (!file) {
            return
        }
        String path = file.toString()
        if (fileExists(path)) {
            cmd << "-f ${q(path)}"
        } else {
            echo "helmDeploy: values file '${path}' not found; skipping."
        }
    }

    setMap.each { k, v ->
        if (k && v != null) {
            cmd << "--set ${q("${k}=${v}")}"
        }
    }

    setStringMap.each { k, v ->
        if (k && v != null) {
            cmd << "--set-string ${q("${k}=${v}")}"
        }
    }

    setFileMap.each { k, v ->
        if (k && v != null) {
            cmd << "--set-file ${q("${k}=${v}")}"
        }
    }

    extraArgs.each { arg ->
        if (arg) {
            cmd << arg.toString()
        }
    }

    Closure runner = {
        sh cmd.join(' ')
    }

    if (kubeconfig) {
        withEnv(["KUBECONFIG=${kubeconfig}"]) {
            runner()
        }
    } else {
        runner()
    }
}

private static String requireArg(Map args, String key) {
    def value = args[key]
    if (!value) {
        error "helmDeploy: missing required argument '${key}'"
    }
    return value.toString()
}

@NonCPS
private static String q(String s) {
    return "'${s.replace("'", "'\"'\"'")}'"
}
