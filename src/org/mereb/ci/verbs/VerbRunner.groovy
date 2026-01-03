package org.mereb.ci.verbs

import org.mereb.ci.release.NpmPublisher
import static org.mereb.ci.util.PipelineUtils.shellEscape

/**
 * Normalizes shorthand "verbs" used across ciV1 so Jenkins steps remain declarative.
 */
class VerbRunner implements Serializable {

    private final def steps

    VerbRunner(def steps) {
        this.steps = steps
    }

    void run(String specRaw) {
        String spec = (specRaw ?: '').trim()
        if (!spec) {
            steps.error('Verb cannot be empty')
        }

        if (spec.startsWith('sh ')) {
            steps.sh unquote(spec.substring(3).trim())
            return
        }
        if (spec.startsWith('npm ')) {
            steps.sh "npm ${unquote(spec.substring(4).trim())}"
            return
        }
        if (spec.startsWith('pnpm.publish') || spec.startsWith('npm.publish')) {
            Map<String, String> kv = parseKVs(spec.substring(spec.indexOf('publish') + 'publish'.length()))
            Map opts = [:]
            if (kv.packageDir) {
                opts.packageDir = kv.packageDir
            }
            if (kv.dir) {
                opts.packageDir = kv.dir
            }
            if (kv.registry) {
                opts.registry = kv.registry
            }
            if (kv.access) {
                opts.access = kv.access
            }
            if (kv.build) {
                opts.build = kv.build
            }
            if (kv.buildCommand) {
                opts.buildCommand = kv.buildCommand
            }
            if (kv.envFile) {
                opts.envFile = kv.envFile
            }
            if (kv.skipTagCheck) {
                opts.skipTagCheck = kv.skipTagCheck
            }
            new NpmPublisher(steps).publish(opts)
            return
        }

        switch (spec) {
            case 'node.install':
                steps.sh 'npm ci'
                return
            case 'node.build':
                steps.sh 'npm run build'
                return
            case 'node.test':
                steps.sh 'npm test -- --ci'
                return
            case 'node.lint':
                steps.sh 'npm run lint'
                return
            case 'gradle.build':
                steps.sh './gradlew build --no-daemon'
                return
            case 'gradle.test':
                steps.sh './gradlew test --no-daemon'
                return
            case 'gradle.publish':
                steps.sh './gradlew publish --no-daemon'
                return
        }

        if (spec.startsWith('docker.build')) {
            Map<String, String> kv = parseKVs(spec.substring('docker.build'.length()))
            String tag = kv['tag']
            String file = kv['file'] ?: '.'
            if (!tag) {
                steps.error('docker.build requires tag=<tag>')
            }
            steps.sh "docker build -t ${shellEscape(tag)} ${shellEscape(file)}"
            return
        }
        if (spec.startsWith('docker.push')) {
            Map<String, String> kv = parseKVs(spec.substring('docker.push'.length()))
            String tag = kv['tag']
            if (!tag) {
                steps.error('docker.push requires tag=<tag>')
            }
            steps.sh "docker push ${shellEscape(tag)}"
            return
        }
        if (spec.startsWith('k8s.apply')) {
            Map<String, String> kv = parseKVs(spec.substring('k8s.apply'.length()))
            String file = kv['file']
            if (!file) {
                steps.error('k8s.apply requires file=<path>')
            }
            steps.sh "kubectl apply -f ${shellEscape(file)}"
            return
        }

        steps.error("Unknown verb: '${spec}'")
    }

    private static String unquote(String s) {
        String trimmed = (s ?: '').trim()
        if ((trimmed.startsWith('"') && trimmed.endsWith('"')) || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1)
        }
        return trimmed
    }

    private static Map<String, String> parseKVs(String tail) {
        Map<String, String> map = [:]
        if (!tail) {
            return map
        }

        String[] parts = tail.trim().split('\\s+')
        for (String pair : parts) {
            if (pair == null || !pair.contains('=')) {
                continue
            }
            int idx = pair.indexOf('=')
            if (idx <= 0) {
                continue
            }
            String key = pair.substring(0, idx).trim()
            String value = pair.substring(idx + 1).trim()
            if (key) {
                map[key] = unquote(value)
            }
        }
        return map
    }
}
