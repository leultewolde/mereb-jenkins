package org.mereb.ci.mfe

import org.mereb.ci.Helpers
import org.mereb.ci.credentials.CredentialHelper
import org.mereb.ci.util.StageExecutor

import static org.mereb.ci.util.PipelineUtils.mapToEnvList
import static org.mereb.ci.util.PipelineUtils.shellEscape

/**
 * Publishes microfrontends to S3/CDN buckets with per-environment approvals.
 */
class MicrofrontendPipeline implements Serializable {

    private final def steps
    private final CredentialHelper credentialHelper
    private final Closure approvalHandler
    private final StageExecutor stageExecutor

    MicrofrontendPipeline(def steps, CredentialHelper credentialHelper, Closure approvalHandler, StageExecutor stageExecutor = null) {
        this.steps = steps
        this.credentialHelper = credentialHelper
        this.approvalHandler = approvalHandler
        this.stageExecutor = stageExecutor ?: new StageExecutor(steps, credentialHelper)
    }

    void run(Map cfg, Map state, Closure tagCallback = null, Closure releaseCallback = null, Map releaseCfg = null) {
        if (!(cfg?.enabled as Boolean)) {
            return
        }
        Map envs = cfg.environments ?: [:]
        if (!envs || envs.isEmpty()) {
            steps.echo 'microfrontend: no environments configured; skipping.'
            return
        }

        String workspace = steps.pwd()
        if (!workspace?.trim()) {
            steps.error('Workspace unavailable for microfrontend pipeline.')
        }

        String nodeVersion = (cfg.nodeVersion ?: '20.19.2').toString()
        prepareTools(workspace, nodeVersion)

        List<String> baseEnv = mapToEnvList(cfg.env)
        baseEnv << "PATH=${toolPath(workspace, nodeVersion)}".toString()
        if (cfg.aws?.region) {
            baseEnv << "AWS_REGION=${cfg.aws.region}".toString()
            baseEnv << "AWS_DEFAULT_REGION=${cfg.aws.region}".toString()
        }
        if (cfg.aws?.endpoint) {
            String endpoint = (cfg.aws.endpoint ?: '').toString()
            if (endpoint) {
                baseEnv << "AWS_S3_ENDPOINT=${endpoint}".toString()
                baseEnv << "AWS_ENDPOINT_URL_S3=${endpoint}".toString()
            }
        }
        String forcePath = (cfg.aws?.forcePathStyle as Boolean) ? 'true' : 'false'
        baseEnv << "AWS_S3_FORCE_PATH_STYLE=${forcePath}".toString()

        List<Map> baseBindings = credentialHelper.bindingsFor([credentials: cfg.aws?.credentials])

        List<String> order = cfg.order instanceof List ? (cfg.order as List).collect { it?.toString() } : []
        if (!order || order.isEmpty()) {
            order = envs.keySet().collect { it?.toString() }
        }

        String remoteName = (cfg.name ?: 'microfrontend').toString()
        String manifestEntry = (cfg.manifestEntry ?: remoteName.replaceAll(/[^A-Za-z0-9_]/, '_')).toString()
        String manifestFlag = (cfg.manifestFlag ?: manifestEntry).toString()
        String distDir = (cfg.distDir ?: 'dist').toString()
        String manifestScript = (cfg.manifestScript ?: 'scripts/update-manifest.js').toString()
        String checkScript = (cfg.checkScript ?: 'scripts/check-remote-entry.js').toString()
        String version = resolveVersion(state)

        for (String envName : order) {
            String envKey = envName?.toString()
            String envLower = envKey?.toLowerCase()
            Map envCfg = envs[envKey]
            if (!envCfg) {
                continue
            }
            if (!Helpers.matchCondition(envCfg.when as String, steps.env)) {
                steps.echo "microfrontend ${remoteName}: skipping ${envKey} (condition '${envCfg.when}' not met)"
                continue
            }
            String stageLabel = envCfg.displayName ?: envKey
            Map approvalCfg = envCfg.approval instanceof Map ? (envCfg.approval as Map) : [:]
            if (approvalCfg.isEmpty() && ['stg', 'prd', 'prod'].contains(envLower)) {
                approvalCfg = [message: "Publish ${remoteName} to ${stageLabel}?", ok: 'Approve']
            }

            List<Map> bindings = []
            bindings.addAll(baseBindings)
            bindings.addAll(credentialHelper.bindingsFor(envCfg))

            List<String> envList = []
            envList.addAll(baseEnv)
            envList.addAll(mapToEnvList(envCfg.env))

            if (approvalCfg && !approvalCfg.isEmpty()) {
                steps.stage("MFE ${stageLabel} Approval") {
                    approvalHandler?.call(approvalCfg as Map, "Publish ${remoteName} to ${stageLabel}?")
                }
            }

            stageExecutor.run("MFE ${stageLabel} Deploy", envList, bindings) {
                steps.sh(script: publishScript(remoteName, manifestFlag, distDir, manifestScript, envCfg, version), label: "Publish ${stageLabel}")
            }

            stageExecutor.run("MFE ${stageLabel} Test", envList, bindings) {
                steps.sh(script: verifyScript(remoteName, manifestEntry, envCfg, version, checkScript), label: "Verify ${stageLabel}")
            }

            if (tagCallback && envLower == 'stg') {
                tagCallback.call(releaseCfg ?: [:], state)
            }
            if (releaseCallback && (envLower == 'prd' || envLower == 'prod')) {
                releaseCallback.call(releaseCfg ?: [:], state)
            }
        }
    }

