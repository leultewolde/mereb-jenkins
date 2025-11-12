import org.mereb.ci.smoke.SmokeRunner

def call(Map args = [:]) {
    new SmokeRunner(this).run(args)
}
