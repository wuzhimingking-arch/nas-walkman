package tech.peakedge.naswalkman.network

import android.util.Log
import java.security.MessageDigest

object SafeHttpLog {
    private const val TAG = "NasNetwork"

    fun event(
        name: String,
        url: String,
        finalUrl: String? = null,
        redirected: Boolean? = null,
        status: Int? = null,
        bodyKind: String? = null,
        bodyReason: String? = null,
        bodySummary: String? = null,
        contentType: String? = null,
        setCookie: String? = null,
        requestCookie: String? = null,
    ) {
        val message = buildString {
            append(name)
            append(" url=").append(redactUrl(url))
            finalUrl?.let { append(" finalUrl=").append(redactUrl(it)) }
            redirected?.let { append(" redirected=").append(it) }
            status?.let { append(" status=").append(it) }
            bodyKind?.let { append(" body=").append(it) }
            bodyReason?.let { append(" reason=").append(redactHeaderValue(it).take(120)) }
            contentType?.let { append(" contentType=").append(redactHeaderValue(it)) }
            setCookie?.takeIf { it.isNotBlank() }?.let {
                append(" setCookieNames=").append(cookieNames(it))
            }
            requestCookie?.takeIf { it.isNotBlank() }?.let {
                append(" requestCookieNames=").append(cookieNames(it))
            }
            bodySummary?.takeIf { it.isNotBlank() }?.let {
                append(" bodySample=").append(it)
            }
        }
        Log.d(TAG, message)
    }

    fun redactUrl(url: String): String =
        runCatching {
            val uri = java.net.URI(url)
            val host = uri.host?.let(::maskHost).orEmpty()
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            val path = uri.rawPath.orEmpty().ifBlank { "/" }
            val query = uri.rawQuery
                ?.split('&')
                ?.joinToString("&") { part ->
                    val key = part.substringBefore('=')
                    if (SENSITIVE_QUERY_KEYS.any { key.contains(it, ignoreCase = true) }) {
                        "$key=<redacted>"
                    } else {
                        part
                    }
                }
                ?.let { "?$it" }
                .orEmpty()
            "${uri.scheme}://$host$port$path$query"
        }.getOrDefault("<redacted-url>")

    fun redactHeaderValue(value: String): String =
        value.take(80).replace(Regex("""(token|session|cookie|password)=([^;&\s]+)""", RegexOption.IGNORE_CASE)) {
            "${it.groupValues[1]}=<redacted>"
        }

    fun redactBody(value: String, limit: Int = 420): String =
        value
            .replace(Regex("""(?i)(password|passwd|token|access_token|refresh_token|session|sid|cookie|auth|sign)["'=:\s]+([^"',;&<>\s]+)""")) {
                "${it.groupValues[1]}=<redacted>"
            }
            .replace(Regex("""(?i)(Authorization:\s*)(Basic|Bearer|Digest)\s+[^\r\n<]+""")) {
                "${it.groupValues[1]}${it.groupValues[2]} <redacted>"
            }
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(limit)

    fun shortHash(value: String): String {
        if (value.isBlank()) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }.take(12)
    }

    private fun maskHost(host: String): String {
        if (host.length <= 8) return "<host>"
        return "${host.take(4)}...${host.takeLast(4)}"
    }

    private fun cookieNames(raw: String): String =
        raw.split(';', ',')
            .mapNotNull { part ->
                part.substringBefore('=').trim()
                    .takeIf { it.isNotBlank() && !COOKIE_ATTRIBUTES.contains(it.lowercase()) }
            }
            .distinct()
            .joinToString("|")
            .ifBlank { "present" }

    private val SENSITIVE_QUERY_KEYS = listOf(
        "token",
        "access_token",
        "refresh_token",
        "password",
        "passwd",
        "session",
        "sid",
        "cookie",
        "auth",
        "sign",
    )

    private val COOKIE_ATTRIBUTES = setOf(
        "path",
        "domain",
        "expires",
        "max-age",
        "secure",
        "httponly",
        "samesite",
    )
}
