package app.notomorrow.service

import app.notomorrow.model.AIProvider
import app.notomorrow.net.BackendClient
import app.notomorrow.net.InMemoryMockPairingStore
import app.notomorrow.net.InMemorySessionStore
import app.notomorrow.net.MockBackendClient
import app.notomorrow.net.MockStrings
import app.notomorrow.net.RemoteBackendClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** `AppConfig.makeBackendClient()`: one client per process, rebuilt only on a demo flip or a URL change. */
class AppConfigSelectionTest {

    private class FakeStore(
        var provider: AIProvider = AIProvider.Standard,
        var url: String = AppConfig.DEFAULT_BACKEND_URL.toString(),
        var mock: Boolean = false,
    ) : AppConfigStore {
        override suspend fun aiProvider(): AIProvider = provider
        override suspend fun backendBaseUrl(): String = url
        override suspend fun useMockBackend(): Boolean = mock
        override suspend fun setAiProvider(provider: AIProvider) { this.provider = provider }
        override suspend fun setBackendBaseUrl(url: String) { this.url = url }
        override suspend fun setUseMockBackend(value: Boolean) { this.mock = value }
    }

    private fun config(store: FakeStore) = AppConfig(
        prefs = store,
        sessionStore = { InMemorySessionStore() },
        mockStrings = { MockStrings { "" } },
        mockPairing = { InMemoryMockPairingStore() },
        // Unconfined so the DataStore write-back the setters fire has already run when we assert.
        scope = CoroutineScope(Dispatchers.Unconfined),
        engine = MockEngine { respond("{}", HttpStatusCode.OK, headersOf()) },
    )

    @Test
    fun `defaults are standard provider, the Fly io URL and the live backend`() = runTest {
        val config = config(FakeStore())
        config.load()
        assertEquals(AIProvider.Standard, config.aiProvider.value)
        assertEquals(BackendClient.DEFAULT_BASE_URL, config.backendBaseUrl.value)
        assertEquals(false, config.useMockBackend.value)
        assertTrue(config.makeBackendClient() is RemoteBackendClient)
    }

    @Test
    fun `a stored legacy URL is discarded on read`() = runTest {
        val config = config(FakeStore(url = AppConfig.LEGACY_BACKEND_URL))
        config.load()
        assertEquals(BackendClient.DEFAULT_BASE_URL, config.backendBaseUrl.value)
    }

    @Test
    fun `a blank or unparsable URL falls back to the default`() {
        assertEquals(AppConfig.DEFAULT_BACKEND_URL, AppConfig.parseBaseUrl(null))
        assertEquals(AppConfig.DEFAULT_BACKEND_URL, AppConfig.parseBaseUrl("   "))
        assertEquals(AppConfig.DEFAULT_BACKEND_URL, AppConfig.parseBaseUrl(AppConfig.LEGACY_BACKEND_URL))
        assertEquals(Url("https://staging.example.com"), AppConfig.parseBaseUrl("https://staging.example.com"))
    }

    @Test
    fun `the demo switch selects the mock client and survives a re-read`() = runTest {
        val config = config(FakeStore(mock = true))
        config.load()
        val first = config.makeBackendClient()
        assertTrue(first is MockBackendClient)
        assertSame(first, config.makeBackendClient())
    }

    @Test
    fun `flipping the demo switch rebuilds the client, and flipping back rebuilds again`() = runTest {
        val store = FakeStore()
        val config = config(store)
        config.load()
        val remote = config.makeBackendClient()
        assertTrue(remote is RemoteBackendClient)

        config.setUseMockBackend(true)
        val mock = config.makeBackendClient()
        assertTrue(mock is MockBackendClient)
        assertNotSame(remote, mock)
        assertEquals(true, store.mock)

        config.setUseMockBackend(false)
        val again = config.makeBackendClient()
        assertTrue(again is RemoteBackendClient)
        assertNotSame(mock, again)
    }

    @Test
    fun `changing the base URL rebuilds the remote client, an unchanged URL does not`() = runTest {
        val config = config(FakeStore())
        config.load()
        val first = config.makeBackendClient()
        assertSame(first, config.makeBackendClient())

        config.setBackendBaseUrl("https://staging.example.com")
        val second = config.makeBackendClient()
        assertNotSame(first, second)
        assertEquals(Url("https://staging.example.com"), second.baseUrl)
    }

    @Test
    fun `the URL is ignored while the demo backend is on`() = runTest {
        val config = config(FakeStore(mock = true))
        config.load()
        val first = config.makeBackendClient()
        config.setBackendBaseUrl("https://staging.example.com")
        assertSame(first, config.makeBackendClient())
    }

    @Test
    fun `the AI provider round-trips through the store`() = runTest {
        val store = FakeStore()
        val config = config(store)
        config.load()
        config.setAiProvider(AIProvider.ClaudeBYOK)
        assertEquals(AIProvider.ClaudeBYOK, config.aiProvider.value)
        assertEquals(AIProvider.ClaudeBYOK, store.provider)
    }
}
