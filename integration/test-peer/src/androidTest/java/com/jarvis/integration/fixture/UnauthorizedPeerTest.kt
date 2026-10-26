package com.jarvis.integration.fixture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.integration.DevelopmentBridgeContract as Wire
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Same signing key and signature permission are insufficient without the expected kernel caller UID. */
class UnauthorizedPeerTest {
    @Test fun unknownPackageCannotImpersonateAssistantWithTheSameSigningKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = CountDownLatch(1); val remote = AtomicReference<IBinder?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { remote.set(binder); ready.countDown() }
            override fun onServiceDisconnected(name: ComponentName) { remote.set(null) }
        }
        assertTrue(context.bindService(Intent().setComponent(ComponentName(Wire.DEVELOPER_PACKAGE, Wire.SERVICE)), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(Wire.DESCRIPTOR)
                data.writeString(Wire.encode(Wire.Request("forged", "capabilities.request")))
                assertTrue(remote.get()!!.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0))
                reply.readException()
                assertEquals("UNAUTHORIZED", Wire.readResponse(reply.readString()!!, "forged").getString("code"))
            } finally { reply.recycle(); data.recycle() }
        } finally { context.unbindService(connection) }
    }
}
