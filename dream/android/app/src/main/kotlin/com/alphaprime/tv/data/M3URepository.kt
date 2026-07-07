package com.alphaprime.tv.data

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object M3URepository {

    // Trust-all SSL — igual ao Dream TV (servidores IPTV usam certs autoassinados)
    private val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(c: Array<X509Certificate>, a: String) = Unit
        override fun checkServerTrusted(c: Array<X509Certificate>, a: String) = Unit
        override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
    })

    val client: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(
            SSLContext.getInstance("TLS").also { it.init(null, trustAll, SecureRandom()) }.socketFactory,
            trustAll[0] as X509TrustManager
        )
        .hostnameVerifier { _, _ -> true }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val CACHE_FILE = "channels_cache.m3u"
    private val CACHE_TTL  = 24 * 3_600_000L  // 24 horas

    // Carrega da cache (thread de background, síncrono)
    fun loadCache(ctx: Context): List<M3UChannel> {
        val f = File(ctx.cacheDir, CACHE_FILE)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > CACHE_TTL) return emptyList()
        return parseLines(f.bufferedReader().lineSequence())
    }

    // Carrega da rede (thread de background, síncrono)
    // onBatch(canais, isFinal) é chamado à medida que chegam lotes de 100
    fun loadNetwork(
        ctx: Context,
        onBatch: (List<M3UChannel>, Boolean) -> Unit
    ) {
        val prefs  = Prefs
        val url = if (prefs.getType(ctx) == "xtream" && prefs.getServer(ctx).isNotBlank()) {
            "${prefs.getServer(ctx).trimEnd('/')}/get.php?username=${prefs.getUser(ctx)}&password=${prefs.getPass(ctx)}&type=m3u_plus&output=ts"
        } else {
            prefs.getM3U(ctx)
        }
        if (url.isBlank()) return

        val response = client.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) return
        val body = response.body ?: return

        val batch    = ArrayList<M3UChannel>(100)
        val cacheOut = StringBuilder()
        var name = ""; var logo = ""; var group = ""

        body.charStream().buffered().useLines { lines ->
            for (line in lines) {
                val l = line.trim()
                cacheOut.appendLine(l)
                when {
                    l.startsWith("#EXTINF:") -> {
                        name  = l.substringAfterLast(",").trim()
                        logo  = attr(l, "tvg-logo")
                        group = attr(l, "group-title")
                    }
                    l.isNotEmpty() && !l.startsWith("#") && name.isNotEmpty() -> {
                        batch.add(M3UChannel(name, l, logo, group, classify(group)))
                        name = ""; logo = ""; group = ""
                        if (batch.size >= 100) {
                            onBatch(batch.toList(), false)
                            batch.clear()
                        }
                    }
                }
            }
        }
        onBatch(batch.toList(), true)

        // Guardar cache
        try { File(ctx.cacheDir, CACHE_FILE).writeText(cacheOut.toString()) } catch (_: Exception) {}
    }

    fun clearCache(ctx: Context) = File(ctx.cacheDir, CACHE_FILE).delete()

    private fun parseLines(lines: Sequence<String>): List<M3UChannel> {
        val out = mutableListOf<M3UChannel>()
        var name = ""; var logo = ""; var group = ""
        for (line in lines) {
            val l = line.trim()
            when {
                l.startsWith("#EXTINF:") -> {
                    name  = l.substringAfterLast(",").trim()
                    logo  = attr(l, "tvg-logo")
                    group = attr(l, "group-title")
                }
                l.isNotEmpty() && !l.startsWith("#") && name.isNotEmpty() -> {
                    out.add(M3UChannel(name, l, logo, group, classify(group)))
                    name = ""; logo = ""; group = ""
                }
            }
        }
        return out
    }

    private fun attr(line: String, key: String): String =
        Regex("""$key="([^"]*)"""").find(line)?.groupValues?.getOrNull(1) ?: ""

    private fun classify(g: String): ChannelType {
        val gl = g.lowercase()
        return when {
            gl.contains("movie") || gl.contains("filme") || gl.contains("vod") ||
            gl.contains("cinema") || gl.contains("filmes") -> ChannelType.MOVIE
            gl.contains("serie") || gl.contains("novela") || gl.contains("show") -> ChannelType.SERIES
            else -> ChannelType.TV
        }
    }
}
