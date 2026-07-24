package com.foreverjukebox.app.net

import java.net.InetAddress
import java.net.UnknownServiceException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CleartextPolicyTest {

    private fun addr(literal: String): InetAddress = InetAddress.getByName(literal)

    @Test
    fun privateAddressCoversLoopbackLinkLocalAndRfc1918() {
        assertTrue(CleartextPolicy.isPrivateAddress(addr("127.0.0.1")))
        assertTrue(CleartextPolicy.isPrivateAddress(addr("::1")))
        assertTrue(CleartextPolicy.isPrivateAddress(addr("169.254.10.20")))
        assertTrue(CleartextPolicy.isPrivateAddress(addr("fe80::1")))
        assertTrue(CleartextPolicy.isPrivateAddress(addr("10.0.0.1")))
        assertTrue(CleartextPolicy.isPrivateAddress(addr("172.16.0.1")))
        assertTrue(CleartextPolicy.isPrivateAddress(addr("172.31.255.255")))
        assertTrue(CleartextPolicy.isPrivateAddress(addr("192.168.1.23")))
    }

    @Test
    fun privateAddressCoversCgnatRangeUsedByTailscale() {
        assertTrue(CleartextPolicy.isPrivateAddress(addr("100.64.0.1")))
        assertTrue(CleartextPolicy.isPrivateAddress(addr("100.101.102.103")))
        assertTrue(CleartextPolicy.isPrivateAddress(addr("100.127.255.255")))
        assertFalse(CleartextPolicy.isPrivateAddress(addr("100.63.255.255")))
        assertFalse(CleartextPolicy.isPrivateAddress(addr("100.128.0.0")))
    }

    @Test
    fun privateAddressCoversIpv6UniqueLocalRange() {
        assertTrue(CleartextPolicy.isPrivateAddress(addr("fc00::1")))
        assertTrue(CleartextPolicy.isPrivateAddress(addr("fd12:3456:789a::1")))
        assertFalse(CleartextPolicy.isPrivateAddress(addr("fe00::1")))
    }

    @Test
    fun privateAddressRejectsPublicAddresses() {
        assertFalse(CleartextPolicy.isPrivateAddress(addr("8.8.8.8")))
        assertFalse(CleartextPolicy.isPrivateAddress(addr("172.32.0.1")))
        assertFalse(CleartextPolicy.isPrivateAddress(addr("193.168.1.23")))
        assertFalse(CleartextPolicy.isPrivateAddress(addr("2001:4860:4860::8888")))
    }

    @Test
    fun knownLocalHostMatchesPrivateLiteralsAndLocalNames() {
        assertTrue(CleartextPolicy.isKnownLocalHost("192.168.1.23"))
        assertTrue(CleartextPolicy.isKnownLocalHost("100.101.102.103"))
        assertTrue(CleartextPolicy.isKnownLocalHost("[fd12:3456:789a::1]"))
        assertTrue(CleartextPolicy.isKnownLocalHost("localhost"))
        assertTrue(CleartextPolicy.isKnownLocalHost("LOCALHOST"))
        assertTrue(CleartextPolicy.isKnownLocalHost("nas"))
        assertTrue(CleartextPolicy.isKnownLocalHost("jukebox.local"))
        assertTrue(CleartextPolicy.isKnownLocalHost("box.lan"))
        assertTrue(CleartextPolicy.isKnownLocalHost("srv.home.arpa"))
        assertTrue(CleartextPolicy.isKnownLocalHost("api.internal"))
    }

    @Test
    fun knownLocalHostRejectsPublicHostsAndAddresses() {
        assertFalse(CleartextPolicy.isKnownLocalHost("example.com"))
        assertFalse(CleartextPolicy.isKnownLocalHost("8.8.8.8"))
        assertFalse(CleartextPolicy.isKnownLocalHost("mylocal.com"))
        assertFalse(CleartextPolicy.isKnownLocalHost(""))
        // Out-of-range dotted quads must not be treated as parseable literals (or resolved).
        assertFalse(CleartextPolicy.isKnownLocalHost("999.168.1.1"))
    }

    @Test
    fun cleartextRequestDecisionAllowsHttpsAlwaysAndHttpOnlyToPrivateDirectRoutes() {
        assertTrue(CleartextPolicy.isCleartextRequestAllowed(isHttps = true, viaProxy = false, address = null))
        assertTrue(
            CleartextPolicy.isCleartextRequestAllowed(isHttps = false, viaProxy = false, address = addr("10.1.2.3"))
        )
        assertFalse(
            CleartextPolicy.isCleartextRequestAllowed(isHttps = false, viaProxy = false, address = addr("8.8.8.8"))
        )
        assertFalse(
            CleartextPolicy.isCleartextRequestAllowed(isHttps = false, viaProxy = true, address = addr("10.1.2.3"))
        )
        assertFalse(CleartextPolicy.isCleartextRequestAllowed(isHttps = false, viaProxy = false, address = null))
    }

    @Test
    fun guardInterceptorAllowsHttpToLoopbackServer() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("ok"))
        server.start()
        try {
            val client = OkHttpClient.Builder()
                .addNetworkInterceptor(CleartextGuardInterceptor)
                .build()
            client.newCall(Request.Builder().url(server.url("/ping")).build()).execute().use { response ->
                assertEquals("ok", response.body.string())
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun guardInterceptorFailsClosedForHttpWithoutAVerifiableRoute() {
        val chain = FakeChain(Request.Builder().url("http://example.com/api/trending").build())
        try {
            CleartextGuardInterceptor.intercept(chain)
            fail("expected UnknownServiceException")
        } catch (expected: UnknownServiceException) {
            assertTrue(expected.message.orEmpty().contains("example.com"))
        }
        assertFalse(chain.proceeded)
    }

    @Test
    fun guardInterceptorAllowsHttpsWithoutAVerifiableRoute() {
        val chain = FakeChain(Request.Builder().url("https://example.com/api/trending").build())
        CleartextGuardInterceptor.intercept(chain)
        assertTrue(chain.proceeded)
    }

    /** A chain with no connection info, mimicking a route the guard cannot verify. */
    private class FakeChain(private val request: Request) : Interceptor.Chain {
        var proceeded = false

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            proceeded = true
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody(null))
                .build()
        }

        override fun connection(): Connection? = null

        override fun call(): Call = OkHttpClient().newCall(request)

        override fun connectTimeoutMillis(): Int = 0

        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override fun readTimeoutMillis(): Int = 0

        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override fun writeTimeoutMillis(): Int = 0

        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }
}
