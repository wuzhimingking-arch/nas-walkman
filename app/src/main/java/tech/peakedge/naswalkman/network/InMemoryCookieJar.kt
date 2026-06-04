package tech.peakedge.naswalkman.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class InMemoryCookieJar : CookieJar {
    private val store = mutableMapOf<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(store) {
            val key = url.host
            val existing = store.getOrPut(key) { mutableListOf() }
            val updated = (cookies + existing)
                .filter { it.expiresAt > System.currentTimeMillis() }
                .distinctBy { "${it.name}|${it.domain}|${it.path}" }
            existing.clear()
            existing.addAll(updated)
        }
        SafeHttpLog.event(
            name = "cookie.save",
            url = url.toString(),
            setCookie = cookies.joinToString(";") { "${it.name}=<redacted>" },
        )
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cookies = synchronized(store) {
            store[url.host]
                .orEmpty()
                .filter { cookie ->
                    cookie.expiresAt > System.currentTimeMillis() &&
                        url.encodedPath.startsWith(cookie.path)
                }
        }
        if (cookies.isNotEmpty()) {
            SafeHttpLog.event(
                name = "cookie.load",
                url = url.toString(),
                requestCookie = cookies.joinToString(";") { "${it.name}=<redacted>" },
            )
        }
        return cookies
    }
}
