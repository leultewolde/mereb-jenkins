# Pipeline Configuration Guide

The shared library expects a YAML-backed Mereb config file in each repository. The preferred filename is `.ci/ci.mjc`; legacy `.ci/ci.yml` and `ci.yml` are still supported. This document expands on [`docs/ci.schema.json`](ci.schema.json) with human-readable explanations and examples.

## Top-Level Keys
| Key | Type | Description |
| --- | --- | --- |
| `version` | `number` | Schema version. Only `1` is supported. |
| `preset` | `string` | Build preset (`node`, `pnpm`, `java-gradle`). Optional when a `build.pnpm` block is present. |
| `recipe` | `string` | Optional explicit recipe override: `build`, `package`, `image`, `service`, `microfrontend`, or `terraform`. |
| `agent` | `map` | Jenkins agent selector (`label`, `docker`). |
| `build` | `map` | Custom build stages or preset overrides. |
| `image` | `map/boolean` | Docker build/push configuration. Set to `false` to disable container workflows. |
| `deploy` | `map` | Declarative Helm deploy definitions. |
| `terraform` | `map` | Infrastructure orchestration settings. |
| `microfrontend` | `map` | Publish MFEs to CDN buckets with environment gates. |
| `release` | `map` | Auto-tag + GitHub release automation. |

## Recipe Selection

`ciV1` now resolves an internal recipe before it runs the recipe-specific stages.

- If `recipe` is set, that value is used and validated against the config shape.
- If `recipe` is omitted, the library auto-detects one of:
  - `terraform`
  - `microfrontend`
  - `service`
  - `image`
  - `package`
  - `build`
- Unsupported mixed shapes fail fast. For example, combining Terraform environments with Helm deploy environments in one config is no longer accepted.

Use `recipe` only when you want to make intent explicit or avoid relying on auto-detection. Existing compatible configs do not need to add it.

## Build Section
```yaml
build:
  stages:
    - name: Install
      verb: node.install
    - name: Lint
      verb: node.lint
```

- Use `verb` for built-in helpers (`node.*`, `gradle.*`).
- Use `sh` to run an arbitrary shell script.
- `env` injects temporary environment variables; `credentials` binds secret files or strings (see `CredentialHelper`).

### pnpm Preset
```yaml
build:
  preset: pnpm
  pnpm:
    packageDir: apps/feed
    packageName: feed
    tasks:
      - lint
      - test
      - { type: build, command: "build --filter '#app'" }
```

The preset automatically bootstraps Node, installs pnpm, and fans out `tasks` into Jenkins stages. Stage names can be overridden via `stageNames.bootstrap`, `prepare`, or `install`.

## Image Section
```yaml
image:
  repository: ghcr.io/mereb/svc-auth
  context: services/svc-auth
  dockerfile: services/svc-auth/Dockerfile
  push:
    when: '!pr'
    credentials:
      id: ghcr-push
      usernameEnv: DOCKER_USER
      passwordEnv: DOCKER_PASSWORD
```

If `repository` is omitted, the library derives it from `app.name`/`app.registry`.
Set `push.registry` when the registry connection used for `docker login`/`docker push` must differ from the hostname baked into `repository` (for example, pushing via an internal IP that bypasses Cloudflare). The library strips `http(s)://` prefixes automatically so you can drop raw URLs into the config.

## Deploy Section
```yaml
deploy:
  order: [dev, stg, prd]
  dev:
    chart: infra/charts/service
    namespace: svc-dev
    approval:
      before: false
  stg:
    when: 'branch=main & !pr'
    autoPromote: true
```

Each environment supports:
- `smoke` blocks (URL or script).
- `repoCredentials` for private Helm repos.
- `repoCredentials.usernameEnv` / `passwordEnv` to customize the env vars bound during deploys (defaults to `HELM_REPO_USERNAME` / `HELM_REPO_PASSWORD`).
- `valuesFiles`, `set`, `setString`, `setFile` to tweak Helm releases.
- `generatedValues` to append a generated Helm values overlay after checked-in `valuesFiles`.
- `approval` and `autoPromote` keys are accepted by the config model, but the current `DeployPipeline` runtime does not enforce them yet.

### Generated Values Overlays
```yaml
deploy:
  dev_outbox:
    release: svc-feed-dev-outbox
    namespace: apps-dev
    chart: app-chart
    valuesFiles:
      - .ci/values-dev.yaml
    generatedValues:
      profile: outboxWorker
      overlay:
        deploymentStrategy:
          type: RollingUpdate
          rollingUpdate:
            maxSurge: 0
            maxUnavailable: 1
```

- `generatedValues.profile` currently supports `outboxWorker`.
- `generatedValues.overlay` is a Helm values fragment merged on top of the profile defaults.
- The library renders the merged result into a temporary workspace file and appends it after `valuesFiles`, so the generated overlay wins on conflicts.
- Merge semantics are recursive for maps; arrays and scalar values replace the profile defaults wholesale.

