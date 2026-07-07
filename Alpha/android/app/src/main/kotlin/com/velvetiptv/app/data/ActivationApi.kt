package com.velvetiptv.app.data

import com.velvetiptv.app.BuildConfig
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

data class DeviceCheckRequest(val mac: String, val deviceKey: String)

data class DeviceCheckResponse(
    val activated: Boolean,
    val reason: String? = null,
    val plan: String? = null,
    val expiresAt: String? = null
)

data class PlaylistDto(
    val id: String,
    val name: String,
    val type: String,
    val url: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val epgUrl: String = "",
    val active: Boolean = false
)

data class PlaylistCreateRequest(
    val mac: String,
    val deviceKey: String,
    val name: String,
    val type: String,
    val url: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val epgUrl: String = ""
)

data class PlaylistDeleteRequest(val mac: String, val deviceKey: String)

data class PlaylistActivateResponse(val success: Boolean, val activeId: String)

data class PlaylistCreateResponse(val success: Boolean, val playlist: PlaylistDto)

data class ParentalSyncDto(val pinHash: String, val lockedCategories: List<String>)

data class ParentalCategoriesRequest(val mac: String, val deviceKey: String, val lockedCategories: List<String>)

data class ParentalSetPinRequest(val mac: String, val deviceKey: String, val currentPin: String?, val newPin: String)

interface ActivationApi {
    @POST("api/device/check")
    suspend fun checkDevice(@Body body: DeviceCheckRequest): DeviceCheckResponse

    @GET("api/playlists/sync")
    suspend fun syncPlaylists(@Query("mac") mac: String, @Query("deviceKey") deviceKey: String): List<PlaylistDto>

    @POST("api/playlists/create")
    suspend fun createPlaylist(@Body body: PlaylistCreateRequest): PlaylistCreateResponse

    @HTTP(method = "DELETE", path = "api/playlists/{id}", hasBody = true)
    suspend fun deletePlaylist(@Path("id") id: String, @Body body: PlaylistDeleteRequest)

    @POST("api/playlists/{id}/activate")
    suspend fun activatePlaylist(@Path("id") id: String, @Body body: PlaylistDeleteRequest): PlaylistActivateResponse

    @GET("api/parental/sync")
    suspend fun syncParental(@Query("mac") mac: String, @Query("deviceKey") deviceKey: String): ParentalSyncDto

    @POST("api/parental/categories")
    suspend fun pushParentalCategories(@Body body: ParentalCategoriesRequest)

    @POST("api/parental/set-pin")
    suspend fun setParentalPin(@Body body: ParentalSetPinRequest)
}

object ActivationApiClient {
    val api: ActivationApi by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ActivationApi::class.java)
    }
}
