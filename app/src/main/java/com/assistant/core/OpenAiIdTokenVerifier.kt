package com.assistant.core

import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec

/** Fail-closed OpenAI OIDC RS256 verification. Never trust JWT claims before signature validation. */
internal object OpenAiIdTokenVerifier {
    private const val ISSUER = "https://auth.openai.com"
    private const val JWKS = "https://auth.openai.com/.well-known/jwks.json"

    fun verify(token: String, clientId: String, expectedNonce: String): String {
        require(clientId.startsWith("oaiapp_") && expectedNonce.isNotBlank())
        val parts = token.split(".")
        require(parts.size == 3) { "Invalid ID token format" }
        fun decode(value: String): ByteArray =
            Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val header = JSONObject(String(decode(parts[0]), Charsets.UTF_8))
        require(header.optString("alg") == "RS256") { "Unsupported signing algorithm" }
        val kid = header.optString("kid")
        require(kid.isNotBlank())
        val connection = URL(JWKS).openConnection() as HttpURLConnection
        val keys = try {
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            require(connection.responseCode == 200)
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONArray("keys")
        } finally {
            connection.disconnect()
        }
        var matched: JSONObject? = null
        for (i in 0 until keys.length()) {
            val candidate = keys.getJSONObject(i)
            if (candidate.optString("kid") == kid &&
                candidate.optString("kty") == "RSA" &&
                candidate.optString("use", "sig") == "sig" &&
                candidate.optString("alg", "RS256") == "RS256") {
                matched = candidate
                break
            }
        }
        val jwk = requireNotNull(matched) { "Unknown signing key" }
        val keySpec = RSAPublicKeySpec(
            BigInteger(1, decode(jwk.getString("n"))),
            BigInteger(1, decode(jwk.getString("e")))
        )
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec)
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initVerify(publicKey)
        signature.update((parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII))
        require(signature.verify(decode(parts[2]))) { "Invalid ID token signature" }

        val claims = JSONObject(String(decode(parts[1]), Charsets.UTF_8))
        require(claims.optString("iss") == ISSUER)
        val audience = claims.opt("aud")
        val audienceValid = when (audience) {
            is String -> audience == clientId
            is org.json.JSONArray -> (0 until audience.length()).any { audience.optString(it) == clientId }
            else -> false
        }
        require(audienceValid) { "Incorrect audience" }
        require(claims.optString("nonce") == expectedNonce) { "Incorrect nonce" }
        val now = System.currentTimeMillis() / 1000L
        require(claims.has("exp") && claims.getLong("exp") > now - 5)
        require(claims.has("iat") && claims.getLong("iat") <= now + 5)
        val subject = claims.optString("sub")
        require(subject.isNotBlank()) { "Missing subject" }
        return subject
    }
}
