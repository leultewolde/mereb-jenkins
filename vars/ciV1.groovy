// vars/ciV1.groovy
//
// Entry point for YAML-driven pipelines (version 1).
// Usage from a repo Jenkinsfile:
//   @Library('hidmo-ci-lib@v1') _
//   ciV1()
//
// Schema summary (ci.yml):
// version: 1
// preset: node | java-gradle
// agent: { label: "...", docker: "node:20" }   # optional
// env: { KEY: "VALUE" }                        # optional
// matrix: { group: [ "npm \"ci && npm run lint\"", "gradle.build" ] }  # optional
// deploy:
//   <env>:
//     when: "branch=main & !pr"                # optional
//     approve: "user:leul" | "group:release-managers"
//     steps: [ "docker.build tag=org/app:${GIT_COMMIT}", "k8s.apply file=k8s/" ]
//
def call(Map args = [:]) {
    // --- Load config -----------------------------------------------------------
    def cfg = null
    def baseEnv = [] as List<String>

    node(args?.bootstrapLabel ?: '') {
        checkout scm
        if (!fileExists('ci.yml')) error "ci.yml not found at repo root"
        cfg = readYaml(file: 'ci.yml') ?: [:]
        if ((cfg.version as Integer) != 1) error "ci.yml version must be 1"
        baseEnv = (cfg.env ?: [:]).collect { k, v -> "${k}=${v}" }
    }

    // -------------------- Resolve agent/env from cfg -----------------------------------
    def label       = (cfg.agent?.label ?: '').trim()
    def dockerImage = (cfg.agent?.docker ?: '').trim()

    // -------------------- Core body to run inside chosen agent -------------------------
    def runCore = {
        withEnv(baseEnv) {
            // 1) Build & Test from preset
            runPreset(cfg)

            // 2) Matrix (parallel optional)
            runMatrix(cfg.matrix)

            // 3) Deploys (conditional + approvals)
            runDeploys(cfg.deploy)
        }
    }


    // -------------------- Agent selection & proper checkout ----------------------------
    if (dockerImage && label) {
        node(label) {
            // checkout on the actual build node so workspace exists on host
            checkout scm
            // then run the pipeline inside the container with mounted workspace
            docker.image(dockerImage).inside {
                runCore()
            }
        }
    } else if (dockerImage) {
        node {
            checkout scm
            docker.image(dockerImage).inside {
                runCore()
            }
        }
    } else if (label) {
        node(label) {
            checkout scm
            runCore()
        }
    } else {
        node {
            checkout scm
            runCore()
        }
    }
}

// --------------------------- PRESET ------------------------------------------
private void runPreset(cfg) {
    def preset = (cfg.preset ?: 'node') as String
    stage("Preset: ${preset}") {
        switch (preset) {
            case 'node':
                runVerb("node.build")
                runVerb("node.test")
                break
            case 'java-gradle':
                runVerb("gradle.build")
                runVerb("gradle.test")
                break
            default:
                error "Unknown preset '${preset}'. Allowed: node, java-gradle"
        }
    }
}

// --------------------------- MATRIX ------------------------------------------
private void runMatrix(Map matrix) {
    if (!matrix || matrix.isEmpty()) return
    matrix.each { groupName, steps ->
        stage("Matrix: ${groupName}") {
            Map branches = [:]
            steps.each { s ->
                def title = titleForStep(s)
                branches[title] = {
                    runVerb(s as String)
                }
            }
            parallel branches
        }
    }
}

private String titleForStep(Object s) {
    def raw = s.toString().trim()
    if (raw.startsWith("sh ")) return raw.substring(3).take(30)
    return raw.take(30)
}

