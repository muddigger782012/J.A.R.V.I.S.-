package com.jarvis.developer

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class DevelopmentArtifactSignatureTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun actualKnownApkVerifiesOnBothSupportedAndroidLevelsAndChangedContentFailsCryptographically() {
        val apk = File(System.getProperty("jarvis.repositoryRoot"), "artifacts/app-debug.apk")
        assertTrue(apk.isFile)
        val trusted = DevelopmentArtifactInspection.verifiedSigners(apk, 26)
        assertEquals(1, trusted.size)
        assertEquals(trusted, DevelopmentArtifactInspection.verifiedSigners(apk, 34))
        val changed = temp.newFile("changed.apk"); apk.copyTo(changed, overwrite = true)
        RandomAccessFile(changed, "rw").use {
            val position = it.length() / 2; it.seek(position); val old = it.read(); it.seek(position); it.write(old xor 1)
        }
        assertEquals(DevelopmentArtifact.Problem.SIGNATURE, assertThrows(DevelopmentArtifact.Failure::class.java) {
            DevelopmentArtifactInspection.verifiedSigners(changed, 34)
        }.problem)
    }
}
