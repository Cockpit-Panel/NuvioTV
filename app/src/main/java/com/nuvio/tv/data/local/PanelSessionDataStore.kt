package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.panelSessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "panel_session_store"
)

data class PanelSession(
    val accessToken: String,
    val refreshToken: String? = null,
    val userId: String,
    val email: String,
    val displayName: String? = null,
    val serverUrl: String? = null
)

@Singleton
class PanelSessionDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val accessTokenKey = stringPreferencesKey("access_token")
    private val refreshTokenKey = stringPreferencesKey("refresh_token")
    private val userIdKey = stringPreferencesKey("user_id")
    private val emailKey = stringPreferencesKey("email")
    private val displayNameKey = stringPreferencesKey("display_name")
    private val serverUrlKey = stringPreferencesKey("server_url")

    val session: Flow<PanelSession?> = context.panelSessionDataStore.data.map { preferences ->
        val accessToken = preferences[accessTokenKey]
        val userId = preferences[userIdKey]
        val email = preferences[emailKey]
        if (accessToken.isNullOrBlank() || userId.isNullOrBlank() || email.isNullOrBlank()) {
            null
        } else {
            PanelSession(
                accessToken = accessToken,
                refreshToken = preferences[refreshTokenKey],
                userId = userId,
                email = email,
                displayName = preferences[displayNameKey],
                serverUrl = preferences[serverUrlKey]
            )
        }
    }

    suspend fun getSession(): PanelSession? = session.first()

    suspend fun save(session: PanelSession) {
        context.panelSessionDataStore.edit { preferences ->
            preferences[accessTokenKey] = session.accessToken
            session.refreshToken?.let { preferences[refreshTokenKey] = it }
            preferences[userIdKey] = session.userId
            preferences[emailKey] = session.email
            session.displayName?.let { preferences[displayNameKey] = it }
            session.serverUrl?.let { preferences[serverUrlKey] = it }
        }
    }

    suspend fun clear() {
        context.panelSessionDataStore.edit { preferences ->
            preferences.remove(accessTokenKey)
            preferences.remove(refreshTokenKey)
            preferences.remove(userIdKey)
            preferences.remove(emailKey)
            preferences.remove(displayNameKey)
            preferences.remove(serverUrlKey)
        }
    }
}