// --------------------------- DEPLOY ------------------------------------------
private void runDeploys(Map deploy) {
    if (!deploy || deploy.isEmpty()) return
    deploy.each { envName, d ->
        if (!shouldRun(d?.when as String)) {
            echo "Skip deploy '${envName}' — condition '${d?.when ?: "none"}' not met"
            return
        }
        stage("Deploy: ${envName}") {
            // approvals (optional)
            if (d?.approve) {
                requireApproval(d.approve as String, envName)
            }
            // steps
            (d.steps ?: []).each { s ->
                stage("Deploy: ${envName} • ${titleForStep(s)}") {
                    runVerb(s as String)
                }
            }
        }
    }
}

// --------------------------- VERBS -------------------------------------------
// Supported verbs (string commands):
// - "npm \"<args>\""                  -> npm <args>
// - "node.build"                      -> npm ci && npm run build
// - "node.test"                       -> npm test -- --ci
// - "gradle.build"                    -> ./gradlew build --no-daemon
// - "gradle.test"                     -> ./gradlew test --no-daemon
// - "gradle.publish"                  -> ./gradlew publish --no-daemon
// - "docker.build tag=<tag> [file=.]" -> docker build -t <tag> <file>
// - "docker.push tag=<tag>"           -> docker push <tag>
// - "k8s.apply file=<path>"           -> kubectl apply -f <path>
// - "sh \"<command>\""                -> raw shell
private void runVerb(String spec) {
    spec = spec.trim()
    if (spec.startsWith('sh ')) {
        def cmd = unquote(spec.substring(3).trim())
        sh cmd
        return
    }
    if (spec.startsWith('npm ')) {
        def args = unquote(spec.substring(4).trim())
        sh "npm ${args}"
        return
    }
    if (spec == 'node.build') {
        sh "npm ci && npm run build"
        return
    }
    if (spec == 'node.test') {
        sh "npm test -- --ci"
        return
    }
    if (spec == 'gradle.build') {
        sh "./gradlew build --no-daemon"
        return
    }
    if (spec == 'gradle.test') {
        sh "./gradlew test --no-daemon"
        return
    }
    if (spec == 'gradle.publish') {
        sh "./gradlew publish --no-daemon"
        return
    }
    if (spec.startsWith('docker.build')) {
        def kv = parseKVs(spec - 'docker.build')
        def tag = kv['tag'] ?: error("docker.build requires tag=<tag>")
        def file = kv['file'] ?: '.'
        sh "docker build -t ${shellEscape(tag)} ${shellEscape(file)}"
        return
    }
    if (spec.startsWith('docker.push')) {
        def kv = parseKVs(spec - 'docker.push')
        def tag = kv['tag'] ?: error("docker.push requires tag=<tag>")
        sh "docker push ${shellEscape(tag)}"
        return
    }
    if (spec.startsWith('k8s.apply')) {
        def kv = parseKVs(spec - 'k8s.apply')
        def file = kv['file'] ?: error("k8s.apply requires file=<path>")
        sh "kubectl apply -f ${shellEscape(file)}"
        return
    }
    error "Unknown verb: '${spec}'"
}

// --------------------------- UTILS -------------------------------------------
@NonCPS
private String unquote(String s) {
    s = s.trim()
    if ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith("'") && s.endsWith("'"))) {
        return s.substring(1, s.length()-1)
    }
    return s
}

@NonCPS
private Map parseKVs(String tail) {
    def m = [:]
    tail.trim().split(/\s+/).findAll { it.contains('=') }.each { p ->
        def i = p.indexOf('=')
        def k = p.substring(0, i).trim()
        def v = p.substring(i+1).trim()
        m[k] = unquote(v)
    }
    return m
}

@NonCPS
private String shellEscape(String s) {
    // naive but safe for tags/paths without newlines
    return "'${s.replace("'", "'\"'\"'")}'"
}

private void requireApproval(String approve, String envName) {
    // approve: "user:<id>" | "group:<name>"
    def who = approve?.trim() ?: ''
    input message: "Deploy '${envName}' requires approval (${who}). Proceed?", ok: "Approve"
}

private boolean shouldRun(String cond) {
    return org._hidmo.Helpers.matchCondition(cond, env)
}