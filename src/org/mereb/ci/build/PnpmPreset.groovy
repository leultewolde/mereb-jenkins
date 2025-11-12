package org.mereb.ci.build

import org.mereb.ci.util.PipelineUtils

/**
 * Encapsulates pnpm build preset logic so we can unit test it in isolation.
 */
class PnpmPreset implements Serializable {

    static List<Map> buildStages(Object pnpmRaw) {
        Map cfg = normalizeConfig(pnpmRaw)
        if (!(cfg.enabled as Boolean)) {
            return []
        }

        List<Map> stages = []
        if (cfg.bootstrap as Boolean) {
            stages << [
                name: stageName(cfg, 'bootstrap', 'Bootstrap Node runtime'),
                env : [PNPM_NODE_VERSION: cfg.nodeVersion],
                sh  : bootstrapScript()
            ]
        }
        if (cfg.prepare as Boolean) {
            stages << [
                name: stageName(cfg, 'prepare', 'Prepare pnpm'),
                env : [PNPM_VERSION: cfg.pnpmVersion],
                sh  : prepareScript()
            ]
        }
        if (cfg.install as Boolean) {
            Map installEnv = [
                PNPM_PACKAGE_DIR      : cfg.packageDir,
                PNPM_WORKSPACE_INSTALL: PipelineUtils.boolString(cfg.workspaceInstall as Boolean),
                PNPM_LOCKFILE         : cfg.lockfile
            ]
            stages << [
                name: stageName(cfg, 'install', 'Install dependencies'),
                env : installEnv,
                sh  : installScript()
            ]
        }

        (cfg.tasks ?: []).each { Map task ->
            String pkgDir = (task.packageDir ?: cfg.packageDir ?: '.').toString().trim()
            if (!pkgDir) {
                pkgDir = '.'
            }
            String command = (task.command ?: defaultCommand(task.type))?.toString()?.trim()
            if (!command) {
                command = defaultCommand(task.type)
            }
            String packageName = (task.packageName ?: cfg.packageName ?: '').toString().trim()
            boolean hasPackage = packageName as boolean
            boolean useFilter = (task.filter as Boolean) && hasPackage
            String stageLabel = task.name ?: defaultStageName(task.type)
            List<String> skipPatterns = PipelineUtils.toStringList(task.skipIfMissing ?: [])

            Map envMap = [
                PNPM_PACKAGE_DIR    : pkgDir,
                PNPM_COMMAND        : command,
                PNPM_USE_FILTER     : PipelineUtils.boolString(useFilter),
                PNPM_ALLOW_WORKSPACE: PipelineUtils.boolString(task.workspace as Boolean)
            ]
            if (hasPackage) {
                envMap.PNPM_PACKAGE_NAME = packageName
            }
            envMap.PNPM_STAGE_LABEL = stageLabel

            stages << [
                name: stageLabel,
                env : envMap,
                sh  : (task.script ?: runScript(skipPatterns))
            ]
        }

        return stages
    }

    static Map normalizeConfig(Object raw) {
        if (!(raw instanceof Map)) {
            return [enabled: false]
        }
        Map data = raw as Map
        Map stageNames = [:]
        if (data.stageNames instanceof Map) {
            (data.stageNames as Map).each { k, v ->
                if (k != null && v != null) {
                    stageNames[k.toString()] = v.toString()
                }
            }
        }
        if (data.bootstrapStageName) {
            stageNames['bootstrap'] = data.bootstrapStageName.toString()
        }
        if (data.prepareStageName) {
            stageNames['prepare'] = data.prepareStageName.toString()
        }
        if (data.installStageName) {
            stageNames['install'] = data.installStageName.toString()
        }

        List<Map> tasks = normalizeTasks(data.steps ?: data.tasks ?: data.run)
        String packageName = (data.packageName ?: data.package ?: data.pkg)?.toString()?.trim()
        String packageDir = (data.packageDir ?: data.packagePath ?: data.path ?: data.dir ?: '.').toString().trim()
        if (!packageDir) {
            packageDir = '.'
        }

        return [
            enabled         : !tasks.isEmpty(),
            tasks           : tasks,
            nodeVersion     : (data.nodeVersion ?: data.node ?: '20.19.2').toString(),
            pnpmVersion     : (data.pnpmVersion ?: data.pnpm ?: '9.0.0').toString(),
            packageDir      : packageDir,
            packageName     : packageName ?: null,
            workspaceInstall: data.containsKey('workspaceInstall') ? (data.workspaceInstall as Boolean) : true,
            lockfile        : (data.lockfile ?: 'pnpm-lock.yaml').toString(),
            bootstrap       : data.containsKey('bootstrap') ? (data.bootstrap as Boolean) : true,
            prepare         : data.containsKey('prepare') ? (data.prepare as Boolean) : true,
            install         : data.containsKey('install') ? (data.install as Boolean) : true,
            stageNames      : stageNames
        ]
    }

