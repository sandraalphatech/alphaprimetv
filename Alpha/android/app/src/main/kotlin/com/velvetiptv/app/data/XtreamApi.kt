package com.velvetiptv.app.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class XtreamCreds(val server: String, val user: String, val pass: String)

data class XtreamVodItem(
    val streamId           : Int,
    val name                : String,
    val icon                : String,
    val categoryId          : String,
    val containerExtension  : String
)

data class XtreamSeriesItem(
    val seriesId   : Int,
    val name        : String,
    val cover       : String,
    val categoryId  : String
)

data class XtreamEpisode(
    val id                  : Int,
    val title                : String,
    val episodeNum           : Int,
    val containerExtension   : String
)

data class XtreamSeason(val season: Int, val episodes: List<XtreamEpisode>)

// Cliente da API Xtream Codes real (player_api.php) — diferente do M3U (get.php).
// Usado para obter o "stream_icon" próprio do painel, que não sofre o
// geo-bloqueio do CDN de logos genérico embutido no M3U (tvg-logo).
object XtreamApi {

    private val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(c: Array<X509Certificate>, a: String) = Unit
        override fun checkServerTrusted(c: Array<X509Certificate>, a: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })
    private val ssl = SSLContext.getInstance("TLS").also { it.init(null, trustAll, SecureRandom()) }
    private val http = OkHttpClient.Builder()
        .sslSocketFactory(ssl.socketFactory, trustAll[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Resolve as credenciais Xtream a partir da configuração guardada —
    // ou os campos dedicados (aba "Xtream Codes"), ou extraídas do próprio
    // link colado na aba "URL M3U" (caso comum). Partilhado por Filmes e Séries.
    fun resolveCreds(connType: String, m3uUrl: String, xtreamServer: String, xtreamUser: String, xtreamPass: String): XtreamCreds? =
        when {
            connType == "xtream" && xtreamServer.isNotBlank() -> XtreamCreds(xtreamServer.trimEnd('/'), xtreamUser, xtreamPass)
            else -> credsFromM3uUrl(m3uUrl)
        }

    // Extrai server/username/password de um link Xtream (get.php?username=...)
    // colado na aba "URL M3U" — cobre o caso comum de o utilizador não usar a
    // aba "Xtream Codes" dedicada.
    fun credsFromM3uUrl(url: String): XtreamCreds? {
        if (!url.contains("username=") || !url.contains("password=")) return null
        return try {
            val uri  = Uri.parse(url)
            val user = uri.getQueryParameter("username")
            val pass = uri.getQueryParameter("password")
            val port = uri.port.takeIf { it > 0 }?.let { ":$it" } ?: ""
            if (user.isNullOrBlank() || pass.isNullOrBlank() || uri.host == null) null
            else XtreamCreds("${uri.scheme}://${uri.host}$port", user, pass)
        } catch (_: Exception) { null }
    }

    private fun getBody(url: String): String? {
        val resp = http.newCall(Request.Builder().url(url).build()).execute()
        resp.use { if (!it.isSuccessful) return null; return it.body?.string() }
    }

    // get_vod_streams devolve um array JSON plano (sem "data" wrapper) na API Xtream.
    suspend fun getVodStreams(creds: XtreamCreds): List<XtreamVodItem> = withContext(Dispatchers.IO) {
        val url = "${creds.server}/player_api.php?username=${creds.user}&password=${creds.pass}&action=get_vod_streams"
        val body = try { getBody(url) } catch (_: Exception) { null } ?: return@withContext emptyList()
        val arr = try { JSONArray(body) } catch (_: Exception) { return@withContext emptyList() }
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optInt("stream_id", -1)
            if (id < 0) return@mapNotNull null
            XtreamVodItem(
                streamId           = id,
                name                = o.optString("name"),
                icon                = o.optString("stream_icon"),
                categoryId          = o.optString("category_id"),
                containerExtension  = o.optString("container_extension").ifBlank { "mp4" }
            )
        }
    }

    suspend fun getVodCategories(creds: XtreamCreds): Map<String, String> = withContext(Dispatchers.IO) {
        val url = "${creds.server}/player_api.php?username=${creds.user}&password=${creds.pass}&action=get_vod_categories"
        val body = try { getBody(url) } catch (_: Exception) { null } ?: return@withContext emptyMap()
        val arr = try { JSONArray(body) } catch (_: Exception) { return@withContext emptyMap() }
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("category_id")
            if (id.isBlank()) null else id to o.optString("category_name")
        }.toMap()
    }

    fun vodPlayUrl(creds: XtreamCreds, item: XtreamVodItem) =
        "${creds.server}/movie/${creds.user}/${creds.pass}/${item.streamId}.${item.containerExtension}"

    suspend fun getSeries(creds: XtreamCreds): List<XtreamSeriesItem> = withContext(Dispatchers.IO) {
        val url = "${creds.server}/player_api.php?username=${creds.user}&password=${creds.pass}&action=get_series"
        val body = try { getBody(url) } catch (_: Exception) { null } ?: return@withContext emptyList()
        val arr = try { JSONArray(body) } catch (_: Exception) { return@withContext emptyList() }
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optInt("series_id", -1)
            if (id < 0) return@mapNotNull null
            XtreamSeriesItem(
                seriesId   = id,
                name        = o.optString("name"),
                cover       = o.optString("cover"),
                categoryId  = o.optString("category_id")
            )
        }
    }

    suspend fun getSeriesCategories(creds: XtreamCreds): Map<String, String> = withContext(Dispatchers.IO) {
        val url = "${creds.server}/player_api.php?username=${creds.user}&password=${creds.pass}&action=get_series_categories"
        val body = try { getBody(url) } catch (_: Exception) { null } ?: return@withContext emptyMap()
        val arr = try { JSONArray(body) } catch (_: Exception) { return@withContext emptyMap() }
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("category_id")
            if (id.isBlank()) null else id to o.optString("category_name")
        }.toMap()
    }

    // get_series_info devolve {"episodes": {"1": [...], "2": [...]}, ...} — as
    // chaves do objeto "episodes" são o número da temporada (como string).
    suspend fun getSeriesInfo(creds: XtreamCreds, seriesId: Int): List<XtreamSeason> = withContext(Dispatchers.IO) {
        val url = "${creds.server}/player_api.php?username=${creds.user}&password=${creds.pass}&action=get_series_info&series_id=$seriesId"
        val body = try { getBody(url) } catch (_: Exception) { null } ?: return@withContext emptyList()
        val root = try { JSONObject(body) } catch (_: Exception) { return@withContext emptyList() }
        val episodesObj = root.optJSONObject("episodes") ?: return@withContext emptyList()
        episodesObj.keys().asSequence().mapNotNull { seasonKey ->
            val seasonNum = seasonKey.toIntOrNull() ?: return@mapNotNull null
            val arr = episodesObj.optJSONArray(seasonKey) ?: return@mapNotNull null
            val episodes = (0 until arr.length()).mapNotNull mapEpisode@{ i ->
                val o = arr.optJSONObject(i) ?: return@mapEpisode null
                val id = o.optInt("id", -1)
                if (id < 0) return@mapEpisode null
                XtreamEpisode(
                    id                  = id,
                    title                = o.optString("title"),
                    episodeNum           = o.optInt("episode_num", 0),
                    containerExtension   = o.optString("container_extension").ifBlank { "mp4" }
                )
            }.sortedBy { it.episodeNum }
            XtreamSeason(seasonNum, episodes)
        }.sortedBy { it.season }.toList()
    }

    fun episodePlayUrl(creds: XtreamCreds, episode: XtreamEpisode) =
        "${creds.server}/series/${creds.user}/${creds.pass}/${episode.id}.${episode.containerExtension}"
}
