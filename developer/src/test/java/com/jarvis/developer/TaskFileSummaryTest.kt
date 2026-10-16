package com.jarvis.developer

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskFileSummaryTest {
    @Test fun summarizesDistinctPathsInStableOrder() {
        assertEquals("2 files\na.kt\nz.xml", TaskFileSummary.describe(listOf("z.xml", "a.kt", "z.xml")))
    }

    @Test fun summarizesEmptyAndSingleFileScopes() {
        assertEquals("0 files", TaskFileSummary.describe(emptyList()))
        assertEquals("1 file\nMain.java", TaskFileSummary.describe(listOf("Main.java")))
    }
}
