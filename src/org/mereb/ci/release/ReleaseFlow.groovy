package org.mereb.ci.release

import groovy.json.JsonOutput
import org.mereb.ci.credentials.CredentialHelper
import org.mereb.ci.Helpers
import org.mereb.ci.util.ApprovalHelper
import org.mereb.ci.util.PipelineUtils

import java.net.URI
import java.net.URLEncoder

import static org.mereb.ci.util.PipelineUtils.mapToEnvList
import static org.mereb.ci.util.PipelineUtils.shellEscape

/**
 * Encapsulates release automation (auto-tagging, release stages, GitHub releases).
 */
class ReleaseFlow implements Serializable {

    private final def steps
    private final CredentialHelper credentialHelper
    private final Closure verbRunner
    private final ApprovalHelper approvalHelper

    ReleaseFlow(def steps, CredentialHelper credentialHelper, Closure verbRunner) {
        this.steps = steps
        this.credentialHelper = credentialHelper
        this.verbRunner = verbRunner
        this.approvalHelper = new ApprovalHelper(steps)
    }

    void runReleaseStages(List<Map> stages) {
        if (!stages || stages.isEmpty()) {
            return
        }
        String effectiveTag = (steps.env.RELEASE_TAG ?: steps.env.TAG_NAME ?: '').trim()
        if (!effectiveTag) {
            steps.echo 'Release stages skipped; release tag not available.'
            return
        }
        stages.each { Map stageCfg ->
            String name = stageCfg.name ?: 'Release Task'
            String whenCond = stageCfg.when ?: ''
            if (whenCond && !Helpers.matchCondition(whenCond, steps.env)) {
                steps.echo "Release stage '${name}' skipped by condition '${whenCond}'"
                return
            }
            steps.stage(name) {
                maybeRequestApproval(stageCfg.approval as Map, "Run '${name}'?")
                List<String> envList = mapToEnvList(stageCfg.env instanceof Map ? stageCfg.env : [:])
                envList << "RELEASE_TAG=${effectiveTag}"
                Map bindingSource = [credentials: stageCfg.credentials]
                List<Map> bindings = credentialHelper.bindingsFor(bindingSource)

                Closure execute = {
                    if (stageCfg.verb) {
                        verbRunner.call(stageCfg.verb as String)
                    } else if (stageCfg.sh) {
                        steps.sh stageCfg.sh as String
                    } else {
                        steps.echo "Stage '${name}' has no action."
                    }
                }

                Closure wrapped = execute
                if (envList && !envList.isEmpty()) {
                    wrapped = { steps.withEnv(envList) { execute() } }
                }
                credentialHelper.withOptionalCredentials(bindings, wrapped)
            }
        }
    }

    void handleRelease(Map releaseCfg, Map state) {
        Map autoTag = releaseCfg?.autoTag instanceof Map ? (releaseCfg.autoTag as Map) : [:]
        Map cred = autoTag.get('credential') instanceof Map ? (autoTag.get('credential') as Map) : [:]
        String configuredId = cred?.get('id')?.toString()
        if (!configuredId) {
            configuredId = autoTag.get('credentialId')?.toString()
        }
        autoTag.credentialId = configuredId
        if (!(autoTag.enabled as Boolean)) {
            return
        }
        steps.echo "AutoTag credential id: ${configuredId ?: 'none'}"
        if (!Helpers.matchCondition(autoTag.when as String, steps.env)) {
            steps.echo "Release auto-tag skipped by condition '${autoTag.when}'"
            return
        }

        steps.stage(autoTag.stageName ?: 'Create Release Tag') {
            if ((steps.env.TAG_NAME ?: '').trim()) {
                steps.echo "Tag build detected (${steps.env.TAG_NAME}); skipping auto-tag."
                return
            }

            requestAutoTagApproval(autoTag.approval)

            cleanWorkspaceForTag(autoTag)

            String treeStatus = steps.sh(script: 'git status --porcelain', returnStdout: true).trim()
            if (treeStatus && !(autoTag.allowDirty as Boolean)) {
                steps.error 'Working tree contains uncommitted changes; refusing to create a release tag.'
            } else if (treeStatus) {
                steps.echo "Working tree has uncommitted changes but continuing because 'allowDirty' is enabled."
            }

            if (autoTag.skipIfTagged as Boolean) {
                String currentTag = steps.sh(script: 'git describe --tags --exact-match 2>/dev/null || true', returnStdout: true).trim()
                if (currentTag) {
                    steps.echo "HEAD already tagged with '${currentTag}'; skipping auto-tag."
                    return
                }
            }

            List<String> envVars = mapToEnvList(autoTag.env ?: [:])
            def runTag = {
                createAndPushTag(autoTag, state)
            }
            def maybeEnv = {
                if (envVars && !envVars.isEmpty()) {
                    steps.withEnv(envVars) { runTag() }
                } else {
                    runTag()
                }
            }
            withRepoCredential(autoTag, maybeEnv)
        }
    }

