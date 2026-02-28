package org.mereb.ci.util

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Shared helpers for manipulating pipeline-friendly data structures and templates.
 */
class PipelineUtils implements Serializable {

    static List<String> toStringList(Object raw) {
        List<String> list = []
        if (raw instanceof Collection) {
            raw.each { if (it != null) list << it.toString() }
        } else if (raw != null) {
            list << raw.toString()
        }
        return list
    }

    static Map<String, String> toStringMap(Object raw) {
        Map<String, String> map = [:]
        if (raw instanceof Map) {
            (raw as Map).each { k, v ->
                if (k != null && v != null) {
                    map[k.toString()] = v.toString()
                }
            }
        }
        return map
    }

    static List<String> mapToEnvList(Map data) {
        if (!(data instanceof Map) || data.isEmpty()) {
            return []
        }
        List<String> envList = []
        (data as Map).each { k, v ->
            envList << "${k}=${v}"
        }
        return envList
    }

    static String boolString(boolean value) {
        return value ? 'true' : 'false'
    }

    static Map templateContext(Map state) {
        return [
            commit     : state.commit ?: '',
            commitShort: state.commitShort ?: '',
            branch     : state.branch ?: '',
            branchSlug : state.branchSanitized ?: '',
            changeId   : state.changeId ?: '',
            isPr       : state.isPr ?: '',
            imageTag   : state.imageTag ?: '',
            imageDigest: state.imageDigest ?: '',
            buildNumber: state.buildNumber ?: '',
            tagName    : state.tagName ?: '',
            repository : state.repository ?: ''
        ]
    }

    static String renderTemplate(String template, Map ctx) {
        if (!template) return ''
        String result = template
        ctx.each { k, v ->
            Pattern pattern = Pattern.compile("\\{\\{\\s*${Pattern.quote(k)}\\s*\\}\\}")
            Matcher matcher = pattern.matcher(result)
            result = matcher.replaceAll(Matcher.quoteReplacement(v ?: ''))
        }
        return result
    }

    static String sanitizeBranch(String branch) {
        if (!branch) {
            return ''
        }
        String cleaned = branch.replaceAll(/[^A-Za-z0-9_.-]+/, '-')
        cleaned = cleaned.replaceAll(/^-+/, '').replaceAll(/-+$/, '')
        return cleaned.toLowerCase()
    }

    static String parentDir(String path) {
        if (!path) {
            return null
        }
        int idx = path.lastIndexOf('/')
        if (idx <= 0) {
            return null
        }
        return path.substring(0, idx)
    }

    static String shellEscape(String s) {
        if (s == null) {
            return "''"
        }
        return "'${s.replace("'", "'\"'\"'")}'"
    }

    static String resolveEnvVar(def steps, String name) {
        if (!name?.trim()) {
            return ''
        }
        String trimmed = name.trim()
        if (!(trimmed ==~ /^[A-Za-z_][A-Za-z0-9_]*$/)) {
            steps.echo "Invalid environment variable name '${trimmed}'; skipping lookup."
            return ''
        }
        StringBuilder script = new StringBuilder()
        script.append('#!/bin/sh\n')
        script.append('set +x\n')
        script.append('if [ -z "${').append(trimmed).append('+x}" ]; then\n')
        script.append('  exit 0\n')
        script.append('fi\n')
        script.append('printf \'%s\' "${').append(trimmed).append('}"\n')
        return steps.sh(script: script.toString(), returnStdout: true).trim()
    }
}
