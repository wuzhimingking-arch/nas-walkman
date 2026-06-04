package tech.peakedge.naswalkman.network

import org.json.JSONArray
import org.json.JSONObject

data class HttpBodyDiagnosis(
    val kind: String,
    val reason: String,
    val summary: String,
)

object HttpBodyClassifier {
    const val WEBDAV_XML = "webdav-xml"
    const val JSON = "json"
    const val HTML_OR_LOGIN = "html-or-login"
    const val EMPTY = "empty"
    const val UNKNOWN = "unknown"

    fun classify(body: String, contentType: String? = null): String {
        val trimmed = body.trimStart()
        return when {
            contentType?.contains("xml", ignoreCase = true) == true ||
                trimmed.contains("<multistatus", ignoreCase = true) ||
                trimmed.contains("<d:multistatus", ignoreCase = true) -> WEBDAV_XML
            contentType?.contains("json", ignoreCase = true) == true ||
                trimmed.startsWith("{") ||
                trimmed.startsWith("[") -> JSON
            trimmed.isBlank() -> EMPTY
            trimmed.contains("<html", ignoreCase = true) ||
                trimmed.contains("<!doctype html", ignoreCase = true) ||
                trimmed.contains("login", ignoreCase = true) ||
                trimmed.contains("captcha", ignoreCase = true) ||
                trimmed.contains("验证码") ||
                trimmed.contains("登录") ||
                trimmed.contains("FN Connect") -> HTML_OR_LOGIN
            else -> UNKNOWN
        }
    }

    fun diagnose(body: String, contentType: String? = null): HttpBodyDiagnosis {
        val kind = classify(body, contentType)
        return HttpBodyDiagnosis(
            kind = kind,
            reason = when (kind) {
                WEBDAV_XML -> diagnoseXml(body)
                JSON -> diagnoseJson(body)
                HTML_OR_LOGIN -> diagnoseHtml(body)
                EMPTY -> "empty-body"
                else -> "unknown-body"
            },
            summary = SafeHttpLog.redactBody(body),
        )
    }

    private fun diagnoseXml(body: String): String {
        val lower = body.lowercase()
        return when {
            "<multistatus" in lower || "<d:multistatus" in lower -> "webdav-multistatus"
            "<response" in lower || "<d:response" in lower -> "webdav-response"
            else -> "xml-without-webdav-directory"
        }
    }

    private fun diagnoseJson(body: String): String =
        runCatching {
            val trimmed = body.trimStart()
            val json = if (trimmed.startsWith("[")) {
                JSONObject().put("data", JSONArray(trimmed))
            } else {
                JSONObject(trimmed)
            }
            val code = when {
                json.has("code") -> json.opt("code")?.toString()
                json.has("errno") -> json.opt("errno")?.toString()
                json.has("status") -> json.opt("status")?.toString()
                else -> null
            }
            val success = when {
                json.has("success") -> json.opt("success")?.toString()
                json.has("ok") -> json.opt("ok")?.toString()
                else -> null
            }
            val message = listOf("message", "msg", "error", "detail")
                .firstNotNullOfOrNull { key -> json.optString(key).takeIf { it.isNotBlank() } }
            buildString {
                append("json")
                code?.let { append("-code=").append(it.take(24)) }
                success?.let { append("-success=").append(it.take(12)) }
                message?.let { append("-message=").append(it.take(60)) }
            }
        }.getOrDefault("json-parse-failed")

    private fun diagnoseHtml(body: String): String {
        val lower = body.lowercase()
        val title = Regex("""<title[^>]*>(.*?)</title>""", RegexOption.IGNORE_CASE)
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            .orEmpty()
        return when {
            "fn connect" in lower || "5ddd" in lower || "远程访问" in body ->
                "fn-connect-page${title.suffixDetail()}"
            "captcha" in lower || "验证码" in body ->
                "captcha-page${title.suffixDetail()}"
            "login" in lower || "signin" in lower || "登录" in body ->
                "login-page${title.suffixDetail()}"
            "forbidden" in lower || "unauthorized" in lower || "permission" in lower || "权限" in body ->
                "permission-page${title.suffixDetail()}"
            "not found" in lower || "404" in lower || "不存在" in body ->
                "not-found-page${title.suffixDetail()}"
            "405" in lower || "not allowed" in lower ->
                "method-not-allowed-page${title.suffixDetail()}"
            "webdav" in lower || "dav" in lower ->
                "html-mentions-webdav${title.suffixDetail()}"
            else ->
                "html-page${title.suffixDetail()}"
        }
    }

    private fun String.suffixDetail(): String =
        takeIf { it.isNotBlank() }?.let { ":$it" }.orEmpty()
}
