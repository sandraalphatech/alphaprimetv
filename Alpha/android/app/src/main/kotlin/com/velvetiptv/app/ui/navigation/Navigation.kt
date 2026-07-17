package com.velvetiptv.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import org.json.JSONArray
import org.json.JSONObject
import com.velvetiptv.app.data.ActivationApiClient
import com.velvetiptv.app.data.DeviceCheckRequest
import com.velvetiptv.app.data.DeviceUtils
import com.velvetiptv.app.data.LicensePreferences
import com.velvetiptv.app.data.SupabaseRegistration
import com.velvetiptv.app.data.VodPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.velvetiptv.app.ui.screens.activation.ActivationScreen
import com.velvetiptv.app.ui.screens.home.HomeMenuScreen
import com.velvetiptv.app.ui.screens.movies.MoviesScreen
import com.velvetiptv.app.ui.screens.player.PlayerScreen
import com.velvetiptv.app.ui.screens.series.SeriesScreen
import com.velvetiptv.app.ui.screens.settings.SettingsScreen
import com.velvetiptv.app.ui.screens.tv.TVScreen
import com.velvetiptv.app.ui.screens.vod.SeriesEpisodesScreen

// Um episódio na "fila" do episódio actualmente a reproduzir — permite ao
// PlayerScreen mostrar/usar os botões de "próximo"/"anterior episódio" sem
// precisar de voltar a chamar a API Xtream a cada salto.
data class PlayQueueItem(val url: String, val name: String, val seriesId: String = "")

// Fila de episódios partilhada entre instâncias do PlayerScreen — evita depender
// de codificar/descodificar a fila na rota de navegação, que falha silenciosamente
// quando os URLs dos episódios são longos ou contêm caracteres especiais no JSON.
object PlayQueueHolder {
    var queue: List<PlayQueueItem> = emptyList()
}

private fun b64Encode(s: String): String = android.util.Base64.encodeToString(
    s.toByteArray(Charsets.UTF_8),
    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
)

private fun b64Decode(s: String): String = try {
    String(android.util.Base64.decode(s, android.util.Base64.URL_SAFE), Charsets.UTF_8)
} catch (_: Exception) { s }

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Activation : Screen("activation", "Ativação",     Icons.Default.Settings)
    object Home       : Screen("home",       "Início",       Icons.Default.LiveTv)
    object TV         : Screen("tv",         "TV ao Vivo",   Icons.Default.LiveTv)
    object Movies     : Screen("movies",     "Filmes",       Icons.Default.Movie)
    object Series     : Screen("series",     "Séries",       Icons.Default.VideoLibrary)
    object Settings   : Screen("settings",   "Configurações", Icons.Default.Settings)
    object Player     : Screen("player/{url}/{name}/{extra}", "Player", Icons.Default.LiveTv) {
        // queue/index opcionais — só usados por Séries, para os botões de
        // próximo/anterior episódio dentro do próprio player. Vazio para Filmes.
        fun createRoute(url: String, name: String, queue: List<PlayQueueItem> = emptyList(), index: Int = 0): String {
            // Base64 URL-safe (só A-Za-z0-9-_) evita problemas com URLDecoder
            // quando o URL original tem sequências % inválidas (listas IPTV mal formadas)
            val encodedUrl  = b64Encode(url)
            val encodedName = b64Encode(name)
            val extraJson = JSONObject().apply {
                put("index", index)
                put("queue", JSONArray().apply {
                    queue.forEach { put(JSONObject().apply {
                        put("url", it.url); put("name", it.name)
                        if (it.seriesId.isNotBlank()) put("sid", it.seriesId)
                    }) }
                })
            }.toString()
            return "player/$encodedUrl/$encodedName/${b64Encode(extraJson)}"
        }
    }
    object SeriesEpisodes : Screen("series_episodes/{seriesId}/{name}", "Episódios", Icons.Default.VideoLibrary) {
        fun createRoute(seriesId: String, name: String): String {
            val encodedName = android.util.Base64.encodeToString(
                name.toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            )
            return "series_episodes/$seriesId/$encodedName"
        }
    }
}

