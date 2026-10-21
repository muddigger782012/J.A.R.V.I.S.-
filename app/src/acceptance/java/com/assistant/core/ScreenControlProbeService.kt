package com.assistant.core

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.ResultReceiver
import com.assistant.core.services.ScreenAccessClient
import com.assistant.core.services.AndroidDeviceReport

/** Same-UID worker in a separate process, exercising the production voice bridge. */
class ScreenControlProbeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        @Suppress("DEPRECATION") val receiver = intent?.getParcelableExtra<ResultReceiver>("receiver")
        val command = intent?.getStringExtra("command").orEmpty()
        Thread {
            val reply = if (command == "fixture accessibility state") AndroidDeviceReport(this).accessibility().value?.name.orEmpty()
                else ScreenAccessClient(this).reply(command)
            receiver?.send(0, Bundle().apply { putString("reply", reply); putInt("pid", Process.myPid()) })
            stopSelf(startId)
        }.start()
        return START_NOT_STICKY
    }
}
