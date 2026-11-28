package com.nuvio.tv.core.auth

import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.local.PanelSession
import com.nuvio.tv.data.local.PanelSessionDataStore
import com.nuvio.tv.data.remote.api.PanelCloudApi
import com.nuvio.tv.data.remote.panel.PanelAddonListResponse
import com.nuvio.tv.data.remote.panel.PanelLoginRequest
import com.nuvio.tv.data.remote.panel.PanelLoginResponse
import com.nuvio.tv.data.remote.panel.PanelPortalDto
import com.nuvio.tv.data.remote.panel.PanelPortalListResponse
import com.nuvio.tv.data.remote.panel.PanelTokenDto
import com.nuvio.tv.data.remote.panel.PanelUserDto
import com.nuvio.tv.data.repository.AuthDiagnosticReportRepository
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.ServerConfiguration
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class AuthManagerPanelSessionTest {

    private fun createAuthManager(
        panelCloudApi: PanelCloudApi,
        panelSessionDataStore: PanelSessionDataStore,
        authSessionNoticeDataStore: AuthSessionNoticeDataStore,
        accountLocalDataResetService: AccountLocalDataResetService
    ): AuthManager {
        return AuthManager(
            auth = mockk<Auth>(relaxed = true),
            postgrest = mockk<Postgrest>(relaxed = true),
            httpClient = OkHttpClient(),
            customServerHttpClient = OkHttpClient(),
            xtreamHttpClient = OkHttpClient(),
            authSessionNoticeDataStore = authSessionNoticeDataStore,
            accountLocalDataResetService = accountLocalDataResetService,
            authDiagnosticReportRepository = mockk<AuthDiagnosticReportRepository>(relaxed = true),
            serverConfiguration = mockk<ServerConfiguration>(relaxed = true),
            panelCloudApi = panelCloudApi,
            panelSessionDataStore = panelSessionDataStore
        )
    }

    private fun mockSessionStore(initial: PanelSession?): Pair<PanelSessionDataStore, () -> PanelSession?> {
        var stored: PanelSession? = initial
        val store = mockk<PanelSessionDataStore>(relaxed = true)
        every { store.session } returns emptyFlow()
        coEvery { store.getSession() } answers { stored }
        coEvery { store.save(any()) } answers { stored = firstArg() }
        coEvery { store.clear() } answers { stored = null }
        return store to { stored }
    }

    @Test
    fun `validatePanelSession signs out and clears local data on 401 or 403`() = runTest {
        val session = PanelSession(accessToken = "tok-1", userId = "u1", email = "user@example.com")
        val (store, stored) = mockSessionStore(session)
        val noticeStore = mockk<AuthSessionNoticeDataStore>(relaxed = true)
        val resetService = mockk<AccountLocalDataResetService>(relaxed = true)
        val api = mockk<PanelCloudApi>()
        coEvery { api.getAddons("Bearer tok-1", 1) } returns Response.error(
            401,
            "Unauthorized".toResponseBody("text/plain".toMediaType())
        )

        val manager = createAuthManager(api, store, noticeStore, resetService)

        val valid = manager.validatePanelSession(force = true)

        assertFalse(valid)
        assertEquals(AuthState.SignedOut, manager.authState.value)
        assertEquals(null, stored())
        coVerify(exactly = 1) { store.clear() }
        coVerify(exactly = 1) { resetService.clearAfterSignOut() }
        coVerify(exactly = 1) { noticeStore.markUnexpectedNuvioLogoutIfNeeded() }
    }

    @Test
    fun `validatePanelSession signs out on 400 with expired error body`() = runTest {
        val session = PanelSession(accessToken = "tok-exp", userId = "u1", email = "user@example.com")
        val (store, _) = mockSessionStore(session)
        val noticeStore = mockk<AuthSessionNoticeDataStore>(relaxed = true)
        val resetService = mockk<AccountLocalDataResetService>(relaxed = true)
        val api = mockk<PanelCloudApi>()
        coEvery { api.getAddons("Bearer tok-exp", 1) } returns Response.error(
            400,
            """{"error":"Line expired"}""".toResponseBody("application/json".toMediaType())
        )

        val manager = createAuthManager(api, store, noticeStore, resetService)

        val valid = manager.validatePanelSession(force = true)

        assertFalse(valid)
        assertEquals(AuthState.SignedOut, manager.authState.value)
        coVerify(exactly = 1) { store.clear() }
        coVerify(exactly = 1) { resetService.clearAfterSignOut() }
    }

    @Test
    fun `validatePanelSession keeps session on network IOException and 503`() = runTest {
        val session = PanelSession(accessToken = "tok-ok", userId = "u1", email = "user@example.com")
        val (store, stored) = mockSessionStore(session)
        val noticeStore = mockk<AuthSessionNoticeDataStore>(relaxed = true)
        val resetService = mockk<AccountLocalDataResetService>(relaxed = true)
        val api = mockk<PanelCloudApi>()
        coEvery { api.getAddons("Bearer tok-ok", 1) } throws IOException("timeout") andThen Response.error(
            503,
            "Service Unavailable".toResponseBody("text/plain".toMediaType())
        )

        val manager = createAuthManager(api, store, noticeStore, resetService)

        assertTrue(manager.validatePanelSession(force = true))
        assertTrue(manager.validatePanelSession(force = true))
        assertEquals(session, stored())
        coVerify(exactly = 0) { store.clear() }
        coVerify(exactly = 0) { resetService.clearAfterSignOut() }
    }

    @Test
    fun `invalidatePanelSessionIfUnauthorized signs out only when token matches active session`() = runTest {
        val session = PanelSession(accessToken = "tok-active", userId = "u1", email = "user@example.com")
        val (store, stored) = mockSessionStore(session)
        val noticeStore = mockk<AuthSessionNoticeDataStore>(relaxed = true)
        val resetService = mockk<AccountLocalDataResetService>(relaxed = true)
        val api = mockk<PanelCloudApi>()
        coEvery { api.getAddons("Bearer tok-active", 1) } returns Response.success(PanelAddonListResponse())
        coEvery { api.getPortals() } returns Response.success(PanelPortalListResponse())

        val manager = createAuthManager(api, store, noticeStore, resetService)
        assertTrue(manager.validatePanelSession(force = true))

        val ignoredStale = manager.invalidatePanelSessionIfUnauthorized(
            expectedAccessToken = "tok-old",
            statusCode = 401,
            errorBody = "Unauthorized"
        )
        assertFalse(ignoredStale)
        assertEquals(session, stored())
        coVerify(exactly = 0) { store.clear() }

        val invalidated = manager.invalidatePanelSessionIfUnauthorized(
            expectedAccessToken = "tok-active",
            statusCode = 403,
            errorBody = "Forbidden"
        )
        assertTrue(invalidated)
        assertEquals(AuthState.SignedOut, manager.authState.value)
        assertEquals(null, stored())
        coVerify(exactly = 1) { store.clear() }
        coVerify(exactly = 1) { resetService.clearAfterSignOut() }
    }

    @Test
    fun `validatePanelSession signs out Xtream user immediately when player_api reports expired or disabled even if panel getAddons returns 200`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"user_info":{"auth":1,"status":"Expired","exp_date":"1700000000"}}""")
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"user_info":{"auth":0,"status":"Active"}}""")
            )

            val baseUrl = server.url("/").toString().trimEnd('/')
            val session = PanelSession(
                accessToken = "tok-xtream",
                userId = "xtream:hash1",
                email = "xtream-user",
                serverUrl = baseUrl,
                serviceId = 2,
                username = "xtream-user",
                password = "secret"
            )
            val portals = listOf(
                PanelPortalDto(0, "Cockpit Panel", ""),
                PanelPortalDto(2, "Main IPTV", baseUrl)
            )
            val api = mockk<PanelCloudApi>()
            coEvery { api.getPortals() } returns Response.success(PanelPortalListResponse(portals))
            coEvery { api.getAddons("Bearer tok-xtream", 1) } returns Response.success(PanelAddonListResponse())

            // Case 1: status Expired
            val (store1, stored1) = mockSessionStore(session)
            val notice1 = mockk<AuthSessionNoticeDataStore>(relaxed = true)
            val reset1 = mockk<AccountLocalDataResetService>(relaxed = true)
            val manager1 = createAuthManager(api, store1, notice1, reset1).apply {
                panelValidationClockMs = { 1_750_000_000_000L }
            }
            assertFalse(manager1.validatePanelSession(force = true))
            assertEquals(AuthState.SignedOut, manager1.authState.value)
            assertNull(stored1())
            coVerify(exactly = 1) { store1.clear() }
            coVerify(exactly = 1) { reset1.clearAfterSignOut() }

            // Case 2: auth=0 (deleted or password changed)
            val (store2, stored2) = mockSessionStore(session)
            val notice2 = mockk<AuthSessionNoticeDataStore>(relaxed = true)
            val reset2 = mockk<AccountLocalDataResetService>(relaxed = true)
            val manager2 = createAuthManager(api, store2, notice2, reset2).apply {
                panelValidationClockMs = { 1_750_000_000_000L }
            }
            assertFalse(manager2.validatePanelSession(force = true))
            assertEquals(AuthState.SignedOut, manager2.authState.value)
            assertNull(stored2())
            coVerify(exactly = 1) { store2.clear() }
            coVerify(exactly = 1) { reset2.clearAfterSignOut() }

            val req1 = server.takeRequest()
            assertTrue(req1.path!!.startsWith("/player_api.php?username=xtream-user&password=secret"))
        }
    }

    @Test
    fun `validatePanelSession signs out Xtream user immediately when service is removed from panel portals`() = runTest {
        val session = PanelSession(
            accessToken = "tok-xtream-svc",
            userId = "xtream:hash2",
            email = "xtream-user",
            serverUrl = "http://iptv.example.com",
            serviceId = 2,
            username = "xtream-user",
            password = "secret"
        )
        val (store, stored) = mockSessionStore(session)
        val noticeStore = mockk<AuthSessionNoticeDataStore>(relaxed = true)
        val resetService = mockk<AccountLocalDataResetService>(relaxed = true)
        val api = mockk<PanelCloudApi>()
        coEvery { api.getPortals() } returns Response.success(
            PanelPortalListResponse(listOf(PanelPortalDto(0, "Cockpit Panel", "")))
        )
        coEvery { api.getAddons("Bearer tok-xtream-svc", 1) } returns Response.success(PanelAddonListResponse())

        val manager = createAuthManager(api, store, noticeStore, resetService)

        assertFalse(manager.validatePanelSession(force = true))
        assertEquals(AuthState.SignedOut, manager.authState.value)
        assertNull(stored())
        coVerify(exactly = 1) { store.clear() }
        coVerify(exactly = 1) { resetService.clearAfterSignOut() }
        coVerify(exactly = 0) { api.getAddons(any(), any()) }
    }

    @Test
    fun `validatePanelSession keeps Xtream session on player_api 503 or non-json response`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("<html>Challenge</html>"))

            val baseUrl = server.url("/").toString().trimEnd('/')
            val session = PanelSession(
                accessToken = "tok-xtream-ok",
                userId = "xtream:hash3",
                email = "xtream-user",
                serverUrl = baseUrl,
                serviceId = 2,
                username = "xtream-user",
                password = "secret"
            )
            val (store, stored) = mockSessionStore(session)
            val noticeStore = mockk<AuthSessionNoticeDataStore>(relaxed = true)
            val resetService = mockk<AccountLocalDataResetService>(relaxed = true)
            val api = mockk<PanelCloudApi>()
            coEvery { api.getPortals() } returns Response.success(
                PanelPortalListResponse(
                    listOf(
                        PanelPortalDto(0, "Cockpit Panel", ""),
                        PanelPortalDto(2, "Main IPTV", baseUrl)
                    )
                )
            )
            coEvery { api.getAddons("Bearer tok-xtream-ok", 1) } returns Response.success(PanelAddonListResponse())

            val manager = createAuthManager(api, store, noticeStore, resetService)

            assertTrue(manager.validatePanelSession(force = true))
            assertTrue(manager.validatePanelSession(force = true))
            assertEquals(session, stored())
            coVerify(exactly = 0) { store.clear() }
            coVerify(exactly = 0) { resetService.clearAfterSignOut() }
        }
    }

    @Test
    fun `validatePanelSession silently refreshes JWT when Xtream line is renewed after old token expired`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"user_info":{"auth":1,"status":"Active","exp_date":"2000000000"}}""")
            )

            val baseUrl = server.url("/").toString().trimEnd('/')
            val session = PanelSession(
                accessToken = "tok-old-jwt",
                userId = "xtream:hash4",
                email = "xtream-user",
                serverUrl = baseUrl,
                serviceId = 2,
                username = "xtream-user",
                password = "secret"
            )
            val (store, stored) = mockSessionStore(session)
            val noticeStore = mockk<AuthSessionNoticeDataStore>(relaxed = true)
            val resetService = mockk<AccountLocalDataResetService>(relaxed = true)
            val api = mockk<PanelCloudApi>()
            coEvery { api.getPortals() } returns Response.success(
                PanelPortalListResponse(
                    listOf(
                        PanelPortalDto(0, "Cockpit Panel", ""),
                        PanelPortalDto(2, "Main IPTV", baseUrl)
                    )
                )
            )
            coEvery { api.getAddons("Bearer tok-old-jwt", 1) } returns Response.error(
                401,
                """{"error":"Session expired"}""".toResponseBody("application/json".toMediaType())
            )
            coEvery {
                api.login(PanelLoginRequest(serverUrl = baseUrl, username = "xtream-user", password = "secret"))
            } returns Response.success(
                PanelLoginResponse(
                    user = PanelUserDto(
                        id = "xtream:hash4",
                        email = "xtream-user",
                        displayName = "xtream-user",
                        serverUrl = baseUrl,
                        serviceId = 2
                    ),
                    tokens = PanelTokenDto(accessToken = "tok-renewed", refreshToken = "ref-renewed")
                )
            )

            val manager = createAuthManager(api, store, noticeStore, resetService).apply {
                panelValidationClockMs = { 1_750_000_000_000L }
            }

            assertTrue(manager.validatePanelSession(force = true))
            assertEquals("tok-renewed", stored()?.accessToken)
            assertEquals("secret", stored()?.password)
            coVerify(exactly = 0) { store.clear() }
            coVerify(exactly = 0) { resetService.clearAfterSignOut() }
        }
    }

    @Test
    fun `signInWithPanel persists credentials only for Xtream sessions`() = runTest {
        val (store, stored) = mockSessionStore(null)
        val noticeStore = mockk<AuthSessionNoticeDataStore>(relaxed = true)
        val resetService = mockk<AccountLocalDataResetService>(relaxed = true)
        val api = mockk<PanelCloudApi>()

        val xtreamPortal = PanelPortalDto(id = 2, name = "IPTV", url = "http://iptv.example.com/")
        coEvery {
            api.login(PanelLoginRequest(username = "xuser", password = "xpass"))
        } returns Response.error(401, "Invalid login credentials".toResponseBody("text/plain".toMediaType()))
        coEvery {
            api.login(PanelLoginRequest(serverUrl = "http://iptv.example.com/", username = "xuser", password = "xpass"))
        } returns Response.success(
            PanelLoginResponse(
                user = PanelUserDto(
                    id = "xtream:abc",
                    email = "xuser",
                    serverUrl = "http://iptv.example.com/",
                    serviceId = 2
                ),
                tokens = PanelTokenDto(accessToken = "tok-x")
            )
        )

        val manager = createAuthManager(api, store, noticeStore, resetService)
        val xtreamResult = manager.signInWithPanel(xtreamPortal, "xuser", "xpass")
        assertTrue(xtreamResult.isSuccess)
        assertEquals("xuser", stored()?.username)
        assertEquals("xpass", stored()?.password)
        assertEquals("http://iptv.example.com", stored()?.serverUrl)

        val panelPortal = PanelPortalDto(id = 0, name = "Cockpit Panel", url = "")
        coEvery {
            api.login(PanelLoginRequest(username = "puser", password = "ppass"))
        } returns Response.success(
            PanelLoginResponse(
                user = PanelUserDto(id = "panel:7", email = "puser@example.com", serviceId = 0),
                tokens = PanelTokenDto(accessToken = "tok-p")
            )
        )

        val panelResult = manager.signInWithPanel(panelPortal, "puser", "ppass")
        assertTrue(panelResult.isSuccess)
        assertNull(stored()?.username)
        assertNull(stored()?.password)
    }
}