@Composable
fun AlphaPrimeNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current

    var startDestination by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val installDate = LicensePreferences.getOrInitInstallDate(context)

        if (LicensePreferences.isTrialActive(installDate)) {
            // Regista o dispositivo e aguarda (idempotente — não sobrescreve se já existe,
            // por isso o admin pode expirar o trial no banco sem o app re-criar o registo).
            SupabaseRegistration.registerIfNeeded(context, installDate)

            // Consulta o backend mesmo durante o trial — permite que o admin revogue
            // ou expire o período sem ter de esperar que os 7 dias locais acabem.
            // Sem rede: benefício da dúvida (true = deixa entrar).
            val trialValid = try {
                val mac = DeviceUtils.getMacAddress(context)
                val key = DeviceUtils.getDeviceKey(context)
                ActivationApiClient.api.checkDevice(DeviceCheckRequest(mac, key)).activated
            } catch (_: Exception) {
                true
            }

            if (trialValid) {
                CoroutineScope(Dispatchers.IO).launch { VodPreferences.syncFavoritesFromServer(context) }
                startDestination = Screen.Home.route
            } else {
                startDestination = Screen.Activation.route
            }
            return@LaunchedEffect
        }

        // Trial expirado localmente: consulta o backend para verificar licença paga
        SupabaseRegistration.registerIfNeeded(context, installDate)

        val isActive = try {
            val mac      = DeviceUtils.getMacAddress(context)
            val key      = DeviceUtils.getDeviceKey(context)
            val response = ActivationApiClient.api.checkDevice(DeviceCheckRequest(mac, key))
            if (response.activated) {
                LicensePreferences.setExpiresAt(context, LicensePreferences.parseExpiresAt(response.expiresAt))
                true
            } else {
                LicensePreferences.setExpiresAt(context, null)
                false
            }
        } catch (_: Exception) {
            // Sem rede: usa cache local
            val expiresAt = LicensePreferences.getExpiresAt(context)
            LicensePreferences.isLicenseValid(expiresAt)
        }

        if (isActive) {
            CoroutineScope(Dispatchers.IO).launch {
                VodPreferences.syncFavoritesFromServer(context)
            }
        }

        startDestination = if (isActive) Screen.Home.route else Screen.Activation.route
    }

    val resolvedStart = startDestination
    if (resolvedStart == null) {
        // Aguarda a decisão acima — evita um "flash" da tela de ativação
        // antes de saber se o cliente já tem acesso válido.
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF080810)))
        return
    }

    // Sem animação de transição entre ecrãs (fade/slide). O vídeo do player
    // (LibVLC/VLCVideoLayout) usa uma SurfaceView nativa, que não respeita o
    // alpha do crossfade do Compose — durante a transição ela continuava a
    // aparecer a 100% de opacidade por cima do destino, parecendo "abrir em
    // ecrã inteiro" por instante antes do menu aparecer. Sem crossfade, o
    // corte é instantâneo e esse efeito desaparece.
    val noEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = { EnterTransition.None }
    val noExit:  AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition  = { ExitTransition.None }

    NavHost(
        navController = navController,
        startDestination = resolvedStart,
        enterTransition    = noEnter,
        exitTransition     = noExit,
        popEnterTransition = noEnter,
        popExitTransition  = noExit
    ) {
        composable(Screen.Activation.route) {
            ActivationScreen(navController = navController)
        }
        composable(Screen.Home.route) {
            HomeMenuScreen(navController = navController)
        }
        composable(Screen.TV.route) {
            TVScreen(navController = navController)
        }
        composable(Screen.Movies.route) {
            MoviesScreen(navController = navController)
        }
        composable(Screen.Series.route) {
            SeriesScreen(navController = navController)
        }
        composable(Screen.Settings.route) {
            SettingsScreen(navController = navController)
        }
        composable(
            route = Screen.Player.route,
            arguments = listOf(
                navArgument("url")   { type = NavType.StringType },
                navArgument("name")  { type = NavType.StringType },
                navArgument("extra") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            // Base64 URL-safe decode — imune a URLs com % inválidos em listas IPTV
            val url  = b64Decode(backStackEntry.arguments?.getString("url")  ?: "")
            val name = b64Decode(backStackEntry.arguments?.getString("name") ?: "")

            val extraRaw = b64Decode(backStackEntry.arguments?.getString("extra") ?: "")
            var queue = emptyList<PlayQueueItem>()
            var index = 0
            try {
                val obj = JSONObject(extraRaw)
                index = obj.optInt("index", 0)
                val arr = obj.optJSONArray("queue")
                if (arr != null) {
                    queue = (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        PlayQueueItem(o.optString("url"), o.optString("name"), o.optString("sid", ""))
                    }
                }
            } catch (_: Exception) { /* sem fila (ex.: Filmes) — fica vazia */ }

            // Usa o holder em memória como fonte primária da fila — só usa o queue
            // descodificado da rota se o episódio actual não estiver no holder
            // (ex: player aberto de "Continuar a Assistir" sem passar por SeriesEpisodes).
            val resolvedQueue = PlayQueueHolder.queue
                .takeIf { q -> q.any { it.url == url } }
                ?: queue

            PlayerScreen(
                navController = navController,
                streamUrl     = url,
                channelName   = name,
                episodeQueue  = resolvedQueue,
                queueIndex    = index
            )
        }
        composable(
            route = Screen.SeriesEpisodes.route,
            arguments = listOf(
                navArgument("seriesId") { type = NavType.StringType },
                navArgument("name")     { type = NavType.StringType }
            )
        ) { backStackEntry ->
            fun b64(s: String): String = try {
                String(android.util.Base64.decode(s, android.util.Base64.URL_SAFE), Charsets.UTF_8)
            } catch (_: Exception) { s }
            val seriesId = backStackEntry.arguments?.getString("seriesId") ?: ""
            val name     = b64(backStackEntry.arguments?.getString("name") ?: "")
            SeriesEpisodesScreen(navController = navController, seriesId = seriesId, seriesName = name)
        }
    }
}
