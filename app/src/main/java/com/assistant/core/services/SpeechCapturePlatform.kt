package com.assistant.core.services

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer

/** Android capture boundary; acceptance can exercise lifecycle races without recording a microphone. */
interface SpeechCapturePlatform {
    fun deviceAvailable(): Boolean
    fun systemAvailable(): Boolean
    fun create(engine: SpeechCaptureRouting.Engine, listener: RecognitionListener): Session

    interface Session {
        fun start(intent: Intent)
        fun cancel()
        fun destroy()
    }
}

class AndroidSpeechCapturePlatform(private val context: Context) : SpeechCapturePlatform {
    override fun deviceAvailable(): Boolean = Build.VERSION.SDK_INT >= 31 &&
        runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
    override fun systemAvailable(): Boolean = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
    override fun create(engine: SpeechCaptureRouting.Engine, listener: RecognitionListener): SpeechCapturePlatform.Session {
        val recognizer = createRecognizer(context, engine)
        recognizer.setRecognitionListener(listener)
        return object : SpeechCapturePlatform.Session {
            override fun start(intent: Intent) = recognizer.startListening(intent)
            override fun cancel() = recognizer.cancel()
            override fun destroy() = recognizer.destroy()
        }
    }

    companion object {
        /** Shared SDK boundary for live and command capture; a strict device request never falls back here. */
        internal fun createRecognizer(context: Context, engine: SpeechCaptureRouting.Engine): SpeechRecognizer {
            if (engine == SpeechCaptureRouting.Engine.ON_DEVICE) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                }
                error("On-device recognition requires Android 12")
            }
            return SpeechRecognizer.createSpeechRecognizer(context)
        }
    }
}
