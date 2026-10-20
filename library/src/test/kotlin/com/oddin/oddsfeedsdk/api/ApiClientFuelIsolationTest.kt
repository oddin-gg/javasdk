package com.oddin.oddsfeedsdk.api

import com.github.kittinunf.fuel.core.Client
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.security.KeyStore
import java.util.concurrent.Executor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext

// The SDK sends its token and base path with its own Fuel manager. Fuel's process-wide
// FuelManager.instance is the application's: anything else in the JVM that makes a Fuel
// request must not carry the SDK's access token or resolve a relative path against the API host.
// What the application set on it for the connection (proxy, TLS, timeouts) still applies to the
// SDK's requests, as it did when they went through it.
class ApiClientFuelIsolationTest {

    private lateinit var server: HttpServer

    // the headers each request to the test server arrived with, by path
    private val received = ConcurrentHashMap<String, Map<String, String>>()

    // Fuel's defaults are filled in lazily on first use, so each test gets an application
    // manager of its own, as an application that configures Fuel before anything else would have
    private val applicationFuel = FuelManager()
    private lateinit var originalInstance: FuelManager

    private val whoAmI =
        """<bookmaker_details response_code="OK" expire_at="2030-01-01T00:00:00Z" bookmaker_id="1" virtual_host="/test"/>"""

    private val config = mockk<OddsFeedConfiguration> {
        every { accessToken } returns "secret-token"
        every { selectedEnvironment } returns Environment("mq.test", "api.test", 5672)
    }

    @Before
    fun setUp() {
        originalInstance = FuelManager.instance
        FuelManager.instance = applicationFuel
        server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/") {
            received[it.requestURI.path] = it.requestHeaders.entries.associate { (name, values) -> name.lowercase() to values.joinToString() }
            val bytes = (if (it.requestURI.path.endsWith("/users/whoami")) whoAmI else "<ok/>").toByteArray()
            it.responseHeaders.add("Content-Type", "application/xml")
            it.sendResponseHeaders(200, bytes.size.toLong())
            it.responseBody.use { out -> out.write(bytes) }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        FuelManager.instance = originalInstance
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun token(path: String) = received[path]?.get("x-access-token") ?: "none"

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

            assertEquals("none", token("/application"))
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

            assertEquals("secret-token", token("/v1/users/whoami"))
            assertEquals("secret-token", token("/v1/replay/events/od:match:1"))
            assertEquals("secret-token", token("/v1/replay/play"))
            // the response is published with the URI the request went to
            assertEquals(listOf(URI.create("http://127.0.0.1:${server.address.port}/v1/users/whoami")), uris)

            // after all of them (get, put, delete, post) the application's manager is still its own
            assertNull(FuelManager.instance.baseHeaders)
            assertNull(FuelManager.instance.basePath)
            url("/application").httpGet().response()
            assertEquals("none", token("/application"))
            assertTrue(received["/application"]?.containsKey("x-access-token") != true)
        } finally {
            client.close()
        }
    }

    @Test(timeout = 60_000)
    fun theTransportSettingsOfTheApplicationsManagerApplyToTheSdkManager() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getByName("127.0.0.1"), 3128))
            val hook = mockk<Client.Hook>(relaxed = true)
            val keystore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            val socketFactory = SSLContext.getInstance("TLS").apply { init(null, null, null) }.socketFactory
            val hostnameVerifier = HostnameVerifier { _, _ -> true }
            val callbackExecutor = Executor { it.run() }
            val appClient = mockk<Client>()
            applicationFuel.also {
                it.proxy = proxy
                it.hook = hook
                it.keystore = keystore
                it.socketFactory = socketFactory
                it.hostnameVerifier = hostnameVerifier
                it.client = appClient
                it.executorService = executor
                it.callbackExecutor = callbackExecutor
                it.timeoutInMillisecond = 1234
                it.timeoutReadInMillisecond = 5678
                it.forceMethods = true
            }

            val sdk = ApiClientImpl(config, mockk<DispatchManager>(relaxed = true)).also { it.close() }.fuel

            assertSame(proxy, sdk.proxy)
            assertSame(hook, sdk.hook)
            assertSame(keystore, sdk.keystore)
            assertSame(socketFactory, sdk.socketFactory)
            assertSame(hostnameVerifier, sdk.hostnameVerifier)
            assertSame(appClient, sdk.client)
            assertSame(executor, sdk.executorService)
            assertSame(callbackExecutor, sdk.callbackExecutor)
            assertEquals(1234, sdk.timeoutInMillisecond)
            assertEquals(5678, sdk.timeoutReadInMillisecond)
            assertTrue(sdk.forceMethods)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test(timeout = 60_000)
    fun theSdkRequestsGoThroughTheProxyOfTheApplicationsManager() {
        // the test server is the proxy: it sees the request for the API host, which does not resolve
        applicationFuel.proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getByName("127.0.0.1"), server.address.port))
        val client = ApiClientImpl(config, mockk<DispatchManager>(relaxed = true))
        try {
            client.fuel.basePath = "http://sdk-api.test/v1"

            runBlocking { client.fetchWhoAmI() }

            assertEquals("secret-token", token("/v1/users/whoami"))
            assertEquals("sdk-api.test", received["/v1/users/whoami"]?.get("host"))
        } finally {
            client.close()
        }
    }

    @Test(timeout = 60_000)
    fun whatTheApplicationSentWithItsOwnRequestsIsNotCopied() {
        applicationFuel.also {
            it.basePath = "http://application.test/api"
            it.baseHeaders = mapOf("x-application" to "yes", "x-access-token" to "application-token")
            it.baseParams = listOf("application" to "yes")
            it.addRequestInterceptor { next -> { request -> next(request.header("x-interceptor", "yes")) } }
        }
        val client = ApiClientImpl(config, mockk<DispatchManager>(relaxed = true))
        try {
            assertEquals("https://api.test/v1", client.fuel.basePath)
            assertEquals(setOf("x-access-token", "accept"), client.fuel.baseHeaders?.keys)
            assertEquals("secret-token", client.fuel.baseHeaders?.get("x-access-token"))
            assertTrue(client.fuel.baseParams.isEmpty())
            client.fuel.basePath = "http://127.0.0.1:${server.address.port}/v1"

            runBlocking { client.fetchWhoAmI() }

            val headers = received["/v1/users/whoami"].orEmpty()
            assertEquals("secret-token", headers["x-access-token"])
            assertNull(headers["x-application"])
            assertNull(headers["x-interceptor"])
        } finally {
            client.close()
        }
    }
}
