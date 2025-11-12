# Mereb Jenkins Shared Library

Modern Jenkins Shared Library that powers the Mereb monorepo. The library now follows the usual Jenkins best practices: core logic lives under `src/org/mereb/ci/**` (where it can be unit-tested) and the public entry point stays in `vars/ciV1.groovy`.

## What's Included
- **Modular pipeline engines** – `BuildStages`, `DeployPipeline`, `DockerPipeline`, `TerraformPipeline`, `ReleaseFlow`, and `CredentialHelper` keep the library composable and testable.
- **Configuration guard rails** – `ConfigValidator` inspects `.ci/ci.yml` up-front and reports misconfigurations with actionable errors. A formal JSON Schema is available under [`docs/ci.schema.json`](docs/ci.schema.json).
- **Preset support** – Node/pnpm, Java/Gradle, Terraform, and Helm deploy flows ship out of the box. Each preset can be mixed with custom stages or credentials.
- **Automated quality gates** – Gradle runs unit tests + Checkstyle locally and in CI (see [`.github/workflows/mereb-jenkins.yml`](../.github/workflows/mereb-jenkins.yml)).

## Setup
1. **Register the library** under *Manage Jenkins → Configure System → Global Pipeline Libraries* (e.g., name it `mereb-jenkins`).
2. **Drop `.ci/ci.yml`** in each repo. The [pipeline config guide](docs/pipeline-config.md) + [JSON schema](docs/ci.schema.json) explain every key, validation rule, and default.
3. **Reference the library** from your Jenkinsfile:

```groovy
@Library('mereb-jenkins') _

ciV1(
  configPath: '.ci/ci.yml',
  bootstrapLabel: 'ubuntu-lts'
)
```

4. **Validate configs locally** before pushing: run `./gradlew test --tests org.mereb.ci.config.ConfigValidatorTest` or lint the YAML with `yq | ajv` (see below).

`ciV1` automatically fan-outs build → Docker → Terraform → deploy → release flows based on the normalized config map. Deployments now flow through `DeployPipeline`, which centralizes Helm values, approvals, repo credentials, and smoke hooks.

### Example Configs
- **pnpm monorepo**
  ```yaml
  version: 1
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
    platforms: [linux/amd64]
  deploy:
    order: [dev]
    dev:
      chart: infra/charts/web
      namespace: web-dev
  ```
- **Java/Gradle service with Terraform + release automation**
  ```yaml
  version: 1
  preset: java-gradle
  image:
    repository: ghcr.io/mereb/svc-auth
  terraform:
    path: infra/platform/terraform
    environments:
      dev:
        displayName: Dev EKS
        when: 'branch=main & !pr'
  release:
    autoTag:
      enabled: true
      when: '!pr'
      credential:
        id: git-token
        type: string
    github:
      enabled: true
      credential:
        id: gh-release-token
        type: string
  ```
- **Terraform-only automation** (no Docker build)
  ```yaml
  version: 1
  image: false
  terraform:
    path: infra/platform/terraform
    autoInstall: true
    environments:
      dev:
        displayName: Dev cluster
        when: 'branch=main & !pr'
        vars:
          environment: dev
      prd:
        displayName: Prod
        when: 'tag=^v'
        approval:
          before: true
  deploy:
    order: [] # Skip Helm deploys entirely
  ```

## Extending & Customizing
- **Repo credentials** – add `deploy.<env>.repoCredentials.id` (plus optional `usernameEnv`/`passwordEnv`) and the library handles Jenkins bindings + Helm repo auth consistently through `CredentialHelper`.
- **Additional stages** – append `build.stages` or `releaseStages` in `.ci/ci.yml` and target verbs exposed by `vars/ciSteps.groovy` (or add new ones under `src/org/mereb/ci`).
- **Migration help** – [`docs/migration-v1.md`](docs/migration-v1.md) tracks deadlines for the legacy `ci.yml`, `LEGACY_CONFIG`, and other removed behaviors.

## Validating Configs
Run the validator locally before pushing:

```bash
cd mereb-jenkins
./gradlew test --tests org.mereb.ci.config.ConfigValidatorTest
```

Or lint the actual YAML via the schema:

```bash
yq -o=json '.ci/ci.yml' | ajv validate -s mereb-jenkins/docs/ci.schema.json -d -
```

## Development
- `./gradlew test checkstyle` – run unit tests and lint.
- `./gradlew test --tests org.mereb.ci.build.PnpmPresetTest` – focus a single spec.
- `pnpm lint` – apply repository linting rules where relevant.

See [`docs/migration-v1.md`](docs/migration-v1.md) for the v0 → v1 changes, timelines for removing `ci.yml`, and how to extend the new presets.
