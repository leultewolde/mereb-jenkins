# Architecture Design

## Purpose

`mereb-jenkins` is a Jenkins Shared Library that gives Mereb repositories a declarative CI/CD surface. The design goal is to keep Jenkins-facing entrypoints very small and move most logic into unit-testable Groovy classes.

The system does not expose an end-user web interface or API. It is an internal delivery component.

## System Context

### Primary actors

| Actor | Role |
| --- | --- |
| Repository maintainer | Adds `.ci/ci.mjc` (or a supported legacy YAML filename), writes Jenkinsfile, and chooses pipeline behavior |
| Release or platform engineer | Manages release credentials, deployment targets, Terraform settings, and operational policy |
| Jenkins controller and agents | Execute the shared library and all external tools |
| External systems | Git, Docker registries, Kubernetes clusters, Helm repos, Terraform providers, Vault, S3-compatible storage, GitHub, optional AI endpoint |
| Product end users | Interact with applications and microfrontends deployed by this library, not with the library itself |

### End-user interaction boundary

The direct users of this code are internal engineers. External end users only see the downstream systems produced by the pipeline:

- web apps and microfrontends published to CDN buckets
- containerized services deployed to Kubernetes
- infrastructure changes applied through Terraform
- GitHub releases and version tags

## Repository Layout

| Path | Responsibility |
| --- | --- |
| `vars/` | Public Jenkins shared-library entrypoints |
| `src/org/mereb/ci/` | Core orchestration and helper modules |
| `docs/` | Config, migration, AI, and design documentation |
| `test/groovy/` | Unit tests for modules under `src/` |
| `integrationTest/groovy/` | Jenkins integration coverage |
| `build.gradle` | Groovy, Checkstyle, Jenkins shared-library plugin, and test harness wiring |

## High-Level Component Model

```text
Jenkinsfile
  -> vars/ciV1.groovy
     -> ConfigValidator
     -> ConfigNormalizer
     -> RecipeResolver
     -> DeliveryPolicy
     -> PipelineStateFactory
     -> RecipeServicesFactory
     -> CommonPipelineExecutor
        -> BuildStages / VerbRunner
        -> AiFactory / AiClient
     -> RecipeExecutorFactory
        -> PackageRecipeExecutor
        -> ImageRecipeExecutor
        -> ServiceRecipeExecutor
        -> MicrofrontendRecipeExecutor
        -> TerraformRecipeExecutor
        -> BuildRecipeExecutor
     -> PipelineHelper cleanup
```

## Public Entrypoints

### `vars/ciV1.groovy`

Main pipeline entrypoint. Responsibilities:

- check out source and locate config
- validate and normalize config
- compute runtime state and exported environment variables
- decide whether the pipeline should run
- instantiate orchestration components
- select the execution agent
- guarantee cleanup

### `vars/ciSteps.groovy`

Exposes reusable step helpers such as pnpm setup, Docker login, Helm environment setup, Argo login, S3 sync, and simple approval gates.

### `vars/helmDeploy.groovy` and `vars/runSmoke.groovy`

Thin wrappers around internal service objects so they can be called as normal Jenkins shared-library steps.

## Internal Modules

### Configuration layer

| Component | Responsibility |
| --- | --- |
| `ConfigValidator` | Structural validation with actionable error messages |
| `ConfigNormalizer` | Converts flexible YAML into predictable runtime maps with defaults |
| `docs/ci.schema.json` | Schema-level contract for repository configs |

This layer is intentionally separate from stage execution so the rest of the pipeline can consume a stable in-memory shape.

### Runtime state and policy layer

| Component | Responsibility |
| --- | --- |
| `PipelineStateFactory` | Builds `state` and exported env such as commit, branch, PR flag, image tag, and image ref |
| `DeliveryPolicy` | Freezes trigger classification for staged delivery so later env mutations do not change behavior |
| `Helpers` | Evaluates simple `when` expressions such as `branch=main & !pr` |

