package com.velvetiptv.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

val Context.iptvDataStore: DataStore<Preferences> by preferencesDataStore(name = "iptv_settings")

data class IPTVPlaylist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String, // "m3u" or "xtream"
    val m3uUrl: String = "",
    val xtreamServer: String = "",
    val xtreamUser: String = "",
    val xtreamPass: String = "",
    val epgUrl: String = ""
)

object IPTVPreferences {
    private val gson = Gson()
    private val PLAYLISTS = stringPreferencesKey("playlists_json")
    private val ACTIVE_PLAYLIST_ID = stringPreferencesKey("active_playlist_id")

    private fun parsePlaylists(json: String?): List<IPTVPlaylist> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<IPTVPlaylist>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getPlaylists(context: Context): Flow<List<IPTVPlaylist>> =
        context.iptvDataStore.data.map { parsePlaylists(it[PLAYLISTS]) }

    fun getActivePlaylistId(context: Context): Flow<String?> =
        context.iptvDataStore.data.map { it[ACTIVE_PLAYLIST_ID] }

    fun getActivePlaylist(context: Context): Flow<IPTVPlaylist?> =
        context.iptvDataStore.data.map { prefs ->
            val playlists = parsePlaylists(prefs[PLAYLISTS])
            val activeId = prefs[ACTIVE_PLAYLIST_ID]
            playlists.firstOrNull { it.id == activeId } ?: playlists.firstOrNull()
        }

    // ── Compatibilidade: derivam sempre da lista ativa ──────────────────────
    fun getConnectionType(context: Context): Flow<String> =
        getActivePlaylist(context).map { it?.type ?: "m3u" }

    fun getM3UUrl(context: Context): Flow<String> =
        getActivePlaylist(context).map { it?.m3uUrl ?: "" }

    fun getXtreamServer(context: Context): Flow<String> =
        getActivePlaylist(context).map { it?.xtreamServer ?: "" }

    fun getXtreamUser(context: Context): Flow<String> =
        getActivePlaylist(context).map { it?.xtreamUser ?: "" }

    fun getXtreamPass(context: Context): Flow<String> =
        getActivePlaylist(context).map { it?.xtreamPass ?: "" }

    suspend fun addM3UPlaylist(context: Context, name: String, url: String, epgUrl: String = ""): String {
        val playlist = IPTVPlaylist(name = name, type = "m3u", m3uUrl = url, epgUrl = epgUrl)
        return addPlaylist(context, playlist)
    }

    suspend fun addXtreamPlaylist(
        context: Context, name: String, server: String, user: String, pass: String, epgUrl: String = ""
    ): String {
        val playlist = IPTVPlaylist(
            name = name, type = "xtream", xtreamServer = server, xtreamUser = user, xtreamPass = pass, epgUrl = epgUrl
        )
        return addPlaylist(context, playlist)
    }

    private suspend fun addPlaylist(context: Context, playlist: IPTVPlaylist): String {
        var newId = playlist.id
        context.iptvDataStore.edit { prefs ->
            val current = parsePlaylists(prefs[PLAYLISTS]).toMutableList()
            current.add(playlist)
            prefs[PLAYLISTS] = gson.toJson(current)
            if (prefs[ACTIVE_PLAYLIST_ID].isNullOrBlank()) {
                prefs[ACTIVE_PLAYLIST_ID] = playlist.id
            }
            newId = playlist.id
        }
        return newId
    }

    // Troca o id local (temporário) pelo id definitivo devolvido pelo servidor ao sincronizar.
    suspend fun adoptServerId(context: Context, localId: String, serverId: String) {
        if (localId == serverId) return
        context.iptvDataStore.edit { prefs ->
            val current = parsePlaylists(prefs[PLAYLISTS])
            val updated = current.map { if (it.id == localId) it.copy(id = serverId) else it }
            prefs[PLAYLISTS] = gson.toJson(updated)
            if (prefs[ACTIVE_PLAYLIST_ID] == localId) {
                prefs[ACTIVE_PLAYLIST_ID] = serverId
            }
        }
    }

    suspend fun removePlaylist(context: Context, id: String) {
        context.iptvDataStore.edit { prefs ->
            val current = parsePlaylists(prefs[PLAYLISTS]).filter { it.id != id }
            prefs[PLAYLISTS] = gson.toJson(current)
            if (prefs[ACTIVE_PLAYLIST_ID] == id) {
                prefs[ACTIVE_PLAYLIST_ID] = current.firstOrNull()?.id ?: ""
            }
        }
    }

    suspend fun setActivePlaylist(context: Context, id: String) {
        context.iptvDataStore.edit { prefs ->
            prefs[ACTIVE_PLAYLIST_ID] = id
        }
    }

    // O servidor é a fonte da verdade: como toda alteração local já é enviada na hora
    // (ver SettingsScreen), o pull substitui a lista local pela do servidor — assim
    // uma lista apagada no dashboard também some do app, e vice-versa.
    suspend fun syncFromServer(context: Context, remote: List<PlaylistDto>) {
        context.iptvDataStore.edit { prefs ->
            val merged = remote.map { r ->
                IPTVPlaylist(
                    id = r.id,
                    name = r.name,
                    type = if (r.type == "xtream") "xtream" else "m3u",
                    m3uUrl = r.url,
                    xtreamServer = r.server,
                    xtreamUser = r.username,
                    xtreamPass = r.password,
                    epgUrl = r.epgUrl
                )
            }
            prefs[PLAYLISTS] = gson.toJson(merged)

            val serverActiveId = remote.firstOrNull { it.active }?.id
            when {
                serverActiveId != null -> prefs[ACTIVE_PLAYLIST_ID] = serverActiveId
                merged.none { it.id == prefs[ACTIVE_PLAYLIST_ID] } -> prefs[ACTIVE_PLAYLIST_ID] = merged.firstOrNull()?.id ?: ""
            }
        }
    }
}
