package org.mereb.ci.deploy

import groovy.json.JsonSlurper

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Renders Helm values files from lightweight templates that can pull sensitive values from Vault.
 */
class ValuesTemplateRenderer implements Serializable {

    private final def steps

    ValuesTemplateRenderer(def steps) {
        this.steps = steps
    }

    List<String> render(String envName, Map envCfg) {
        List templates = envCfg?.valuesTemplates instanceof List ? envCfg.valuesTemplates : []
        if (!templates || templates.isEmpty()) {
            return []
        }
        List<String> rendered = []
        templates.eachWithIndex { Object entry, int idx ->
            Map templateCfg = entry instanceof Map ? entry as Map : [:]
            String templatePath = (templateCfg.template ?: templateCfg.path ?: '').toString().trim()
            if (!templatePath) {
                steps.error("deploy.${envName}: valuesTemplates[${idx}] is missing 'template'")
            }
            if (!steps.fileExists(templatePath)) {
                steps.error("deploy.${envName}: template file '${templatePath}' not found")
            }
            String outputPath = (templateCfg.output ?: templateCfg.destination ?: '').toString().trim()
            if (!outputPath) {
                outputPath = ".ci/.rendered/${envName}-${idx}.yaml"
            }
            ensureParentDirectory(outputPath)
            Map replacements = resolveReplacements(templateCfg)
            String templateContent = steps.readFile(templatePath)
            String renderedContent = applyTemplate(templateContent, replacements)
            steps.writeFile(file: outputPath, text: renderedContent)
            rendered << outputPath
        }
        return rendered
    }

    private Map resolveReplacements(Map templateCfg) {
        Map replacements = [:]
        Map vars = templateCfg?.vars instanceof Map ? templateCfg.vars as Map : [:]
        Map vaultDefaults = templateCfg?.vault instanceof Map ? templateCfg.vault as Map : [:]
        vars.each { Object key, Object rawValue ->
            if (!key) {
                return
            }
            String name = key.toString()
            replacements[name] = resolveValue(name, rawValue, vaultDefaults)
        }
        return replacements
    }

    private String resolveValue(String name, Object rawValue, Map vaultDefaults) {
        if (rawValue instanceof Map) {
            Map spec = rawValue as Map
            if (spec.containsKey('value')) {
                return spec.value?.toString() ?: ''
            }
            Map effectiveVault = [:]
            effectiveVault.putAll(vaultDefaults ?: [:])
            if (spec.vault instanceof Map) {
                effectiveVault.putAll(spec.vault as Map)
            }
            if (spec.path) {
                effectiveVault.path = spec.path
            }
            if (spec.vaultPath) {
                effectiveVault.path = spec.vaultPath
            }
            if (spec.field) {
                effectiveVault.field = spec.field
            }
            if (spec.vaultField) {
                effectiveVault.field = spec.vaultField
            }
            if (!effectiveVault.isEmpty()) {
                return fetchVaultValue(name, effectiveVault)
            }
        }
        return rawValue?.toString() ?: ''
    }

    private String fetchVaultValue(String placeholder, Map cfg) {
        String url = (cfg.url ?: cfg.baseUrl ?: '').toString().trim()
        String path = (cfg.path ?: cfg.secretPath ?: '').toString().trim()
        String field = (cfg.field ?: cfg.secretField ?: placeholder)?.toString()?.trim()
        String tokenEnv = (cfg.tokenEnv ?: 'VAULT_TOKEN').toString().trim()
        String engine = (cfg.engine ?: cfg.kv ?: 'kv2').toString().trim().toLowerCase()
        if (!url || !path) {
            steps.error("valuesTemplates: vault url/path must be provided for placeholder '${placeholder}'")
        }
        String token = readEnv(tokenEnv)
        if (!token) {
            steps.error("valuesTemplates: environment variable '${tokenEnv}' is empty while resolving '${placeholder}'")
        }
        Map secretData = readVaultSecret(url, path, token, engine == 'kv1' ? 'kv1' : 'kv2')
        Object resolved = extractField(secretData, field)
        if (resolved == null) {
            steps.error("valuesTemplates: field '${field}' not found in Vault secret '${path}'")
        }
        return resolved.toString()
    }

    private Map readVaultSecret(String baseUrl, String path, String token, String engine) {
        String root = baseUrl.replaceAll(/\/+$/, '')
        String secretPath = path.replaceAll(/^\/+/, '')
        URL target = new URL("${root}/v1/${secretPath}")
        HttpURLConnection conn = (HttpURLConnection) target.openConnection()
        conn.setRequestMethod('GET')
        conn.setRequestProperty('X-Vault-Token', token)
        conn.setConnectTimeout(10000)
        conn.setReadTimeout(10000)
        conn.connect()
        int status = conn.responseCode
        String payload = ''
        if (status >= 200 && status < 300) {
            payload = conn.inputStream?.getText('UTF-8') ?: ''
        } else {
            payload = conn.errorStream?.getText('UTF-8') ?: ''
            steps.error("Vault request to '${secretPath}' failed (HTTP ${status}): ${payload}")
        }
        def parsed = payload ? new JsonSlurper().parseText(payload) : [:]
        if (!(parsed instanceof Map)) {
            return [:]
        }
        Map data = parsed as Map
        if (engine == 'kv2') {
            if (data.data instanceof Map && data.data.data instanceof Map) {
                return data.data.data as Map
            }
            return [:]
        }
        if (data.data instanceof Map) {
            return data.data as Map
        }
        return [:]
    }

    private Object extractField(Map data, String fieldPath) {
        if (!fieldPath) {
            return null
        }
        Object current = data
        fieldPath.split(/\./).each { String part ->
            if (!(current instanceof Map)) {
                current = null
                return
            }
            current = (current as Map).get(part)
            if (current == null) {
                return
            }
        }
        return current
    }

    private String applyTemplate(String content, Map replacements) {
        String rendered = content
        replacements.each { String key, String value ->
            Pattern pattern = Pattern.compile("\\{\\{\\s*${Pattern.quote(key)}\\s*\\}\\}")
            Matcher matcher = pattern.matcher(rendered)
            rendered = matcher.replaceAll(Matcher.quoteReplacement(value ?: ''))
        }
        return rendered
    }

    private void ensureParentDirectory(String path) {
        File file = new File(path)
        File parent = file.parentFile
        if (parent) {
            String dir = parent.path
            if (dir?.trim()) {
                steps.sh(script: "mkdir -p '${dir}'")
            }
        }
    }

    private String readEnv(String name) {
        if (!name?.trim()) {
            return ''
        }
        String safe = name.replaceAll(/[^A-Za-z0-9_]/, '')
        if (!safe) {
            return ''
        }
        return steps.sh(script: "printenv ${safe} || true", returnStdout: true).trim()
    }
}
