package com.jarvis.developer

import org.junit.Assert.assertEquals
import org.junit.Test

// Reviewed source: bed69b8c65030d24871d75186075bfecc125f043
class TaskFileSummaryTest {
    @Test fun summarizesDistinctPathsInStableOrder() {
        assertEquals("2 files\nTypes: Kotlin 1, Java 0, XML 1, Gradle 0, Other 0\na.kt\nz.xml",
            TaskFileSummary.describe(listOf("z.xml", "a.kt", "z.xml")))
    }

    @Test fun summarizesEmptyAndSingleFileScopes() {
        assertEquals("0 files\nTypes: Kotlin 0, Java 0, XML 0, Gradle 0, Other 0", TaskFileSummary.describe(emptyList()))
        assertEquals("1 file\nTypes: Kotlin 0, Java 1, XML 0, Gradle 0, Other 0\nMain.java",
            TaskFileSummary.describe(listOf("Main.java")))
    }

    @Test fun countsGradleScriptsBeforeKotlinAndHandlesMixedCase() {
        val paths = listOf("src/Main.KT", "src/Thing.JAVA", "res/layout.xml", "build.gradle.kts",
            "legacy.gradle", "script.kts", "README", "src/Main.KT")
        val expected = listOf("7 files", "Types: Kotlin 2, Java 1, XML 1, Gradle 2, Other 1") + paths.distinct().sorted()
        assertEquals(expected.joinToString("\n"), TaskFileSummary.describe(paths))
    }

    @Test fun classifiesTheFilenameInsteadOfParentDirectory() {
        assertEquals("1 file\nTypes: Kotlin 0, Java 0, XML 0, Gradle 0, Other 1\nfolder.kt/readme",
            TaskFileSummary.describe(listOf("folder.kt/readme")))
    }
}
