package com.velvetiptv.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

// Guarda Favoritos, "Continuar a assistir" e a posição de reprodução de cada
// filme/episódio para Filmes/Séries — persistido em disco (sobrevive a
// reinícios da app), separado por ChannelType onde aplicável.
object VodPreferences {
    private val FAVORITES          = stringPreferencesKey("vod_favorites")
    private val CONTINUE_WATCHING  = stringPreferencesKey("vod_continue_watching")
    private val POSITIONS          = stringPreferencesKey("vod_positions") // {url: positionMs}
    private val WATCHED             = stringPreferencesKey("vod_watched")   // [url, url, ...]
    private const val MAX_CONTINUE_WATCHING = 24
    private const val MAX_POSITIONS = 200
    private const val MAX_WATCHED   = 500
    // Abaixo disto não vale a pena perguntar — é como se não tivesse começado.
    const val RESUME_THRESHOLD_MS = 5_000L

    private fun encode(list: List<M3UChannel>): String {
        val arr = JSONArray()
        list.forEach { ch ->
            arr.put(JSONObject().apply {
                put("name", ch.name); put("url", ch.url)
                put("logo", ch.logo); put("group", ch.group); put("type", ch.type.name)
            })
        }
        return arr.toString()
    }

    private fun decode(s: String): List<M3UChannel> {
        if (s.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                M3UChannel(
                    name  = o.optString("name"),
                    url   = o.optString("url"),
                    logo  = o.optString("logo"),
                    group = o.optString("group"),
                    type  = try { ChannelType.valueOf(o.optString("type")) } catch (_: Exception) { ChannelType.MOVIE }
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun favorites(context: Context, type: ChannelType): Flow<List<M3UChannel>> =
        context.iptvDataStore.data.map { decode(it[FAVORITES] ?: "").filter { ch -> ch.type == type } }

    fun continueWatching(context: Context, type: ChannelType): Flow<List<M3UChannel>> =
        context.iptvDataStore.data.map { decode(it[CONTINUE_WATCHING] ?: "").filter { ch -> ch.type == type } }

    suspend fun toggleFavorite(context: Context, channel: M3UChannel) {
        context.iptvDataStore.edit { prefs ->
            val current = decode(prefs[FAVORITES] ?: "").toMutableList()
            val idx = current.indexOfFirst { it.url == channel.url }
            if (idx >= 0) current.removeAt(idx) else current.add(0, channel)
            prefs[FAVORITES] = encode(current)
        }
        // Sync ao backend em background — falha silenciosa, sincroniza no próximo arranque
        try {
            val mac = DeviceUtils.getMacAddress(context)
            val key = DeviceUtils.getDeviceKey(context)
            val tipo = when (channel.type) {
                ChannelType.TV     -> "canal"
                ChannelType.MOVIE  -> "filme"
                ChannelType.SERIES -> "serie"
            }
            ActivationApiClient.api.toggleFavoriteRemote(
                FavoriteToggleRequest(
                    mac = mac, deviceKey = key,
                    item_id = channel.url,
                    tipo = tipo,
                    nome = channel.name.ifBlank { null },
                    url = channel.url,
                    logo = channel.logo.ifBlank { null },
                    grupo = channel.group.ifBlank { null }
                )
            )
        } catch (_: Exception) { /* sync no próximo arranque */ }
    }

    suspend fun syncFavoritesFromServer(context: Context) {
        try {
            val mac = DeviceUtils.getMacAddress(context)
            val key = DeviceUtils.getDeviceKey(context)
            val remote = ActivationApiClient.api.syncFavorites(mac, key)
            if (remote.isEmpty()) return
            val serverFavs = remote.mapNotNull { dto ->
                val url = dto.url ?: dto.item_id
                if (url.isBlank()) return@mapNotNull null
                M3UChannel(
                    name  = dto.nome  ?: "",
                    url   = url,
                    logo  = dto.logo  ?: "",
                    group = dto.grupo ?: "",
                    type  = when (dto.tipo) {
                        "canal"  -> ChannelType.TV
                        "serie"  -> ChannelType.SERIES
                        else     -> ChannelType.MOVIE
                    }
                )
            }
            context.iptvDataStore.edit { prefs ->
                prefs[FAVORITES] = encode(serverFavs)
            }
        } catch (_: Exception) { /* mantém local */ }
    }

    suspend fun addToContinueWatching(context: Context, channel: M3UChannel) {
        context.iptvDataStore.edit { prefs ->
            val current = decode(prefs[CONTINUE_WATCHING] ?: "").toMutableList()
            current.removeAll { it.url == channel.url }
            current.add(0, channel)
            while (current.size > MAX_CONTINUE_WATCHING) current.removeAt(current.size - 1)
            prefs[CONTINUE_WATCHING] = encode(current)
        }
    }

    // Posição de reprodução guardada para este url, em ms (0 se nunca visto/concluído).
    suspend fun getPosition(context: Context, url: String): Long {
        val json = context.iptvDataStore.data.first()[POSITIONS] ?: return 0L
        return try { JSONObject(json).optLong(url, 0L) } catch (_: Exception) { 0L }
    }

    suspend fun savePosition(context: Context, url: String, positionMs: Long) {
        context.iptvDataStore.edit { prefs ->
            val json = try { JSONObject(prefs[POSITIONS] ?: "{}") } catch (_: Exception) { JSONObject() }
            json.put(url, positionMs)
            // Evita crescimento sem fim — remove entradas mais antigas quando excede o limite.
            if (json.length() > MAX_POSITIONS) {
                val keys = json.keys().asSequence().toMutableList()
                while (json.length() > MAX_POSITIONS && keys.isNotEmpty()) {
                    json.remove(keys.removeAt(0))
                }
            }
            prefs[POSITIONS] = json.toString()
        }
    }

    // Marca como concluído — não voltar a perguntar para retomar este vídeo.
    suspend fun clearPosition(context: Context, url: String) {
        context.iptvDataStore.edit { prefs ->
            val json = try { JSONObject(prefs[POSITIONS] ?: "{}") } catch (_: Exception) { JSONObject() }
            json.remove(url)
            prefs[POSITIONS] = json.toString()
        }
    }

    // Ao terminar de ver até ao fim, sai da lista "Continuar a assistir".
    suspend fun removeFromContinueWatching(context: Context, url: String) {
        context.iptvDataStore.edit { prefs ->
            val current = decode(prefs[CONTINUE_WATCHING] ?: "").toMutableList()
            current.removeAll { it.url == url }
            prefs[CONTINUE_WATCHING] = encode(current)
        }
    }

    // Posição actual de cada url visto/em progresso — para mostrar indicador
    // de progresso nos episódios sem ter de pedir um a um (suspend) à UI.
    fun positions(context: Context): Flow<Map<String, Long>> =
        context.iptvDataStore.data.map { prefs ->
            try {
                val o = JSONObject(prefs[POSITIONS] ?: "{}")
                o.keys().asSequence().associateWith { o.optLong(it, 0L) }
            } catch (_: Exception) { emptyMap() }
        }

    fun watchedUrls(context: Context): Flow<Set<String>> =
        context.iptvDataStore.data.map { prefs ->
            try {
                val arr = JSONArray(prefs[WATCHED] ?: "[]")
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            } catch (_: Exception) { emptySet() }
        }

    // Chamado quando um vídeo chega ao fim sozinho — fica marcado como visto
    // para mostrar o indicador na lista de episódios, mesmo depois de a
    // posição ser limpa (clearPosition) e de sair de "Continuar a assistir".
    suspend fun markWatched(context: Context, url: String) {
        context.iptvDataStore.edit { prefs ->
            val arr = try { JSONArray(prefs[WATCHED] ?: "[]") } catch (_: Exception) { JSONArray() }
            val set = (0 until arr.length()).map { arr.getString(it) }.toMutableSet()
            set.add(url)
            val capped = if (set.size > MAX_WATCHED) set.toList().takeLast(MAX_WATCHED) else set.toList()
            prefs[WATCHED] = JSONArray(capped).toString()
        }
    }
}