    private static List<Map> normalizeTasks(Object raw) {
        List entries = []
        if (raw instanceof List) {
            entries = raw as List
        } else if (raw != null) {
            entries = [raw]
        }
        if (!entries || entries.isEmpty()) {
            entries = ['lint', 'build']
        }

        List<Map> tasks = []
        entries.each { Object entry ->
            if (entry == null) {
                return
            }
            if (entry instanceof Map) {
                Map stage = entry as Map
                String type = (stage.type ?: stage.step ?: stage.kind ?: stage.task ?: stage.action)?.toString()?.trim()
                if (!type) {
                    return
                }
                String stageName = (stage.stageName ?: stage.displayName ?: stage.label ?: stage.title)?.toString()
                if (!stageName && stage.name && stage.name?.toString()?.trim() && stage.name?.toString()?.trim() != type) {
                    stageName = stage.name.toString()
                }
                String command = (stage.command ?: stage.cmd ?: defaultCommand(type))?.toString()?.trim()
                Map normalized = [
                    type        : type,
                    name        : stageName ?: defaultStageName(type),
                    command     : command ?: defaultCommand(type),
                    script      : stage.sh ? stage.sh.toString() : (stage.script ? stage.script.toString() : null),
                    filter      : stage.containsKey('filter') ? (stage.filter as Boolean) : true,
                    workspace   : stage.containsKey('workspace') ? (stage.workspace as Boolean) : true,
                    packageDir  : (stage.packageDir ?: stage.dir ?: stage.path ?: stage.packagePath)?.toString(),
                    packageName : (stage.packageName ?: stage.package ?: stage.pkg)?.toString(),
                    skipIfMissing: PipelineUtils.toStringList(stage.skipIfMissing ?: stage.skipIfMissingFiles)
                ]
                tasks << normalized
            } else {
                String type = entry.toString().trim()
                if (!type) {
                    return
                }
                tasks << [
                    type        : type,
                    name        : defaultStageName(type),
                    command     : defaultCommand(type),
                    filter      : true,
                    workspace   : true,
                    skipIfMissing: []
                ]
            }
        }
        return tasks
    }

    private static String stageName(Map cfg, String key, String fallback) {
        Map names = cfg.stageNames ?: [:]
        String value = names[key]
        return value ? value : fallback
    }

    private static String defaultStageName(String type) {
        String normalized = type?.toString()?.trim()?.toLowerCase()
        switch (normalized) {
            case 'lint':
                return 'Lint'
            case 'typecheck':
                return 'Typecheck'
            case 'test':
                return 'Test'
            case 'build':
                return 'Build'
            default:
                if (!type) {
                    return 'Command'
                }
                return type.capitalize()
        }
    }

    private static String defaultCommand(String type) {
        String normalized = type?.toString()?.trim()
        if (!normalized) {
            return 'build'
        }
        return normalized
    }

    private static String bootstrapScript() {
        return '''#!/usr/bin/env bash
set -euo pipefail

mkdir -p .ci

if command -v node >/dev/null 2>&1; then
  echo "Detected preinstalled Node at $(command -v node)"
  NODE_BIN_DIR="$(dirname "$(command -v node)")"
  printf 'export PATH="%s:$PATH"\n' "${NODE_BIN_DIR}" > .ci/env.sh
  chmod +x .ci/env.sh
  exit 0
fi

NODE_VERSION="${PNPM_NODE_VERSION:-20.19.2}"
NODE_DIST="node-v${NODE_VERSION}-linux-x64"
NODE_ARCHIVE="${NODE_DIST}.tar.xz"
NODE_URL="https://nodejs.org/dist/v${NODE_VERSION}/${NODE_ARCHIVE}"
NODE_DIR=".ci/${NODE_DIST}"

if [ ! -d "${NODE_DIR}" ]; then
  echo "Downloading Node.js ${NODE_VERSION} from ${NODE_URL}"
  curl -fsSL "${NODE_URL}" -o ".ci/${NODE_ARCHIVE}"
  tar -xJf ".ci/${NODE_ARCHIVE}" -C .ci
fi

NODE_BIN_DIR="$(pwd)/${NODE_DIR}/bin"
printf 'export PATH="%s:$PATH"\n' "${NODE_BIN_DIR}" > .ci/env.sh
chmod +x .ci/env.sh
if [ -x "${NODE_BIN_DIR}/node" ]; then
  echo "Node.js $(. .ci/env.sh && node -v) bootstrapped into ${NODE_DIR}"
else
  echo "Failed to bootstrap Node.js" >&2
  exit 1
fi
'''.stripIndent()
    }

