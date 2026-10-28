package com.jarvis.integration

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import com.jarvis.integration.DevelopmentBridgeContract as Wire
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException

/** Invoke from an existing bounded background worker; never block the conversation UI. */
class DevelopmentBridgeClient(context: Context) {
    private val context = context.applicationContext
    fun request(value: Wire.Request): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Development RPC must run off the UI thread" }
        val future = try { worker.submit<JSONObject> { requestBound(value) } }
            catch (_: RejectedExecutionException) { throw Wire.Rejected("BUSY") }
        return try { future.get(4500, TimeUnit.MILLISECONDS) }
        catch (_: TimeoutException) { future.cancel(true); worker.purge(); throw Wire.Rejected("TIMEOUT") }
        catch (_: InterruptedException) { future.cancel(true); worker.purge(); Thread.currentThread().interrupt(); throw Wire.Rejected("CANCELLED") }
        catch (failure: ExecutionException) { throw (failure.cause as? Wire.Rejected ?: Wire.Rejected("COMPANION_UNAVAILABLE")) }
    }
    private fun requestBound(value: Wire.Request): JSONObject {
        Wire.validate(value)
        val uid = try { context.packageManager.getApplicationInfo(Wire.DEVELOPER_PACKAGE, 0).uid }
            catch (_: Exception) { throw Wire.Rejected("COMPANION_UNAVAILABLE") }
        if (DevelopmentPeerIdentity.packageForUid(context, uid, setOf(Wire.DEVELOPER_PACKAGE)) == null)
            throw Wire.Rejected("UNAUTHORIZED")
        val ready = CountDownLatch(1); val service = AtomicReference<IBinder?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { service.set(binder); ready.countDown() }
            override fun onServiceDisconnected(name: ComponentName) { service.set(null); ready.countDown() }
            override fun onNullBinding(name: ComponentName) { ready.countDown() }
            override fun onBindingDied(name: ComponentName) { service.set(null); ready.countDown() }
        }
        var bound = false
        try {
            bound = context.bindService(Intent().setComponent(ComponentName(Wire.DEVELOPER_PACKAGE, Wire.SERVICE)), connection, Context.BIND_AUTO_CREATE)
            if (!bound || !ready.await(3, TimeUnit.SECONDS)) throw Wire.Rejected("COMPANION_UNAVAILABLE")
            val remote = service.get() ?: throw Wire.Rejected("COMPANION_UNAVAILABLE")
            if (DevelopmentPeerIdentity.packageForUid(context, uid, setOf(Wire.DEVELOPER_PACKAGE)) == null ||
                context.packageManager.getApplicationInfo(Wire.DEVELOPER_PACKAGE, 0).uid != uid) throw Wire.Rejected("UNAUTHORIZED")
            fun exchange(request: Wire.Request): JSONObject {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(Wire.DESCRIPTOR); data.writeString(Wire.encode(request))
                if (!remote.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)) throw Wire.Rejected("UNSUPPORTED_VERSION")
                if (reply.dataSize() > Wire.MAX_PARCEL_BYTES || reply.hasFileDescriptors()) throw Wire.Rejected("TOO_LARGE")
                reply.readException()
                return Wire.readResponse(reply.readString() ?: throw Wire.Rejected("INVALID_REQUEST"), request.requestId)
            } finally { reply.recycle(); data.recycle() }
            }
            if (value.kind != "capabilities.request") {
                val negotiation = exchange(Wire.Request(java.util.UUID.randomUUID().toString(), "capabilities.request", mode = value.mode))
                if (negotiation.getString("code") != "OK") throw Wire.Rejected(negotiation.getString("code"))
                Wire.negotiate(negotiation.getJSONObject("data"), value)
                if (Thread.currentThread().isInterrupted) throw Wire.Rejected("CANCELLED")
            }
            return exchange(value)
        } finally { if (bound) runCatching { context.unbindService(connection) } }
    }
    companion object {
        // A completed Future can release its caller before the worker finishes returning.
        // Two waiting slots admit that caller's next phase alongside a queued status read.
        // Binder execution remains serial, admission bounded and call deadlines unchanged.
        internal fun newExecutor() = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(2),
            java.util.concurrent.ThreadFactory { task -> Thread(task, "developer-bridge-io").apply { isDaemon = true } })
            .apply { allowCoreThreadTimeOut(true) }
        private val worker = newExecutor()
    }
}
