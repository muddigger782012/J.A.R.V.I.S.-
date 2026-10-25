package com.jarvis.developer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.TimeZone
import java.util.zip.ZipInputStream

class ApplicationScaffoldsTest {
    private fun spec(platform: ApplicationPlatform = ApplicationPlatform.ANDROID) = ApplicationSpecification(
        "Focus counter", "Show my focus goal and count each recorded action.", platform,
        "FocusCounter", "io.example.focuscounter"
    )
    private fun approvedZip(plan: ApplicationScaffoldPlan): ByteArray = ApplicationScaffolds.exportZip(
        plan, ApplicationScaffolds.approve(plan, plan.fingerprint, true)
    )
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected bounded scaffold rejection") }
        catch (_: IllegalArgumentException) { /* The API rejects before returning an export capability. */ }
    }
    private fun files(plan: ApplicationScaffoldPlan) = plan.files.associate { it.path to it.content }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                assertFalse(entry.isDirectory)
                assertFalse(result.containsKey(entry.name))
                result[entry.name] = zip.readBytes()
            }
        }
        return result
    }

    @Test fun androidIsAnIndependentFunctionalCounterWithASeparateTestedModel() {
        val output = files(ApplicationScaffolds.plan(spec()))
        assertEquals(setOf("README.md", "settings.gradle.kts", "build.gradle.kts", "gradle.properties",
            "app/build.gradle.kts", "app/src/main/AndroidManifest.xml",
            "app/src/main/java/io/example/focuscounter/CounterState.kt",
            "app/src/main/java/io/example/focuscounter/ThemedActivity.kt",
            "app/src/main/java/io/example/focuscounter/StarterActivity.kt",
            "app/src/test/java/io/example/focuscounter/CounterStateTest.kt"), output.keys)
        val activity = output.getValue("app/src/main/java/io/example/focuscounter/StarterActivity.kt")
        assertTrue(activity.contains("class StarterActivity : ThemedActivity()"))
        assertTrue(activity.contains("text = \"Focus counter\""))
        assertTrue(activity.contains("Show my focus goal and count each recorded action."))
        assertTrue(activity.contains("counter.recordAction()"))
        assertTrue(activity.contains("ScrollView(this)"))
        assertTrue(activity.contains("onSaveInstanceState"))
        assertFalse(output.getValue("app/src/main/AndroidManifest.xml").contains("uses-permission"))
        assertTrue(output.getValue("app/build.gradle.kts").contains("applicationId = \"io.example.focuscounter\""))
        assertTrue(output.getValue("app/build.gradle.kts").contains("minSdk = 26"))
        assertTrue(output.getValue("build.gradle.kts").contains("version \"8.6.1\""))
        assertTrue(output.getValue("app/src/test/java/io/example/focuscounter/CounterStateTest.kt")
            .contains("actionsAdvanceAndRestoredStateContinues"))
    }

    @Test fun androidUsesSystemThemedWidgetsAndContainsNoReleaseOrSecretConfiguration() {
        val output = files(ApplicationScaffolds.plan(spec()))
        val themed = output.getValue("app/src/main/java/io/example/focuscounter/ThemedActivity.kt")
        assertTrue(themed.contains("Configuration.UI_MODE_NIGHT_YES"))
        assertTrue(themed.contains("android.R.style.Theme_Material"))
        val all = output.values.joinToString("\n")
        assertFalse(all.contains("setTextColor("))
        assertFalse(all.contains("setBackgroundColor("))
        assertFalse(all.contains("signingConfigs"))
        assertFalse(all.contains("storePassword"))
        assertFalse(all.contains("com.jarvis"))
        assertFalse(all.contains("com.assistant"))
    }

    @Test fun backendHasRealLoopbackRoutesAndEphemeralHttpTestsWithoutThirdPartyPackages() {
        val output = files(ApplicationScaffolds.plan(spec(ApplicationPlatform.BACKEND)))
        assertEquals(setOf("README.md", "server.py", "test_server.py"), output.keys)
        val server = output.getValue("server.py")
        assertTrue(server.startsWith("# Independent"))
        assertTrue(server.contains("ThreadingHTTPServer((\"127.0.0.1\", port), Handler)"))
        assertTrue(server.contains("self.path == \"/health\""))
        assertTrue(server.contains("self.path == \"/api/info\""))
        assertTrue(server.contains("404, {\"error\": \"not_found\"}"))
        assertFalse(server.contains("subprocess"))
        assertFalse(server.contains("eval("))
        val tests = output.getValue("test_server.py")
        assertTrue(tests.contains("server.create_server(0)"))
        assertTrue(tests.contains("test_written_specification_is_returned_as_data"))
        assertTrue(tests.contains("cls.server.shutdown()"))
        assertTrue(tests.contains("cls.server.server_close()"))
    }

    @Test fun windowsIncludesAnActualStaFormInteractionSmokeProjectWithoutNuget() {
        val output = files(ApplicationScaffolds.plan(spec(ApplicationPlatform.WINDOWS)))
        assertEquals(setOf("README.md", "app/StarterApp.csproj", "app/Program.cs", "app/StarterForm.cs",
            "smoke/StarterSmoke.csproj", "smoke/Program.cs"), output.keys)
        val project = output.getValue("app/StarterApp.csproj")
        assertTrue(project.contains("net8.0-windows"))
        assertTrue(project.contains("<RootNamespace>GeneratedStarter</RootNamespace>"))
        assertTrue(project.contains("<UseWindowsForms>true</UseWindowsForms>"))
        assertFalse(project.contains("PackageReference"))
        val smoke = output.getValue("smoke/Program.cs")
        assertTrue(smoke.contains("[STAThread]"))
        assertTrue(smoke.contains("GetApartmentState() == System.Threading.ApartmentState.STA"))
        assertTrue(smoke.contains("form.ActionButton.PerformClick()"))
        assertTrue(smoke.contains("JARVIS_PHASE6_WINDOWS_SMOKE="))
        assertTrue(smoke.contains("form.Status.Text == \"Completed actions: 2\""))
        val form = output.getValue("app/StarterForm.cs")
        assertTrue(form.contains("AutoScaleMode.Font"))
        assertTrue(form.contains("UseMnemonic = false"))
        assertFalse(form.contains("System.Diagnostics.Process"))
    }

    @Test fun everyPlatformHasAnExplicitScopeAndNonInstallationReadme() {
        for (platform in ApplicationPlatform.values()) {
            val readme = files(ApplicationScaffolds.plan(spec(platform))).getValue("README.md")
            assertTrue(readme.contains("does not implement arbitrary features"))
            assertTrue(readme.contains("ZIP approval does not approve building, installing or publishing"))
            assertTrue(readme.contains("No release signing or installer is generated"))
        }
    }

    @Test fun sameSpecificationProducesIdenticalSortedFilesReviewFingerprintAndZip() {
        for (platform in ApplicationPlatform.values()) {
            val first = ApplicationScaffolds.plan(spec(platform))
            val second = ApplicationScaffolds.plan(spec(platform))
            assertEquals(first.files, second.files)
            assertEquals(first.files.map { it.path }.sorted(), first.files.map { it.path })
            assertEquals(first.fingerprint, second.fingerprint)
            assertTrue(Regex("[0-9a-f]{64}").matches(first.fingerprint))
            assertEquals(ApplicationScaffolds.review(first), ApplicationScaffolds.review(second))
            assertArrayEquals(approvedZip(first), approvedZip(second))
        }
    }

    @Test fun zipIsStableAcrossHostTimezones() {
        val original = TimeZone.getDefault()
        try {
            val plan = ApplicationScaffolds.plan(spec())
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            val east = approvedZip(plan)
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertArrayEquals(east, approvedZip(plan))
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun reviewContainsAllExactFilesAndMakesNoArbitrarySpecImplementationClaim() {
        val plan = ApplicationScaffolds.plan(spec())
        val review = ApplicationScaffolds.review(plan)
        for (file in plan.files) {
            assertTrue(review.contains("=== ${file.path} ("))
            assertTrue(review.contains(file.content))
            assertTrue(review.contains("=== END ${file.path} ==="))
        }
        assertTrue(review.contains(plan.fingerprint))
        assertTrue(review.contains("arbitrary requested features are not implemented"))
    }

    @Test fun everySpecificationFieldAndPlatformIsBoundIntoTheFingerprint() {
        val original = spec()
        val fingerprint = ApplicationScaffolds.plan(original).fingerprint
        for (changed in listOf(original.copy(title = "Another title"), original.copy(writtenSpec = "Another goal."),
            original.copy(projectName = "OtherProject"), original.copy(packageName = "io.example.other"),
            original.copy(platform = ApplicationPlatform.BACKEND), original.copy(platform = ApplicationPlatform.WINDOWS))) {
            assertNotEquals(fingerprint, ApplicationScaffolds.plan(changed).fingerprint)
        }
    }

    @Test fun exportRequiresFullExactFingerprintAndExplicitConfirmation() {
        val plan = ApplicationScaffolds.plan(spec())
        rejects { ApplicationScaffolds.approve(plan, plan.fingerprint, false) }
        rejects { ApplicationScaffolds.approve(plan, plan.fingerprint.take(12), true) }
        rejects { ApplicationScaffolds.approve(plan, "0".repeat(64), true) }
        rejects { ApplicationScaffolds.approve(plan, " " + plan.fingerprint, true) }
        assertTrue(approvedZip(plan).isNotEmpty())
    }

    @Test fun approvalCannotBeReusedAfterAChangedSpecification() {
        val original = ApplicationScaffolds.plan(spec())
        val approval = ApplicationScaffolds.approve(original, original.fingerprint, true)
        for (changed in listOf(spec().copy(title = "Changed title"), spec().copy(writtenSpec = "Changed goal"),
            spec(ApplicationPlatform.BACKEND), spec(ApplicationPlatform.WINDOWS))) {
            rejects { ApplicationScaffolds.exportZip(ApplicationScaffolds.plan(changed), approval) }
        }
    }

    @Test fun forgedOrMutatedTemplateContentCannotReceiveOrReuseApproval() {
        val original = ApplicationScaffolds.plan(spec())
        val approval = ApplicationScaffolds.approve(original, original.fingerprint, true)
        val alteredFiles = original.files.mapIndexed { index, file ->
            if (index == 0) file.copy(content = file.content + "Unexpected command\n") else file
        }
        val altered = ApplicationScaffoldPlan(original.specification, alteredFiles, original.fingerprint)
        rejects { ApplicationScaffolds.review(altered) }
        rejects { ApplicationScaffolds.approve(altered, original.fingerprint, true) }
        rejects { ApplicationScaffolds.exportZip(altered, approval) }
        val forged = ApplicationScaffoldPlan(original.specification, original.files, "0".repeat(64))
        rejects { ApplicationScaffolds.exportZip(forged, approval) }
    }

    @Test fun planCopiesAndFreezesItsFileCollection() {
        val original = ApplicationScaffolds.plan(spec())
        val supplied = original.files.toMutableList()
        val copied = ApplicationScaffoldPlan(original.specification, supplied, original.fingerprint)
        supplied.clear()
        assertEquals(original.files, copied.files)
        try {
            @Suppress("UNCHECKED_CAST")
            (copied.files as MutableList<GeneratedApplicationFile>).clear()
            fail("The plan file list must be immutable")
        } catch (_: UnsupportedOperationException) { /* Java and Kotlin callers cannot modify it. */ }
        assertArrayEquals(approvedZip(original), approvedZip(copied))
    }

    @Test fun exportedZipContainsExactlyTheReviewedUtf8BytesInSortedSafeEntries() {
        for (platform in ApplicationPlatform.values()) {
            val fixture = spec(platform).copy(writtenSpec =
                "Show the \"Focus\" goal and count each recorded action.\nDisplay a " + '$' + "5 goal and C:\\notes as text; café 🌍.")
            val plan = ApplicationScaffolds.plan(fixture)
            val zip = approvedZip(plan)
            val extracted = unzip(zip)
            assertEquals(plan.files.map { it.path }, extracted.keys.toList())
            for (file in plan.files) assertArrayEquals(file.path, file.content.toByteArray(Charsets.UTF_8), extracted[file.path])
            assertTrue(zip.size <= ApplicationScaffolds.MAX_BYTES + 32768)
            assertTrue(plan.files.size <= ApplicationScaffolds.MAX_FILES)
            assertTrue(plan.files.sumOf { it.content.toByteArray(Charsets.UTF_8).size } <= ApplicationScaffolds.MAX_BYTES)
        }
    }

    @Test fun traversalAbsolutePathsAndDuplicatePathsAreRejectedEvenInAForgedPlan() {
        val original = ApplicationScaffolds.plan(spec())
        val approval = ApplicationScaffolds.approve(original, original.fingerprint, true)
        for (path in listOf("../outside", "/outside", "app/../../outside", "app//file", "app/./file",
            "C:/outside", "app\\outside", "app/%2e%2e/file", "app\nfile")) {
            val forged = ApplicationScaffoldPlan(original.specification,
                listOf(GeneratedApplicationFile(path, "text\n")), original.fingerprint)
            rejects { ApplicationScaffolds.exportZip(forged, approval) }
        }
        val duplicate = ApplicationScaffoldPlan(original.specification, listOf(original.files.first(), original.files.first()), original.fingerprint)
        rejects { ApplicationScaffolds.exportZip(duplicate, approval) }
    }

    @Test fun fileCountAndBytesAndBinaryContentCannotExceedTheReviewedBudget() {
        val original = ApplicationScaffolds.plan(spec())
        val approval = ApplicationScaffolds.approve(original, original.fingerprint, true)
        for (altered in listOf(emptyList(), (0..32).map { GeneratedApplicationFile("file$it.txt", "text\n") }.sortedBy { it.path },
            listOf(GeneratedApplicationFile("large.txt", "x".repeat(ApplicationScaffolds.MAX_BYTES) + "\n")),
            listOf(GeneratedApplicationFile("binary.txt", "binary\u0000\n")))) {
            rejects { ApplicationScaffolds.exportZip(ApplicationScaffoldPlan(original.specification, altered, original.fingerprint), approval) }
        }
    }

    @Test fun invalidProjectNamesCannotReachPathsOrBuildScripts() {
        for (name in listOf("", "Ab", "../Other", "/tmp/Other", "other-project", "Project Name", "1Project",
            "Project;sh", "Project\nOther", "A".repeat(41))) {
            rejects { ApplicationScaffolds.plan(spec().copy(projectName = name)) }
        }
    }

    @Test fun invalidPackagesAndJarvisIdentitiesAreRejectedForEveryPlatform() {
        for (platform in ApplicationPlatform.values()) {
            for (name in listOf("", "single", "io.Example.app", "io..app", ".io.app", "io.app.", "io.1app",
                "io.example.class", "io.example.typealias", "io.example.typeof", "com.jarvis", "com.jarvis.developer", "com.jarvis.beta", "com.assistant.core",
                "org.jarvis.starter", "io/example/app", "io.example.app;exec", "io.example." + "x".repeat(31),
                "a.b.c.d.e.f.g")) {
                rejects { ApplicationScaffolds.plan(spec(platform).copy(packageName = name)) }
            }
        }
    }

    @Test fun emptyOversizedControlAndInvalidUnicodeInputsAreRejected() {
        for (title in listOf("", " ", " title", "title ", "Line\nOther", "Title\tOther", "x".repeat(81), "bad\u0000", "bad\u202e", "bad\ud800", "bad\u0085line", "bad\u2028line", "bad\u2029line")) {
            rejects { ApplicationScaffolds.plan(spec().copy(title = title)) }
        }
        for (text in listOf("", " \n ", "x".repeat(4001), "bad\rvalue", "bad\u0001", "bad\u2066", "bad\udfff")) {
            rejects { ApplicationScaffolds.plan(spec().copy(writtenSpec = text)) }
        }
    }

    @Test fun credentialsPrivateKeysAndShellSubstitutionsAreRejectedBeforeReview() {
        for (text in listOf("ghp_" + "a".repeat(24), "github_pat_" + "b".repeat(24), "sk-" + "x".repeat(24),
            "AIza" + "a".repeat(30), "api_key=example-value", "password: example-value", "Authorization=Bearer value",
            "-----BEGIN PRIVATE KEY-----", "Run `touch unsafe`", "Run " + '$' + "(touch unsafe)")) {
            rejects { ApplicationScaffolds.plan(spec().copy(writtenSpec = text)) }
        }
        ApplicationScaffolds.plan(spec().copy(writtenSpec = "No API keys are needed. Explain password handling without credentials."))
    }

    @Test fun quotesBackslashesNewlinesDollarAndUnicodeRemainLiteralDataAcrossPlatforms() {
        val text = "Show \"quoted\" C:\\notes\nNext\tline " + '$' + "{expression} café 🌍\u2028last\u0085end"
        for (platform in ApplicationPlatform.values()) {
            val output = files(ApplicationScaffolds.plan(spec(platform).copy(writtenSpec = text)))
            val relevant = output.entries.single { (path, _) -> when (platform) {
                ApplicationPlatform.ANDROID -> path.endsWith("/StarterActivity.kt")
                ApplicationPlatform.BACKEND -> path == "server.py"
                ApplicationPlatform.WINDOWS -> path == "app/StarterForm.cs"
            } }.value
            assertTrue(relevant.contains("\\\"quoted\\\" C:\\\\notes\\nNext\\tline \\u0024{expression}"))
            assertTrue(relevant.contains("café 🌍\\u2028last\\u0085end"))
            assertFalse(relevant.contains(text))
            assertTrue(output.values.all { it.endsWith('\n') })
        }
    }

    @Test fun largestAllowedWrittenSpecRemainsReviewableAndExportable() {
        val written = "界".repeat(ApplicationScaffolds.MAX_SPEC_CHARACTERS)
        for (platform in ApplicationPlatform.values()) {
            val plan = ApplicationScaffolds.plan(spec(platform).copy(writtenSpec = written, title = "T".repeat(80)))
            assertTrue(ApplicationScaffolds.review(plan).contains(written))
            assertTrue(approvedZip(plan).size < ApplicationScaffolds.MAX_BYTES)
        }
    }

    @Test fun applicationApprovalBoundaryRemainsProtectedFromGeneratedRepositoryPatches() {
        val path = "developer/src/main/java/com/jarvis/developer/ApplicationScaffoldApproval.kt"
        assertFalse(PatchEngine.allowedPath(path))
        rejects {
            PatchEngine.validateRequest(PatchEngine.Request("scaffold-policy", "Modify the scaffold engine", "a".repeat(40),
                listOf(path), listOf(PatchEngine.SourceFile(path, null, null)), "Fixture", "deterministic-v1"))
        }
    }

    /** Writes only accepted fixed-template fixtures under build/, for downstream real platform toolchains. */
    @Test fun actualCheckoutAcceptanceExportsReviewedPlatformProjectsAndSourceBoundEvidence() {
        val root = Paths.get(requireNotNull(System.getProperty("jarvis.repositoryRoot"))).toRealPath()
        val source = System.getProperty("jarvis.repositoryCommit").orEmpty()
        assertTrue("Actual-checkout acceptance requires a full immutable source SHA", Regex("[0-9a-f]{40}").matches(source))
        val engine = root.resolve("developer/src/main/java/com/jarvis/developer/ApplicationScaffoldApproval.kt")
        assertTrue("Acceptance requires the actual checked-out implementation", Files.isRegularFile(engine))
        val protectedPaths = listOf("app/src/main/AndroidManifest.xml", "developer/src/main/AndroidManifest.xml",
            "developer/src/main/java/com/jarvis/developer/ApplicationScaffoldApproval.kt", "developer/build.gradle")
            .map { root.resolve(it) }
        val before = protectedPaths.associateWith { sha(Files.readAllBytes(it)) }
        val destination = root.resolve("developer/build/reports/phase6-acceptance")
        ensureSafeDirectory(root, destination)
        val platforms = JSONArray()
        for (platform in ApplicationPlatform.values()) {
            val name = platform.name.lowercase(java.util.Locale.ROOT)
            val fixture = spec(platform).copy(writtenSpec =
                "Show the \"Focus\" goal and count each recorded action.\nDisplay a " + '$' + "5 goal and C:\\notes as text; café 🌍.")
            val plan = ApplicationScaffolds.plan(fixture)
            val rendered = ApplicationScaffolds.review(plan)
            assertTrue(rendered.contains(plan.fingerprint))
            val zip = approvedZip(plan)
            val extracted = unzip(zip)
            val directory = destination.resolve(name)
            ensureSafeDirectory(root, directory)
            val records = JSONArray()
            for (file in plan.files) {
                val target = directory.resolve(file.path).normalize()
                assertTrue(target.startsWith(directory))
                ensureSafeDirectory(root, target.parent)
                require(!Files.isSymbolicLink(target)) { "Refusing a linked acceptance output" }
                val bytes = file.content.toByteArray(Charsets.UTF_8)
                assertArrayEquals(bytes, extracted.getValue(file.path))
                Files.write(target, bytes)
                records.put(JSONObject().put("path", file.path).put("bytes", bytes.size).put("sha256", sha(bytes)))
            }
            val zipPath = destination.resolve("$name.zip")
            require(!Files.isSymbolicLink(zipPath))
            Files.write(zipPath, zip)
            platforms.put(JSONObject().put("platform", name).put("fingerprint", plan.fingerprint)
                .put("zipPath", "$name.zip").put("zipSha256", sha(zip)).put("files", records))
        }
        val evidence = JSONObject().put("schemaVersion", 1).put("sourceCommitSha", source)
            .put("templateVersion", ApplicationScaffolds.TEMPLATE_VERSION).put("platforms", platforms)
        val evidencePath = destination.resolve("evidence.json")
        require(!Files.isSymbolicLink(evidencePath))
        Files.write(evidencePath, (evidence.toString(2) + "\n").toByteArray(Charsets.UTF_8))
        assertEquals(before, protectedPaths.associateWith { sha(Files.readAllBytes(it)) })
        println("JARVIS_PHASE6_TEMPLATE_EVIDENCE=" + evidence.toString())
    }

    private fun ensureSafeDirectory(root: Path, directory: Path) {
        require(directory.normalize().startsWith(root.resolve("developer/build")))
        var component = root
        for (segment in root.relativize(directory)) {
            component = component.resolve(segment)
            require(!Files.isSymbolicLink(component)) { "Refusing linked acceptance directories" }
        }
        Files.createDirectories(directory)
        require(directory.toRealPath().startsWith(root.resolve("developer/build").toRealPath()))
    }
}
