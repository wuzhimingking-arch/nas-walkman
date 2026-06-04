package tech.peakedge.naswalkman.network

import androidx.core.net.toUri
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.StringReader
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.net.URLDecoder
import java.util.Locale
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RemoteItem(
    val remotePath: String,
    val displayName: String,
    val isDirectory: Boolean,
    val size: Long?,
    val modifiedAt: String?,
) {
    val isSupportedAudio: Boolean
        get() = !isDirectory && AudioFormats.isSupported(displayName)

    val isLrcFile: Boolean
        get() = !isDirectory && displayName.endsWith(".lrc", ignoreCase = true)
}

sealed class WebDavResult {
    data class Success(val message: String? = null) : WebDavResult()
    data class Failure(val message: String, val code: Int? = null) : WebDavResult()
}

class WebDavClient(private val baseClient: OkHttpClient) {
    suspend fun testConnection(credentials: NasCredentials): WebDavResult = withContext(Dispatchers.IO) {
        try {
            val items = listDirectory(credentials, "/")
            WebDavResult.Success(
                message = if (items.isEmpty()) {
                    "连接成功，但当前目录没有发现音乐"
                } else {
                    null
                },
            )
        } catch (error: WebDavHttpException) {
            when (error.code) {
                401 -> WebDavResult.Failure("登录失败，请检查 NAS 用户名或密码。", error.code)
                403 -> WebDavResult.Failure("权限不足，当前账号无法访问文件服务。请到飞牛 OS 为该账号开启文件访问或共享目录权限。", error.code)
                404, 405, 501 -> WebDavResult.Failure("文件服务未开启或当前地址不是文件访问地址。请到飞牛 OS 的系统设置 > 文件共享协议 > WebDAV 开启服务，并确认共享目录权限。", error.code)
                else -> WebDavResult.Failure("连接 NAS 失败，文件服务响应异常（HTTP ${error.code}）。请检查 FN Connect、文件访问服务和地址配置。", error.code)
            }
        } catch (error: WebDavUnexpectedResponseException) {
            WebDavResult.Failure(unexpectedResponseMessage(error), error.code)
        } catch (_: UnknownHostException) {
            WebDavResult.Failure("无法找到这个 FN Connect 地址，请检查 FN ID 或远程访问地址。")
        } catch (_: SocketTimeoutException) {
            WebDavResult.Failure("网络不可达或连接超时，可能是网络较慢、NAS 休眠或 FN Connect 当前不稳定。")
        } catch (_: SSLHandshakeException) {
            WebDavResult.Failure("安全证书校验失败，请检查访问地址是否正确。")
        } catch (_: SSLException) {
            WebDavResult.Failure("安全连接失败，请检查访问地址或证书配置。")
        } catch (_: ConnectException) {
            WebDavResult.Failure("网络不可达，NAS 拒绝连接。请检查 FN Connect 或文件访问服务是否开启。")
        } catch (_: IllegalArgumentException) {
            WebDavResult.Failure("请填写有效的 FN Connect 地址或远程访问地址。")
        } catch (_: Exception) {
            WebDavResult.Failure("网络不可达，无法连接 NAS。请检查 FN Connect 是否开启或当前网络是否可用。")
        }
    }

