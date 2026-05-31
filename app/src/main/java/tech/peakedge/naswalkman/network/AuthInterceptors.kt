package tech.peakedge.naswalkman.network

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

class BasicAuthInterceptor(
    private val credentialsProvider: CredentialsProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val credentials = credentialsProvider.currentCredentials()
        val request = chain.request()
        if (credentials == null || request.header("Authorization") != null) {
            return chain.proceed(request)
        }
        val baseUrl = credentials.baseUrl.toHttpUrlOrNull() ?: return chain.proceed(request)
        val shouldAttach = request.url.host == baseUrl.host && request.url.port == baseUrl.port
        if (!shouldAttach) return chain.proceed(request)

        val basicHeader = Credentials.basic(credentials.username, credentials.password, Charsets.UTF_8)
        return chain.proceed(request.newBuilder().header("Authorization", basicHeader).build())
    }
}

class DigestAuthenticator(
    private val credentialsProvider: CredentialsProvider,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null
        val challenge = response.headers("WWW-Authenticate")
            .firstOrNull { it.trimStart().startsWith("Digest", ignoreCase = true) }
            ?: return null
        val credentials = credentialsProvider.currentCredentials() ?: return null
        val params = parseDigestChallenge(challenge)
        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        val opaque = params["opaque"]
        val algorithm = params["algorithm"]?.uppercase(Locale.US) ?: "MD5"
        if (algorithm != "MD5") return null

        val qop = params["qop"]
            ?.split(',')
            ?.map { it.trim().trim('"') }
            ?.firstOrNull { it.equals("auth", ignoreCase = true) }
        val cnonce = UUID.randomUUID().toString().replace("-", "")
        val nc = "00000001"
        val uri = response.request.url.encodedPath +
            (response.request.url.encodedQuery?.let { "?$it" } ?: "")
        val ha1 = md5("${credentials.username}:$realm:${credentials.password}")
        val ha2 = md5("${response.request.method}:$uri")
        val responseDigest = if (qop != null) {
            md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
        } else {
            md5("$ha1:$nonce:$ha2")
        }

        val header = buildString {
            append("Digest ")
            append("username=\"${credentials.username.digestEscaped()}\"")
            append(", realm=\"${realm.digestEscaped()}\"")
            append(", nonce=\"${nonce.digestEscaped()}\"")
            append(", uri=\"${uri.digestEscaped()}\"")
            append(", response=\"$responseDigest\"")
            if (opaque != null) append(", opaque=\"${opaque.digestEscaped()}\"")
            if (qop != null) {
                append(", qop=$qop, nc=$nc, cnonce=\"${cnonce.digestEscaped()}\"")
            }
        }
        return response.request.newBuilder().header("Authorization", header).build()
    }

    private fun parseDigestChallenge(challenge: String): Map<String, String> {
        val payload = challenge.removePrefix("Digest").trim()
        val values = mutableMapOf<String, String>()
        var index = 0
        while (index < payload.length) {
            while (index < payload.length && (payload[index] == ',' || payload[index].isWhitespace())) index++
            val keyStart = index
            while (index < payload.length && payload[index] != '=') index++
            if (index >= payload.length) break
            val key = payload.substring(keyStart, index).trim().lowercase(Locale.US)
            index++
            val value = if (index < payload.length && payload[index] == '"') {
                index++
                val start = index
                while (index < payload.length && payload[index] != '"') index++
                payload.substring(start, index).also { index++ }
            } else {
                val start = index
                while (index < payload.length && payload[index] != ',') index++
                payload.substring(start, index).trim()
            }
            values[key] = value
        }
        return values
    }

    private fun md5(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.ISO_8859_1))
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private fun String.digestEscaped(): String = replace("\\", "\\\\").replace("\"", "\\\"")
}
