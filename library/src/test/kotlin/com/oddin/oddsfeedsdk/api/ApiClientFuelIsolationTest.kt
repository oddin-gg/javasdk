package com.oddin.oddsfeedsdk.api

import com.github.kittinunf.fuel.core.FuelManager
import com.github.kittinunf.fuel.httpGet
import com.oddin.oddsfeedsdk.DispatchManager
import com.oddin.oddsfeedsdk.config.Environment
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import com.oddin.oddsfeedsdk.schema.utils.URN
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

// The SDK sends its token and base path with its own Fuel manager. Fuel's process-wide
// FuelManager.instance is the application's: anything else in the JVM that makes a Fuel
// request must not carry the SDK's access token or resolve a relative path against the API host.
class ApiClientFuelIsolationTest {

    private lateinit var server: HttpServer

    // the access token header each request to the test server arrived with, by path
    private val tokens = ConcurrentHashMap<String, String>()

    private val whoAmI =
        """<bookmaker_details response_code="OK" expire_at="2030-01-01T00:00:00Z" bookmaker_id="1" virtual_host="/test"/>"""

    private val config = mockk<OddsFeedConfiguration> {
        every { accessToken } returns "secret-token"
        every { selectedEnvironment } returns Environment("mq.test", "api.test", 5672)
    }

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/") {
            tokens[it.requestURI.path] = it.requestHeaders.getFirst("x-access-token") ?: "none"
            val bytes = (if (it.requestURI.path.endsWith("/users/whoami")) whoAmI else "<ok/>").toByteArray()
            it.responseHeaders.add("Content-Type", "application/xml")
            it.sendResponseHeaders(200, bytes.size.toLong())
            it.responseBody.use { out -> out.write(bytes) }
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    @Test(timeout = 60_000)
    fun theProcessWideFuelManagerIsLeftAloneAndSendsNoToken() {
        val headersBefore = FuelManager.instance.baseHeaders
        val basePathBefore = FuelManager.instance.basePath
        val client = ApiClientImpl(config, mockk<DispatchManager>(relaxed = true))
        try {
            assertEquals(headersBefore, FuelManager.instance.baseHeaders)
            assertEquals(basePathBefore, FuelManager.instance.basePath)
            assertNull(FuelManager.instance.baseHeaders?.get("x-access-token"))

            // a request the application makes with Fuel's defaults
            url("/application").httpGet().response()

            assertEquals("none", tokens["/application"])
        } finally {
            client.close()
        }
    }

    @Test(timeout = 60_000)
    fun theSdkRequestsStillCarryTheTokenAndTheBasePath() {
        val client = ApiClientImpl(config, mockk<DispatchManager>(relaxed = true))
        try {
            assertEquals("https://api.test/v1", client.fuel.basePath)
            client.fuel.basePath = "http://127.0.0.1:${server.address.port}/v1"
            val uris = mutableListOf<URI>()
            client.subscribeForClass(ApiResponse::class.java).subscribe { uris.add(it.uri) }

            runBlocking {
                client.fetchWhoAmI()
                client.putReplayEvent(URN.parse("od:match:1"), null)
                client.deleteReplayEvent(URN.parse("od:match:1"), null)
                client.postReplayStart(null)
            }

            assertEquals("secret-token", tokens["/v1/users/whoami"])
            assertEquals("secret-token", tokens["/v1/replay/events/od:match:1"])
            assertEquals("secret-token", tokens["/v1/replay/play"])
            // the response is published with the URI the request went to
            assertEquals(listOf(URI.create("http://127.0.0.1:${server.address.port}/v1/users/whoami")), uris)
        } finally {
            client.close()
        }
    }
}
