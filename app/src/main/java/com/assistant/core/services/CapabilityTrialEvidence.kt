package com.assistant.core.services

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/** Request-linked bounded trial evidence only; never grants approval, execution or learned matching. */
class CapabilityTrialEvidence(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    enum class Outcome(val label: String) {
        DEVICE_VERIFIED("Android volume readback verified"), NOT_VERIFIED("Android volume readback could not verify the target"),
        USER_CONFIRMED("User confirmed success"), USER_FAILED("User reported failure"), REQUEST_FAILED("Android launch request failed")
    }
    data class Entry(val requestId: String, val action: LearnableDeviceAction, val outcome: Outcome, val time: Long)
    private val file get() = File(directory, "capability-trial-evidence.json")
    fun snapshot(): List<Entry> = runCatching {
        val array = JSONArray(file.readText())
        (0 until minOf(array.length(), 100)).mapNotNull { index -> runCatching {
            val row = array.getJSONObject(index)
            val params = row.getJSONObject("parameters")
            val action = LearnableDeviceAction(row.getString("capability"), params.keys().asSequence().associateWith { params.getString(it) })
            Entry(row.getString("requestId"), action, Outcome.valueOf(row.getString("outcome")), row.getLong("time"))
                .takeIf { CapabilityRequestStore.validId(it.requestId) && validEvidence(it.action, it.outcome) && it.time >= 0 }
        }.getOrNull() }
    }.getOrDefault(emptyList())
    fun record(requestId: String?, action: LearnableDeviceAction, outcome: Outcome): Boolean {
        if (requestId == null || !CapabilityRequestStore.validId(requestId) || !validEvidence(action, outcome) || now() < 0) return false
        return synchronized(lock) { runCatching {
            RandomAccessFile(File(directory, "capability-trial-evidence.lock"), "rw").use { mutex ->
                mutex.channel.lock().use {
                    val rows = (snapshot() + Entry(requestId, action, outcome, now())).takeLast(100)
                    val array = JSONArray().apply { rows.forEach { entry -> put(JSONObject().put("requestId", entry.requestId)
                        .put("capability", entry.action.capabilityId).put("parameters", JSONObject(entry.action.parameters))
                        .put("outcome", entry.outcome.name).put("time", entry.time)) } }
                    val temporary = File(directory, "capability-trial-evidence.tmp")
                    FileOutputStream(temporary).use { stream -> stream.write(array.toString().toByteArray(Charsets.UTF_8)); stream.fd.sync() }
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            true
        }.getOrDefault(false) }
    }
    fun latest(request: CapabilityRequestStore.Entry): Entry? = snapshot().lastOrNull { it.requestId == request.id && it.time >= request.created && it.time <= now() }
    fun render(request: CapabilityRequestStore.Entry, definition: CapabilityRequestReview.Definition?): String {
        val entry = latest(request) ?: return "No linked trial result recorded for this request."
        val sameTarget = definition != null && definition.capability == entry.action.capabilityId && definition.parameters == entry.action.parameters
        return "Last linked trial: ${entry.outcome.label}. Tested target: ${description(entry.action)}. Recorded: ${java.time.Instant.ofEpochMilli(entry.time)}. " +
            if (sameTarget) "This records the result of that existing bounded action on this device. It does not establish a new installed feature or future availability."
            else "The tested target differs from the current request assessment. Review the original request and test its exact target before treating it as demonstrated."
    }
    companion object {
        private val lock = Any()
        fun description(action: LearnableDeviceAction): String = if (action.capabilityId == ExactVolumeLearning.CAPABILITY)
            "set media volume to ${action.parameters["percent"]}% (nearest supported level)" else action.description()
        private fun validEvidence(action: LearnableDeviceAction, outcome: Outcome): Boolean =
            if (action.capabilityId == ExactVolumeLearning.CAPABILITY)
                ExactVolumeLearning.fromParameters(action.parameters) != null && outcome in setOf(Outcome.DEVICE_VERIFIED, Outcome.NOT_VERIFIED)
            else action.isValid() && outcome !in setOf(Outcome.DEVICE_VERIFIED, Outcome.NOT_VERIFIED)
    }
}