    private void requestAutoTagApproval(Map approval) {
        approvalHelper.request(approval, 'Create release tag?')
    }

    private void maybeRequestApproval(Map approval, String defaultMessage) {
        approvalHelper.request(approval, defaultMessage)
    }

    void publishRelease(Map releaseCfg, Map state) {
        Map githubCfg = releaseCfg?.github ?: [:]
        String tag = (steps.env.RELEASE_TAG ?: '').trim()
        if (!(githubCfg.enabled as Boolean)) {
            return
        }
        if (!tag) {
            steps.echo 'Release tag not available; skipping GitHub release.'
            return
        }
        if (!Helpers.matchCondition(githubCfg.when as String, steps.env)) {
            steps.echo "GitHub release skipped by condition '${githubCfg.when}'"
            return
        }

        String repo = determineGithubRepo(githubCfg.repo)
        if (!repo?.trim()) {
            steps.echo 'Unable to determine GitHub repository; skipping release.'
            return
        }
        String apiBase = (githubCfg.apiUrl ?: 'https://api.github.com').toString().replaceAll(/\/$/, '')
        Map payload = [
            tag_name               : tag,
            target_commitish       : state.commit ?: 'HEAD',
            name                   : renderReleaseTemplate(githubCfg.nameTemplate ?: tag, state, tag),
            body                   : renderReleaseTemplate(githubCfg.bodyTemplate ?: '', state, tag),
            draft                  : githubCfg.draft ?: false,
            prerelease             : githubCfg.prerelease ?: false,
            generate_release_notes : githubCfg.containsKey('generateReleaseNotes') ? githubCfg.generateReleaseNotes : true
        ]
        if (!payload.body?.trim()) {
            payload.remove('body')
        }
        if (githubCfg.discussionCategory) {
            payload.discussion_category_name = githubCfg.discussionCategory
        }

        String payloadJson = JsonOutput.toJson(payload)
        String apiUrl = "${apiBase}/repos/${repo}/releases"
        String checkUrl = "${apiBase}/repos/${repo}/releases/tags/${tag}"

        Map credential = githubCfg.credential ?: [:]
        List<Map> bindings = []
        String mode = (credential.type ?: 'token').toString().toLowerCase()
        String script
        if (mode in ['basic', 'usernamepassword']) {
            String userEnv = credential.usernameEnv ?: 'GITHUB_USERNAME'
            String passEnv = credential.passwordEnv ?: 'GITHUB_PASSWORD'
            bindings << steps.usernamePassword(credentialsId: credential.id, usernameVariable: userEnv, passwordVariable: passEnv)
            script = basicReleaseScript(tag, repo, checkUrl, apiUrl, payloadJson, userEnv, passEnv)
        } else {
            String tokenEnv = credential.tokenEnv ?: 'GITHUB_TOKEN'
            bindings << steps.string(credentialsId: credential.id, variable: tokenEnv)
            script = tokenReleaseScript(tag, repo, checkUrl, apiUrl, payloadJson, tokenEnv)
        }

        steps.withCredentials(bindings) {
            steps.sh script: script, label: 'Publish GitHub release'
        }
    }

