# Pipeline Configuration Guide

The shared library expects a `.ci/ci.yml` file at the root of each service repository. This document expands on [`docs/ci.schema.json`](ci.schema.json) with human-readable explanations and examples.

## Top-Level Keys
| Key | Type | Description |
| --- | --- | --- |
| `version` | `number` | Schema version. Only `1` is supported. |
| `preset` | `string` | Build preset (`node`, `pnpm`, `java-gradle`). Optional when a `build.pnpm` block is present. |
| `agent` | `map` | Jenkins agent selector (`label`, `docker`). |
| `build` | `map` | Custom build stages or preset overrides. |
| `image` | `map/boolean` | Docker build/push configuration. Set to `false` to disable container workflows. |
| `deploy` | `map` | Declarative Helm deploy definitions. |
| `terraform` | `map` | Infrastructure orchestration settings. |
| `release` | `map` | Auto-tag + GitHub release automation. |

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

## Terraform Section
```yaml
terraform:
  autoInstall: true
  environments:
    dev:
      displayName: Dev Cluster
      when: 'branch=main & !pr'
      vars:
        environment: dev
      approval:
        before: true
```

The pipeline auto-installs Terraform (per `version`), selects workspaces, and can defer environments gated on tags (e.g., `when: 'tag=^v'`).

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

- `autoTag.afterEnvironment` gates tagging until a specific deploy environment finishes.
- GitHub releases inherit credentials from `autoTag` when not explicitly provided.

## Credentials & Secrets
- Add inline bindings per stage using `credentials:` blocks. Supported types: `string`, `file`, `usernamePassword`.
- Helm repo auth should use `deploy.<env>.repoCredentials` to benefit from `CredentialHelper` masking + audit logging.
- Git/Terraform helpers take `credential` or `credentialsId` keys as documented in this guide – the helpers ensure remotes and CLI env vars are restored automatically.

## Validation & Migration
- The library emits warnings and fails fast if the config violates the schema.
- Legacy `ci.yml` support is deprecated and will be removed after **December 2024**. Use `.ci/ci.yml` plus the schema in this folder to migrate.
- For detailed tips on moving from the v0 pipelines, read [`migration-v1.md`](migration-v1.md).