    suspend fun testDirectory(credentials: NasCredentials, remotePath: String): WebDavResult = withContext(Dispatchers.IO) {
        try {
            listDirectory(credentials, remotePath)
            WebDavResult.Success()
        } catch (error: WebDavHttpException) {
            when (error.code) {
                401 -> WebDavResult.Failure("登录失败，请检查 NAS 用户名或密码。", error.code)
                403 -> WebDavResult.Failure("当前账号没有该目录权限，请到飞牛 OS 为该账号授权共享目录。", error.code)
                404 -> WebDavResult.Failure("音乐目录不存在，请重新选择目录或确认路径是否正确。", error.code)
                405, 501 -> WebDavResult.Failure("文件服务未开启或不支持目录读取。请到飞牛 OS 的系统设置 > 文件共享协议 > WebDAV 开启服务。", error.code)
                else -> WebDavResult.Failure("已连接到 NAS，但暂时无法读取文件夹，文件服务响应异常（HTTP ${error.code}）。", error.code)
            }
        } catch (error: WebDavUnexpectedResponseException) {
            WebDavResult.Failure(unexpectedResponseMessage(error), error.code)
        } catch (_: UnknownHostException) {
            WebDavResult.Failure("无法找到这个 FN Connect 地址，请检查 FN ID 或远程访问地址。")
        } catch (_: SocketTimeoutException) {
            WebDavResult.Failure("网络不可达或连接超时，可能是 NAS 休眠、网络较慢或远程访问不稳定。")
        } catch (_: SSLHandshakeException) {
            WebDavResult.Failure("安全证书校验失败，请检查访问地址是否正确。")
        } catch (_: SSLException) {
            WebDavResult.Failure("安全连接失败，请检查访问地址或证书配置。")
        } catch (_: ConnectException) {
            WebDavResult.Failure("网络不可达，NAS 拒绝连接。请检查远程访问服务是否开启。")
        } catch (_: IllegalArgumentException) {
            WebDavResult.Failure("路径解析失败，请重新进入目录选择器选择文件夹。")
        } catch (_: Exception) {
            WebDavResult.Failure("已连接到 NAS，但暂时无法读取文件夹。请确认飞牛 NAS 已开启文件访问服务，并给当前账号授权共享目录。")
        }
    }

    suspend fun listDirectory(credentials: NasCredentials, remotePath: String): List<RemoteItem> =
        listDirectoryInternal(credentials, remotePath)
            .filter { it.isDirectory || it.isSupportedAudio }
            .sortedWith(compareBy<RemoteItem> { !it.isDirectory }.thenBy { it.displayName.lowercase(Locale.ROOT) })

    suspend fun listLyricFiles(credentials: NasCredentials, remotePath: String): List<RemoteItem> =
        listDirectoryInternal(credentials, remotePath)
            .filter { it.isLrcFile }
            .sortedBy { it.displayName.lowercase(Locale.ROOT) }

