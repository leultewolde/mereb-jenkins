package org.mereb.ci.ai

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import static org.mereb.ci.util.PipelineUtils.shellEscape

/**
 * Deepseek AI client (OpenAI-compatible chat API). Requires an API token credential.
 */
class DeepseekClient extends AiClient {

    private final def steps
    private final Map cfg

    DeepseekClient(def steps, Map cfg = [:]) {
        this.steps = steps
        this.cfg = cfg ?: [:]
    }

    @Override
    AiSuggestion suggest(Map context) {
        if (!steps) {
            return new AiSuggestion()
        }
        String credentialId = (cfg.credentialId ?: cfg.tokenCredentialId ?: '').toString().trim()
        if (!credentialId) {
            return new AiSuggestion()
        }

        String endpoint = (cfg.endpoint ?: 'https://api.deepseek.com/v1/chat/completions').toString()
        String model = (cfg.model ?: 'deepseek-chat').toString()
        int maxTokens = (cfg.maxTokens ?: 600) as int
        int timeout = (cfg.timeoutSeconds ?: 30) as int

        Map promptCtx = [
            branch : context?.state?.branch ?: '',
            tag    : context?.state?.tagName ?: '',
            commit : context?.state?.commit ?: '',
            packages: (context?.config?.buildStages ?: []).collect { it?.env?.PNPM_PACKAGE_NAME ?: it?.env?.PNPM_PACKAGE_DIR ?: it?.name }.findAll { it }
        ]

        String systemPrompt = 'You are a release assistant. Respond ONLY with a JSON object: {"bumpTypes":{"pkgName":"patch|minor|major",...},"changeset":"markdown summary"}.'
        String userPrompt = "Context: ${JsonOutput.toJson(promptCtx)}"

        Map body = [
            model    : model,
            messages : [
                [role: 'system', content: systemPrompt],
                [role: 'user', content: userPrompt]
            ],
            temperature: 0,
            max_tokens : maxTokens,
            response_format: [type: 'json_object']
        ]

        String payload = JsonOutput.toJson(body)
        String tokenVar = 'DEEPSEEK_TOKEN'
        String script = [
            '#!/usr/bin/env bash',
            'set -euo pipefail',
            'set +x',
            'PAYLOAD_FILE="$(mktemp)"',
            "printf %s ${shellEscape(payload)} > \"\$PAYLOAD_FILE\"",
            "curl -sS --max-time ${timeout} -H \"Content-Type: application/json\" -H \"Authorization: Bearer ${'$'}${tokenVar}\" \\",
            "  -d @\"\\$PAYLOAD_FILE\" ${shellEscape(endpoint)}"
        ].join('\n')

        String response = ''
        try {
            steps.withCredentials([steps.string(credentialsId: credentialId, variable: tokenVar)]) {
                response = steps.sh(script: script, returnStdout: true).trim()
            }
        } catch (Exception e) {
            steps.echo "Deepseek AI call failed: ${e.message}"
            return new AiSuggestion()
        }

        if (!response) {
            return new AiSuggestion()
        }

        try {
            Map parsed = new JsonSlurper().parseText(response) as Map
            String content = parsed?.choices?.getAt(0)?.message?.content?.toString()
            if (!content) {
                return new AiSuggestion()
            }
            Map structured = new JsonSlurper().parseText(content) as Map
            AiSuggestion suggestion = new AiSuggestion()
            if (structured.bumpTypes instanceof Map) {
                suggestion.bumpTypes = (structured.bumpTypes as Map).collectEntries { k, v -> [(k?.toString() ?: ''): v?.toString()] }.findAll { it.key }
            }
            if (structured.changeset) {
                suggestion.changeset = structured.changeset.toString()
            }
            return suggestion
        } catch (Exception e) {
            steps.echo "Failed to parse Deepseek AI response: ${e.message}"
            return new AiSuggestion()
        }
    }
}
