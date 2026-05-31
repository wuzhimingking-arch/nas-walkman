package com.fnnas.music.network

import androidx.core.net.toUri
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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
}

sealed class WebDavResult {
    data class Success(val message: String? = null) : WebDavResult()
    data class Failure(val message: String, val code: Int? = null) : WebDavResult()
}

class WebDavClient(private val baseClient: OkHttpClient) {
    suspend fun testConnection(credentials: NasCredentials): WebDavResult = withContext(Dispatchers.IO) {
        try {
            val items = listDirectory(credentials, credentials.musicRootPath)
            WebDavResult.Success(
                message = if (items.isEmpty()) {
                    "连接成功，但当前目录没有发现音乐"
                } else {
                    null
                },
            )
        } catch (error: WebDavHttpException) {
            when (error.code) {
                401, 403 -> WebDavResult.Failure("登录失败，请检查飞牛 NAS 用户名或密码。", error.code)
                404 -> WebDavResult.Failure("没有找到这个音乐目录，请检查路径是否正确。", error.code)
                else -> WebDavResult.Failure("连接飞牛 NAS 失败，请检查 FN Connect 是否开启、地址是否正确、账号密码是否正确。", error.code)
            }
        } catch (_: UnknownHostException) {
            WebDavResult.Failure("无法找到这个 FN Connect 地址，请检查 FN ID 或远程访问地址。")
        } catch (_: SocketTimeoutException) {
            WebDavResult.Failure("连接超时，可能是网络较慢、NAS 休眠或 FN Connect 当前不稳定。")
        } catch (_: SSLHandshakeException) {
            WebDavResult.Failure("安全证书校验失败，请检查访问地址是否正确。")
        } catch (_: SSLException) {
            WebDavResult.Failure("安全连接失败，请检查访问地址或证书配置。")
        } catch (_: ConnectException) {
            WebDavResult.Failure("NAS 拒绝连接，请检查远程访问服务是否开启。")
        } catch (_: IllegalArgumentException) {
            WebDavResult.Failure("请填写有效的 FN Connect 地址或远程访问地址。")
        } catch (_: Exception) {
            WebDavResult.Failure("无法连接到飞牛 NAS，请检查 FN Connect 是否开启或当前网络是否可用。")
        }
    }

    suspend fun listDirectory(credentials: NasCredentials, remotePath: String): List<RemoteItem> =
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
                if (!it.isSuccessful || it.code != 207) {
                    throw WebDavHttpException(it.code)
                }
                parseMultiStatus(
                    xml = it.body?.string().orEmpty(),
                    credentials = credentials,
                    requestedPath = normalizeRemotePath(remotePath),
                )
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
            .filter { it.isDirectory || it.isSupportedAudio }
            .sortedWith(compareBy<RemoteItem> { !it.isDirectory }.thenBy { it.displayName.lowercase(Locale.ROOT) })
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

object AudioFormats {
    private val supported = setOf("mp3", "flac", "m4a", "aac", "wav", "ogg", "opus")

    fun isSupported(fileName: String): Boolean =
        fileName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT) in supported
}
