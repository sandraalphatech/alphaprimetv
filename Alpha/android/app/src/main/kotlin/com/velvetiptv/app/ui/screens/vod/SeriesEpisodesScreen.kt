package com.velvetiptv.app.ui.screens.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.velvetiptv.app.data.ChannelType
import com.velvetiptv.app.data.IPTVPreferences
import com.velvetiptv.app.data.M3UChannel
import com.velvetiptv.app.data.VodPreferences
import com.velvetiptv.app.data.XtreamApi
import com.velvetiptv.app.data.XtreamCreds
import com.velvetiptv.app.data.XtreamSeason
import com.velvetiptv.app.ui.navigation.PlayQueueHolder
import com.velvetiptv.app.ui.navigation.PlayQueueItem
import com.velvetiptv.app.ui.navigation.Screen
import com.velvetiptv.app.ui.screens.tv.ErrorMessage
import com.velvetiptv.app.ui.screens.tv.LoadingMessage
import com.velvetiptv.app.ui.theme.AccentPrimary
import com.velvetiptv.app.ui.theme.DarkBackground
import com.velvetiptv.app.ui.theme.SurfaceDark
import com.velvetiptv.app.ui.theme.TextLight
import kotlinx.coroutines.launch

private sealed class EpisodesLoadState {
    object Loading : EpisodesLoadState()
    data class Success(val creds: XtreamCreds, val seasons: List<XtreamSeason>) : EpisodesLoadState()
    data class Error(val message: String) : EpisodesLoadState()
}

// Um episódio já com o seu URL de stream resolvido — feito uma vez ao
// carregar a temporada, evitado recalcular a cada clique/indicador.
private data class ResolvedEpisode(
    val episodeNum: Int,
    val title: String,
    val url: String,
    val displayName: String
)

