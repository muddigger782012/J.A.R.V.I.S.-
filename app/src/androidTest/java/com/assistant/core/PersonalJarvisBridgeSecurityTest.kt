package com.assistant.core

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.assistant.core.services.PersonalJarvisBridgeProtocol
import com.assistant.core.services.PersonalJarvisBridgeToken
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonalJarvisBridgeSecurityTest {
    @Test fun tokenRotationRevokesPriorBearerWithoutTouchingInstalledOwnerToken() {
        val real = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(real.cacheDir, "bridge-security-test").apply { mkdirs() }
        val sandbox = object : ContextWrapper(real) {
            override fun getNoBackupFilesDir(): File = root
        }
        try {
            val first = PersonalJarvisBridgeToken.getOrCreate(sandbox)
            assertEquals(64, first.length)
            assertEquals(first, PersonalJarvisBridgeToken.getOrCreate(sandbox))
            assertTrue(PersonalJarvisBridgeProtocol.authorized("Bearer $first", first))

            val next = PersonalJarvisBridgeToken.rotate(sandbox)
            assertEquals(64, next.length)
            assertNotEquals(first, next)
            assertEquals(next, PersonalJarvisBridgeToken.getOrCreate(sandbox))
            assertFalse(PersonalJarvisBridgeProtocol.authorized("Bearer $first", next))
            assertTrue(PersonalJarvisBridgeProtocol.authorized("Bearer $next", next))
        } finally {
            root.deleteRecursively()
        }
    }
}
