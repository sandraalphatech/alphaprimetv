package com.velvetiptv.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class M3UChannel(
    val name: String,
    val url: String,
    val logo: String,
    val group: String,
    val type: ChannelType,
    val tvgId: String = ""   // mapeia para os programas no XMLTV
)

enum class ChannelType { TV, MOVIE, SERIES }

data class ChannelBatch(
    val channels: List<M3UChannel>,
    val isNetworkReset: Boolean = false,
    val isFinalBatch: Boolean = false,
    val epgUrl: String = ""  // url-tvg / x-tvg-url do cabeçalho #EXTM3U
)

object M3URepository {

    // Aceita todos os certificados SSL (igual ao Dream TV) — servidores IPTV
    // frequentemente usam certificados autoassinados ou com chain incompleta.
    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private val sslContext = SSLContext.getInstance("TLS").also {
        it.init(null, trustAllCerts, SecureRandom())
    }

    private val client = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun loadChannelsFlow(
        context: Context,
        m3uUrl: String = "",
        xtreamServer: String = "",
        xtreamUser: String = "",
        xtreamPass: String = "",
        connType: String = "m3u"
    ): Flow<ChannelBatch> = flow {

        val cacheFile = File(context.cacheDir, "channels_cache.txt")

        // 1. Emitir cache imediatamente se existir (< 24h) — em lotes de 200
        if (cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < 86_400_000L) {
            val parsed = parseCacheFile(cacheFile)
            if (parsed.channels.isNotEmpty()) {
                parsed.channels.chunked(200).forEachIndexed { i, chunk ->
                    emit(ChannelBatch(
                        channels = chunk,
                        epgUrl   = if (i == 0) parsed.epgUrl else ""
                    ))
                }
            }
        }

        // 2. Construir URL
        val url = if (connType == "xtream" && xtreamServer.isNotBlank())
            "${xtreamServer.trimEnd('/')}/get.php?username=$xtreamUser&password=$xtreamPass&type=m3u_plus&output=ts"
        else m3uUrl

        if (url.isBlank()) return@flow

        // 3. Obter o conteúdo: ficheiro local (lista carregada via "File") ou rede
        val contentStream = if (url.startsWith("file://")) {
            val file = File(java.net.URI(url))
            if (!file.exists()) throw java.io.IOException("Ficheiro da lista não encontrado")
            file.inputStream()
        } else {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                throw java.io.IOException("Servidor respondeu ${response.code} ${response.message}".trim())
            }
            (response.body ?: throw java.io.IOException("Resposta vazia do servidor")).byteStream()
        }

        val batch = ArrayList<M3UChannel>(100)

        var name = ""; var logo = ""; var group = ""; var tvgId = ""
        var epgUrl = ""
        var isFirstBatch = true
        var wroteCache = false

        // Escrever cache de forma incremental para evitar acumular o ficheiro inteiro em RAM.
        // O StringBuilder anterior (~20–40 MB para listas grandes) causava OOM no dispositivo.
        val cacheWriter = cacheFile.bufferedWriter()
        try {
            contentStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val l = line.trim()
                    if (l.isNotEmpty()) {
                        cacheWriter.write(l)
                        cacheWriter.newLine()
                        wroteCache = true
                    }

                    when {
                        l.startsWith("#EXTM3U") -> {
                            // Tenta os três nomes de atributo mais comuns nos fornecedores IPTV
                            epgUrl = extractAttr(l, "url-tvg")
                                .ifBlank { extractAttr(l, "x-tvg-url") }
                                .ifBlank { extractAttr(l, "tvg-url") }
                        }
                        l.startsWith("#EXTINF:") -> {
                            name  = l.substringAfterLast(",").trim()
                            logo  = extractAttr(l, "tvg-logo")
                            group = extractAttr(l, "group-title")
                            tvgId = extractAttr(l, "tvg-id")
                        }
                        l.isNotEmpty() && !l.startsWith("#") && name.isNotEmpty() -> {
                            batch.add(M3UChannel(name, l, logo, group, classify(group), tvgId))
                            name = ""; logo = ""; group = ""; tvgId = ""

                            if (batch.size >= 100) {
                                emit(ChannelBatch(
                                    channels       = batch.toList(),
                                    isNetworkReset = isFirstBatch,
                                    isFinalBatch   = false,
                                    epgUrl         = if (isFirstBatch) epgUrl else ""
                                ))
                                isFirstBatch = false
                                batch.clear()
                            }
                        }
                    }
                }
            }
        } finally {
            cacheWriter.close()
            if (!wroteCache) cacheFile.delete()
        }

        emit(ChannelBatch(
            channels       = batch.toList(),
            isNetworkReset = isFirstBatch,
            isFinalBatch   = true,
            epgUrl         = if (isFirstBatch) epgUrl else ""
        ))

    }.flowOn(Dispatchers.IO)

    fun clearCache(context: Context) {
        File(context.cacheDir, "channels_cache.txt").delete()
    }

    data class CacheParseResult(val epgUrl: String, val channels: List<M3UChannel>)

    private fun parseCacheFile(file: File): CacheParseResult {
        val channels = mutableListOf<M3UChannel>()
        var name = ""; var logo = ""; var group = ""; var tvgId = ""
        var epgUrl = ""
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                val l = line.trim()
                when {
                    l.startsWith("#EXTM3U") -> {
                        epgUrl = extractAttr(l, "url-tvg")
                            .ifBlank { extractAttr(l, "x-tvg-url") }
                            .ifBlank { extractAttr(l, "tvg-url") }
                    }
                    l.startsWith("#EXTINF:") -> {
                        name  = l.substringAfterLast(",").trim()
                        logo  = extractAttr(l, "tvg-logo")
                        group = extractAttr(l, "group-title")
                        tvgId = extractAttr(l, "tvg-id")
                    }
                    l.isNotEmpty() && !l.startsWith("#") && name.isNotEmpty() -> {
                        channels.add(M3UChannel(name, l, logo, group, classify(group), tvgId))
                        name = ""; logo = ""; group = ""; tvgId = ""
                    }
                }
            }
        }
        return CacheParseResult(epgUrl, channels)
    }

    private fun extractAttr(line: String, attr: String): String =
        Regex("""$attr="([^"]*)"""").find(line)?.groupValues?.getOrNull(1) ?: ""

    private fun classify(group: String): ChannelType {
        val g = group.lowercase()
        return when {
            g.contains("movie") || g.contains("filme") || g.contains("vod") ||
            g.contains("cinema") || g.contains("filmes") -> ChannelType.MOVIE
            g.contains("serie") || g.contains("series") || g.contains("novela") ||
            g.contains("show")  -> ChannelType.SERIES
            else -> ChannelType.TV
        }
    }
}
