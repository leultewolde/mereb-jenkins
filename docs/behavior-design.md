# Behavior Design

## Scope

This document describes how the shared library behaves at runtime today, including trigger classification, stage sequencing, side effects, and the current gap between configured approval fields and implemented execution behavior.

## Actors and Interaction Model

### Direct users

- Repository maintainers trigger behavior indirectly through `.ci/ci.yml` and Jenkinsfile usage.
- Release and platform engineers interact through credentials, registries, clusters, buckets, and release settings.
- Jenkins administrators operate the runtime environment.

### Indirect users

External product end users do not use this shared library directly. They interact with:

- applications and APIs deployed by Helm and Docker flows
- infrastructure affected by Terraform
- microfrontends published to S3 or CDN endpoints
- release artifacts and changelogs produced by Git tagging and GitHub release publication

## Pipeline Trigger Behavior

The pipeline first resolves config, then freezes delivery behavior through `DeliveryPolicy`.

### Custom mode

- Default mode when `delivery.mode` is omitted
- The pipeline runs for PRs, main-branch builds, and tag builds
- Individual modules honor their own `when` expressions

### Staged mode

- PR builds run
- Main-branch builds run
- Standalone tag builds are skipped
- Docker push behavior is centralized
- Deployment and microfrontend environment selection is centralized
- Auto-tagging is expected to happen from the main-branch pipeline, not from a separate tag-triggered build

### Staged-mode environment behavior

| Trigger | Allowed environments |
| --- | --- |
| PR build | `dev`, plus `stg` only when `delivery.pr.deployToStg` is true |
| Main branch build | `dev`, `stg`, `prd`, `prod`, plus matching prefixed variants such as `dev_outbox` |
| Standalone tag build | none, because the pipeline is skipped |

## End-to-End Runtime Sequence

### 1. Bootstrap

`ciV1` runs on a bootstrap node and:

- checks out SCM
- locates `.ci/ci.yml` or legacy `ci.yml`
- reads YAML
- validates `version`
- collects base environment variables from `env:`
- stashes the workspace

### 2. Validation and normalization

Outside the bootstrap node, `ConfigValidator` reports warnings and fails fast on structural errors. `ConfigNormalizer` then produces canonical config maps for:

- agent
- delivery
- build stages and matrix
- image
- SBOM, scan, and signing
- Terraform
- deploy environments
- microfrontends
- release automation
- AI settings

### 3. Delivery-policy gate

`DeliveryPolicy` decides whether the pipeline should run at all. If not, the build is marked `NOT_BUILT` and exits early.

### 4. Main execution node

The pipeline picks the execution agent from normalized config:

- `agent.label`
- `agent.docker`
- both
- or default Jenkins node

The stashed workspace is then restored.

### 5. State construction

`PipelineStateFactory` computes:

- commit and short SHA
- branch and sanitized branch
- PR identity
- build number
- tag name
- repository and image metadata when image builds are enabled

It also exports environment variables such as `IMAGE_REPOSITORY`, `IMAGE_TAG`, `IMAGE_REF`, `CHANGE_ID`, and `IS_PR`.

## Build Behavior

`BuildStages` runs normalized build stages in order.

Behavior:

- If no build stages exist, the pipeline logs that build and test are skipped.
- Each stage can run either a declarative `verb` or an arbitrary shell command.
- Stage-local env vars and credentials are applied through `StageExecutor`.
- Matrix groups run as Jenkins `parallel` branches.

Supported built-in verbs include:

- `node.install`, `node.lint`, `node.test`, `node.build`
- `gradle.test`, `gradle.build`, `gradle.publish`
- `pnpm.publish` and `npm.publish`
- raw `sh ...`, `npm ...`, `docker.build`, `docker.push`, `k8s.apply`

## AI Suggestion Behavior

AI suggestions are fetched after build stages and before Docker and release work.

Current behavior:

- Default client is a no-op
- `deepseek` is the only built-in provider
- If a suggestion returns data, the pipeline may export:
  - `AI_CHANGESET_PATH`
  - `AI_CHANGESET`
  - `AI_BUMP_TYPES`

The AI result augments release behavior but does not replace the core pipeline flow.

## Docker Behavior

`DockerPipeline` runs when `image.enabled` is true.

Sequence:

1. Docker build
2. SBOM generation if enabled and `syft` is available
3. Vulnerability scan if enabled and `grype` is available
4. Docker push if conditions allow it
5. Optional pull verification
6. Optional signing

Important details:

- Tags are derived from explicit templates, release tags, or fallback branch and SHA logic.
- Extra tags are rendered from templates and pushed after the primary tag.
- In staged delivery, image push behavior is decided by `DeliveryPolicy` rather than per-stage `when`.
- Auto-tagging can happen before Docker push so the final pushed image can use the release tag immediately.

