package tech.peakedge.naswalkman.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Locale
import kotlin.random.Random

data class FnConnectEndpoint(
    val baseUrl: String,
    val source: String,
)

data class FnConnectResolution(
    val fnId: String,
    val version: String?,
    val hasCheckSum: Boolean,
    val candidates: List<FnConnectEndpoint>,
)

data class FnConnectEndpointCheck(
    val isReachable: Boolean,
    val message: String? = null,
)

class FnConnectException(message: String) : Exception(message)

class FnConnectClient(private val client: OkHttpClient) {
    suspend fun resolve(fnId: String): FnConnectResolution = withContext(Dispatchers.IO) {
        val normalizedFnId = fnId.trim().trim('/').removePrefix("@")
        val body = """{"fnId":"$normalizedFnId"}"""
        val timestamp = System.currentTimeMillis()
        val nonce = Random.nextInt(100_000, 1_000_000).toString()
        val requestPath = "/api/v1/fn/con"
        val authSign = md5(
            listOf(
                TRIM_PREFIX,
                requestPath,
                nonce,
                timestamp.toString(),
                md5(body),
                API_KEY,
            ).joinToString("_"),
        )
        val request = Request.Builder()
            .url("$FN_CONNECT_ORIGIN$requestPath")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("Origin", FN_CONNECT_ORIGIN)
            .header("Referer", "$FN_CONNECT_ORIGIN/$normalizedFnId")
            .header("fn-sign", sha256("trim_connect`$normalizedFnId`$timestamp`anna"))
            .header("authx", "nonce=$nonce&timestamp=$timestamp&sign=$authSign")
            .build()

        val response = client.newCall(request).execute()
        response.use {
            val contentType = it.body?.contentType()?.toString()
            val text = it.body?.string().orEmpty()
            val diagnosis = HttpBodyClassifier.diagnose(text, contentType)
            SafeHttpLog.event(
                name = "fnconnect.resolve",
                url = request.url.toString(),
                finalUrl = it.request.url.toString(),
                redirected = it.priorResponse != null,
                status = it.code,
                bodyKind = diagnosis.kind,
                bodyReason = diagnosis.reason,
                bodySummary = diagnosis.summary,
                contentType = contentType,
                setCookie = it.headers("Set-Cookie").joinToString(";"),
                requestCookie = it.request.header("Cookie"),
            )
            if (!it.isSuccessful) {
                throw FnConnectException("FN Connect 查询失败，服务返回 HTTP ${it.code}。")
            }
            if (diagnosis.kind != HttpBodyClassifier.JSON) {
                throw FnConnectException("FN Connect 返回的不是有效连接信息：${diagnosis.reason}。请稍后重试。")
            }

            val json = runCatching { JSONObject(text) }
                .getOrElse {
                    throw FnConnectException("FN Connect 返回 HTTP 200，但返回体不是有效 JSON。")
                }
            val code = json.optInt("code", -1)
            if (code != 0) {
                val message = json.optString("msg").ifBlank { "FN Connect 查询失败，错误码 $code。" }
                throw FnConnectException(fnConnectErrorMessage(code, message))
            }
            val data = json.optJSONObject("data")
                ?: throw FnConnectException("FN Connect 返回成功，但缺少连接地址信息。")
            val port = data.optJSONObject("port")
            val httpPort = port?.optInt("httpPort", -1)?.takeIf { value -> value > 0 }
            val httpsPort = port?.optInt("httpsPort", -1)?.takeIf { value -> value > 0 }
            val candidates = buildList {
                data.optStringArray("fn").forEach { host ->
                    add(FnConnectEndpoint(toBaseUrl("https", host, null), "relay"))
                }
                data.optStringArray("ddns").forEach { host ->
                    add(FnConnectEndpoint(toBaseUrl("https", host, httpsPort), "ddns"))
                }
                data.optStringArray("publicIpv4").forEach { host ->
                    httpPort?.let { portValue -> add(FnConnectEndpoint(toBaseUrl("http", host, portValue), "public-ipv4")) }
                    httpsPort?.let { portValue -> add(FnConnectEndpoint(toBaseUrl("https", host, portValue), "public-ipv4-https")) }
                }
                data.optStringArray("publicIpv6").forEach { host ->
                    httpPort?.let { portValue -> add(FnConnectEndpoint(toBaseUrl("http", host, portValue), "public-ipv6")) }
                    httpsPort?.let { portValue -> add(FnConnectEndpoint(toBaseUrl("https", host, portValue), "public-ipv6-https")) }
                }
                data.optStringArray("ipv4").forEach { host ->
                    httpPort?.let { portValue -> add(FnConnectEndpoint(toBaseUrl("http", host, portValue), "lan-ipv4")) }
                    httpsPort?.let { portValue -> add(FnConnectEndpoint(toBaseUrl("https", host, portValue), "lan-ipv4-https")) }
                }
                data.optStringArray("ipv6").forEach { host ->
                    httpPort?.let { portValue -> add(FnConnectEndpoint(toBaseUrl("http", host, portValue), "lan-ipv6")) }
                    httpsPort?.let { portValue -> add(FnConnectEndpoint(toBaseUrl("https", host, portValue), "lan-ipv6-https")) }
                }
            }.distinctBy { endpoint -> endpoint.baseUrl }

            if (candidates.isEmpty()) {
                throw FnConnectException("FN Connect 可查询到设备，但没有返回可用访问地址。请确认设备在线并已开启 FN Connect。")
            }
            FnConnectResolution(
                fnId = normalizedFnId,
                version = data.optString("ver").ifBlank { null },
                hasCheckSum = data.optString("checkSum").isNotBlank(),
                candidates = candidates,
            )
        }
    }

