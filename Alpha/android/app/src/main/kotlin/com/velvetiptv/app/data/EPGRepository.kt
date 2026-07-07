package com.velvetiptv.app.data

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class EpgProgram(
    val title: String,
    val startMs: Long,
    val stopMs: Long
)

object EPGRepository {

    // tvg-id → lista de programas ordenada por hora de início
    private val data = HashMap<String, MutableList<EpgProgram>>(2048)

    @Volatile private var loadedUrl = ""
    @Volatile var isLoading = false
        private set

    private val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(c: Array<X509Certificate>, a: String) = Unit
        override fun checkServerTrusted(c: Array<X509Certificate>, a: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })
    private val ssl = SSLContext.getInstance("TLS").also { it.init(null, trustAll, SecureRandom()) }
    private val http = OkHttpClient.Builder()
        .sslSocketFactory(ssl.socketFactory, trustAll[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Devolve os programas de um canal numa janela de ±3h / +9h em torno de agora
    fun getProgramsFor(tvgId: String): List<EpgProgram> {
        if (tvgId.isBlank()) return emptyList()
        val now  = System.currentTimeMillis()
        val from = now - 3 * 3_600_000L
        val to   = now + 9 * 3_600_000L
        return data[tvgId]
            ?.filter { it.stopMs > from && it.startMs < to }
            ?: emptyList()
    }

    suspend fun loadIfNeeded(url: String) {
        if (url.isBlank() || url == loadedUrl || isLoading) return
        withContext(Dispatchers.IO) {
            isLoading = true
            try {
                val now  = System.currentTimeMillis()
                val from = now - 3 * 3_600_000L   // só guarda ±3h passadas / +9h futuras
                val to   = now + 9 * 3_600_000L

                val resp = http.newCall(Request.Builder().url(url).build()).execute()
                if (!resp.isSuccessful) return@withContext
                val body = resp.body ?: return@withContext

                val rawStream = body.byteStream()
                val stream = if (url.endsWith(".gz", ignoreCase = true))
                    GZIPInputStream(rawStream) else rawStream

                val parser = Xml.newPullParser()
                parser.setInput(stream, null)

                // Reutilizar o mesmo SimpleDateFormat dentro da coroutine IO (thread-safe aqui)
                val sdfTz  = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
                val sdfUtc = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).also {
                    it.timeZone = TimeZone.getTimeZone("UTC")
                }

                var channel = ""; var title = ""; var startMs = 0L; var stopMs = 0L
                var inTitle = false

                var ev = parser.eventType
                while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    when (ev) {
                        org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.name) {
                            "programme" -> {
                                channel = parser.getAttributeValue(null, "channel") ?: ""
                                startMs = parseDate(parser.getAttributeValue(null, "start") ?: "", sdfTz, sdfUtc)
                                stopMs  = parseDate(parser.getAttributeValue(null, "stop")  ?: "", sdfTz, sdfUtc)
                                title   = ""
                                inTitle = false
                            }
                            "title" -> {
                                // Guarda apenas se o programa está dentro da janela
                                inTitle = channel.isNotEmpty() && stopMs > from && startMs < to
                            }
                        }
                        org.xmlpull.v1.XmlPullParser.TEXT -> {
                            if (inTitle && title.isEmpty()) title = parser.text ?: ""
                        }
                        org.xmlpull.v1.XmlPullParser.END_TAG -> when (parser.name) {
                            "title"     -> inTitle = false
                            "programme" -> {
                                if (channel.isNotEmpty() && title.isNotEmpty()
                                    && stopMs > from && startMs < to) {
                                    data.getOrPut(channel) { mutableListOf() }
                                        .add(EpgProgram(title, startMs, stopMs))
                                }
                                channel = ""
                            }
                        }
                    }
                    ev = parser.next()
                }

                // Ordenar cada canal por hora de início
                data.values.forEach { list -> list.sortBy { it.startMs } }
                loadedUrl = url

            } catch (_: Exception) {
                // EPG falhou — grid vai mostrar "Sem dados" sem crashar
            } finally {
                isLoading = false
            }
        }
    }

    fun clear() {
        data.clear()
        loadedUrl = ""
    }

    private fun parseDate(s: String, sdfTz: SimpleDateFormat, sdfUtc: SimpleDateFormat): Long {
        return try {
            val clean = s.trim()
            if (clean.length < 14) return 0L
            val datePart = clean.take(14)
            val tzRaw    = clean.drop(14).trim()
            // Normalizar "+01:00" → "+0100"
            val tzNorm   = if (tzRaw.length == 6 && (tzRaw[0] == '+' || tzRaw[0] == '-'))
                tzRaw.replace(":", "") else tzRaw
            if (tzNorm.isBlank()) sdfUtc.parse(datePart)?.time ?: 0L
            else sdfTz.parse("$datePart $tzNorm")?.time ?: 0L
        } catch (_: Exception) { 0L }
    }
}
