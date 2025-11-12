package org.mereb.ci.util

/**
 * Wraps ad-hoc Jenkins step helpers (pnpm setup, docker login, etc.) so vars stay declarative.
 */
class StepsHelper implements Serializable {

    private final def steps

    StepsHelper(def steps) {
        this.steps = steps
    }

    void withPnpm(Closure body) {
        steps.nodejs('node20') {
            steps.sh 'corepack enable'
            steps.sh 'pnpm -v || npm install -g pnpm@9'
            body?.call()
        }
    }

    void dockerLogin(Map opts = [:]) {
        String registryRaw = (opts.registry ?: steps.env.REGISTRY ?: '').trim()
        registryRaw = registryRaw.replaceFirst('^https?://', '')
        String registryHost = registryRaw
        int slashIdx = registryRaw.indexOf('/')
        if (slashIdx > 0) {
            registryHost = registryRaw.substring(0, slashIdx)
        }
        if (!registryHost) {
            registryHost = opts.defaultRegistry ?: 'registry.leultewolde.com'
        }

        steps.withCredentials([
            steps.usernamePassword(credentialsId: opts.credentialId ?: 'docker-registry-local', usernameVariable: 'DU', passwordVariable: 'DP')
        ]) {
            def hostSegment = registryHost ? " ${registryHost}" : ''
            steps.sh "echo \$DP | docker login${hostSegment} -u \$DU --password-stdin"
        }
    }

    void buildxInit(String builder = 'ci-builder') {
        steps.sh """
            docker buildx inspect ${builder} >/dev/null 2>&1 || docker buildx create --use --name ${builder}
            docker buildx use ${builder}
        """
    }

    void configureHelmEnv(String envName) {
        steps.withCredentials([
            steps.file(credentialsId: "kubeconfig-${envName}", variable: 'KCFG')
        ]) {
            steps.sh 'mkdir -p $HOME/.kube'
            steps.sh 'cp "$KCFG" $HOME/.kube/config'
        }
    }

    void argoLogin(String envName) {
        steps.withCredentials([
            steps.string(credentialsId: "ARGOCD_TOKEN_${envName.toUpperCase()}", variable: 'ATOK')
        ]) {
            steps.sh """
              argocd login ${envName}.argocd.internal --grpc-web --insecure --auth-token $ATOK || true
            """
        }
    }

    void s3Sync(String fromDir, String toUri) {
        steps.withCredentials([
            [$class: 'AmazonWebServicesCredentialsBinding', credentialsId: 'CLOUD_CDN_S3']
        ]) {
            steps.sh "aws s3 sync '${fromDir}' '${toUri}' --delete --cache-control max-age=31536000,immutable"
        }
    }

    void approvalGate(String message = 'Proceed?', String ok = 'Deploy', int timeoutMinutes = 30) {
        steps.timeout(time: timeoutMinutes, unit: 'MINUTES') {
            steps.input message: message, ok: ok
        }
    }
}
