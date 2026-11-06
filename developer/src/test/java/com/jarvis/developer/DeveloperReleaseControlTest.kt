package com.jarvis.developer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeveloperReleaseControlTest {
    private val sha = "a".repeat(40)
    private val token = "github_pat_test_fixture_12345678"
    private val branch = "feature/jarvis-developer"
    private fun ref(value: String = sha) = JSONObject().put("object", JSONObject().put("sha", value))
    private fun run(value: String = sha, status: String = "completed", conclusion: String = "success") =
        JSONObject().put("id", 1234L).put("run_number", 100L)
            .put("head_sha", value).put("head_branch", branch)
            .put("status", status).put("conclusion", conclusion)
    private fun runs(vararg entries: JSONObject) =
        JSONObject().put("workflow_runs", JSONArray(entries.toList()))

    @Test fun readinessRequiresExactCanonicalCompletedPassingCi() {
        val reads = mutableListOf<String>()
        val client = DeveloperReleaseControl.Client(DeveloperReleaseControl.Transport { method, path, _, _ ->
            assertEquals("GET", method)
            reads += path
            if (path.startsWith("/git/ref/")) DeveloperReleaseControl.Reply(200, ref())
            else DeveloperReleaseControl.Reply(200, runs(run()))
        })
        assertEquals(DeveloperReleaseControl.Candidate(sha, 1234L), client.inspect(token))
        assertEquals(2, reads.size)
        assertTrue(reads[1].contains("head_sha=$sha"))
    }

    @Test fun noReleaseDispatchForRunningFailedWrongShaOrMissingCi() {
        for (mode in listOf("running", "failed", "missing", "wrong-sha", "moved")) {
            var posts = 0
            val client = DeveloperReleaseControl.Client(DeveloperReleaseControl.Transport { method, path, _, _ ->
                if (method == "POST") {
                    posts++
                    DeveloperReleaseControl.Reply(204)
                } else if (path.startsWith("/git/ref/")) {
                    DeveloperReleaseControl.Reply(200, ref(if (mode == "moved") "b".repeat(40) else sha))
                } else DeveloperReleaseControl.Reply(200, when (mode) {
                    "running" -> runs(run(status = "in_progress", conclusion = ""))
                    "failed" -> runs(run(conclusion = "failure"))
                    "missing" -> runs()
                    "wrong-sha" -> runs(run(value = "b".repeat(40)))
                    else -> runs(run(value = "b".repeat(40)))
                })
            })
            try { client.publish(token, sha); fail("Expected blocked release: $mode") }
            catch (_: IllegalArgumentException) { /* explicit precondition rejected */ }
            catch (_: IllegalStateException) { /* missing evidence rejected */ }
            assertEquals("No signing workflow dispatch for $mode", 0, posts)
        }
    }

    @Test fun explicitExactShaRechecksAndDispatchesOnceWithPinnedSourceAndNoInstall() {
        val methods = mutableListOf<String>()
        var payload: JSONObject? = null
        val client = DeveloperReleaseControl.Client(DeveloperReleaseControl.Transport { method, path, _, body ->
            methods += method
            when {
                method == "POST" -> {
                    assertEquals("/actions/workflows/release-jarvis-developer.yml/dispatches", path)
                    payload = body
                    DeveloperReleaseControl.Reply(204)
                }
                path.startsWith("/git/ref/") -> DeveloperReleaseControl.Reply(200, ref())
                else -> DeveloperReleaseControl.Reply(200, runs(run()))
            }
        })
        assertEquals(DeveloperReleaseControl.Candidate(sha, 1234), client.publish(token, sha))
        assertEquals(listOf("GET", "GET", "POST"), methods)
        assertEquals(branch, payload!!.getString("ref"))
        val inputs = payload!!.getJSONObject("inputs")
        assertEquals(sha, inputs.getString("expected_source_sha"))
        assertEquals("false", inputs.getString("defer_recovery"))
        assertFalse(payload.toString().contains("install", ignoreCase = true))
    }

    @Test fun chatCommandsRequireExplicitFullShaForPublication() {
        assertTrue(DeveloperReleaseChatCommand.check("Check Developer release readiness"))
        assertTrue(DeveloperReleaseChatCommand.check("Prepare Developer update!"))
        assertFalse(DeveloperReleaseChatCommand.check("Publish Developer update"))
        assertEquals(sha, DeveloperReleaseChatCommand.publishSha("Publish Developer update $sha"))
        assertNull(DeveloperReleaseChatCommand.publishSha("Publish Developer update"))
        assertNull(DeveloperReleaseChatCommand.publishSha("Publish Developer update ${sha.take(12)}"))
        assertNull(DeveloperReleaseChatCommand.publishSha("Publish Developer update $sha and install"))
        assertFalse(DeveloperReleaseChatCommand.publishAttempt("Can you publish the update?"))
    }
}