    private String basicReleaseScript(String tag, String repo, String checkUrl, String apiUrl, String payloadJson, String userEnv, String passEnv) {
        String userRef = '$' + "{${userEnv}}"
        String passRef = '$' + "{${passEnv}}"
        String template = '''#!/usr/bin/env bash
set -euo pipefail
set +x
TAG=%s
REPO=%s
if [ -z "%s" ] || [ -z "%s" ]; then
  echo "GitHub credentials unavailable; skipping release." >&2
  exit 1
fi
auth="%s:%s"
CHECK_STATUS=$(curl -s -o /dev/null -w '%%{http_code}' -u "${auth}" -H "Accept: application/vnd.github+json" %s || true)
if [ "$CHECK_STATUS" = "200" ]; then
  echo "GitHub release for ${TAG} already exists; skipping."
  exit 0
fi
curl -sSf -X POST -u "${auth}" -H "Accept: application/vnd.github+json" -H "Content-Type: application/json" --data %s %s > /dev/null
echo "Published GitHub release ${TAG} to ${REPO}"
'''.stripIndent()
        return String.format(template, tag, repo, userRef, passRef, userRef, passRef, shellEscape(checkUrl), shellEscape(payloadJson), shellEscape(apiUrl))
    }

    private String tokenReleaseScript(String tag, String repo, String checkUrl, String apiUrl, String payloadJson, String tokenEnv) {
        String tokenRef = '$' + "{${tokenEnv}}"
        return '''#!/usr/bin/env bash
set -euo pipefail
set +x
TAG=%s
REPO=%s
if [ -z "%s" ]; then
  echo "GitHub token unavailable; skipping release." >&2
  exit 1
fi
auth_header="Authorization: Bearer %s"
CHECK_STATUS=$(curl -s -o /dev/null -w '%%{http_code}' -H "${auth_header}" -H "Accept: application/vnd.github+json" %s || true)
if [ "$CHECK_STATUS" = "200" ]; then
  echo "GitHub release for ${TAG} already exists; skipping."
  exit 0
fi
curl -sSf -X POST -H "${auth_header}" -H "Accept: application/vnd.github+json" -H "Content-Type: application/json" --data %s %s > /dev/null
echo "Published GitHub release ${TAG} to ${REPO}"
''' .stripIndent()
        return String.format(script, tag, repo, tokenRef, tokenRef, shellEscape(checkUrl), shellEscape(payloadJson), shellEscape(apiUrl))
    }

    private void cleanWorkspaceForTag(Map autoTag) {
        if (!(autoTag.clean as Boolean)) {
            return
        }
        steps.sh 'git reset --hard HEAD'
        steps.sh 'git clean -fd'
    }

    private void createAndPushTag(Map autoTag, Map state) {
        String remote = (autoTag.remote ?: 'origin').toString()
        String prefix = (autoTag.prefix ?: 'v').toString()
        String bump = (autoTag.bump ?: 'patch').toString()
        boolean annotated = autoTag.annotated as Boolean
        boolean push = autoTag.push as Boolean
        String message = (autoTag.message ?: "Automated release for ${state.commitShort}").toString()
        String user = (autoTag.gitUser ?: 'Mereb CI').toString()
        String email = (autoTag.gitEmail ?: 'ci@mereb.local').toString()

        withRepoCredential(autoTag) {
            steps.sh "git fetch --tags --quiet ${shellEscape(remote)} || true"
        }

        String pattern = "${prefix}[0-9]*"
        String latest = steps.sh(script: "git tag --list ${shellEscape(pattern)} --sort=-version:refname | head -n1", returnStdout: true).trim()
        Map next = computeNextTag(prefix, latest, bump)
        String nextTag = (next.tag ?: '').toString()
        if (!nextTag?.trim()) {
            steps.error 'Failed to compute next release tag.'
        }

        String exists = steps.sh(script: "git rev-parse --quiet --verify refs/tags/${shellEscape(nextTag)} >/dev/null 2>&1 && echo yes || true", returnStdout: true).trim()
        if ('yes'.equalsIgnoreCase(exists)) {
            steps.echo "Tag ${nextTag} already exists; skipping auto-tag."
            return
        }

        steps.sh "git config user.name ${shellEscape(user)}"
        steps.sh "git config user.email ${shellEscape(email)}"

        if (annotated) {
            steps.sh "git tag -a ${shellEscape(nextTag)} -m ${shellEscape(message)}"
        } else {
            steps.sh "git tag ${shellEscape(nextTag)}"
        }

        if (push) {
            withRepoCredential(autoTag) {
                steps.sh "git push ${shellEscape(remote)} ${shellEscape(nextTag)}"
            }
        } else {
            steps.echo "Auto-tag push disabled; created local tag ${nextTag}"
        }

        steps.env.RELEASE_TAG = nextTag
        steps.env.TAG_NAME = nextTag
        steps.echo "Created release tag ${nextTag}"
    }

