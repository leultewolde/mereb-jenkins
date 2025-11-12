# v1 Migration Notes

This document captures the intentional breaking changes between the legacy `ciV0` library and the new modular `ciV1` implementation.

## Highlights
- **Config lives in `.ci/ci.yml`** – the legacy `ci.yml` path still works but prints a deprecation warning. Support will be permanently removed after **December 2024**.
- **Preset-specific logic moved to classes** – pnpm, Docker, Terraform, and release behaviour can be unit-tested and extended under `src/org/mereb/ci/**`.
- **Deployments flow through `DeployPipeline`** – Helm releases, repo credentials, and approvals are no longer hand-written per Jenkinsfile.
- **Credential handling centralised** – all pipeline stages now go through `CredentialHelper`, ensuring consistent masking and audit logging.
- **Config validation** – `ConfigValidator` fails the build when required fields are missing (e.g., `image.repository` or `app.name`). Warnings highlight optional but recommended keys.

## Recommended Migration Flow
1. **Create `.ci/ci.yml`** using the schema and samples in [`docs/pipeline-config.md`](pipeline-config.md).
2. **Remove legacy keys** such as `LEGACY_CONFIG`, `releaseStages` arrays with inline shell, or direct Docker push commands. Use `build.stages`/`deploy`/`release` sections instead.
3. **Pin pnpm/preset versions** if you previously relied on global installations.
4. **Adopt Terraform environments** instead of ad-hoc shell stages. Deferred/tag gated environments are now first-class.
5. **Turn on GitHub releases** via `release.github` once the auto-tag credential is wired.

## Timeline
| Date | Action |
| --- | --- |
| _Now_ | v1 available; `ci.yml` still supported with warnings. |
| 2024-10 | Jenkins jobs will refuse to run without `.ci/ci.yml`. |
| 2024-12 | Legacy `ci.yml` loader removed. |

Reach out in `#mereb-platform` if you need migration help.
