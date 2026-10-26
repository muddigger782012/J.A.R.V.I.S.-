package com.jarvis.developer

import org.json.JSONObject

/** Evidence identities only. Neither an artifact receipt nor a checkpoint grants installation. */
internal object DevelopmentArtifact {
    const val MAX_APK = 128L * 1024 * 1024
    const val MAX_ARCHIVE = 256L * 1024 * 1024
    val activeInstalls = setOf("PREPARING", "SUBMITTED", "PENDING_USER_ACTION", "OUTCOME_UNKNOWN")
    val installStates = activeInstalls + setOf("PACKAGE_PRESENT", "FAILED", "CANCELLED")
    data class Source(val job: String, val lease: String, val commit: String, val branch: String,
        val target: PatchEngine.Target, val buildRun: Long, val buildAttempt: Int, val buildFingerprint: String)
    data class Record(val commit: String, val run: Long, val attempt: Int, val artifact: Long,
        val archiveSha256: String, val apkSha256: String, val bytes: Long, val packageName: String,
        val versionCode: Long, val versionName: String, val signerSha256: String, val signing: String) {
        fun json(): JSONObject = JSONObject().put("commit", commit).put("run", run).put("attempt", attempt)
            .put("artifact", artifact).put("archiveSha256", archiveSha256).put("apkSha256", apkSha256)
            .put("bytes", bytes).put("packageName", packageName).put("versionCode", versionCode)
            .put("versionName", versionName).put("signerSha256", signerSha256).put("signing", signing)
    }
    data class Installation(val job: String, val nonce: String, val record: Record, val session: Int = -1)
    fun number(json: JSONObject, key: String): Long {
        val value = json.get(key); require(value is Int || value is Long)
        return (value as Number).toLong()
    }
    fun packageName(target: PatchEngine.Target) = when (target) {
        PatchEngine.Target.DEVELOPER -> "com.jarvis.developer"
        PatchEngine.Target.PERSONAL -> "com.assistant.core"
    }
    fun record(json: JSONObject): Record = Record(json.getString("commit"), json.getLong("run"), json.getInt("attempt"),
        json.getLong("artifact"), json.getString("archiveSha256"), json.getString("apkSha256"), json.getLong("bytes"),
        json.getString("packageName"), json.getLong("versionCode"), json.getString("versionName"),
        json.getString("signerSha256"), json.getString("signing")).also(::validate)
    fun validate(r: Record) {
        require(r.commit.matches(Regex("[a-f0-9]{40}")) && r.run > 0 && r.attempt in 1..1_000_000 && r.artifact > 0)
        require(listOf(r.archiveSha256, r.apkSha256, r.signerSha256).all { it.matches(Regex("[a-f0-9]{64}")) })
        require(r.bytes in 1..MAX_APK && r.packageName in PatchEngine.Target.values().map(::packageName))
        require(r.versionCode in 1..2_099_999_999 && r.versionName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+ -]{0,119}")))
        require(r.signing in setOf("debug", "production"))
    }
    fun validateSource(source: Source) {
        require(source.job.matches(Regex("job_[a-f0-9]{64}")) && source.lease.matches(Regex("[a-f0-9-]{36}")))
        require(source.commit.matches(Regex("[a-f0-9]{40}")) && BuildDiagnostics.validBranch(source.branch, source.target))
        require(source.buildRun > 0 && source.buildAttempt > 0 && source.buildFingerprint.matches(Regex("[a-f0-9]{64}")))
    }
    enum class Problem { PROVENANCE, NETWORK, LIMIT, CANCELLED, SIGNATURE, COMPATIBILITY, STORAGE, INSTALLATION }
    class Failure(val problem: Problem) : IllegalStateException(problem.name)
    fun explain(failure: Throwable): String = when ((failure as? Failure)?.problem) {
        Problem.PROVENANCE -> "This APK could not be linked to the approved source and workflow. Use a retained build with current APK provenance; no installation was authorized."
        Problem.NETWORK -> "APK evidence could not be downloaded. Check GitHub access and connectivity, then explicitly retry."
        Problem.LIMIT -> "This artifact exceeds the supported size, format or time limits. No installation was authorized."
        Problem.CANCELLED -> "Artifact inspection was cancelled. No installation was requested."
        Problem.SIGNATURE -> "The APK signature is invalid or differs from the existing Jarvis signing identity. Keep the installed application and check the selected build."
        Problem.COMPATIBILITY -> "The APK package, version, Android requirement or CPU architecture is incompatible. Keep the installed application and choose a compatible build."
        Problem.STORAGE -> "The verified APK is unavailable or changed. Inspect the artifact again; no installation was requested."
        else -> "The artifact or installation result could not be verified. Refresh this job and review its recorded status; no successful installation is inferred."
    }
}