    private Map computeNextTag(String prefix, String latest, String bump) {
        int major = 0
        int minor = 0
        int patch = 0
        boolean haveLatest = latest?.trim()

        if (haveLatest) {
            String base = latest.startsWith(prefix) ? latest.substring(prefix.length()) : latest
            List<String> parts = base.tokenize('.')
            major = parseIntOr(parts, 0, 0)
            minor = parseIntOr(parts, 1, 0)
            patch = parseIntOr(parts, 2, 0)
        }

        switch (bump) {
            case 'major':
                major += 1
                minor = 0
                patch = 0
                break
            case 'minor':
                minor += 1
                patch = 0
                break
            default:
                patch += 1
                break
        }

        String tag = "${prefix}${major}.${minor}.${patch}"
        return [tag: tag, major: major, minor: minor, patch: patch]
    }

    private int parseIntOr(List parts, int index, int fallback) {
        if (index >= (parts?.size() ?: 0)) {
            return fallback
        }
        String raw = parts[index]?.toString()
        if (!raw) {
            return fallback
        }
        try {
            return Integer.parseInt(raw)
        } catch (NumberFormatException ignore) {
            return fallback
        }
    }

    private void withRepoCredential(Map autoTag, Closure body) {
        Map sourceMap = autoTag instanceof Map ? autoTag : [:]
        Map cred = [:]
        Object embedded = sourceMap.get('credential')
        if (embedded instanceof Map) {
            cred.putAll(embedded as Map)
        } else {
            Object idVal = sourceMap.get('credentialId')
            if (idVal) {
                cred.id = idVal
                cred.type = sourceMap.get('credentialType') ?: 'usernamePassword'
                cred.usernameEnv = sourceMap.get('usernameEnv')
                cred.passwordEnv = sourceMap.get('passwordEnv')
                cred.tokenEnv = sourceMap.get('tokenEnv')
                cred.tokenUser = sourceMap.get('tokenUser')
            }
        }
        if (!cred.id) {
            body()
            return
        }

        String remote = (autoTag.remote ?: 'origin').toString()
        String originalUrl = ''
        boolean hasRemote = true
        try {
            originalUrl = steps.sh(script: "git remote get-url ${shellEscape(remote)}", returnStdout: true).trim()
        } catch (Exception ignored) {
            hasRemote = false
        }

        Closure restore = {
            if (hasRemote && originalUrl) {
                steps.withEnv(["REMOTE=${remote}".toString(), "URL=${originalUrl}".toString()]) {
                    steps.sh 'git remote set-url "$REMOTE" "$URL"'
                }
            }
        }

        switch ((cred.type ?: 'usernamePassword').toString()) {
            case 'string':
                String tokenEnv = cred.tokenEnv ?: 'GIT_TOKEN'
                String tokenUser = cred.tokenUser ?: 'x-access-token'
                steps.withCredentials([steps.string(credentialsId: cred.id, variable: tokenEnv)]) {
                    if (hasRemote && originalUrl) {
                        String tokenVal = resolveEnvVar(tokenEnv)
                        String updated = injectCredentialsIntoUrl(originalUrl, tokenUser, tokenVal ?: '')
                        steps.withEnv(["REMOTE=${remote}".toString(), "URL=${updated}".toString()]) {
                            steps.sh 'git remote set-url "$REMOTE" "$URL"'
                        }
                    }
                    try {
                        body()
                    } finally {
                        restore()
                    }
                }
                break
            default:
                String userEnv = cred.usernameEnv ?: 'GIT_USERNAME'
                String passEnv = cred.passwordEnv ?: 'GIT_PASSWORD'
                steps.withCredentials([steps.usernamePassword(credentialsId: cred.id, usernameVariable: userEnv, passwordVariable: passEnv)]) {
                    if (hasRemote && originalUrl) {
                        String userVal = resolveEnvVar(userEnv) ?: ''
                        String passVal = resolveEnvVar(passEnv) ?: ''
                        String updated = injectCredentialsIntoUrl(originalUrl, userVal, passVal)
                        steps.withEnv(["REMOTE=${remote}".toString(), "URL=${updated}".toString()]) {
                            steps.sh 'git remote set-url "$REMOTE" "$URL"'
                        }
                    }
                    try {
                        body()
                    } finally {
                        restore()
                    }
                }
                break
        }
    }

