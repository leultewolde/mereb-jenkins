# AI Client Integration Guide

The shared library includes a minimal AI abstraction so future releases can auto-suggest bump types and changeset text without blocking current pipelines.

## Components
- `AiClient` – abstract base (`src/org/mereb/ci/ai/AiClient.groovy`)
- `AiSuggestion` – value object with `bumpTypes` and `changeset` fields
- `NullAiClient` – no-op implementation (default)
- `AiFactory` – picks an implementation based on `cfg.ai.provider`

## Wiring a real client
1. **Add credentials** to Jenkins (e.g., OpenAI/Anthropic API key). Avoid hard-coding tokens.
2. **Implement a client** under `src/org/mereb/ci/ai`:
   ```groovy
   package org.mereb.ci.ai

   class MyAiClient extends AiClient {
     @Override
     AiSuggestion suggest(Map ctx) {
       // ctx.state, ctx.config, ctx.env are available
       // Build a prompt from git diff + commit messages
       // Call your model; parse bump types and optional changeset text
       AiSuggestion s = new AiSuggestion()
       s.bumpTypes = [ '@mereb/app-feed': 'patch' ] // example
       s.changeset = '## Changes\n- …'
       return s
     }
   }
   ```
3. **Update `AiFactory`** to select your client:
   ```groovy
   switch (provider) {
     case 'my-ai':
       return new MyAiClient()
     default:
       return new NullAiClient()
   }
   ```
4. **Pass config in `.ci/ci.yml`** (optional keys; safe defaults remain no-op):
   ```yaml
   ai:
     provider: my-ai
     model: gpt-4o
     credentialId: openai-token
   ```
5. **Use credentials** inside the client via `steps.withCredentials(...)` to set `Authorization` headers for HTTP calls. Keep timeouts short to avoid blocking the pipeline.

## Runtime behavior
- ciV1 invokes `aiClient.suggest(...)` before Docker/release steps.
- If the suggestion contains data, it writes `.ci/ai-changeset.md` and exports:
  - `AI_CHANGESET_PATH` – absolute path to the file
  - `AI_CHANGESET` – raw changeset text
  - `AI_BUMP_TYPES` – JSON map of package → bump type
- Downstream stages can consume these env vars to drive versioning/publishing. If the AI returns nothing or is disabled, behavior is unchanged.

## Guardrails
- Keep AI optional; never block the pipeline on AI failures. Return an empty `AiSuggestion` on errors.
- Log prompts/responses as artifacts only if they don’t leak secrets.
- Enforce a max timeout and payload size to avoid hangs.