// ── Ecrã de episódios de uma série — temporadas em separadores no topo e a ────
// lista de episódios da temporada seleccionada por baixo. Aberto a partir de
// um poster de série em VodGridScreen (que só lista séries, não episódios).
@Composable
fun SeriesEpisodesScreen(
    navController: NavController?,
    seriesId: String,
    seriesName: String
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    val m3uUrl       by IPTVPreferences.getM3UUrl(context).collectAsState(initial = "")
    val xtreamServer by IPTVPreferences.getXtreamServer(context).collectAsState(initial = "")
    val xtreamUser   by IPTVPreferences.getXtreamUser(context).collectAsState(initial = "")
    val xtreamPass   by IPTVPreferences.getXtreamPass(context).collectAsState(initial = "")
    val connType     by IPTVPreferences.getConnectionType(context).collectAsState(initial = "m3u")

    var loadState by remember { mutableStateOf<EpisodesLoadState>(EpisodesLoadState.Loading) }
    var retryKey by remember { mutableStateOf(0) }
    var selectedSeason by remember { mutableStateOf<Int?>(null) }

    val watchedUrls by VodPreferences.watchedUrls(context).collectAsState(initial = emptySet())
    val positions by VodPreferences.positions(context).collectAsState(initial = emptyMap())

    LaunchedEffect(seriesId, retryKey) {
        loadState = EpisodesLoadState.Loading
        val creds = XtreamApi.resolveCreds(connType, m3uUrl, xtreamServer, xtreamUser, xtreamPass)
        if (creds == null) {
            loadState = EpisodesLoadState.Error("Não foi possível identificar o servidor Xtream desta lista.")
            return@LaunchedEffect
        }
        try {
            val seasons = XtreamApi.getSeriesInfo(creds, seriesId.toIntOrNull() ?: -1)
            if (seasons.isEmpty()) {
                loadState = EpisodesLoadState.Error("Esta série não tem episódios disponíveis.")
            } else {
                selectedSeason = seasons.first().season
                loadState = EpisodesLoadState.Success(creds, seasons)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadState = EpisodesLoadState.Error(e.message ?: "Erro ao carregar episódios")
        }
    }

    Box(Modifier.fillMaxSize().background(DarkBackground)) {
        // Cabeçalho — sempre visível, mesmo durante o carregamento/erro.
        Row(
            Modifier.fillMaxWidth().background(SurfaceDark).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController?.navigateUp() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.ArrowBack, null, tint = TextLight, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                seriesName, color = TextLight, fontWeight = FontWeight.Bold,
                fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        Box(Modifier.fillMaxSize().padding(top = 52.dp)) {
            when (val state = loadState) {
                is EpisodesLoadState.Loading -> LoadingMessage()
                is EpisodesLoadState.Error -> ErrorMessage(
                    state.message,
                    onRetry = { retryKey++ }
                )
                is EpisodesLoadState.Success -> {
                    val season = state.seasons.firstOrNull { it.season == selectedSeason } ?: state.seasons.first()

                    // Resolve os URLs de toda a temporada de uma vez — usados tanto
                    // para os indicadores (visto/em progresso) como para a fila de
                    // próximo/anterior episódio passada ao PlayerScreen.
                    val resolved = remember(season, state.creds) {
                        season.episodes.map { ep ->
                            val url = XtreamApi.episodePlayUrl(state.creds, ep)
                            val name = "$seriesName · T${season.season} E${ep.episodeNum}" +
                                if (ep.title.isNotBlank() && ep.title != ep.episodeNum.toString()) " · ${ep.title}" else ""
                            ResolvedEpisode(ep.episodeNum, ep.title, url, name)
                        }
                    }
                    val queue = remember(resolved) { resolved.map { PlayQueueItem(it.url, it.displayName, seriesId) } }

                    Column(Modifier.fillMaxSize()) {
                        // Separadores de temporada
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .background(SurfaceDark, RoundedCornerShape(10.dp))
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            state.seasons.forEach { s ->
                                val isSelected = s.season == season.season
                                val interactionSource = remember { MutableInteractionSource() }
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) AccentPrimary else Color.Transparent)
                                        .clickable(interactionSource = interactionSource, indication = null) {
                                            selectedSeason = s.season
                                        }
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        "Temporada ${s.season}",
                                        color = if (isSelected) Color.White else TextLight.copy(0.7f),
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }

                        // Lista de episódios da temporada seleccionada
                        LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                            itemsIndexed(resolved, key = { _, ep -> ep.url }) { index, ep ->
                                val isWatched = ep.url in watchedUrls
                                val posMs = positions[ep.url] ?: 0L
                                val hasProgress = !isWatched && posMs >= VodPreferences.RESUME_THRESHOLD_MS
                                EpisodeRow(
                                    episodeNum  = ep.episodeNum,
                                    title       = ep.title,
                                    isWatched   = isWatched,
                                    hasProgress = hasProgress,
                                    onClick = {
                                        scope.launch {
                                            VodPreferences.addToContinueWatching(
                                                context,
                                                M3UChannel(name = ep.displayName, url = ep.url, logo = seriesId, group = seriesName, type = ChannelType.SERIES)
                                            )
                                        }
                                        PlayQueueHolder.queue = queue
                                        navController?.navigate(Screen.Player.createRoute(ep.url, ep.displayName, queue, index))
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episodeNum  : Int,
    title       : String,
    isWatched   : Boolean,
    hasProgress : Boolean,
    onClick     : () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isWatched) SurfaceDark.copy(alpha = 0.5f) else SurfaceDark)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(50))
                .background(if (isWatched) AccentPrimary.copy(0.35f) else AccentPrimary.copy(0.18f)),
            contentAlignment = Alignment.Center
        ) {
            if (isWatched) {
                Icon(Icons.Default.Check, "Já visto", tint = AccentPrimary, modifier = Modifier.size(18.dp))
            } else {
                Text("$episodeNum", color = AccentPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title.ifBlank { "Episódio $episodeNum" },
                color = if (isWatched) TextLight.copy(0.5f) else TextLight,
                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (hasProgress) {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth(0.5f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(AccentPrimary.copy(0.3f))) {
                    Box(Modifier.fillMaxWidth(0.4f).height(3.dp).background(AccentPrimary))
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Default.PlayArrow, null,
            tint = if (isWatched) TextLight.copy(0.3f) else AccentPrimary,
            modifier = Modifier.size(22.dp)
        )
    }
    Spacer(Modifier.height(8.dp))
}
