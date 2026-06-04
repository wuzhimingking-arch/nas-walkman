package tech.peakedge.naswalkman.network

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
}
