package com.surenjanath.crownfoundry.api

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** What Settings may type, and what the socket must end up seeing. */
class BaseUrlTest : MockBackendTest() {

    private fun normalised(raw: String) = normaliseBaseUrl(raw)

    @Test
    fun `the default is the emulator loopback`() {
        assertEquals("http://10.0.2.2:8000", DEFAULT_BASE_URL)
        assertEquals("http://10.0.2.2:8000", CrownFoundryClient.baseUrl)
    }

    @Test
    fun `normalisation table`() {
        assertEquals("http://10.0.2.2:8000", normalised("http://10.0.2.2:8000"))
        assertEquals("http://10.0.2.2:8000", normalised("http://10.0.2.2:8000/"))
        assertEquals("http://10.0.2.2:8000", normalised("http://10.0.2.2:8000///"))
        assertEquals("http://10.0.2.2:8000", normalised("10.0.2.2:8000"))
        assertEquals("http://10.0.2.2:8000", normalised("  http://10.0.2.2:8000/  "))
        assertEquals("http://10.0.2.2:8000", normalised("\n\t10.0.2.2:8000/\n"))
        assertEquals("https://crownfoundry.example.com", normalised("https://crownfoundry.example.com/"))
        assertEquals("https://crownfoundry.example.com", normalised("HTTPS://crownfoundry.example.com"))
        assertEquals("http://192.168.1.20:8000/gateway", normalised("192.168.1.20:8000/gateway/"))
        assertEquals("http://localhost:8000", normalised("localhost:8000"))

        // Nothing usable in the box: keep the app pointed somewhere real.
        assertEquals(DEFAULT_BASE_URL, normalised(""))
        assertEquals(DEFAULT_BASE_URL, normalised("   "))
        assertEquals(DEFAULT_BASE_URL, normalised("\t\n"))
        assertEquals(DEFAULT_BASE_URL, normalised("http://"))
        assertEquals(DEFAULT_BASE_URL, normalised("///"))
    }

    @Test
    fun `the setter normalises`() {
        CrownFoundryClient.baseUrl = "192.168.1.20:8000/"
        assertEquals("http://192.168.1.20:8000", CrownFoundryClient.baseUrl)

        CrownFoundryClient.baseUrl = "   "
        assertEquals(DEFAULT_BASE_URL, CrownFoundryClient.baseUrl)
    }

    @Test
    fun `a trailing slash does not double up in the path`() = runTest {
        serving(Fixtures.HEALTH)
        CrownFoundryClient.baseUrl = "http://192.168.1.20:9000/"

        CrownFoundryClient.health().succeeded()

        assertEquals("/api/health/", lastRequest.path)
        assertEquals("http://192.168.1.20:9000/api/health/", lastRequest.url.toString())
    }

    @Test
    fun `an https base keeps its scheme`() = runTest {
        serving(Fixtures.HEALTH)
        CrownFoundryClient.baseUrl = "https://crownfoundry.example.com"

        CrownFoundryClient.health().succeeded()

        assertEquals("https", lastRequest.url.protocol.name)
        assertEquals("crownfoundry.example.com", lastRequest.url.host)
        assertEquals("/api/health/", lastRequest.path)
    }

    @Test
    fun `changing the base url mid-session redirects the next call without a new engine`() = runTest {
        serving(Fixtures.HEALTH)
        val client = CrownFoundryClient.httpClient

        CrownFoundryClient.health().succeeded()
        assertEquals("10.0.2.2", lastRequest.url.host)

        CrownFoundryClient.baseUrl = "laptop.local:8001"
        CrownFoundryClient.health().succeeded()

        assertEquals("laptop.local", lastRequest.url.host)
        assertEquals(8001, lastRequest.url.port)
        assertEquals(2, requests.size)
        assertSame("the engine must not be rebuilt", client, CrownFoundryClient.httpClient)
    }

    @Test
    fun `test connection probes a candidate without adopting it`() = runTest {
        serving(Fixtures.HEALTH)

        val health = CrownFoundryClient.testConnection("laptop.local:9999/").succeeded()

        assertEquals("1.0.0", health.version)
        assertEquals("laptop.local", lastRequest.url.host)
        assertEquals(9999, lastRequest.url.port)
        assertEquals("/api/health/", lastRequest.path)
        assertEquals(DEFAULT_BASE_URL, CrownFoundryClient.baseUrl)
    }
}
