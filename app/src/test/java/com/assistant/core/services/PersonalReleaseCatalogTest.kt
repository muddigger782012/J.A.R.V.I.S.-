package com.assistant.core.services

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PersonalReleaseCatalogTest {
    private val owner = "muddigger782012"
    private val repo = "J.A.R.V.I.S.-"
    private val abi = "arm64-v8a"

    private fun release(code: Int, valid: Boolean = true, draft: Boolean = false, pre: Boolean = false): JSONObject {
        val tag = "jarvis-3.6.$code"
        val asset = "Jarvis-3.6.$code-$abi.apk"
        return JSONObject()
            .put("tag_name", tag)
            .put("draft", draft)
            .put("prerelease", pre)
            .put("assets", JSONArray().put(JSONObject()
                .put("name", asset)
                .put("size", 54_000_000L)
                .put("browser_download_url", if (valid)
                    "https://github.com/$owner/$repo/releases/download/$tag/$asset"
                    else "https://other.invalid/supply-chain-apk")))
    }

    @Test fun choosesBuild599OverStale594EvenWhenGithubLists579First() {
        val catalog = JSONArray()
            .put(release(579)).put(release(599)).put(release(594))
        val selected = PersonalReleaseCatalog.highestVerifiedAsset(catalog.toString(), owner, repo, abi)
        assertEquals("https://github.com/$owner/$repo/releases/download/jarvis-3.6.599/Jarvis-3.6.599-arm64-v8a.apk", selected)
    }

    @Test fun ignoresUnrelatedDeveloperBetaPrereleaseAndUntrustedAssets() {
        val catalog = JSONArray()
            .put(release(9999, draft = true))
            .put(release(9998, pre = true))
            .put(release(9997, valid = false))
            .put(JSONObject().put("tag_name", "developer-0.1.9999").put("assets", JSONArray()))
            .put(release(599))
        assertTrue(PersonalReleaseCatalog.highestVerifiedAsset(catalog.toString(), owner, repo, abi)!!.contains("3.6.599"))
    }

    @Test fun refusesAnUnsupportedAbiAndAnEmptyReleaseListing() {
        val catalog = JSONArray().put(release(599)).toString()
        assertNull(PersonalReleaseCatalog.highestVerifiedAsset(catalog, owner, repo, "mips"))
        assertNull(PersonalReleaseCatalog.highestVerifiedAsset("[]", owner, repo, abi))
    }

    @Test fun releaseWithoutExpectedArm64AssetNeverWins() {
        val empty = JSONObject().put("tag_name", "jarvis-3.6.601")
            .put("assets", JSONArray())
        val catalog = JSONArray().put(empty).put(release(599)).toString()
        val selected = PersonalReleaseCatalog.highestVerifiedAsset(catalog, owner, repo, abi)
        assertTrue(selected!!.contains("3.6.599"))
    }

    @Test fun ignoresAssetWithImpossibleSize() {
        val invalid = release(900)
        invalid.getJSONArray("assets").getJSONObject(0).put("size", 50)
        val catalog = JSONArray().put(invalid).put(release(599)).toString()
        assertTrue(PersonalReleaseCatalog.highestVerifiedAsset(catalog, owner, repo, abi)!!.contains("3.6.599"))
    }
}
