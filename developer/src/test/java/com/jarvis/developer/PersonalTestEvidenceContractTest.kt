package com.jarvis.developer

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** Executes the actual CI adapter against XML, then feeds its output to the real Android parser. */
class PersonalTestEvidenceContractTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun actualXmlProducerAndFeedbackConsumerAgreeWithoutDisclosingNames() {
        val root = File(requireNotNull(System.getProperty("jarvis.repositoryRoot")))
        val sha = requireNotNull(System.getProperty("jarvis.repositoryCommit"))
        val reports = temporary.newFolder("results")
        File(reports, "TEST-private.xml").writeText("""
            <testsuite name="OwnerPrivateSuite" tests="1" failures="0" errors="0" skipped="0">
                <testcase name="private-owner-contact-example"/>
                <system-out>private stdout must be omitted</system-out>
            </testsuite>
        """.trimIndent())
        val output = temporary.newFile("record.txt")
        val process = ProcessBuilder("python3", File(root, "scripts/personal_test_evidence.py").absolutePath,
            "--source", sha, "--results", reports.absolutePath).directory(root).redirectErrorStream(true).redirectOutput(output).start()
        val completed = process.waitFor(10, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        assertTrue("The bounded producer must exit", completed)
        assertEquals(0, process.exitValue())
        val record = output.readText()
        assertFalse(record.contains("OwnerPrivateSuite")); assertFalse(record.contains("private-owner-contact"))
        assertFalse(record.contains("private stdout"))
        val report = BuildDiagnostics.parse(sha, "main", 42, 1, "completed", "success", record, PatchEngine.Target.PERSONAL)
        BuildDiagnostics.validateReport(report)
        assertTrue(report.evidenceComplete); assertEquals(1, report.totals!!.tests)
    }
}
