package com.assistant.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.assistant.core.services.NativeTtsCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The optimized voiceCheck app is R8-shrunk separately from the test APK.
 * Sherpa JNI calls the concrete typed method, not Function1.invoke(Object).
 */
@RunWith(AndroidJUnit4::class)
class NativeTtsCallbackAcceptanceTest {
    @Test fun nativeStreamingCallbackKeepsExactBoxedReturnSignatureAfterR8() {
        val method = NativeTtsCallback::class.java.getDeclaredMethod(
            "invoke", FloatArray::class.java
        )
        assertEquals(java.lang.Integer::class.java, method.returnType)
        val callback = NativeTtsCallback { samples -> samples.size }
        assertEquals(3, callback.invoke(floatArrayOf(1f, 2f, 3f)))
        assertNotNull(NativeTtsCallback::class.java.methods.firstOrNull {
            it.name == "invoke" && it.parameterTypes.contentEquals(arrayOf(Any::class.java))
        })
    }
}
