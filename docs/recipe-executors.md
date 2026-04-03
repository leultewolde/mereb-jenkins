# Recipe Executors

This document is for maintainers working on the internal split behind `ciV1`.

## Ownership Boundary

Core owns:

- config loading, validation, and normalization
- recipe resolution
- delivery policy and runtime state creation
- workspace and agent handling
- shared service construction
- common phases: build stages, matrix stages, AI suggestions

Recipe executors own:

- the sequencing that is unique to a recipe after the common phases complete
- recipe-specific release timing
- orchestration of the shared services for their flow

## Current Recipe Set

- `build`: build-only flows
- `package`: package publishing and release automation
- `image`: image build and release automation without deploys
- `service`: image build, deploy, and release automation
- `microfrontend`: microfrontend publish and release automation
- `terraform`: terraform execution and release automation

## Dispatch Rules

Resolution order:

1. explicit `recipe`
2. terraform environments
3. microfrontend environments
4. deploy environments
5. image orchestration
6. release automation or release stages
7. build-only fallback

Keep the resolver and the validator aligned. If a new recipe is added, update both the auto-detection logic and the compatibility validation rules in the same change.

## Design Rule

Do not add recipe-specific branching back into `vars/ciV1.groovy`.

If a behavior is shared across all recipes, it belongs in the core runtime or a shared helper. If it changes sequencing for only one recipe family, it belongs in that recipe executor.