    private suspend fun listDirectoryInternal(credentials: NasCredentials, remotePath: String): List<RemoteItem> =
        withContext(Dispatchers.IO) {
            val body = PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE)
            val request = authedRequest(credentials, remotePath)
                .method("PROPFIND", body)
                .header("Depth", "1")
                .header("Accept", "application/xml,text/xml")
                .header("Content-Type", "application/xml; charset=utf-8")
                .build()
            val response = clientFor(credentials).newCall(request).execute()
            response.use {
                val contentType = it.body?.contentType()?.toString()
                val text = it.body?.string().orEmpty()
                val diagnosis = HttpBodyClassifier.diagnose(text, contentType)
                if (!it.isSuccessful || it.code != 207) {
                    SafeHttpLog.event(
                        name = "webdav.propfind.unexpected",
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
                    if (it.isSuccessful) {
                        throw WebDavUnexpectedResponseException(
                            code = it.code,
                            diagnosis = diagnosis,
                            contentType = contentType,
                            redirected = it.priorResponse != null,
                            finalUrl = it.request.url.toString(),
                        )
                    }
                    throw WebDavHttpException(it.code)
                }
                if (diagnosis.kind != HttpBodyClassifier.WEBDAV_XML) {
                    SafeHttpLog.event(
                        name = "webdav.propfind.invalid-body",
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
                    throw WebDavUnexpectedResponseException(
                        code = it.code,
                        diagnosis = diagnosis,
                        contentType = contentType,
                        redirected = it.priorResponse != null,
                        finalUrl = it.request.url.toString(),
                    )
                }
                try {
                    parseMultiStatus(
                        xml = text,
                        credentials = credentials,
                        requestedPath = normalizeRemotePath(remotePath),
                    )
                } catch (_: XmlPullParserException) {
                    SafeHttpLog.event(
                        name = "webdav.propfind.parse-failed",
                        url = request.url.toString(),
                        finalUrl = it.request.url.toString(),
                        redirected = it.priorResponse != null,
                        status = it.code,
                        bodyKind = diagnosis.kind,
                        bodyReason = "webdav-xml-parse-failed",
                        bodySummary = diagnosis.summary,
                        contentType = contentType,
                        requestCookie = it.request.header("Cookie"),
                    )
                    throw WebDavUnexpectedResponseException(
                        code = it.code,
                        diagnosis = diagnosis.copy(reason = "webdav-xml-parse-failed"),
                        contentType = contentType,
                        redirected = it.priorResponse != null,
                        finalUrl = it.request.url.toString(),
                    )
                }
            }
        }

    suspend fun downloadToFile(credentials: NasCredentials, remotePath: String, target: File): Long =
        withContext(Dispatchers.IO) {
            val request = authedRequest(credentials, remotePath).get().build()
            val response = clientFor(credentials).newCall(request).execute()
            response.use {
                if (!it.isSuccessful) throw WebDavHttpException(it.code)
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { output ->
                    val body = it.body ?: return@withContext 0L
                    body.byteStream().use { input -> input.copyTo(output) }
                }
                target.length()
            }
        }

    suspend fun readTextFile(credentials: NasCredentials, remotePath: String, maxBytes: Long): String =
        withContext(Dispatchers.IO) {
            val request = authedRequest(credentials, remotePath).get().build()
            val response = clientFor(credentials).newCall(request).execute()
            response.use {
                if (!it.isSuccessful) throw WebDavHttpException(it.code)
                val body = it.body ?: return@withContext ""
                val contentLength = body.contentLength()
                if (contentLength > maxBytes) {
                    throw IOException("text file is too large: $contentLength")
                }
                decodeText(body.bytes())
            }
        }

    fun urlFor(credentials: NasCredentials, remotePath: String): String =
        buildUrl(credentials.baseUrl, remotePath)

    private fun authedRequest(credentials: NasCredentials, remotePath: String): Request.Builder {
        val basicHeader = Credentials.basic(credentials.username, credentials.password, Charsets.UTF_8)
        return Request.Builder()
            .url(buildUrl(credentials.baseUrl, remotePath))
            .header("Authorization", basicHeader)
    }

    private fun clientFor(credentials: NasCredentials): OkHttpClient =
        baseClient.newBuilder()
            .authenticator(DigestAuthenticator(FixedCredentialsProvider(credentials)))
            .build()

    private fun buildUrl(baseUrl: String, remotePath: String): String {
        val base = baseUrl.ensureTrailingSlash().toHttpUrl()
        val builder = base.newBuilder()
        normalizeRemotePath(remotePath).trim('/').split('/')
            .filter { it.isNotBlank() }
            .forEach { builder.addPathSegment(it) }
        return builder.build().toString()
    }

    private fun parseMultiStatus(
        xml: String,
        credentials: NasCredentials,
        requestedPath: String,
    ): List<RemoteItem> {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(StringReader(xml))
        val responses = mutableListOf<ResponseBuilder>()
        var current: ResponseBuilder? = null
        var text = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.localNameCompat()) {
                        "response" -> current = ResponseBuilder()
                        "collection" -> current?.isDirectory = true
                    }
                    text = ""
                }
                XmlPullParser.TEXT -> text = parser.text.orEmpty()
                XmlPullParser.END_TAG -> {
                    when (parser.localNameCompat()) {
                        "href" -> current?.href = text.trim()
                        "displayname" -> current?.displayName = text.trim()
                        "getcontentlength" -> current?.size = text.trim().toLongOrNull()
                        "getlastmodified" -> current?.modifiedAt = text.trim().ifBlank { null }
                        "response" -> current?.let { responses += it }.also { current = null }
                    }
                    text = ""
                }
            }
            event = parser.next()
        }

        return responses.mapNotNull { response ->
            val remotePath = response.href
                ?.let { remotePathFromHref(credentials.baseUrl, it) }
                ?.takeIf { it != requestedPath.trimEnd('/') }
                ?: return@mapNotNull null
            val displayName = response.displayName
                ?.takeIf { it.isNotBlank() }
                ?: remotePath.substringAfterLast('/').ifBlank { remotePath }
            RemoteItem(
                remotePath = remotePath,
                displayName = displayName,
                isDirectory = response.isDirectory,
                size = response.size,
                modifiedAt = response.modifiedAt,
            )
        }
    }

    private fun remotePathFromHref(baseUrl: String, href: String): String {
        val base = baseUrl.ensureTrailingSlash().toHttpUrl()
        val hrefPath = if (href.startsWith("http", ignoreCase = true)) {
            href.toUri().encodedPath.orEmpty()
        } else {
            href.toUri().encodedPath ?: href.substringBefore('?')
        }
        val decodedHref = URLDecoder.decode(hrefPath, Charsets.UTF_8.name()).trimEnd('/')
        val decodedBase = URLDecoder.decode(base.encodedPath, Charsets.UTF_8.name()).trimEnd('/')
        val withoutBase = decodedHref.removePrefix(decodedBase).trim('/')
        return "/$withoutBase"
    }

    private fun normalizeRemotePath(path: String): String =
        "/" + path.trim().trim('/').replace('\\', '/')

    private fun String.ensureTrailingSlash(): String = if (endsWith("/")) this else "$this/"

    private fun XmlPullParser.localNameCompat(): String =
        (name ?: "").substringAfter(':').lowercase(Locale.ROOT)

    private fun unexpectedResponseMessage(error: WebDavUnexpectedResponseException): String {
        val reason = error.diagnosis.reason
        return when {
            reason.startsWith("fn-connect-page") ->
                "返回的是 FN Connect 远程访问引导页，不是文件目录。FN Connect 只提供远程入口，音乐文件仍需要开启 WebDAV/文件访问服务；请在飞牛 OS 开启 WebDAV，并使用 WebDAV 文件服务地址。"
            reason.startsWith("login-page") ->
                "返回的是登录页，登录态失效或文件服务需要网页登录。请重新登录，或改用 WebDAV 文件服务地址。"
            reason.startsWith("captcha-page") ->
                "返回的是验证码页面，App 无法通过该页面读取文件目录。请在飞牛 OS 开启 WebDAV/文件访问服务后重试。"
            reason.startsWith("permission-page") ->
                "返回的是权限页面，当前账号没有文件访问权限。请到飞牛 OS 为该账号开启文件访问或共享目录权限。"
            reason.startsWith("not-found-page") ->
                "返回的是 404/不存在页面，未找到文件服务或音乐目录。请检查 music 目录路径和 WebDAV 地址。"
            reason.startsWith("method-not-allowed-page") ->
                "当前地址不支持 PROPFIND 目录读取，可能是 fnOS 管理入口而不是 WebDAV 文件服务。请确认 WebDAV 服务已开启并使用文件服务地址。"
            reason == "empty-body" ->
                "文件服务返回空内容，无法确认目录列表。请检查 WebDAV/文件访问服务是否开启。"
            reason == "webdav-xml-parse-failed" ->
                "文件服务可访问，但目录 XML 解析失败。请稍后重试，或检查 WebDAV 服务返回格式。"
            error.diagnosis.kind == HttpBodyClassifier.JSON ->
                "返回的是 JSON 响应（${reason}），不是 WebDAV 目录。可能是登录态、权限或文件服务接口异常。"
            error.diagnosis.kind == HttpBodyClassifier.WEBDAV_XML ->
                "文件服务可访问，但目录解析失败。请检查 WebDAV 返回内容是否为有效目录列表。"
            else ->
                "文件服务返回了无法识别的内容，已在调试日志输出脱敏摘要。请检查文件访问服务和目录路径。"
        }
    }

    private fun decodeText(bytes: ByteArray): String {
        val utf8 = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF')
        if ('\uFFFD' !in utf8) return utf8
        return runCatching { String(bytes, Charset.forName("GB18030")).trimStart('\uFEFF') }
            .getOrDefault(utf8)
    }

    private data class ResponseBuilder(
        var href: String? = null,
        var displayName: String? = null,
        var isDirectory: Boolean = false,
        var size: Long? = null,
        var modifiedAt: String? = null,
    )

    private companion object {
        val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8" ?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:displayname/>
    <d:resourcetype/>
    <d:getcontentlength/>
    <d:getlastmodified/>
  </d:prop>
</d:propfind>"""
    }
}

class WebDavHttpException(val code: Int) : Exception("WebDAV request failed with HTTP $code")

class WebDavUnexpectedResponseException(
    val code: Int,
    val diagnosis: HttpBodyDiagnosis,
    val contentType: String?,
    val redirected: Boolean = false,
    val finalUrl: String? = null,
) : Exception("WebDAV returned unexpected HTTP $code body=${diagnosis.kind} reason=${diagnosis.reason} contentType=$contentType") {
    val bodyKind: String
        get() = diagnosis.kind
}

object AudioFormats {
    private val supported = setOf("mp3", "flac", "m4a", "aac", "wav", "ogg", "opus")

    fun isSupported(fileName: String): Boolean =
        fileName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT) in supported
}
