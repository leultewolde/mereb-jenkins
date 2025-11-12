import org.mereb.ci.helm.HelmDeployer

def call(Map args = [:]) {
    new HelmDeployer(this).deploy(args)
}
