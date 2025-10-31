import org.jenkinsci.plugins.workflow.cps.NonCPS

def call(Map args = [:]) {
    String label = (args.name ?: args.environment ?: 'Smoke').toString()
    String url = args.url ? args.url.toString() : null
    String script = firstNonNull(args.script, args.command, args.cmd)
    Map headers = args.headers instanceof Map ? args.headers : [:]

    int retries = parsePositive(args.retries ?: args.retry, 0)
    int delaySeconds = parsePositive(args.delay, 10)
    int timeoutSeconds = parseDuration(args.timeout ?: args.maxTime ?: '60s')

    if (!url && !script) {
        error "runSmoke: supply either 'url' or 'script'/'command'"
    }

    int attempt = 0
    int totalAttempts = retries + 1

    while (attempt < totalAttempts) {
        attempt++
        try {
            echo "runSmoke: ${label} attempt ${attempt}/${totalAttempts}"
            if (timeoutSeconds > 0) {
                timeout(time: timeoutSeconds, unit: 'SECONDS') {
                    executeSmoke(url, script, headers)
                }
            } else {
                executeSmoke(url, script, headers)
            }
            return
        } catch (err) {
            if (attempt >= totalAttempts) {
                throw err
            }
            echo "runSmoke: attempt ${attempt} failed: ${err}. Retrying after ${delaySeconds}s"
            sleep time: delaySeconds, unit: 'SECONDS'
        }
    }
}

private void executeSmoke(String url, String script, Map headers) {
    if (url) {
        runCurl(url, headers)
    } else {
        sh script
    }
}

private void runCurl(String url, Map headers) {
    List<String> cmd = ["curl", "-fsS", "--retry", "0", "--connect-timeout", "10"]
    headers.each { k, v ->
        cmd << "--header"
        cmd << headerPair(k.toString(), v.toString())
    }
    cmd << url
    sh cmd.collect { q(it) }.join(' ')
}

private static String firstNonNull(Object... values) {
    values.find { it != null }?.toString()
}

private static String headerPair(String key, String value) {
    return "${key}: ${value}"
}

@NonCPS
private static int parsePositive(Object raw, int defaultVal) {
    if (raw == null) {
        return defaultVal
    }
    try {
        int v = raw.toString().toInteger()
        return v >= 0 ? v : defaultVal
    } catch (NumberFormatException ignore) {
        return defaultVal
    }
}

@NonCPS
private static int parseDuration(Object raw) {
    if (raw == null) {
        return 0
    }
    String text = raw.toString().trim().toLowerCase()
    if (text.isEmpty()) {
        return 0
    }
    try {
        if (text.endsWith('ms')) {
            return Math.max(1, (text[0..-3] as BigDecimal) / 1000 as int)
        }
        if (text.endsWith('s')) {
            return Math.max(1, (text[0..-2] as BigDecimal).intValue())
        }
        if (text.endsWith('m')) {
            return Math.max(1, ((text[0..-2] as BigDecimal) * 60).intValue())
        }
        if (text.endsWith('h')) {
            return Math.max(1, ((text[0..-2] as BigDecimal) * 3600).intValue())
        }
        return Math.max(1, text.toInteger())
    } catch (NumberFormatException ignore) {
        return 60
    }
}

@NonCPS
private static String q(String s) {
    return "'${s.replace("'", "'\"'\"'")}'"
}
