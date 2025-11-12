import org.mereb.ci.util.StepsHelper

def helper = new StepsHelper(this)

def withPnpm(body) {
  helper.withPnpm(body)
}

def dockerLogin(Map opts = [:]) {
  helper.dockerLogin(opts)
}

def buildxInit() {
  helper.buildxInit()
}

def helmEnv(String envName) {
  helper.configureHelmEnv(envName)
}

def argoLogin(String envName) {
  helper.argoLogin(envName)
}

def s3Sync(String fromDir, String toUri) {
  helper.s3Sync(fromDir, toUri)
}

def approvalGate(String message = 'Proceed?') {
  helper.approvalGate(message)
}

return this
