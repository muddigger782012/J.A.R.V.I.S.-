package com.jarvis.developer

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File

/** Fixed read-only preflight coverage. Passing these checks never claims functional acceptance. */
internal object InstalledApkCheck {
    enum class Check { PACKAGE, VERSION, SIGNATURE, APK_BYTES, LAUNCHER }
    enum class Result { PASSED, FAILED, BLOCKED }
    enum class Problem { NONE, NOT_INSTALLED, UNAVAILABLE, CHANGED, DEADLINE }
    data class Report(val api: Int, val checkedAt: Long, val elapsedMillis: Long,
        val checks: Map<Check, Result>, val problem: Problem) {
        init {
            require(api in 26..10000 && checkedAt >= 0 && elapsedMillis >= 0)
            require(checks.keys == Check.values().toSet())
            require(problem == Problem.NONE || checks.values.any { it != Result.PASSED })
        }
        fun json() = JSONObject().put("v", 1).put("api", api).put("checkedAt", checkedAt)
            .put("elapsedMillis", elapsedMillis).put("problem", problem.name)
            .put("checks", JSONObject().also { json -> checks.forEach { (key, value) -> json.put(key.name, value.name) } })
    }
    fun decode(raw: JSONObject): Report {
        require(raw.keys().asSequence().toSet() == setOf("v", "api", "checkedAt", "elapsedMillis", "problem", "checks"))
        require(raw.opt("v") is Int && raw.getInt("v") == 1 && raw.opt("api") is Int)
        val checks = raw.getJSONObject("checks")
        require(checks.keys().asSequence().toSet() == Check.values().map { it.name }.toSet())
        return Report(raw.getInt("api"), DevelopmentArtifact.number(raw, "checkedAt"),
            DevelopmentArtifact.number(raw, "elapsedMillis"), Check.values().associateWith {
                Result.valueOf(checks.getString(it.name)) }, Problem.valueOf(raw.getString("problem")))
    }

    /** Android's installed-package record is re-read after hashing to reject replacement during a check. */
    @Suppress("DEPRECATION")
    fun run(context: Context, record: DevelopmentArtifact.Record): Report {
        DevelopmentArtifact.validate(record)
        val start = android.os.SystemClock.elapsedRealtime()
        val at = System.currentTimeMillis()
        val checks = Check.values().associateWith { Result.BLOCKED }.toMutableMap()
        fun expired() = Thread.currentThread().isInterrupted || android.os.SystemClock.elapsedRealtime() - start > 3000
        fun report(problem: Problem) = Report(Build.VERSION.SDK_INT, at,
            (android.os.SystemClock.elapsedRealtime() - start).coerceAtLeast(0), checks.toMap(), problem)
        return try {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val installed = pm.getPackageInfo(record.packageName, flags)
            if (expired()) return report(Problem.DEADLINE)
            fun result(match: Boolean) = if (match) Result.PASSED else Result.FAILED
            checks[Check.PACKAGE] = result(installed.packageName == record.packageName)
            checks[Check.VERSION] = result(DevelopmentArtifactInspection.version(installed) == record.versionCode && installed.versionName == record.versionName)
            checks[Check.SIGNATURE] = result(DevelopmentArtifactInspection.certificates(installed) == setOf(record.signerSha256))
            val file = File(installed.applicationInfo.sourceDir)
            checks[Check.APK_BYTES] = result(file.isFile && file.length() == record.bytes &&
                DevelopmentArtifactFiles.sha256(file) { expired() } == record.apkSha256)
            if (expired()) { checks[Check.APK_BYTES] = Result.BLOCKED; return report(Problem.DEADLINE) }
            val launch = pm.getLaunchIntentForPackage(record.packageName)
            checks[Check.LAUNCHER] = result(installed.applicationInfo.enabled && launch != null &&
                pm.resolveActivity(launch, 0)?.activityInfo?.let { it.enabled && it.packageName == record.packageName } == true)
            val after = pm.getPackageInfo(record.packageName, flags)
            if (expired()) { checks.replaceAll { _, _ -> Result.BLOCKED }; return report(Problem.DEADLINE) }
            if (after.lastUpdateTime != installed.lastUpdateTime ||
                DevelopmentArtifactInspection.version(after) != DevelopmentArtifactInspection.version(installed) ||
                after.applicationInfo.sourceDir != installed.applicationInfo.sourceDir ||
                DevelopmentArtifactInspection.certificates(after) != DevelopmentArtifactInspection.certificates(installed)) {
                checks.replaceAll { _, _ -> Result.BLOCKED }; report(Problem.CHANGED)
            } else report(Problem.NONE)
        } catch (_: PackageManager.NameNotFoundException) {
            checks.replaceAll { _, _ -> Result.BLOCKED }; checks[Check.PACKAGE] = Result.FAILED
            report(Problem.NOT_INSTALLED)
        } catch (_: Exception) {
            checks.replaceAll { _, _ -> Result.BLOCKED }
            report(if (expired()) Problem.DEADLINE else Problem.UNAVAILABLE)
        }
    }
}
