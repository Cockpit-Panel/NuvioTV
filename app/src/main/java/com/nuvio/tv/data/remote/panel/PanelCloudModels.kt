package com.nuvio.tv.data.remote.panel

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PanelLoginRequest(
    @Json(name = "server_url") val serverUrl: String? = null,
    val username: String,
    val password: String,
    @Json(name = "device_name") val deviceName: String? = null
)

@JsonClass(generateAdapter = true)
data class PanelLoginResponse(
    val user: PanelUserDto,
    val tokens: PanelTokenDto
)

@JsonClass(generateAdapter = true)
data class PanelUserDto(
    val id: String,
    val email: String,
    @Json(name = "display_name") val displayName: String? = null,
    @Json(name = "server_url") val serverUrl: String? = null
)

@JsonClass(generateAdapter = true)
data class PanelTokenDto(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "refresh_token") val refreshToken: String? = null,
    @Json(name = "expires_in") val expiresIn: Long? = null,
    @Json(name = "token_type") val tokenType: String? = null
)

@JsonClass(generateAdapter = true)
data class PanelAddonListResponse(
    val addons: List<PanelAddonDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class PanelPluginListResponse(
    val plugins: List<PanelPluginDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class PanelPortalListResponse(
    val portals: List<PanelPortalDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class PanelPortalDto(
    val id: Int,
    val name: String,
    val url: String
)

@JsonClass(generateAdapter = true)
data class PanelAddonDto(
    val url: String,
    val name: String? = null,
    val enabled: Boolean = true,
    @Json(name = "sort_order") val sortOrder: Int = 0,
    @Json(name = "profile_id") val profileId: Int = 1
)

@JsonClass(generateAdapter = true)
data class PanelPluginDto(
    val url: String,
    val name: String? = null,
    val enabled: Boolean = true,
    @Json(name = "sort_order") val sortOrder: Int = 0,
    @Json(name = "profile_id") val profileId: Int = 1,
    @Json(name = "repo_type") val repoType: String? = null
)
