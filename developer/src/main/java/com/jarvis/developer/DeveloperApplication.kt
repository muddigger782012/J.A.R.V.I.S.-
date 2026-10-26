package com.jarvis.developer

import android.app.Application
import kotlin.concurrent.thread

class DeveloperApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Repair the admission/enqueue crash window. Only previously admitted GET-only jobs run.
        thread(name = "developer-source-queue-recovery", isDaemon = true) {
            runCatching { DevelopmentAnalysisScheduler.recover(this) }
        }
    }
}
