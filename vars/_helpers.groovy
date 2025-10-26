// vars/_helpers.groovy
//
// Small helper namespace used from ciV1.groovy
//

class Helpers implements Serializable {

    static boolean matchCondition(String cond, Map env) {
        // Empty -> run
        if (!cond || cond.trim().isEmpty()) return true

        // Split by '&' (AND) only for v2 schema
        def parts = cond.split(/&/)*.trim()
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
            if (!ok) return false
        }
        return ok
    }

    private static boolean matchBranch(String actual, String pattern) {
        // Treat as regex if it contains regex metachars (^ $ . * + ? [ ])
        if (pattern =~ /[\^\$\.\*\+\?\[\]\(\)\|\\]/) {
            return (actual ?: '') ==~ (pattern)
        }
        return (actual ?: '') == (pattern)
    }
}
