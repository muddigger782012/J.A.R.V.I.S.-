package com.jarvis.developer

import org.junit.Assert.assertEquals
import org.junit.Test

/** Additional regression coverage; the existing summary tests remain unchanged. */
class TaskFileSummaryClassificationTest {
    @Test fun classifiesOnlyAnExactRecognizedFilenameSuffix() {
        val paths = listOf("src/Folder.KT/README", "res/Folder.XML/build.gradle.kts",
            "src/Fake.java/Feature.KTS", "backup/Screen.kt.bak", "backup/layout.xml.orig",
            "notes/build.gradle.kts.txt", "Main.JAVA", "layout/Screen.XML", "build.GRADLE")
        val expected = listOf("9 files", "Types: Kotlin 1, Java 1, XML 1, Gradle 2, Other 4",
            "Main.JAVA", "backup/Screen.kt.bak", "backup/layout.xml.orig", "build.GRADLE",
            "layout/Screen.XML", "notes/build.gradle.kts.txt", "res/Folder.XML/build.gradle.kts",
            "src/Fake.java/Feature.KTS", "src/Folder.KT/README")
        assertEquals(expected.joinToString("\n"), TaskFileSummary.describe(paths))
    }

    @Test fun preservesCaseSensitivePathsWhileClassifyingExtensionsWithoutCase() {
        val paths = listOf("a.kt", "A.KT", "a.kt", "view.xml", "VIEW.XML", "build.gradle.kts",
            "BUILD.GRADLE.KTS", "build.gradle.kts", "script.KTS")
        val expected = listOf("7 files", "Types: Kotlin 3, Java 0, XML 2, Gradle 2, Other 0",
            "A.KT", "BUILD.GRADLE.KTS", "VIEW.XML", "a.kt", "build.gradle.kts", "script.KTS", "view.xml")
        assertEquals(expected.joinToString("\n"), TaskFileSummary.describe(paths))
    }
}
