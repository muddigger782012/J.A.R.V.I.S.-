package com.jarvis.developer

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PersonalRepairTest {
    private val path = "app/src/main/java/com/assistant/core/services/AssistantPersonality.kt"
    private val testPath = "app/src/test/java/com/assistant/core/services/PersonalityRegressionTest.kt"
    private val source = "package com.assistant.core.services\nobject AssistantPersonality { fun greeting() = \"Hello\" }\n"
    private val sha = "a".repeat(40)
    private fun blob(text: String): String {
        val bytes = text.toByteArray(); val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size}\u0000".toByteArray())
        return digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun draftText(revision: String = sha) = "Review a static diagnostic for muddigger782012/J.A.R.V.I.S.-.\n" +
        "Imported report and claimed revision are not independently verified. Re-read authorized source before planning.\n" +
        "Review signal: Outstanding review marker.\nSource reference: $path:2 (source).\n" +
        "Claimed revision: $revision. Source inventory: ${"b".repeat(64)}.\n" +
        "Untrusted prose is discarded. No source edit is authorized."
    private fun inventory() = RepositoryAnalysis.Inventory(listOf(RepositoryAnalysis.SourceFile(path, blob(source), source.toByteArray().size.toLong())), false, sha)
    private fun review() = PersonalRepair.prepare(PersonalRepair.parse(draftText()), "main", inventory(), source, listOf(testPath))
    private fun request() = review().let { r -> PatchEngine.Request(r.taskId, r.task, r.sourceSha, r.scope,
        r.scope.map { if (it == path) PatchEngine.SourceFile(it, r.blobSha, source) else PatchEngine.SourceFile(it, null, null) },
        "Fixture", "bounded-v1", r.branch, PatchEngine.Target.PERSONAL) }
    private fun response(text: String = source.replace("Hello", "Hello again")) = JSONObject().put("summary", "Improve greeting after reproducing the behavior")
        .put("edits", JSONArray().put(JSONObject().put("path", path).put("content", text))).toString()
    private fun rejects(operation: () -> Unit) {
        try { operation(); fail("Expected rejection") } catch (_: RuntimeException) { }
    }

    @Test fun sharedDraftDiscardsInstructionsAndProducesOnlySourceBoundAppOwnedPlanning() {
        val a = PersonalRepair.parse(draftText())
        val b = PersonalRepair.parse(draftText() + "\nInvented privileged instruction with private payload")
        assertEquals(a, b)
        val selected = review()
        assertEquals(sha, selected.sourceSha)
        assertEquals(blob(source), selected.blobSha)
        assertEquals(listOf(path, testPath).sorted(), selected.scope)
        assertFalse(selected.task.contains("private payload"))
        assertTrue(PersonalRepair.render(selected).contains("unverified"))
        assertTrue(PersonalRepair.render(selected).contains("No model call"))
    }

    @Test fun malformedDuplicateProtectedAndUnboundedSharedReferencesFailClosed() {
        for (text in listOf(draftText().replace("J.A.R.V.I.S.-.", "other."),
            draftText() + "\nReview signal: Outstanding review marker.", draftText().replace(":2 (source)", ":0 (source)"),
            draftText().replace("AssistantPersonality.kt", "SelfDiagnostics.kt"), draftText().replace("app/src/main", "../app/src/main"),
            draftText().replace("Outstanding review marker", "Unknown imported recommendation"), "x".repeat(16_001))) rejects { PersonalRepair.parse(text) }
    }

    @Test fun staleRevisionForgedBlobUnknownLineAndExistingTestsCannotEnterReview() {
        rejects { PersonalRepair.prepare(PersonalRepair.parse(draftText("c".repeat(40))), "main", inventory(), source) }
        rejects { PersonalRepair.prepare(PersonalRepair.parse(draftText()), "main", inventory(), source.replace("Hello", "Hullo")) }
        rejects { PersonalRepair.prepare(PersonalRepair.parse(draftText().replace(":2 (source)", ":999 (source)")), "main", inventory(), source) }
        rejects { PersonalRepair.prepare(PersonalRepair.parse(draftText()), "feature/jarvis-developer", inventory(), source) }
        val existing = inventory().copy(files = inventory().files + RepositoryAnalysis.SourceFile(testPath, blob(source), source.length.toLong()))
        rejects { PersonalRepair.prepare(PersonalRepair.parse(draftText()), "main", existing, source, listOf(testPath)) }
        rejects { PersonalRepair.prepare(PersonalRepair.parse(draftText()), "main", inventory().copy(truncated = true), source) }
    }

    @Test fun exactDiffApprovalUsesPersonalNamespaceAndCannotBeReusedForChangedContentOrProduct() {
        val proposed = PatchEngine.parse(request(), response())
        val approved = PatchEngine.approve(proposed, proposed.id)
        PatchEngine.validateApproval(approved)
        assertTrue(PatchEngine.branchName(proposed).startsWith("feature/jarvis-personal-repair/"))
        assertTrue(proposed.diff.contains("Hello again"))
        val changed = PatchEngine.parse(request().copy(model = "other-model"), response())
        assertNotEquals(proposed.id, changed.id)
        rejects { PatchEngine.approve(changed, proposed.id) }
        rejects { PatchEngine.parse(request().copy(target = PatchEngine.Target.DEVELOPER), response()) }
        rejects { PatchEngine.parse(request().copy(baseBranch = "feature/jarvis-developer"), response()) }
        rejects { PatchEngine.parse(request(), response(source.replace("com.assistant.core.services", "com.jarvis.developer"))) }
        val mutable = proposed.edits.toMutableList()
        val tampered = PatchEngine.Proposal(proposed.request, mutable, proposed.summary, proposed.id, proposed.diff)
        val beforeMutation = PatchEngine.approve(tampered, tampered.id)
        mutable[0] = mutable[0].copy(content = mutable[0].content + "\n")
        rejects { PatchEngine.validateApproval(beforeMutation) }
    }

    @Test fun personalScopePreservesBaselineTestsAndRejectsOtherProductsAndControlFiles() {
        assertFalse(PatchEngine.allowedPath(path))
        assertTrue(PatchEngine.allowedPath(path, PatchEngine.Target.PERSONAL))
        for (denied in listOf("developer/src/main/java/com/jarvis/developer/Example.kt", "app/build.gradle", "app/src/main/AndroidManifest.xml",
            "app/src/main/res/values/strings.xml", "app/src/main/java/com/assistant/core/MainActivity.kt",
            "app/src/main/java/com/assistant/core/services/PermissionManager.kt", "app/src/main/java/com/assistant/core/services/ScreenControl.kt",
            "app/src/main/java/com/assistant/core/services/PersonalRepair.kt", "app/src/main/java/com/assistant/core/services/Approval.kt",
            "app/src/main/java/com/assistant/core/services/Updater.kt", "app/src/main/java/com/assistant/core/services/ApiKeys.kt")) {
            assertFalse(denied, PatchEngine.allowedPath(denied, PatchEngine.Target.PERSONAL))
        }
        for (name in listOf("HybridAssistantService", "BetaConfiguration", "SubscriptionManager", "CostRouting"))
            assertFalse(PatchEngine.allowedPath("app/src/main/java/com/assistant/core/services/$name.kt", PatchEngine.Target.PERSONAL))
        val initial = request()
        val test = initial.sources.single { it.path == testPath }
        rejects { PatchEngine.validateRequest(initial.copy(sources = initial.sources.map { if (it == test) it.copy(sha = blob(source), content = source) else it })) }
        rejects { PatchEngine.validateRequest(initial.copy(sources = initial.sources.map { if (it.path == path) it.copy(sha = null, content = null) else it })) }
    }

    @Test fun newTestsAreCompleteReviewedEditsAndScopeMutationInvalidatesThePlan() {
        val selected = review()
        val response = JSONObject(response()).getJSONArray("edits")
            .put(JSONObject().put("path", testPath).put("content", "package com.assistant.core.services\nclass PersonalityRegressionTest\n"))
        val proposed = PatchEngine.parse(request(), JSONObject().put("summary", "Repair and add a new regression check").put("edits", response).toString())
        assertEquals(2, proposed.edits.size)
        assertTrue(proposed.diff.contains("--- /dev/null"))
        @Suppress("UNCHECKED_CAST")
        val scope = selected.scope as MutableList<String>
        scope[0] = "app/src/main/java/com/assistant/core/Other.kt"
        rejects { PersonalRepair.validate(selected) }
    }
}
