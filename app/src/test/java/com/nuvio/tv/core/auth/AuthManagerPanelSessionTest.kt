package com.nuvio.tv.core.auth

import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.local.PanelSession
import com.nuvio.tv.data.local.PanelSessionDataStore
import com.nuvio.tv.data.remote.api.PanelCloudApi
import com.nuvio.tv.data.remote.panel.PanelAddonListResponse
import com.nuvio.tv.data.remote.panel.PanelPortalListResponse
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