### Execution layer

| Component | Responsibility |
| --- | --- |
| `BuildStages` | Runs normalized build stages and matrix branches |
| `VerbRunner` | Interprets declarative verbs such as `node.test`, `gradle.build`, `pnpm.publish`, and `docker.push` |
| `StageExecutor` | Wraps stage execution with env vars and credentials |
| `CredentialHelper` | Central credential binding and repo auth handling |

### Delivery layer

| Component | Responsibility |
| --- | --- |
| `DockerPipeline` | Image build, push, extra tags, pull verification, SBOM, scan, signing |
| `TerraformPipeline` | Ordered environment rollout, workspace selection, plan and apply, optional verification and smoke |
| `DeployPipeline` | Helm deploys, values rendering, pull-secret creation, rollout restart, live artifact verification, smoke, diagnostics |
| `MicrofrontendPipeline` | AWS CLI and Node bootstrapping, S3 publish, manifest update, CDN verification |
| `ReleaseFlow` | Git tagging, release stages, GitHub release publishing |
| `ReleaseCoordinator` | Coordinates eager vs post-environment release timing |
| `ReleaseOrchestrator` | Bridges Terraform, deploys, microfrontends, tagging, and release publication |

## Data Flow

### 1. Repository config ingestion

Input comes from a consumer repository's `.ci/ci.mjc` file, or a supported legacy YAML filename. `ciV1` reads it in a bootstrap node and stashes the workspace.

### 2. Validation and normalization

The raw config is checked by `ConfigValidator`, then transformed by `ConfigNormalizer` into a canonical map that the rest of the system assumes.

### 3. Runtime state construction

`PipelineStateFactory` derives execution state from Jenkins environment variables and Git metadata. This state becomes the main context object for downstream modules.

### 4. Side-effect execution

Each delivery module converts the normalized config and runtime state into shell commands or Jenkins steps that affect external systems:

- Docker CLI against registries
- `terraform` against providers and state backends
- `helm` and `kubectl` against Kubernetes
- `aws` and `curl` for microfrontend publishing and verification
- Git and GitHub API calls for releases

## Execution Topology

The architecture intentionally uses two node phases:

1. A bootstrap node checks out source, discovers config, and stashes the workspace.
2. The main execution node or Dockerized agent unstashes the workspace and runs the full pipeline.

This allows early config resolution while still supporting an execution agent chosen from normalized config.

## External Dependencies

The library assumes the Jenkins environment provides or permits:

- Git
- Docker CLI
- Helm
- `kubectl`
- Terraform, or network access to auto-install it
- `curl`
- `aws` CLI for microfrontend flows, or network access to install it
- Node.js for microfrontend helper scripts, or network access to install it
- optional `syft`, `grype`, and signing tooling

It also assumes Jenkins plugins or steps such as:

- Pipeline Shared Libraries
- Credentials Binding
- Workspace Cleanup plugin for `cleanWs` if available
- Lockable Resources plugin when Terraform locks are configured

## Testing Strategy

The project is structured to keep most behavior in plain Groovy classes so unit tests can cover:

- config validation and normalization
- state derivation
- credential binding
- deploy, docker, terraform, smoke, and release behavior

Integration tests under `integrationTest/groovy/` provide Jenkins-oriented coverage for the full shared-library flow.

## Architectural Notes and Gaps

- The schema and normalizer expose a richer approval surface than the current runtime actually executes.
- `approvalHandler` is injected into Terraform and microfrontend orchestration, but approval gates are not currently invoked.
- Deploy environments carry `autoPromote` and `approval` metadata, but `DeployPipeline` does not consume them.
- Legacy `.ci/ci.yml` and `ci.yml` fallbacks still exist even though `.ci/ci.mjc` is the canonical path.

These are behavior gaps, not just documentation gaps, and they are called out more explicitly in [`behavior-design.md`](behavior-design.md).
