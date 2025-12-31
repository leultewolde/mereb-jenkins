package org.mereb.ci.release

import org.mereb.ci.util.PipelineUtils

/**
 * Publishes a Node package to an npm-compatible registry with basic safety checks.
 */
class NpmPublisher implements Serializable {

    private final def steps

    NpmPublisher(def steps) {
        this.steps = steps
    }

    void publish(Map opts = [:]) {
        String pkgDir = (opts.packageDir ?: opts.dir ?: steps.env.PACKAGE_DIR ?: '.').toString().trim()
        if (!pkgDir) {
            pkgDir = '.'
        }
        String envFile = (opts.envFile ?: '.ci/env.sh').toString().trim()
        boolean loadEnvFile = opts.containsKey('loadEnvFile') ? toBool(opts.loadEnvFile) : true
        String registryOverride = (opts.registry ?: '').toString().trim()
        String accessOverride = (opts.access ?: '').toString().trim()
        boolean runBuild = opts.containsKey('build') ? toBool(opts.build) : false
        String buildCommand = (opts.buildCommand ?: 'pnpm run build').toString().trim()
        boolean skipTagCheck = opts.containsKey('skipTagCheck') ? toBool(opts.skipTagCheck) : false

        String envFileSnippet = ''
        if (loadEnvFile && envFile) {
            envFileSnippet = """if [ -f ${PipelineUtils.shellEscape(envFile)} ]; then
  . ${PipelineUtils.shellEscape(envFile)}
fi

"""
        }

        String registrySnippet = registryOverride
                ? "REGISTRY_URL=${PipelineUtils.shellEscape(registryOverride)}\n"
                : 'REGISTRY_URL="${NPM_REGISTRY:-https://registry.npmjs.org}"\n'
        String accessSnippet = accessOverride
                ? "ACCESS_MODE=${PipelineUtils.shellEscape(accessOverride)}\n"
                : 'ACCESS_MODE="${NPM_ACCESS:-public}"\n'

        String buildSnippet = ''
        if (runBuild) {
            String buildCmd = buildCommand ?: 'pnpm run build'
            buildSnippet = """echo "Running build before publish..."
${buildCmd}

"""
        }

        String tagCheckSnippet = ''
        if (!skipTagCheck) {
            tagCheckSnippet = '''if [ "v${PACKAGE_VERSION}" != "$TAG" ]; then
  echo "Tag ${TAG} does not match package version v${PACKAGE_VERSION}; aborting publish." >&2
  exit 1
fi

'''
        }

        String script = """#!/usr/bin/env bash
set -euo pipefail

${envFileSnippet}PKG_DIR=${PipelineUtils.shellEscape(pkgDir)}
if [ ! -d "\$PKG_DIR" ]; then
  PKG_DIR="."
fi

TAG="\${TAG_NAME:-\${RELEASE_TAG:-}}"
if [ -z "\$TAG" ]; then
  echo "Release tag not available; skipping npm publish."
  exit 0
fi

PACKAGE_JSON_PATH="\${PKG_DIR%/}/package.json"
if [ ! -f "\$PACKAGE_JSON_PATH" ]; then
  echo "package.json not found at \${PACKAGE_JSON_PATH}" >&2
  exit 1
fi
PACKAGE_VERSION="\$(node -p "require('\${PACKAGE_JSON_PATH}').version")"
${tagCheckSnippet}if [ -z "\${NPM_TOKEN:-}" ]; then
  echo "NPM_TOKEN credential is not available; configure 'npm-registry-token' in Jenkins." >&2
  exit 1
fi

${registrySnippet}${accessSnippet}REGISTRY_HOST="\${REGISTRY_URL#http://}"
REGISTRY_HOST="\${REGISTRY_HOST#https://}"
REGISTRY_HOST="\${REGISTRY_HOST%%/}"

mkdir -p "\${HOME}/.npm"
{
  printf '//%s/:_authToken=%s\\n' "\${REGISTRY_HOST}" "\${NPM_TOKEN}"
  printf 'registry=%s\\n' "\${REGISTRY_URL}"
  printf 'always-auth=true\\n'
} > "\${HOME}/.npmrc"

cd "\$PKG_DIR"
${buildSnippet}ACCESS_FLAGS=()
case "\${REGISTRY_URL}" in
  https://registry.npmjs.org*|http://registry.npmjs.org*)
    ACCESS_FLAGS+=(--access "\${ACCESS_MODE}")
    ;;
esac

pnpm publish --registry "\${REGISTRY_URL}" --no-git-checks "\${ACCESS_FLAGS[@]}"
"""

        steps.sh script: script, label: "Publish npm package from ${pkgDir}"
    }

    private static boolean toBool(Object value) {
        if (value instanceof Boolean) {
            return value as Boolean
        }
        String normalized = value?.toString()?.trim()?.toLowerCase()
        return normalized in ['1', 'true', 'yes', 'on']
    }
}