    private void prepareTools(String workspace, String nodeVersion) {
        String script = '''#!/usr/bin/env bash
set -euo pipefail

AWS_DIR="__WORKSPACE__/.ci/aws-cli"
AWS_BIN="__WORKSPACE__/.ci/aws-cli/bin"
NODE_ARCHIVE="node-v__NODE_VERSION__-linux-x64"
NODE_DIR="__WORKSPACE__/.ci/${NODE_ARCHIVE}"
NODE_BIN="${NODE_DIR}/bin"

if [ -d "${AWS_BIN}" ]; then
  export PATH="${AWS_BIN}:${PATH}"
fi
if ! command -v aws >/dev/null 2>&1; then
  echo "[mfe] aws CLI not found; installing to ${AWS_DIR}"
  tmp_dir="$(mktemp -d)"
  curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "${tmp_dir}/awscliv2.zip"
  unzip -q "${tmp_dir}/awscliv2.zip" -d "${tmp_dir}"
  mkdir -p "${AWS_DIR}"
  "${tmp_dir}/aws/install" -i "${AWS_DIR}" -b "${AWS_BIN}" >/dev/null
  rm -rf "${tmp_dir}"
fi

if [ -d "${NODE_BIN}" ]; then
  export PATH="${NODE_BIN}:${PATH}"
fi
if ! command -v node >/dev/null 2>&1; then
  echo "[mfe] node runtime not found; installing __NODE_VERSION__ locally"
  tmp_dir="$(mktemp -d)"
  curl -fsSL "https://nodejs.org/dist/v__NODE_VERSION__/${NODE_ARCHIVE}.tar.xz" -o "${tmp_dir}/node.tar.xz"
  tar -xJf "${tmp_dir}/node.tar.xz" -C "${tmp_dir}"
  rm -rf "${NODE_DIR}"
  mkdir -p "$(dirname "${NODE_DIR}")"
  mv "${tmp_dir}/${NODE_ARCHIVE}" "${NODE_DIR}"
  rm -rf "${tmp_dir}"
fi
'''
        String rendered = script
            .replace('__WORKSPACE__', workspace)
            .replace('__NODE_VERSION__', nodeVersion)
        steps.sh(script: rendered, label: 'Prepare MFE tools')
    }

    private String toolPath(String workspace, String nodeVersion) {
        String awsBin = "${workspace}/.ci/aws-cli/bin"
        String nodeBin = "${workspace}/.ci/node-v${nodeVersion}-linux-x64/bin"
        String basePath = steps.env.PATH ?: ''
        return "${awsBin}:${nodeBin}:${basePath}"
    }

    private String resolveVersion(Map state) {
        List<String> candidates = [
            steps.env.TAG_NAME?.toString(),
            steps.env.RELEASE_TAG?.toString(),
            steps.env.GIT_COMMIT?.toString(),
            state?.commitShort?.toString(),
            state?.commit?.toString()
        ]
        for (String candidate : candidates) {
            if (candidate?.trim()) {
                return candidate.trim()
            }
        }
        try {
            return steps.sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
        } catch (Exception ignored) {
            steps.error('Unable to determine version for microfrontend publish.')
            return ''
        }
    }