    private static String prepareScript() {
        return '''#!/usr/bin/env bash
set -euo pipefail

if [ -f .ci/env.sh ]; then
  . .ci/env.sh
fi

if command -v pnpm >/dev/null 2>&1; then
  pnpm --version
  exit 0
fi

PNPM_VERSION="${PNPM_VERSION:-9.0.0}"

if command -v corepack >/dev/null 2>&1; then
  corepack prepare pnpm@${PNPM_VERSION} --activate
  # Ensure shims are actually written into the PATH we set in bootstrap.
  corepack enable pnpm >/dev/null 2>&1 || corepack enable >/dev/null 2>&1 || true
elif command -v npm >/dev/null 2>&1; then
  npm install -g pnpm@${PNPM_VERSION}
else
  echo "Neither pnpm, corepack, nor npm are available on this agent." >&2
  exit 1
fi

if ! command -v pnpm >/dev/null 2>&1; then
  echo "pnpm was not installed even after running the prepare step." >&2
  exit 1
fi

pnpm --version
'''.stripIndent()
    }

    private static String installScript() {
        return '''#!/usr/bin/env bash
set -euo pipefail

if [ -f .ci/env.sh ]; then
  . .ci/env.sh
fi

PKG_DIR="${PNPM_PACKAGE_DIR:-.}"
LOCK_PATH="${PNPM_LOCKFILE:-pnpm-lock.yaml}"
WORKSPACE_INSTALL="${PNPM_WORKSPACE_INSTALL:-true}"
LOCK_EXISTS="true"

cd "${PKG_DIR}"

if [ ! -f "${LOCK_PATH}" ]; then
  echo "Lockfile ${LOCK_PATH} not found in ${PKG_DIR}."
  LOCK_EXISTS="false"
fi

if [ "${WORKSPACE_INSTALL}" = "true" ]; then
  if [ "${LOCK_EXISTS}" = "true" ]; then
    pnpm install --frozen-lockfile
  else
    echo "Falling back to --no-frozen-lockfile because the lockfile is missing."
    pnpm install --no-frozen-lockfile
  fi
else
  pnpm install --no-frozen-lockfile
fi
'''.stripIndent()
    }

    private static String runScript(List<String> skipPatterns = []) {
        StringBuilder sb = new StringBuilder()
        sb.append('''#!/usr/bin/env bash
set -euo pipefail

if [ -f .ci/env.sh ]; then
  . .ci/env.sh
fi

PKG_DIR="${PNPM_PACKAGE_DIR:-.}"
COMMAND="${PNPM_COMMAND:-build}"
PACKAGE_NAME="${PNPM_PACKAGE_NAME:-}"
USE_FILTER="${PNPM_USE_FILTER:-true}"
ALLOW_WORKSPACE="${PNPM_ALLOW_WORKSPACE:-true}"
LABEL="${PNPM_STAGE_LABEL:-pnpm command}"
''')
        skipPatterns?.findAll { it }.eachWithIndex { pattern, idx ->
            sb.append("FILE_${idx}=${pattern}\n")
        }
        sb.append('''
missing_required="false"
''')
        skipPatterns?.findAll { it }.eachWithIndex { pattern, idx ->
            sb.append("if [ ! -e \"${pattern}\" ]; then missing_required=\"true\"; fi\n")
        }
        sb.append('''
if [ "${missing_required}" = "true" ]; then
  echo "Skipping ${LABEL} because required files were not found in ${PKG_DIR}."
  exit 0
fi

cd "${PKG_DIR}"

if [ -f pnpm-workspace.yaml ] && [ "${ALLOW_WORKSPACE}" = "true" ] && [ "${USE_FILTER}" = "true" ] && [ -n "${PACKAGE_NAME}" ]; then
  pnpm --filter "${PACKAGE_NAME}" ${COMMAND}
else
  pnpm ${COMMAND}
fi
''')
        return sb.toString()
    }
}
