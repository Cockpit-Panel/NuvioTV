package com.nuvio.tv.core.auth

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.local.PanelSession
import com.nuvio.tv.data.local.PanelSessionDataStore
import com.nuvio.tv.data.remote.api.PanelCloudApi
import com.nuvio.tv.data.remote.panel.PanelLoginRequest
import com.nuvio.tv.data.remote.panel.PanelPortalDto
import com.nuvio.tv.data.remote.supabase.TvLoginPollResult
import com.nuvio.tv.data.remote.supabase.TvLoginStartResult
import com.nuvio.tv.domain.model.AuthState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AuthManager"

@Singleton
class AuthManager @Inject constructor(
    private val panelCloudApi: PanelCloudApi,
    private val panelSessionDataStore: PanelSessionDataStore,
    private val authSessionNoticeDataStore: AuthSessionNoticeDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private var cachedEffectiveUserId: String? = null
    private var cachedEffectiveUserSourceUserId: String? = null
    @Volatile
    private var currentSession: PanelSession? = null

    val supportsFullCloudSync: Boolean = false

    init {
        observePanelSession()
    }

    private fun observePanelSession() {
        scope.launch {
            panelSessionDataStore.session.collect { session ->
                currentSession = session
                if (session == null) {
                    cachedEffectiveUserId = null
                    cachedEffectiveUserSourceUserId = null
                    _authState.value = AuthState.SignedOut
                } else {
                    cachedEffectiveUserId = session.userId
                    cachedEffectiveUserSourceUserId = session.userId
                    _authState.value = AuthState.FullAccount(userId = session.userId, email = session.email)
                }
            }
        }
    }

    fun isPanelCloudConfigured(): Boolean = BuildConfig.PANEL_CLOUD_API_URL.isNotBlank()

    val isAuthenticated: Boolean
        get() = _authState.value is AuthState.FullAccount

    val currentUserId: String?
        get() = when (val state = _authState.value) {
            is AuthState.FullAccount -> state.userId
            else -> null
        }

    /**
     * Returns the effective user ID for data operations.
     * For sync-linked devices, this returns the sync owner's user ID.
     * For direct users, returns their own user ID.
     */
    suspend fun getEffectiveUserId(fallbackToOwnIdOnFailure: Boolean = true): String? {
        return currentUserId
    }

    suspend fun signUpWithEmail(email: String, password: String): Result<Unit> {
        return Result.failure(UnsupportedOperationException("Panel sign up is not supported"))
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> {
        return Result.failure(UnsupportedOperationException("Use panel sign in"))
    }

    suspend fun getPanelPortals(): Result<List<PanelPortalDto>> {
        if (!isPanelCloudConfigured()) {
            return Result.failure(IllegalStateException("Panel cloud API URL is not configured"))
        }
        return try {
            val response = panelCloudApi.getPortals()
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    response.errorBody()?.string().orEmpty().ifBlank { "Failed to load service list" }
                )
            }
            val body = response.body() ?: throw IllegalStateException("Empty portals response")
            Result.success(body.portals)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load panel portals", e)
            Result.failure(e)
        }
    }

    suspend fun signInWithPanel(
        serverUrl: String,
        username: String,
        password: String,
        deviceName: String? = null
    ): Result<Unit> {
        if (!isPanelCloudConfigured()) {
            return Result.failure(IllegalStateException("Panel cloud API URL is not configured"))
        }
        return try {
            val normalizedUsername = username.trim()
            val normalizedServerUrl = serverUrl.trim().takeIf { it.isNotBlank() }
            val loginAttempts = listOf(
                PanelLoginRequest(
                    username = normalizedUsername,
                    password = password
                ),
                PanelLoginRequest(
                    serverUrl = normalizedServerUrl,
                    username = normalizedUsername,
                    password = password,
                    deviceName = deviceName
                )
            )
            var lastError: IllegalStateException? = null
            var successfulBody: com.nuvio.tv.data.remote.panel.PanelLoginResponse? = null

            for (request in loginAttempts.distinct()) {
                val response = panelCloudApi.login(request)
                if (response.isSuccessful) {
                    successfulBody = response.body()
                    break
                }

                val rawError = response.errorBody()?.string().orEmpty()
                lastError = IllegalStateException(
                    extractPanelApiError(rawError).ifBlank {
                        when (response.code()) {
                            401 -> "Invalid login credentials"
                            403 -> "Access denied"
                            404 -> "Panel cloud service unavailable"
                            else -> "Panel login failed (${response.code()})"
                        }
                    }
                )
            }

            val body = successfulBody ?: throw (lastError ?: IllegalStateException("Empty response from panel login"))
            panelSessionDataStore.save(
                PanelSession(
                    accessToken = body.tokens.accessToken,
                    refreshToken = body.tokens.refreshToken,
                    userId = body.user.id,
                    email = body.user.email.ifBlank { normalizedUsername },
                    displayName = body.user.displayName,
                    serverUrl = body.user.serverUrl ?: normalizedServerUrl
                )
            )
            authSessionNoticeDataStore.markNuvioAuthenticated()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Panel sign in failed", e)
            Result.failure(e)
        }
    }

    /**
     * QR login RPCs currently require an authenticated Supabase session.
     * This creates/reuses an anonymous session only for the QR flow while
     * keeping app-level auth state exposed as SignedOut until a full account exists.
     */
    suspend fun ensureQrSessionAuthenticated(): Result<Unit> {
        return Result.failure(UnsupportedOperationException("QR sign in is not available in panel mode"))
    }

    suspend fun signOut(explicit: Boolean = true) {
        if (explicit) {
            authSessionNoticeDataStore.markNuvioExplicitLogout()
        } else {
            authSessionNoticeDataStore.markUnexpectedNuvioLogoutIfNeeded()
        }
        panelSessionDataStore.clear()
        currentSession = null
        cachedEffectiveUserId = null
        cachedEffectiveUserSourceUserId = null
        _authState.value = AuthState.SignedOut
    }

    fun clearEffectiveUserIdCache() {
        cachedEffectiveUserId = null
        cachedEffectiveUserSourceUserId = null
    }

    suspend fun refreshSessionIfJwtExpired(error: Throwable): Boolean {
        return false
    }

    suspend fun startTvLoginSession(deviceNonce: String, deviceName: String?, redirectBaseUrl: String): Result<TvLoginStartResult> {
        return Result.failure(UnsupportedOperationException("QR sign in is not available in panel mode"))
    }

    suspend fun pollTvLoginSession(code: String, deviceNonce: String): Result<TvLoginPollResult> {
        return Result.failure(UnsupportedOperationException("QR sign in is not available in panel mode"))
    }

    suspend fun exchangeTvLoginSession(code: String, deviceNonce: String): Result<Unit> {
        return Result.failure(UnsupportedOperationException("QR sign in is not available in panel mode"))
    }

    suspend fun getAccessToken(): String? = panelSessionDataStore.getSession()?.accessToken
}

private fun extractPanelApiError(rawMessage: String): String {
    val trimmed = rawMessage.trim()
    if (trimmed.isBlank()) return ""

    val errorMatch = Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)
    if (errorMatch != null) {
        return errorMatch.groupValues[1]
    }

    return trimmed.lineSequence().firstOrNull()?.trim().orEmpty()
}

private fun Throwable.isJwtExpiredError(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current.message?.contains("jwt expired", ignoreCase = true) == true) return true
        current = current.cause
    }
    return false
}
