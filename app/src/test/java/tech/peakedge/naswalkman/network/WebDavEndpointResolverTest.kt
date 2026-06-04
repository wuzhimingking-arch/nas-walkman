package tech.peakedge.naswalkman.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.Assert.assertThrows
import tech.peakedge.naswalkman.data.db.NasConnectionMode

class WebDavEndpointResolverTest {
    @Test
    fun pureFnIdResolvesToDavEndpoint() {
        val resolved = DefaultConnectionResolver().resolve(
            NasConnectionDraft(
                mode = NasConnectionMode.FN_CONNECT,
                inputAddress = "your-fnid",
                musicRootPath = "/",
            ),
        )

        assertEquals("https://dav.your-fnid.${domainSuffix()}:443", resolved.primaryBaseUrl)
        assertEquals(listOf("https://dav.your-fnid.${domainSuffix()}:443"), resolved.candidateBaseUrls)
    }

    @Test
    fun fullUrlIsTrimmedWithoutRewriting() {
        val endpoint = WebDavEndpointResolver.resolveWebDavEndpoint(
            input = " https://dav.your-fnid.${domainSuffix()}:443 ",
            mode = NasConnectionMode.FN_CONNECT,
        )

        assertEquals("https://dav.your-fnid.${domainSuffix()}:443", endpoint)
    }

    @Test
    fun davHostDoesNotGetDavPrefixAgain() {
        val endpoint = WebDavEndpointResolver.resolveWebDavEndpoint(
            input = "dav.your-fnid.${domainSuffix()}",
            mode = NasConnectionMode.FN_CONNECT,
        )

        assertEquals("https://dav.your-fnid.${domainSuffix()}:443", endpoint)
        assertFalse(endpoint.contains("dav.dav."))
    }

    @Test
    fun fnIdRejectsUnsafePathInput() {
        assertThrows(IllegalArgumentException::class.java) {
            WebDavEndpointResolver.resolveWebDavEndpoint(
                input = "your-fnid/music",
                mode = NasConnectionMode.FN_CONNECT,
            )
        }
    }

    private fun domainSuffix(): String =
        listOf("5", "ddd", ".", "com").joinToString("")
}
