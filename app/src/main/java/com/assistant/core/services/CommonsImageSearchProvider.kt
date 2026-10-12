package com.assistant.core.services

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Public file search with original attribution pages; no API credential required. */
class CommonsImageSearchProvider {
    fun search(query: String): List<SearchRouter.Result> {
        require(query.isNotBlank() && query.length <= 300)
        val connection = URL(requestUrl(query)).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 7000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "JARVIS/1.0 (https://github.com/muddigger782012/J.A.R.V.I.S.-)")
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) throw SearchProviderFailure.fromHttp(connection.responseCode)
            parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    companion object {
        fun requestUrl(query: String): String = "https://commons.wikimedia.org/w/api.php?action=query&format=json&formatversion=2" +
            "&generator=search&gsrnamespace=6&gsrlimit=6&gsrsearch=" + URLEncoder.encode(query, "UTF-8") +
            "&prop=imageinfo&iiprop=url%7Cmime%7Cextmetadata&iiurlwidth=480&iiextmetadatafilter=Artist%7CLicenseShortName"

        fun parse(raw: String): List<SearchRouter.Result> {
            val root = JSONObject(raw)
            if (root.has("error")) throw SearchProviderFailure(SearchProviderFailure.Kind.UNAVAILABLE)
            val pages = root.optJSONObject("query")?.optJSONArray("pages") ?: return emptyList()
            return (0 until pages.length()).mapNotNull { index -> pages.optJSONObject(index) }
                .sortedBy { it.optInt("index", Int.MAX_VALUE) }.take(6).mapNotNull { page ->
                    val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@mapNotNull null
                    if (info.optString("mime") !in setOf("image/jpeg", "image/png", "image/gif", "image/webp")) return@mapNotNull null
                    val source = info.optString("descriptionurl")
                    val thumbnail = info.optString("thumburl").ifBlank { info.optString("url") }
                    if (!ChatSearchResults.safeUrl(source) || java.net.URI(source).host != "commons.wikimedia.org" ||
                        !ChatSearchResults.safeUrl(thumbnail) || java.net.URI(thumbnail).host != "upload.wikimedia.org") return@mapNotNull null
                    fun metadata(key: String): String = info.optJSONObject("extmetadata")?.optJSONObject(key)?.optString("value").orEmpty()
                        .replace(Regex("<[^>]*>"), "").replace("&amp;", "&").trim().take(120)
                    val credit = listOf(metadata("Artist"), metadata("LicenseShortName")).filter { it.isNotBlank() }.joinToString(" · ")
                    val title = page.optString("title").removePrefix("File:").replace('_', ' ').trim().take(250)
                    if (title.isBlank()) null else SearchRouter.Result(title, credit, source, "image", thumbnail)
                }
        }
    }
}
