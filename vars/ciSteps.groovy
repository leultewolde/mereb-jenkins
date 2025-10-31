def withPnpm(body) {
  nodejs('node20') {
    sh 'corepack enable'
    sh 'pnpm -v || npm install -g pnpm@9'
    body()
  }
}

def dockerLogin() {
  String registryRaw = (env.REGISTRY ?: '').trim()
  registryRaw = registryRaw.replaceFirst('^https?://', '')
  String registryHost = registryRaw
  int slashIdx = registryRaw.indexOf('/')
  if (slashIdx > 0) {
    registryHost = registryRaw.substring(0, slashIdx)
  }
  if (!registryHost) {
    registryHost = 'registry.leultewolde.com'
  }

  withCredentials([usernamePassword(credentialsId: 'docker-registry-local', usernameVariable: 'DU', passwordVariable: 'DP')]) {
    def hostSegment = registryHost ? " ${registryHost}" : ''
    sh "echo \$DP | docker login${hostSegment} -u \$DU --password-stdin"
  }
}

def buildxInit() {
  sh '''
    docker buildx inspect ci-builder >/dev/null 2>&1 || docker buildx create --use --name ci-builder
    docker buildx use ci-builder
  '''
}

def helmEnv(String envName) {
  withCredentials([file(credentialsId: "kubeconfig-${envName}", variable: 'KCFG')]) {
    sh 'mkdir -p $HOME/.kube'
    sh 'cp "$KCFG" $HOME/.kube/config'
  }
}

def argoLogin(String envName) {
  withCredentials([string(credentialsId: "ARGOCD_TOKEN_${envName.toUpperCase()}", variable: 'ATOK')]) {
    sh """
      argocd login ${envName}.argocd.internal --grpc-web --insecure --auth-token $ATOK || true
    """
  }
}

def s3Sync(String fromDir, String toUri) {
  withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: 'CLOUD_CDN_S3']]) {
    sh "aws s3 sync '${fromDir}' '${toUri}' --delete --cache-control max-age=31536000,immutable"
  }
}

def approvalGate(String message = 'Proceed?') {
  timeout(time: 30, unit: 'MINUTES') {
    input message: message, ok: 'Deploy'
  }
}

return this
