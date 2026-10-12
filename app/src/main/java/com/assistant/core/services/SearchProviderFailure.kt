package com.assistant.core.services

/** Sanitized provider state; never expose exception bodies, credentials or server instructions. */
class SearchProviderFailure(val kind: Kind) : IllegalStateException(kind.name) {
    enum class Kind { RATE_LIMIT, AUTHENTICATION, UNAVAILABLE }
    companion object {
        fun fromHttp(status: Int) = SearchProviderFailure(when (status) {
            429 -> Kind.RATE_LIMIT
            401, 403 -> Kind.AUTHENTICATION
            else -> Kind.UNAVAILABLE
        })
        fun fromEnvelope(code: String) = SearchProviderFailure(when {
            code.contains("limit", true) || code.contains("quota", true) || code.contains("budget", true) -> Kind.RATE_LIMIT
            code.contains("key", true) || code.contains("auth", true) -> Kind.AUTHENTICATION
            else -> Kind.UNAVAILABLE
        })
    }
}
