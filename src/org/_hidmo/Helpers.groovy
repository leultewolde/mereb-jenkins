package org._hidmo

/**
 * Helper utilities for CI pipeline condition evaluation.
 */
class Helpers implements Serializable {

    static boolean matchCondition(String cond, Object envCtx) {
        if (!cond || cond.trim().isEmpty()) {
            return true
        }

        List<String> parts = []
        for (String segment : cond.split(/&/)) {
            parts << segment.trim()
        }
        boolean ok = true
        for (p in parts) {
            if (p == 'pr') {
                ok &= (envValue(envCtx, 'CHANGE_ID')?.trim())
            } else if (p == '!pr') {
                ok &= !(envValue(envCtx, 'CHANGE_ID')?.trim())
            } else if (p.startsWith('branch=')) {
                def val = p.substring('branch='.length())
                ok &= matchBranch(envValue(envCtx, 'BRANCH_NAME') ?: '', val)
            } else if (p.startsWith('tag=')) {
                def re = p.substring('tag='.length())
                ok &= (envValue(envCtx, 'TAG_NAME') ?: '') ==~ (re)
            } else {
                ok = false
            }

            if (!ok) {
                return false
            }
        }
        return ok
    }

    private static boolean matchBranch(String actual, String pattern) {
        if (pattern =~ /[\^\$\.\*\+\?\[\]\(\)\|\\]/) {
            return (actual ?: '') ==~ (pattern)
        }
        return (actual ?: '') == (pattern)
    }

    private static String envValue(Object envCtx, String key) {
        if (envCtx instanceof Map) {
            def v = ((Map) envCtx)[key]
            return v?.toString()
        }
        if (envCtx?.metaClass?.respondsTo(envCtx, 'getEnvironment')) {
            def envMap = envCtx.getEnvironment()
            if (envMap instanceof Map) {
                def v = envMap[key]
                if (v != null) {
                    return v.toString()
                }
            }
        }
        try {
            def v = envCtx?.getProperty(key)
            return v?.toString()
        } catch (MissingPropertyException ignored) {
            return null
        }
    }
}
