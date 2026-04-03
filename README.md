# Mereb Jenkins Shared Library

`mereb-jenkins` is an internal Jenkins Shared Library for building, packaging, deploying, and releasing Mereb services. It is not an end-user application with its own UI or API. Application repositories call into this library through a small `vars/` surface, while most behavior lives in testable Groovy classes under `src/org/mereb/ci/**`.

## What This Library Does

The main entrypoint, `ciV1`, loads a repository's pipeline config, validates and normalizes it, computes runtime state, and then orchestrates some or all of the following:

- Build and test stages
- Docker image build, push, pull verification, SBOM generation, vulnerability scan, and signing
- Terraform plan and apply flows
- Helm-based Kubernetes deployments
- Microfrontend publish flows to S3 or CDN-style buckets
- Smoke checks and rollout verification
- Git tag creation and GitHub release publishing
- Optional AI-generated release suggestions

Internally, `ciV1` now resolves a recipe and dispatches to a recipe-specific executor. The public entrypoint and existing consumer Jenkinsfiles stay the same.

## Who Uses It

Internal users interact with this project directly:

- Repository maintainers define `.ci/ci.yml` and call `ciV1(...)` from their Jenkinsfile.
- Platform and release engineers manage credentials, clusters, registries, Terraform backends, and release policies.
- Jenkins operators maintain the controller, agents, plugins, and shared library registration.

External end users do not interact with this repository directly. They interact with the applications, APIs, jobs, and microfrontends that this library builds and deploys.

## Public Entrypoints

- `vars/ciV1.groovy`: primary declarative pipeline entrypoint
- `vars/ciSteps.groovy`: helper verbs for ad hoc pipeline use
- `vars/helmDeploy.groovy`: thin wrapper around `HelmDeployer`
- `vars/runSmoke.groovy`: thin wrapper around `SmokeRunner`

Typical Jenkinsfile usage:

```Jenkinsfile
@Library('mereb-jenkins') _

ciV1(
  configPath: '.ci/ci.yml',
  bootstrapLabel: 'ubuntu-lts'
)
```

## Runtime Model

At a high level the library behaves like this:

1. Bootstrap on a Jenkins node, check out source, and locate `.ci/ci.yml` (falling back to legacy `ci.yml` if present).
2. Validate the raw config with `ConfigValidator` and normalize it with `ConfigNormalizer`.
3. Resolve a recipe from the config shape or optional explicit `recipe` field.
4. Freeze trigger behavior with `DeliveryPolicy` and compute runtime state with `PipelineStateFactory`.
5. Run shared phases: build stages, matrix stages, and optional AI suggestions.
6. Hand off to the resolved recipe executor for package, image, service, microfrontend, terraform, or build-only sequencing.
7. Clean the workspace in a `finally` block.

Two delivery styles are supported:

- `custom`: each stage honors its own `when` expression
- `staged`: PR and main-branch behavior is derived centrally by `DeliveryPolicy`

## Key Components

- `ConfigValidator` and `docs/ci.schema.json`: fail fast on malformed pipeline config
- `ConfigNormalizer`: turns loose YAML into a predictable runtime config map
- `RecipeResolver` and recipe executors: dispatch `ciV1` into the correct internal pipeline recipe
- `BuildStages` and `VerbRunner`: execute preset or custom build work
- `DockerPipeline`: image lifecycle including push verification, SBOM, scan, and signing
- `TerraformPipeline`: ordered environment orchestration with deferred tag-gated runs
- `DeployPipeline`: Helm deploys, values rendering, rollout restarts, live artifact verification, and smoke tests
- `MicrofrontendPipeline`: S3/CDN publishing with manifest updates and remote verification
- `ReleaseFlow` and `ReleaseCoordinator`: tag creation, release stages, and GitHub release publishing
- `CredentialHelper` and Vault helpers: central secret binding and repo auth handling

## Project Structure

```text
mereb-jenkins/
  vars/                  Jenkins shared-library entrypoints
  src/org/mereb/ci/      Testable pipeline modules
  docs/                  Config and design documentation
  test/groovy/           Unit tests
  integrationTest/groovy/ Jenkins integration tests
```

## Configuration

Repository consumers configure the library with `.ci/ci.yml`. The most important docs are:

- [`docs/pipeline-config.md`](docs/pipeline-config.md): human-readable config guide
- [`docs/ci.schema.json`](docs/ci.schema.json): schema for validation and editor tooling
- [`docs/recipe-executors.md`](docs/recipe-executors.md): maintainer guide for core-vs-recipe ownership
- [`docs/architecture.md`](docs/architecture.md): architectural design of this library
- [`docs/behavior-design.md`](docs/behavior-design.md): runtime behavior and actor interaction design
- [`docs/ai-client.md`](docs/ai-client.md): optional AI suggestion integration

Minimal example:

```yaml
version: 1
recipe: service # optional; auto-detected when omitted
build:
  preset: pnpm
  pnpm:
    packageDir: apps/web
    packageName: web
    tasks:
      - type: lint
      - type: test
      - type: build
image:
  repository: ghcr.io/mereb/web
deploy:
  order: [dev]
  dev:
    chart: infra/charts/web
    namespace: web-dev
```

## Current Behavior Notes

- `.ci/ci.yml` is the primary config path. Legacy `ci.yml` fallback still exists in the runtime.
- `recipe` is optional. When omitted, `ciV1` auto-detects the internal executor from the config shape.
- The runtime already parses several approval-related config blocks, but deploy, terraform, microfrontend, auto-tag, and release-stage approval gates are not currently enforced by the execution code.
- `deploy.autoPromote` is normalized and validated, but it is not currently used by `DeployPipeline`.

Those gaps are documented in more detail in [`docs/behavior-design.md`](docs/behavior-design.md).

## Development

- `./gradlew check`: run unit tests, integration tests, and Checkstyle
- `./gradlew test --tests org.mereb.ci.config.ConfigValidatorTest`: focus config validation
- `./gradlew integrationTest`: run Jenkins integration coverage

This library is built as a Groovy Jenkins shared library with the `com.mkobit.jenkins.pipelines.shared-library` plugin and Jenkins test harness support.
