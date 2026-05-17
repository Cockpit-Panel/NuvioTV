package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.panel.PanelAddonListResponse
import com.nuvio.tv.data.remote.panel.PanelLoginRequest
import com.nuvio.tv.data.remote.panel.PanelLoginResponse
import com.nuvio.tv.data.remote.panel.PanelPortalListResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface PanelCloudApi {
    @GET("portals.php")
    suspend fun getPortals(): Response<PanelPortalListResponse>

    @POST("api/auth/login")
    suspend fun login(@Body request: PanelLoginRequest): Response<PanelLoginResponse>

    @GET("api/addons")
    suspend fun getAddons(
        @Header("Authorization") authorization: String,
        @Query("profile_id") profileId: Int
    ): Response<PanelAddonListResponse>
}