    private String publishScript(String remoteName,
                                 String manifestFlag,
                                 String distDir,
                                 String manifestScript,
                                 Map envCfg,
                                 String version) {
        boolean clearBeforeSync = envCfg.containsKey('clearBeforeSync') ? (envCfg.clearBeforeSync as Boolean) : true
        String bucket = envCfg.bucket ?: ''
        String publicBase = envCfg.publicBase ?: ''
        return """#!/usr/bin/env bash
set -euo pipefail

REMOTE_NAME=${shellEscape(remoteName)}
VERSION=${shellEscape(version)}
DIST_DIR=${shellEscape(distDir)}
BUCKET=${shellEscape(bucket)}
PUBLIC_BASE=${shellEscape(publicBase.replaceAll(/\/$/, ''))}
MANIFEST_SCRIPT=${shellEscape(manifestScript)}

if [ -z "\${BUCKET}" ] || [ -z "\${PUBLIC_BASE}" ]; then
  echo "[publish] bucket and publicBase are required" >&2
  exit 1
fi

if [ ! -d "\${DIST_DIR}" ]; then
  echo "[publish] build artifacts not found at \${DIST_DIR}; run the build step first." >&2
  exit 1
fi

if [ ! -f "\${MANIFEST_SCRIPT}" ]; then
  echo "[publish] unable to locate manifest updater at \${MANIFEST_SCRIPT}" >&2
  exit 1
fi

AWS_ENDPOINT="\${AWS_ENDPOINT_URL_S3:-\${AWS_ENDPOINT_URL:-\${AWS_S3_ENDPOINT:-}}}"
AWS_ENDPOINT_ARGS=()
if [ -n "\${AWS_ENDPOINT}" ]; then
  AWS_ENDPOINT_ARGS+=(--endpoint-url "\${AWS_ENDPOINT}")
fi
AWS_SYNC_ARGS=(--delete)
if [ "\${#AWS_ENDPOINT_ARGS[@]}" -gt 0 ]; then
  AWS_SYNC_ARGS+=("\${AWS_ENDPOINT_ARGS[@]}")
fi

DEST="s3://\${BUCKET}/\${REMOTE_NAME}/\${VERSION}"
""" +
                (clearBeforeSync ? '''
echo "[publish] clearing existing artifacts in ${DEST}"
aws s3 rm "${DEST}/" --recursive "${AWS_ENDPOINT_ARGS[@]}" || true
''' : '') +
                '''
echo "[publish] syncing ${DIST_DIR} to ${DEST}"
aws s3 sync "${DIST_DIR}/" "${DEST}/" "${AWS_SYNC_ARGS[@]}"

echo "[manifest] updating manifest in bucket ${BUCKET}"
node "${MANIFEST_SCRIPT}" \
  --bucket "s3://${BUCKET}" \
  --key manifest.json \
  --public-base "${PUBLIC_BASE}" \
  --''' + manifestFlag + ''' "${VERSION}"
'''
    }

    private String verifyScript(String remoteName,
                                String manifestEntry,
                                Map envCfg,
                                String version,
                                String checkScript) {
        String bucket = envCfg.bucket ?: ''
        String publicBase = envCfg.publicBase ?: ''
        String template = '''#!/usr/bin/env bash
set -euo pipefail

BUCKET=__BUCKET__
PUBLIC_BASE=__PUBLIC_BASE__
CHECK_SCRIPT=__CHECK_SCRIPT__

AWS_ENDPOINT="${AWS_ENDPOINT_URL_S3:-${AWS_ENDPOINT_URL:-${AWS_S3_ENDPOINT:-}}}"
AWS_ENDPOINT_ARGS=()
if [ -n "${AWS_ENDPOINT}" ]; then
  AWS_ENDPOINT_ARGS+=(--endpoint-url "${AWS_ENDPOINT}")
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT
MANIFEST_PATH="${TMP_DIR}/${BUCKET}-manifest.json"
aws s3 cp "${AWS_ENDPOINT_ARGS[@]}" "s3://${BUCKET}/manifest.json" "${MANIFEST_PATH}"

EXPECTED="${PUBLIC_BASE}/__REMOTE_NAME__/__VERSION__/remoteEntry.js"
ACTUAL="$(
  MANIFEST_FILE="${MANIFEST_PATH}" node --input-type=module -e "import fs from 'node:fs'; const content = fs.readFileSync(process.env.MANIFEST_FILE, 'utf8'); const manifest = JSON.parse(content); process.stdout.write(manifest.__MANIFEST_ENTRY__ ? String(manifest.__MANIFEST_ENTRY__) : '');"
)"

if [ "${ACTUAL}" != "${EXPECTED}" ]; then
  echo "[verify] ${BUCKET} manifest mismatch: expected '${EXPECTED}', found '${ACTUAL}'" >&2
  exit 1
fi

MANIFEST_URL="${PUBLIC_BASE}/manifest.json"
echo "[verify] checking manifest URL ${MANIFEST_URL}"
if ! curl -fsS --max-time 15 "${MANIFEST_URL}" >/dev/null; then
  echo "[verify] unable to fetch ${MANIFEST_URL}" >&2
  exit 1
fi

echo "[verify] checking public URL ${EXPECTED}"
node "${CHECK_SCRIPT}" \
  --bucket "${BUCKET}" \
  --endpoint "${PUBLIC_BASE}" \
  --key "__REMOTE_NAME__/__VERSION__/remoteEntry.js" \
  --region "${AWS_REGION:-${AWS_DEFAULT_REGION:-us-east-1}}"
'''

        return template
            .replace('__BUCKET__', shellEscape(bucket))
            .replace('__PUBLIC_BASE__', shellEscape(publicBase.replaceAll(/\/$/, '')))
            .replace('__CHECK_SCRIPT__', shellEscape(checkScript))
            .replace('__REMOTE_NAME__', remoteName)
            .replace('__VERSION__', version)
            .replace('__MANIFEST_ENTRY__', manifestEntry)
    }
}
