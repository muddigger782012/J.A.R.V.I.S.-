package com.assistant.core.services

import org.json.JSONArray

/**
 * Choose the highest *numeric* Personal release, not whatever GitHub returns first.
 * Other releases (Developer/Beta) share this repository and must never enter the updater.
 */
object PersonalReleaseCatalog {
    private val tag = Regex("""jarvis-3\.6\.([1-9][0-9]*)""")
    private val allowedAbi = setOf("arm64-v8a", "armeabi-v7a", "x86_64")

    fun highestVerifiedAsset(rawJson: String, owner: String, repository: String, abi: String): String? {
        if (abi !in allowedAbi || owner.isBlank() || repository.isBlank()) return null
        val releases = JSONArray(rawJson)
        var maxBuild = 0L
        var selected: String? = null
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            val releaseTag = release.optString("tag_name")
            val match = tag.matchEntire(releaseTag) ?: continue
            val build = match.groupValues[1].toLongOrNull() ?: continue
            if (build <= maxBuild) continue
            val assetName = "Jarvis-3.6.$build-$abi.apk"
            val expected = "https://github.com/$owner/$repository/releases/download/$releaseTag/$assetName"
            val assets = release.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                if (asset.optString("name") == assetName &&
                    asset.optString("browser_download_url") == expected &&
                    asset.optLong("size") > 1_000_000L) {
                    maxBuild = build
                    selected = expected
                    break
                }
            }
        }
        return selected
    }
}
