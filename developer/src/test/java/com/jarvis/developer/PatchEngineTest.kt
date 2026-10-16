package com.jarvis.developer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class PatchEngineTest {
    private val sourcePath = "developer/src/main/java/com/jarvis/developer/TaskFileSummary.kt"
    private val testPath = "developer/src/test/java/com/jarvis/developer/TaskFileSummaryTest.kt"
    private val original = "package com.jarvis.developer\n\nobject TaskFileSummary { fun count() = 1 }\n"
    private val changed = original.replace("= 1", "= 2")

    private fun sha(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size}\u0000".toByteArray())
        return digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun request(paths: List<String> = listOf(sourcePath), originals: List<String?> = listOf(original)) = PatchEngine.Request(
        "summary-1", "Improve task file summaries", "a".repeat(40), paths,
        paths.zip(originals).map { (path, text) -> PatchEngine.SourceFile(path, text?.let(::sha), text) },
        "Fixture", "deterministic-v1"
    )
    private fun json(vararg edits: Pair<String, String>, summary: String = "Improve file summaries") = JSONObject()
        .put("summary", summary).put("edits", JSONArray(edits.map { (path, text) -> JSONObject().put("path", path).put("content", text) })).toString()
    private fun proposal() = PatchEngine.parse(request(), json(sourcePath to changed))
    private fun rejects(part: String = "", block: () -> Unit) {
        try { block(); fail("Expected rejection") }
        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(part)) }
    }

    @Test fun acceptsTwoFileProposalWithDeterministicFullDiffAndExplicitApproval() {
        val test = "package com.jarvis.developer\nclass TaskFileSummaryTest\n"
        val scope = request(listOf(sourcePath, testPath), listOf(original, null))
        val result = PatchEngine.parse(scope, json(testPath to test, sourcePath to changed))
        assertEquals(listOf(sourcePath, testPath).sorted(), result.edits.map { it.path })
        assertTrue(result.diff.contains("-$original".substringBefore('\n')) || result.diff.contains("-object TaskFileSummary"))
        assertTrue(result.diff.contains("+object TaskFileSummary { fun count() = 2 }"))
        assertTrue(result.diff.contains("--- /dev/null"))
        assertTrue(result.diff.contains("new file mode 100644"))
        assertEquals(result.diff, PatchEngine.parse(scope, json(sourcePath to changed, testPath to test)).diff)
        assertEquals(result.id, PatchEngine.parse(scope, json(sourcePath to changed, testPath to test)).id)
        assertTrue(result.id.matches(Regex("[0-9a-f]{64}")))
        assertEquals("feature/jarvis-developer-task/summary-1-${result.id.take(12)}", PatchEngine.branchName(result))
        rejects("Exact") { PatchEngine.approve(result, result.id.take(12)) }
        rejects("Exact") { PatchEngine.approve(result, "another-proposal") }
        PatchEngine.validateApproval(PatchEngine.approve(result, result.id))
        assertEquals(original, scope.sources.first().content)
        assertNull(scope.sources.last().content)
    }

    @Test fun acceptsKotlinJavaXmlAndStaticGradleDependencyEdits() {
        val java = "developer/src/main/java/com/jarvis/developer/TaskCount.java"
        val xml = "developer/src/main/res/values/task_count.xml"
        val gradle = "developer/build.gradle"
        val beforeGradle = "plugins { id 'com.android.application' }\nandroid { namespace 'com.jarvis.developer' }\ndependencies {\n    implementation 'androidx.core:core:1.12.0'\n}\n"
        val scope = request(listOf(sourcePath, java, xml, gradle), listOf(original, null, "<resources><string name=\"task_count\">One</string></resources>\n", beforeGradle))
        val result = PatchEngine.parse(scope, json(sourcePath to changed,
            java to "package com.jarvis.developer;\npublic class TaskCount { public int count() { return 2; } }\n",
            xml to "<resources><string name=\"task_count\">Two</string></resources>\n",
            gradle to beforeGradle.replace("1.12.0", "1.13.1")))
        assertEquals(4, result.edits.size)
        assertTrue(result.diff.contains("-    implementation 'androidx.core:core:1.12.0'"))
        assertTrue(result.diff.contains("+    implementation 'androidx.core:core:1.13.1'"))
        PatchEngine.revalidate(result)
    }

    @Test fun supportsKotlinGradleLiteralDependencies() {
        val path = "developer/build.gradle.kts"
        val before = "plugins { id(\"com.android.application\") }\ndependencies {\n    implementation(\"androidx.core:core:1.12.0\")\n}\n"
        val result = PatchEngine.parse(request(listOf(path), listOf(before)), json(path to before.replace("1.12.0", "1.13.1")))
        assertTrue(result.diff.contains("+    implementation(\"androidx.core:core:1.13.1\")"))
    }

    @Test fun rejectsTraversalOtherApplicationsAndSafetyConfiguration() {
        for (path in listOf("../developer/Task.kt", "developer/../app/Task.kt", "developer//src/Task.kt",
            "app/src/main/java/com/assistant/core/MainActivity.kt", ".github/workflows/developer.yml", "docs/UI_THEMING.md",
            "developer/src/main/AndroidManifest.xml", "developer/local.properties", "developer/src/main/res/values/secrets.xml",
            "developer/src/main/res/xml/developer_file_paths.xml", "developer/src/main/java/com/jarvis/developer/CodingAgent.kt",
            "developer/src/main/java/com/jarvis/developer/MainActivity.kt", "developer/src/main/java/com/jarvis/developer/PatchEngine.kt",
            "developer/src/main/java/com/jarvis/developer/PatchWorkbench.kt", "developer/src/main/java/com/jarvis/developer/RepositoryIntelligence.kt",
            "developer/src/main/java/com/jarvis/developer/TaskPlanner.kt", "developer/src/main/java/com/jarvis/developer/TaskPlanStore.kt",
            "developer/src/main/java/com/jarvis/developer/TaskPlanningWorkbench.kt",
            "developer/src/main/java/com/jarvis/developer/GithubPlans.kt", "developer/src/main/java/com/jarvis/developer/ModelAdapter.kt",
            "developer/src/main/java/com/jarvis/developer/UpdateChannel.kt", "developer/src/main/java/com/jarvis/developer/SigningConfig.kt",
            "developer/src/main/java/com/jarvis/developer/A%2fB.kt", "developer/src/main/java/com/jarvis/developer/A\\B.kt")) {
            assertFalse(path, PatchEngine.allowedPath(path))
            rejects { PatchEngine.validateRequest(request(listOf(path), listOf(null))) }
        }
        assertTrue(PatchEngine.allowedPath(sourcePath))
        assertTrue(PatchEngine.allowedPath(testPath))
    }

    @Test fun rejectsMissingOrForgedSourceIdentitiesAndIncompleteScopes() {
        rejects("SHA") { PatchEngine.validateRequest(request().copy(baseCommitSha = "main")) }
        rejects("SHA") { PatchEngine.validateRequest(request().copy(sources = listOf(PatchEngine.SourceFile(sourcePath, "b".repeat(40), original)))) }
        rejects("both") { PatchEngine.validateRequest(request().copy(sources = listOf(PatchEngine.SourceFile(sourcePath, null, original)))) }
        rejects("both") { PatchEngine.validateRequest(request().copy(sources = listOf(PatchEngine.SourceFile(sourcePath, sha(original), null)))) }
        rejects("scope") { PatchEngine.validateRequest(request().copy(sources = emptyList())) }
        rejects("Duplicate") { PatchEngine.validateRequest(request().copy(allowedPaths = listOf(sourcePath, sourcePath))) }
        rejects { PatchEngine.validateRequest(request().copy(taskId = "../bad")) }
        rejects { PatchEngine.validateRequest(request().copy(taskId = "x".repeat(49))) }
    }

    @Test fun rejectsOutOfScopeDuplicateNoopEmptyAndUnknownPatchOperations() {
        rejects("scope") { PatchEngine.parse(request(), json(testPath to changed)) }
        rejects("Duplicate") { PatchEngine.parse(request(), json(sourcePath to changed, sourcePath to changed)) }
        rejects("No-op") { PatchEngine.parse(request(), json(sourcePath to original)) }
        rejects("Empty") { PatchEngine.parse(request(), json(sourcePath to "")) }
        rejects("bounded") { PatchEngine.parse(request(), json()) }
        rejects("Unknown") { PatchEngine.parse(request(), json(sourcePath to changed).dropLast(1) + ",\"delete\":true}") }
        rejects("Unknown") { PatchEngine.parse(request(), "{\"summary\":\"x\",\"edits\":[{\"path\":\"$sourcePath\",\"content\":\"x\",\"rename\":\"other\"}]}") }
    }

    @Test fun strictJsonRejectsDuplicateKeysTailsCommentsFencesAndCoercions() {
        val valid = json(sourcePath to changed)
        for (text in listOf("$valid {}", "```json\n$valid\n```", "/*comment*/$valid", valid.dropLast(1) + ",}",
            "{\"summary\":\"one\",\"summary\":\"two\",\"edits\":[]}", "{\"summary\":1,\"edits\":[]}",
            "{\"summary\":\"x\",\"edits\":[{\"path\":\"$sourcePath\",\"path\":\"$sourcePath\",\"content\":\"x\"}]}",
            "{\"summary\":\"x\",\"edits\":[],}", "{'summary':'x','edits':[]}")) {
            rejects { PatchEngine.parse(request(), text) }
        }
    }

    @Test fun rejectsMalformedAndExternalEntityXml() {
        val path = "developer/src/main/res/layout/task_count.xml"
        val scope = request(listOf(path), listOf(null))
        for (xml in listOf("<resources>", "<!DOCTYPE x SYSTEM 'file:///etc/passwd'><x/>",
            "<!DOCTYPE x [<!ENTITY value SYSTEM 'https://example.com/secret'>]><x>&value;</x>", "<x>&unbound;</x>", "<x android:id=\"x\"/>")) {
            rejects { PatchEngine.parse(scope, json(path to xml)) }
        }
    }

    @Test fun rejectsShellReflectionUnicodeEscapeAndChangedPackages() {
        for (body in listOf("Runtime.getRuntime().exec(\"echo\")", "ProcessBuilder(\"sh\").start()", "Class.forName(\"example\")",
            "target.getDeclaredMethod(\"run\")", "method.invoke(target)", "System.loadLibrary(\"unsafe\")", "System.load(\"unsafe\")",
            "java.lang.reflect.Method::class", "\\u0052untime.getRuntime()",
            "Class.forName /*gap*/ (\"java.lang.Run\" + \"time\").getMethod /*gap*/ (\"get\" + \"Runtime\").invoke /*gap*/ (null)",
            "System /* gap */ . load /* gap */ (\"unsafe\")", "javaClass.getDeclaredConstructor /* gap */ ().newInstance /* gap */ ()")) {
            rejects("execution") { PatchEngine.parse(request(), json(sourcePath to "package com.jarvis.developer\nfun action() { $body }\n")) }
        }
        rejects("package") { PatchEngine.parse(request(), json(sourcePath to changed.replace("com.jarvis.developer", "com.jarvis.other"))) }
        rejects("package") { PatchEngine.parse(request(), json(sourcePath to "class TaskFileSummary")) }
    }

    @Test fun rejectsGradlePluginsTasksExecCredentialsAndSigningChanges() {
        val path = "developer/build.gradle"
        val before = "plugins { id 'com.android.application' }\nandroid { namespace 'com.jarvis.developer' }\ndependencies {\n    implementation 'androidx.core:core:1.12.0'\n}\n"
        val scope = request(listOf(path), listOf(before))
        for (after in listOf(before.replace("com.android.application", "unsafe.plugin"), before + "task unsafe { }\n",
            before + "exec { commandLine 'sh' }\n", before.replace("implementation 'androidx.core:core:1.12.0'", "implementation project(':app')"),
            before.replace("com.jarvis.developer", "com.jarvis.beta"), before + "signingConfigs { release { storeFile file('x') } }\n")) {
            rejects("Gradle") { PatchEngine.parse(scope, json(path to after)) }
        }
        rejects("New Gradle") { PatchEngine.validateRequest(request(listOf(path), listOf(null))) }
    }

    @Test fun rejectsRecognizedSecretsBeforePromptAndBeforeDiff() {
        val secret = "ghp_" + "x".repeat(30)
        rejects("credential") { PatchEngine.validateRequest(request().copy(taskId = secret)) }
        rejects("credential") { PatchEngine.validateRequest(request().copy(task = "Use $secret")) }
        val secretPath = "developer/src/main/java/com/jarvis/developer/$secret.kt"
        rejects("credential") { PatchEngine.validateRequest(request(listOf(secretPath), listOf(null))) }
        rejects("credential") { PatchEngine.validateRequest(request(listOf(sourcePath), listOf(original + "// $secret\n"))) }
        rejects("credential") { PatchEngine.parse(request(), json(sourcePath to changed + "val apiKey = \"not-a-real-key\"\n")) }
        rejects("credential") { PatchEngine.parse(request(), json(sourcePath to changed, summary = secret)) }
        rejects("credential") { PatchEngine.parse(request(), json(sourcePath to changed + "val password: String = \"private\"\n")) }
        val resource = "developer/src/main/res/values/task_text.xml"
        val xml = "<resources><string translatable=\"false\" name=\"api_key\">private-value</string></resources>"
        rejects("credential") { PatchEngine.validateRequest(request(listOf(resource), listOf(xml))) }
        rejects("credential") { PatchEngine.parse(request(listOf(resource), listOf(null)), json(resource to xml)) }
        val array = "<resources><string-array name=\"api_keys\"><item>private-value</item></string-array></resources>"
        rejects("credential") { PatchEngine.validateRequest(request(listOf(resource), listOf(array))) }
        val encoded = "<resources><string name=\"task_text\">ghp&#95;${"x".repeat(30)}</string></resources>"
        rejects("credential") { PatchEngine.validateRequest(request(listOf(resource), listOf(encoded))) }
    }

    @Test fun rejectsProtectedXmlPackageSigningAndUpdateResourceChanges() {
        val path = "developer/src/main/res/values/task_text.xml"
        for (name in listOf("update_channel", "package_name", "signing_certificate", "up&#100;ate_url")) {
            val before = "<resources><string name=\"$name\">original</string></resources>\n"
            rejects("Protected") { PatchEngine.parse(request(listOf(path), listOf(before)), json(path to before.replace("original", "changed"))) }
        }
        val multiline = "<resources><string name\n=\"update_channel\">original</string></resources>\n"
        rejects("Protected") { PatchEngine.parse(request(listOf(path), listOf(multiline)), json(path to multiline.replace("original", "changed"))) }
    }

    @Test fun rejectsBinaryUnpairedUnicodeOversizedFilesAndScopes() {
        rejects("Binary") { PatchEngine.parse(request(), json(sourcePath to changed + '\u0000')) }
        rejects("Unicode") { PatchEngine.parse(request(), json(sourcePath to changed + '\uD800')) }
        rejects("byte budget") { PatchEngine.parse(request(), json(sourcePath to changed + "x".repeat(PatchEngine.MAX_FILE_BYTES))) }
        val paths = (1..9).map { "developer/src/main/java/com/jarvis/developer/Count$it.kt" }
        rejects("1..8") { PatchEngine.validateRequest(request(paths, paths.map { null })) }
        val large = original + "//" + "x".repeat(140_000)
        rejects("total") { PatchEngine.validateRequest(request(paths.take(3), List(3) { large })) }
        val scoped = request(paths.take(3), List(3) { null })
        rejects("total") { PatchEngine.parse(scoped, json(*paths.take(3).map { it to large }.toTypedArray())) }
    }

    @Test fun fingerprintsBindAllMetadataSourcesSummaryAndDiff() {
        val result = proposal()
        for (changedRequest in listOf(result.request.copy(taskId = "another"), result.request.copy(task = "Another task"),
            result.request.copy(baseCommitSha = "b".repeat(40)), result.request.copy(provider = "Other"), result.request.copy(model = "other"))) {
            rejects("fingerprint") { PatchEngine.revalidate(PatchEngine.Proposal(changedRequest, result.edits, result.summary, result.id, result.diff)) }
        }
        rejects("fingerprint") { PatchEngine.revalidate(PatchEngine.Proposal(result.request, result.edits, "Changed summary", result.id, result.diff)) }
        rejects("fingerprint") { PatchEngine.revalidate(PatchEngine.Proposal(result.request, listOf(PatchEngine.Edit(sourcePath, changed.replace("= 2", "= 3"))), result.summary, result.id, result.diff)) }
        rejects("diff") { PatchEngine.approve(PatchEngine.Proposal(result.request, result.edits, result.summary, result.id, "clipped"), result.id) }
        val alteredSource = original.replace("= 1", "= 0")
        val altered = result.request.copy(sources = listOf(PatchEngine.SourceFile(sourcePath, sha(alteredSource), alteredSource)))
        rejects("fingerprint") { PatchEngine.revalidate(PatchEngine.Proposal(altered, result.edits, result.summary, result.id, result.diff)) }
    }

    @Test fun approvalRechecksMutationAndParserSnapshotsCallerLists() {
        val paths = mutableListOf(sourcePath)
        val sources = mutableListOf(PatchEngine.SourceFile(sourcePath, sha(original), original))
        val result = PatchEngine.parse(request().copy(allowedPaths = paths, sources = sources), json(sourcePath to changed))
        paths.clear(); sources.clear()
        PatchEngine.revalidate(result)
        val mutableEdits = result.edits.toMutableList()
        val mutableProposal = PatchEngine.Proposal(result.request, mutableEdits, result.summary, result.id, result.diff)
        val approved = PatchEngine.approve(mutableProposal, result.id)
        mutableEdits[0] = PatchEngine.Edit(sourcePath, changed.replace("= 2", "= 3"))
        rejects("fingerprint") { PatchEngine.validateApproval(approved) }
    }

    @Test fun unifiedDiffKeepsRealContextAndMarksMissingFinalNewlines() {
        val before = "package com.jarvis.developer\n// alpha\n// beta\n// gamma\n// delta\n// epsilon\n// zeta\n// eta\n// theta\nclass TaskFileSummary"
        val after = before.replace("// alpha", "// new alpha").replace("class TaskFileSummary", "class TaskFileSummary { val count = 2 }") + "\n"
        val result = PatchEngine.parse(request(originals = listOf(before)), json(sourcePath to after))
        assertTrue(result.diff.contains("-// alpha\n+// new alpha\n // beta"))
        assertTrue(result.diff.contains("-class TaskFileSummary\n\\ No newline at end of file\n+class TaskFileSummary { val count = 2 }\n"))
        assertTrue(result.diff.contains("@@ -1,5 +1,5 @@"))
        assertTrue(result.diff.contains("@@ -7,4 +7,4 @@"))
        assertFalse(result.diff.contains("[REDACTED]"))
        assertFalse(result.diff.contains("clipped"))
    }

    @Test fun boundedDiffRejectsComplexOrHugeReviewsRatherThanClipping() {
        val before = original + (1..1500).joinToString("\n") { "// line $it" }
        rejects("comparison budget") { PatchEngine.parse(request(originals = listOf(before)), json(sourcePath to before.replace("line", "changed"))) }
        val longBefore = original + "//" + "a".repeat(130_000)
        val longAfter = changed + "//" + "b".repeat(130_000)
        rejects("review budget") { PatchEngine.parse(request(originals = listOf(longBefore)), json(sourcePath to longAfter)) }
    }

    @Test(timeout = 10_000) fun largeNonmatchingIdentifierTextHasBoundedValidationCost() {
        val before = original + "//" + "a".repeat(190_000)
        val result = PatchEngine.parse(request(originals = listOf(before)), json(sourcePath to before.replace("= 1", "= 2")))
        PatchEngine.revalidate(result)
    }

    @Test(timeout = 10_000) fun largeBlankRunsHaveBoundedPackageValidationAndDiffCost() {
        val before = original + "\n".repeat(190_000)
        rejects("comparison budget") { PatchEngine.parse(request(originals = listOf(before)), json(sourcePath to before.replace("= 1", "= 2"))) }
        val nearMiss = "package com.jarvis.developer" + " ".repeat(190_000) + "!\nclass TaskFileSummary\n"
        rejects("package") { PatchEngine.parse(request(), json(sourcePath to nearMiss)) }
    }

    @Test(timeout = 10_000) fun largeCredentialKeywordWhitespaceNearMissHasBoundedCost() {
        val before = original + "// password" + " ".repeat(190_000) + "x"
        val result = PatchEngine.parse(request(originals = listOf(before)), json(sourcePath to before.replace("= 1", "= 2")))
        PatchEngine.revalidate(result)
    }

    @Test(timeout = 10_000) fun rejectsDeepXmlWithinBoundedValidation() {
        val path = "developer/src/main/res/layout/task_count.xml"
        val xml = "<x>".repeat(1000) + "</x>".repeat(1000)
        rejects("complexity") { PatchEngine.parse(request(listOf(path), listOf(null)), json(path to xml)) }
    }
}