    suspend fun checkEndpoint(baseUrl: String): FnConnectEndpointCheck = withContext(Dispatchers.IO) {
        try {
            val url = "${baseUrl.trimEnd('/')}/trimcon"
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "text/plain,text/html,application/json")
                .build()
            val response = client.newCall(request).execute()
            response.use {
                val contentType = it.body?.contentType()?.toString()
                val text = it.body?.string().orEmpty()
                val diagnosis = HttpBodyClassifier.diagnose(text, contentType)
                SafeHttpLog.event(
                    name = "fnconnect.check",
                    url = url,
                    finalUrl = it.request.url.toString(),
                    redirected = it.priorResponse != null,
                    status = it.code,
                    bodyKind = diagnosis.kind,
                    bodyReason = diagnosis.reason,
                    bodySummary = diagnosis.summary,
                    contentType = contentType,
                    setCookie = it.headers("Set-Cookie").joinToString(";"),
                    requestCookie = it.request.header("Cookie"),
                )
                if ((it.code == 200 || it.code == 204) && diagnosis.kind != HttpBodyClassifier.HTML_OR_LOGIN) {
                    FnConnectEndpointCheck(isReachable = true)
                } else {
                    FnConnectEndpointCheck(
                        isReachable = false,
                        message = "FN Connect 地址可打开，但会话检查返回 ${diagnosis.reason}，不是有效文件服务会话。",
                    )
                }
            }
        } catch (_: UnknownHostException) {
            FnConnectEndpointCheck(false, "FN Connect 地址无法解析。")
        } catch (_: SocketTimeoutException) {
            FnConnectEndpointCheck(false, "FN Connect 会话检查超时。")
        } catch (_: Exception) {
            FnConnectEndpointCheck(false, "FN Connect 会话检查失败。")
        }
    }

    private fun fnConnectErrorMessage(code: Int, fallback: String): String =
        when (code) {
            3001, 3002, 3003 -> "FN ID 不存在或设备未开启 FN Connect，请检查 FN ID。"
            4001, 4003 -> "FN Connect 暂时不可用，请稍后重试。"
            5000 -> "FN Connect 校验失败，请稍后重试。"
            else -> fallback
        }

    private fun JSONObject.optStringArray(name: String): List<String> {
        val array = optJSONArray(name) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun toBaseUrl(scheme: String, rawHost: String, port: Int?): String {
        val value = rawHost.trim().trimEnd('/')
        if (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)) {
            return value
        }
        val host = if (value.contains(":") && !value.startsWith("[") && value.count { it == ':' } > 1) {
            "[$value]"
        } else {
            value
        }
        val hasPort = host.substringAfterLast(']').contains(":")
        val suffix = if (port != null && !hasPort) ":$port" else ""
        return "$scheme://$host$suffix"
    }

    private fun md5(value: String): String =
        digest("MD5", value)

    private fun sha256(value: String): String =
        digest("SHA-256", value)

    private fun digest(algorithm: String, value: String): String {
        val digest = MessageDigest.getInstance(algorithm).digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }.lowercase(Locale.US)
    }

    private companion object {
        const val FN_CONNECT_ORIGIN = "https://5ddd.com"
        const val TRIM_PREFIX = "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh"
        const val API_KEY = "zIGtkc3dqZnJpd29qZXJqa2w7c"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
