package com.jarvis.developer

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.jarvis.integration.DevelopmentBridgeContract as Wire
import com.jarvis.integration.DevelopmentPeerIdentity
import org.json.JSONArray
import org.json.JSONObject

/** Local metadata RPC only. It exposes no shell, source-write, approval-grant or installer operation. */
class DevelopmentBridgeService : Service() {
    // Android may expose its framework storage root through an alias. Resolve that trusted
    // anchor only; PrivateCheckpointFile still rejects links within the inbox and its files.
    private val inbox by lazy { DevelopmentJobInbox(java.io.File(noBackupFilesDir.canonicalFile, "development-inbox")) }
    private val endpoint = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) { reply?.writeString(Wire.DESCRIPTOR); return true }
            if (code != IBinder.FIRST_CALL_TRANSACTION || reply == null) return false
            val peer = DevelopmentPeerIdentity.packageForUid(this@DevelopmentBridgeService,
                Binder.getCallingUid(), Wire.assistantPackages)
            if (peer == null) {
                reply.writeNoException(); reply.writeString(Wire.response("unavailable", "UNAUTHORIZED")); return true
            }
            var requestId = "unavailable"
            var admitted = false
            val response = try {
                if (data.dataSize() > Wire.MAX_PARCEL_BYTES || data.hasFileDescriptors()) throw Wire.Rejected("TOO_LARGE")
                data.enforceInterface(Wire.DESCRIPTOR)
                val request = Wire.decode(data.readString() ?: throw Wire.Rejected("INVALID_REQUEST"))
                requestId = request.requestId
                if (data.dataAvail() != 0) throw Wire.Rejected("INVALID_REQUEST")
                val personal = Wire.personalPackage(packageName)
                if (!Wire.permitsMode(request.mode, personal, Wire.personalPackage(peer))) throw Wire.Rejected("POLICY_REQUIRED")
                admitted = true
                val result = if (request.kind == "capabilities.request") JSONObject()
                    .put("protocol", Wire.VERSION).put("operations", JSONArray(Wire.operations.toList()))
                    .put("modes", JSONArray(Wire.availableModes(personal && Wire.personalPackage(peer))))
                    .put("sourceExecution", false).put("installation", false).put("sourceWritesRequireApproval", true)
                else inbox.handle(peer, personal, request)
                Wire.response(requestId, "OK", result)
            } catch (failure: Wire.Rejected) {
                Wire.response(requestId, failure.code, JSONObject().put("supportedVersion", Wire.VERSION))
            } catch (failure: Exception) {
                val category = when (failure) {
                    is UnsupportedOperationException -> "PLATFORM_OPERATION"
                    is java.io.IOException -> "IO"
                    is IllegalArgumentException -> "VALIDATION"
                    else -> "UNCLASSIFIED"
                }
                android.util.Log.w("DevelopmentBridge", "queue.failure $category")
                Wire.response(requestId, if (admitted) "STORAGE_UNAVAILABLE" else "INVALID_REQUEST")
            }
            reply.writeNoException(); reply.writeString(response)
            return true
        }
    }
    override fun onBind(intent: Intent?): IBinder = endpoint
}