## Terraform Behavior

`TerraformPipeline` executes ordered Terraform environments when `terraform.enabled` is true.

Per environment it can:

- evaluate `when`
- optionally defer tag-gated environments until a release tag exists
- set env vars and credentials
- acquire a Jenkins `lock(resource: ...)`
- run `terraform init`
- select or create a workspace
- run `prePlan` shell hooks
- run `terraform plan`
- optionally run `terraform apply`
- optionally verify Kubernetes resources through `TerraformVerifier`
- optionally run smoke checks

Cleanup behavior:

- removes plan output
- removes `.terraform`
- restores `.terraform.lock.hcl` with `git checkout -- ... || true`

Important current limitation:

- `terraform.<env>.approval` is normalized but not enforced by the runtime.

## Helm Deploy Behavior

`DeployPipeline` executes ordered deployment environments when deploy config is present.

Per environment it:

- checks whether the environment should run
- prepares Vault context and credential bindings
- resolves values files and renders values templates
- builds Helm arguments
- optionally creates an image pull secret named `regcred`
- calls `helmDeploy`
- discovers workloads for the Helm release
- restarts those workloads
- waits for rollout success
- verifies that the live workload matches the expected image tag or digest
- optionally runs smoke checks

Failure behavior:

- when deploy or smoke fails, the pipeline gathers Kubernetes diagnostics:
  - workloads
  - pods
  - recent events
  - workload descriptions
  - pod descriptions and logs

Important current limitations:

- `deploy.<env>.approval` is normalized but not enforced
- `deploy.<env>.autoPromote` is normalized and validated but not used

## Microfrontend Behavior

`MicrofrontendPipeline` runs when `microfrontend.enabled` is true.

Per run it:

- ensures AWS CLI and Node.js exist locally under `.ci/`
- constructs base AWS and PATH environment variables
- binds base and per-environment credentials
- chooses environment order
- publishes static artifacts to `s3://<bucket>/<remote>/<version>`
- updates `manifest.json`
- verifies the manifest entry and public remote URL

Release coupling:

- the staging environment triggers the tag callback when one is provided
- production or prod triggers the release callback when one is provided

Version selection:

- prefers `TAG_NAME`
- then `RELEASE_TAG`
- then Git SHA

Important current limitation:

- environment `approval` blocks are normalized but not enforced, even though the config surface suggests they should be.

## Release Behavior

Release behavior is split between `ReleaseFlow`, `ReleaseCoordinator`, and `ReleaseOrchestrator`.

### Auto-tagging

When enabled, auto-tagging:

- checks `when`
- optionally refuses to run on dirty worktrees
- can skip when HEAD is already tagged
- determines bump type, optionally overridden by AI output
- creates and pushes a Git tag
- records `RELEASE_TAG`
- cleans up the created tag if the pipeline later fails

### Release timing

`ReleaseCoordinator` decides when release work happens:

- immediately when `autoTag.afterEnvironment` is not set
- after a named environment finishes when `afterEnvironment` is configured
- after deferred Terraform work if tag-gated Terraform environments exist

### Release stages

Custom `releaseStages` only execute when a release tag exists.

### GitHub release publication

When enabled and a release tag exists:

- the pipeline confirms the tag exists on the remote
- resolves the GitHub repo
- composes a release payload
- merges AI changeset content into the release body when available
- creates the release, or publishes an existing draft release

Important current limitations:

- `release.autoTag.approval` is normalized but not enforced
- `releaseStages[].approval` is normalized but not enforced

## Smoke Behavior

`SmokeRunner` supports:

- URL-based checks using `curl`
- script or command execution
- retries
- retry delay
- timeout parsing for `ms`, `s`, `m`, `h`, or plain seconds

It is reused by both deploy and Terraform flows.

## Current Behavior Gaps

These are notable mismatches between config surface and runtime behavior:

1. Approval config is modeled broadly, but approval gates are not actually executed in deploy, Terraform, microfrontend, auto-tag, or release stages.
2. `deploy.autoPromote` is normalized and validated, but has no effect.
3. Legacy `ci.yml` fallback still exists, even though `.ci/ci.yml` is the intended primary path.
4. Existing documentation in older files may imply approval behavior that the current runtime does not implement.

## Practical Summary

For internal users, this library is a declarative CI/CD engine driven by `.ci/ci.yml`.

For external end users, the observable behavior is indirect:

- new versions of services become available in Kubernetes
- infrastructure changes take effect
- microfrontends appear under new CDN paths and manifest entries
- release tags and GitHub releases are published

That is the true end-user interaction surface of the project today.
