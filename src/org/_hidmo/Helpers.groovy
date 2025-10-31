package org._hidmo

/**
 * Helper utilities for CI pipeline condition evaluation.
 */
class Helpers implements Serializable {

    static boolean matchCondition(String cond, Map env) {
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
                ok &= (env.CHANGE_ID?.trim())
            } else if (p == '!pr') {
                ok &= !(env.CHANGE_ID?.trim())
            } else if (p.startsWith('branch=')) {
                def val = p.substring('branch='.length())
                ok &= matchBranch(env.BRANCH_NAME ?: '', val)
            } else if (p.startsWith('tag=')) {
                def re = p.substring('tag='.length())
                ok &= (env.TAG_NAME ?: '') ==~ (re)
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
}