    private String injectCredentialsIntoUrl(String remoteUrl, String username, String password) {
        if (!remoteUrl) {
            return remoteUrl
        }
        if (!(remoteUrl.startsWith('http://') || remoteUrl.startsWith('https://'))) {
            return remoteUrl
        }
        String scheme = remoteUrl.startsWith('https://') ? 'https://' : 'http://'
        String remainder = remoteUrl.substring(scheme.length())
        String safeUser = urlEncode(username ?: '')
        String safePass = password ? urlEncode(password) : ''
        String userInfo = safePass ? "${safeUser}:${safePass}@" : "${safeUser}@"
        if (remainder.startsWith(userInfo)) {
            return scheme + remainder
        }
        return scheme + userInfo + remainder
    }

    private String urlEncode(String value) {
        try {
            return URLEncoder.encode(value ?: '', 'UTF-8').replace('+', '%20')
        } catch (Exception ignored) {
            return value ?: ''
        }
    }

    private String determineGithubRepo(String configured) {
        if (configured?.trim()) {
            return configured.trim().replaceAll(/\.git$/, '')
        }
        try {
            String remote = steps.sh(script: 'git remote get-url origin', returnStdout: true).trim()
            String parsed = parseGithubRepoFromUrl(remote)
            return parsed?.replaceAll(/\.git$/, '')
        } catch (Exception e) {
            steps.echo "Failed to determine GitHub repository: ${e.message}"
            return ''
        }
    }

    private String parseGithubRepoFromUrl(String url) {
        if (!url) {
            return ''
        }
        String cleaned = url.trim()
        if (cleaned.endsWith('.git')) {
            cleaned = cleaned.substring(0, cleaned.length() - 4)
        }
        if (cleaned.startsWith('git@')) {
            int idx = cleaned.indexOf(':')
            if (idx >= 0 && idx + 1 < cleaned.length()) {
                return cleaned.substring(idx + 1)
            }
            return ''
        }
        try {
            URI uri = new URI(cleaned)
            String path = uri.getPath() ?: ''
            path = path.replaceAll('^/', '')
            return path
        } catch (Exception ignored) {
            // Fallback to simple stripping
        }
        int githubIdx = cleaned.indexOf('github.com/')
        if (githubIdx >= 0) {
            return cleaned.substring(githubIdx + 'github.com/'.length())
        }
        return cleaned
    }

    private String renderReleaseTemplate(String template, Map state, String tag) {
        if (!template) {
            return ''
        }
        Map<String, String> tokens = [
            tag         : tag,
            commit      : state.commit ?: '',
            commit_short: state.commitShort ?: '',
            branch      : state.branch ?: '',
            image_tag   : state.imageTag ?: '',
            build       : state.buildNumber ?: ''
        ]
        String result = template
        tokens.each { k, v ->
            result = result.replace("{{${k}}}", v ?: '')
        }
        return result
    }

    private String resolveEnvVar(String name) {
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