## Terraform Section
```yaml
terraform:
  autoInstall: true
  pluginCacheDir: .ci/terraform-plugin-cache
  environments:
    dev:
      displayName: Dev Cluster
      when: 'branch=main & !pr'
      lock:
        resource: infra-platform-dev
      prePlan:
        - terraform state rm 'module.legacy.kubernetes_manifest.old_resource' || true
      vars:
        environment: dev
      verify:
        timeout: 180s
        resources:
          - kind: deployment
            name: api
            namespace: apps
            wait: available
          - kind: pod
            selector: app=optional-worker
            namespace: apps
            wait: ready
            optional: true
```

The pipeline auto-installs Terraform (per `version`), selects workspaces, can run one-off `prePlan` shell hooks after `init`/workspace selection, can reuse providers through `pluginCacheDir`, can queue full environment rollouts through Jenkins `lock(resource: ...)` when the Lockable Resources plugin is available, can verify Kubernetes resources after apply, and can defer environments gated on tags (e.g., `when: 'tag=^v'`).

`approval` is accepted by the config model, but the current `TerraformPipeline` runtime does not enforce it yet.

## Microfrontend Section
```yaml
microfrontend:
  name: mfe-admin
  distDir: dist
  manifestScript: scripts/update-manifest.js
  checkScript: scripts/check-remote-entry.js
  nodeVersion: 20.19.2
  aws:
    endpoint: https://minio.leultewolde.com
    region: us-east-1
    forcePathStyle: true
    credential:
      id: minio-credentials
      type: usernamePassword
      usernameEnv: AWS_ACCESS_KEY_ID
      passwordEnv: AWS_SECRET_ACCESS_KEY
  order: [dev, stg, prd]
  environments:
    dev:
      displayName: Dev CDN
      bucket: cdn-dev
      publicBase: https://cdn-dev.mereb.app
      when: 'branch=main & !pr'
    stg:
      displayName: Staging CDN
      bucket: cdn-stg
      publicBase: https://cdn-stg.mereb.app
      when: 'branch=main & !pr'
      approval:
        message: Promote admin remote to staging?
        submitter: release-managers
    prd:
      displayName: Prod CDN
      bucket: cdn
      publicBase: https://cdn.mereb.app
      when: 'tag=^v[0-9].*'
      approval:
        message: Publish admin remote to production?
        submitter: release-managers
```

- Uses the release tag when available (falls back to the commit SHA) to version S3 paths.
- `manifestFlag`/`manifestEntry` default to the `name` (`mfe-admin` -> `--admin`, `mfe_admin` entry).
- `approval` blocks are accepted by the config model, but the current `MicrofrontendPipeline` runtime does not execute those gates yet.
- `forcePathStyle` and `endpoint` support Minio and other S3-compatible targets.

## Release Section
```yaml
release:
  autoTag:
    enabled: true
    when: '!pr'
    bump: minor
    credential:
      id: git-pat
      type: string
  github:
    enabled: true
    credential:
      id: gh-release-token
      type: string
```

- `autoTag.afterEnvironment` gates tagging until a specific deploy or Terraform environment finishes.
- GitHub releases inherit credentials from `autoTag` when not explicitly provided.
- `approval` on `autoTag` and `releaseStages` is normalized, but current runtime code does not execute those approval gates yet.

### Release Stage Helper: `pnpm.publish`
Use the built-in verb instead of hand-written bash to publish npm packages:

```yaml
releaseStages:
  - name: Publish package
    when: branch=main & !pr
    credentials:
      - { id: npm-registry-token, type: string, env: NPM_TOKEN }
    verb: "pnpm.publish packageDir=packages/app-feed build=true"
```

Defaults:
- Uses `PACKAGE_DIR` or `.` when `packageDir` is omitted.
- Reads `NPM_REGISTRY`/`NPM_ACCESS` (override with `registry`/`access`).
- Sources `.ci/env.sh` when present (disable via `loadEnvFile=false`).
- Verifies `RELEASE_TAG`/`TAG_NAME` matches `package.json` version unless `skipTagCheck=true`.
- Runs `pnpm run build` when `build=true` (override with `buildCommand=...`).

## Credentials & Secrets
- Add inline bindings per stage using `credentials:` blocks. Supported types: `string`, `file`, `usernamePassword`.
- Helm repo auth should use `deploy.<env>.repoCredentials` to benefit from `CredentialHelper` masking + audit logging.
- Git/Terraform helpers take `credential` or `credentialsId` keys as documented in this guide – the helpers ensure remotes and CLI env vars are restored automatically.

## Validation & Migration
- The library emits warnings and fails fast if the config violates the schema.
- `.ci/ci.mjc` is the primary config path. Legacy `.ci/ci.yml` and `ci.yml` still work.
- For detailed tips on moving from the v0 pipelines, read [`migration-v1.md`](migration-v1.md).
