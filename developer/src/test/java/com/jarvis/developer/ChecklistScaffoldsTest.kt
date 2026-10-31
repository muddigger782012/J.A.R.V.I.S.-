package com.jarvis.developer

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChecklistScaffoldsTest {
    private fun spec() = ApplicationSpecification("Offline tasks", "Keep my tasks on this device.",
        ApplicationPlatform.ANDROID, "OfflineChecklist", "io.example.offlinechecklist",
        AndroidApplicationBehavior.CHECKLIST)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun zip(plan: ApplicationScaffoldPlan) = ApplicationScaffolds.exportZip(plan,
        ApplicationScaffolds.approve(plan, plan.fingerprint, true))
    private fun rejects(action: () -> Unit) {
        try { action(); fail("Expected exact-review rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun checklistContainsAnIndependentOfflineAppAndExecutableModelAndNativeTests() {
        val plan = ApplicationScaffolds.plan(spec())
        val files = plan.files.associate { it.path to it.content }
        assertEquals(ApplicationScaffolds.CHECKLIST_TEMPLATE_VERSION, plan.templateVersion)
        assertEquals(12, files.size)
        assertTrue(files.keys.any { it.endsWith("/ChecklistState.kt") })
        assertFalse(files.keys.any { it.endsWith("/CounterState.kt") })
        assertTrue(files.keys.any { it.endsWith("/ChecklistStateTest.kt") })
        assertTrue(files.keys.any { it.contains("/androidTest/") && it.endsWith("/ChecklistDeviceTest.kt") })
        val all = files.values.joinToString("\n")
        assertFalse(all.contains("com.jarvis"))
        assertFalse(all.contains("uses-permission"))
        assertFalse(all.contains("signingConfigs"))
        assertTrue(files.getValue("README.md").contains("does not implement arbitrary features"))
        val review = ApplicationScaffolds.review(plan)
        assertTrue(review.contains("Other written requirements are not implemented"))
        files.forEach { (path, content) -> assertTrue(path, review.contains(content)) }
    }

    @Test fun switchingBehaviorRequiresFreshApprovalEvenWithIdenticalWrittenRequirements() {
        val checklist = ApplicationScaffolds.plan(spec())
        val counter = ApplicationScaffolds.plan(spec().copy(androidBehavior = AndroidApplicationBehavior.COUNTER))
        assertNotEquals(counter.fingerprint, checklist.fingerprint)
        val approval = ApplicationScaffolds.approve(counter, counter.fingerprint, true)
        rejects { ApplicationScaffolds.exportZip(checklist, approval) }
        rejects { ApplicationScaffolds.approve(checklist, counter.fingerprint, true) }
        rejects { ApplicationScaffolds.approve(checklist, checklist.fingerprint, false) }
        rejects { ApplicationScaffolds.plan(spec().copy(platform = ApplicationPlatform.BACKEND)) }
        rejects { ApplicationScaffolds.plan(spec().copy(platform = ApplicationPlatform.WINDOWS)) }
    }

    @Test fun editedOrForgedChecklistFilesCannotObtainExportApproval() {
        val plan = ApplicationScaffolds.plan(spec())
        val altered = ApplicationScaffoldPlan(spec(), plan.files.map {
            if (it.path.endsWith("/ChecklistState.kt")) it.copy(content = it.content + "// Changed\n") else it
        }, plan.fingerprint)
        rejects { ApplicationScaffolds.review(altered) }
        rejects { ApplicationScaffolds.approve(altered, altered.fingerprint, true) }
        assertArrayEquals(zip(plan), zip(ApplicationScaffolds.plan(spec())))
    }

    @Test fun checklistUsesTheSharedEscapingBoundaryAndNeverAddsSpecificationAsExecutableCode() {
        val hostile = spec().copy(title = "Tasks \"quoted\"", writtenSpec =
            "Show a " + '$' + "{value} and C:\\notes; café 🌍.\nKeep this as text.")
        val plan = ApplicationScaffolds.plan(hostile)
        val activity = plan.files.single { it.path.endsWith("/StarterActivity.kt") }.content
        assertTrue(activity.contains("\\u0024{value}"))
        assertTrue(activity.contains("C:\\\\notes"))
        assertTrue(activity.contains("Tasks \\\"quoted\\\""))
        rejects { ApplicationScaffolds.plan(spec().copy(packageName = "com.jarvis.developer")) }
        rejects { ApplicationScaffolds.plan(spec().copy(writtenSpec = "password=private")) }
    }

    @Test fun trustedTemplateIsOutsideAutomaticPatchAndSelfDevelopmentScope() {
        val path = "developer/src/main/java/com/jarvis/developer/AndroidChecklistScaffold.kt"
        assertFalse(PatchEngine.allowedPath(path))
        assertFalse(SelfDevelopment.allowedPath(path))
    }

    @Test fun existingThreePlatformArchivesAndFingerprintsAreByteForByteCompatible() {
        val fingerprints = listOf(
            "536ef664b0b2c174b7d231ac063a0de9181e340ed94c142167154553288e4c55",
            "778a16c106ca750b5320a080bdb23645ef67192f7771ab9c77db577c5123bbd9",
            "df8368c2dba2106e06d8982bdd45e3e8d70a9b784e5b1c5ec2ea1f21af90c41a")
        val archives = listOf(
            "e76357a9c01d9f76450256799d332b652cfc4c2fd6b773d5cbe491b6f33c0cec",
            "b2adb7e68de52832f49b0c60dfccda45cc5d0dbb1876dc62343ef7a76475c3e1",
            "1524f2cf307fb9a241e453b94ad9841f067bf76df2bb06ed59e0e86bc1c7fdf2")
        ApplicationPlatform.values().forEachIndexed { index, platform ->
            val plan = ApplicationScaffolds.plan(ApplicationSpecification("Focus counter",
                "Show the \"Focus\" goal and count each recorded action.\nDisplay a " + '$' +
                    "5 goal and C:\\notes as text; café 🌍.", platform, "FocusCounter", "io.example.focuscounter"))
            assertEquals(ApplicationScaffolds.TEMPLATE_VERSION, plan.templateVersion)
            assertEquals(platform.name, fingerprints[index], plan.fingerprint)
            assertEquals(platform.name, archives[index], sha(zip(plan)))
        }
    }

    @Test fun actualCheckoutAcceptanceExportsTheExactApprovedChecklistWithRevisionBoundEvidence() {
        val root = Paths.get(requireNotNull(System.getProperty("jarvis.repositoryRoot"))).toRealPath()
        val source = System.getProperty("jarvis.repositoryCommit").orEmpty()
        assertTrue(Regex("[0-9a-f]{40}").matches(source))
        val protected = listOf("developer/src/main/java/com/jarvis/developer/AndroidChecklistScaffold.kt",
            "developer/src/main/java/com/jarvis/developer/ApplicationScaffoldApproval.kt",
            "developer/src/main/AndroidManifest.xml", "developer/build.gradle").map(root::resolve)
        val before = protected.associateWith { sha(Files.readAllBytes(it)) }
        val destination = root.resolve("developer/build/reports/phase6-checklist-acceptance")
        fun directory(path: Path) {
            require(path.normalize().startsWith(root.resolve("developer/build")))
            var component = root
            for (part in root.relativize(path)) {
                component = component.resolve(part)
                require(!Files.isSymbolicLink(component))
            }
            Files.createDirectories(path)
            require(path.toRealPath().startsWith(root.resolve("developer/build").toRealPath()))
        }
        directory(destination)
        val plan = ApplicationScaffolds.plan(spec())
        val archive = zip(plan)
        val extracted = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                assertFalse(extracted.containsKey(entry.name))
                extracted[entry.name] = input.readBytes()
            }
        }
        val records = JSONArray()
        for (file in plan.files) {
            val target = destination.resolve("android").resolve(file.path).normalize()
            require(target.startsWith(destination.resolve("android")))
            directory(target.parent); require(!Files.isSymbolicLink(target))
            val bytes = file.content.toByteArray(Charsets.UTF_8)
            assertArrayEquals(bytes, extracted.getValue(file.path))
            Files.write(target, bytes)
            records.put(JSONObject().put("path", file.path).put("bytes", bytes.size).put("sha256", sha(bytes)))
        }
        val archivePath = destination.resolve("android.zip")
        require(!Files.isSymbolicLink(archivePath)); Files.write(archivePath, archive)
        val evidence = JSONObject().put("schemaVersion", 1).put("sourceCommitSha", source)
            .put("templateVersion", plan.templateVersion).put("platforms", JSONArray().put(JSONObject()
                .put("platform", "android").put("fingerprint", plan.fingerprint)
                .put("zipPath", "android.zip").put("zipSha256", sha(archive)).put("files", records)))
        val evidencePath = destination.resolve("evidence.json")
        require(!Files.isSymbolicLink(evidencePath))
        Files.write(evidencePath, (evidence.toString(2) + "\n").toByteArray(Charsets.UTF_8))
        assertEquals(before, protected.associateWith { sha(Files.readAllBytes(it)) })
        println("JARVIS_PHASE6_CHECKLIST_EVIDENCE=" + evidence.toString())
    }
}
