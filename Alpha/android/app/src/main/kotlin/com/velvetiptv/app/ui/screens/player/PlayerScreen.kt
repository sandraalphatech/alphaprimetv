package com.velvetiptv.app.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import com.velvetiptv.app.data.ChannelType
import com.velvetiptv.app.data.M3UChannel
import com.velvetiptv.app.data.VodPreferences
import com.velvetiptv.app.ui.navigation.PlayQueueItem
import com.velvetiptv.app.ui.navigation.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.velvetiptv.app.ui.theme.AccentPrimary
import com.velvetiptv.app.ui.theme.TextLight
import java.util.Locale

private const val SEEK_STEP_MS = 10_000L
private const val AUTO_HIDE_MS = 4_000L

private val LANGUAGE_NAMES = mapOf(
    // undetermined — padrão sem etiqueta de idioma
    "und" to "Original",
    // Português (2-letras, 3-letras ISO 639-2, variantes regionais e IPTV comuns)
    "pt" to "Português", "por" to "Português",
    "pt-br" to "Português (Brasil)", "pob" to "Português (Brasil)", "ptb" to "Português (Brasil)",
    "pt-pt" to "Português (Portugal)",
    "portuguese" to "Português",
    // Inglês
    "en" to "Inglês", "eng" to "Inglês", "english" to "Inglês",
    // Espanhol
    "es" to "Espanhol", "spa" to "Espanhol", "spanish" to "Espanhol",
    "es-la" to "Espanhol (Latino)",
    // Coreano
    "ko" to "Coreano", "kor" to "Coreano", "korean" to "Coreano",
    // Japonês
    "ja" to "Japonês", "jpn" to "Japonês", "japanese" to "Japonês",
    // Francês
    "fr" to "Francês", "fra" to "Francês", "fre" to "Francês", "french" to "Francês",
    // Alemão
    "de" to "Alemão", "deu" to "Alemão", "ger" to "Alemão", "german" to "Alemão",
    // Italiano
    "it" to "Italiano", "ita" to "Italiano", "italian" to "Italiano",
    // Chinês
    "zh" to "Chinês", "zho" to "Chinês", "chi" to "Chinês", "cmn" to "Chinês",
    "chinese" to "Chinês",
    // Árabe
    "ar" to "Árabe", "ara" to "Árabe", "arabic" to "Árabe",
    // Russo
    "ru" to "Russo", "rus" to "Russo", "russian" to "Russo",
    // Holandês
    "nl" to "Holandês", "nld" to "Holandês", "dut" to "Holandês", "dutch" to "Holandês",
    // Turco
    "tr" to "Turco", "tur" to "Turco", "turkish" to "Turco",
    // Hindi
    "hi" to "Hindi", "hin" to "Hindi", "hindi" to "Hindi",
    // Polaco
    "pl" to "Polaco", "pol" to "Polaco", "polish" to "Polaco",
    // Sueco
    "sv" to "Sueco", "swe" to "Sueco", "swedish" to "Sueco",
    // Grego
    "el" to "Grego", "ell" to "Grego", "gre" to "Grego", "greek" to "Grego",
    // Ucraniano
    "uk" to "Ucraniano", "ukr" to "Ucraniano", "ukrainian" to "Ucraniano",
    // Outros comuns em IPTV
    "th" to "Tailandês",  "tha" to "Tailandês",
    "id" to "Indonésio",  "ind" to "Indonésio",
    "cs" to "Checo",      "ces" to "Checo",    "cze" to "Checo",
    "ro" to "Romeno",     "ron" to "Romeno",   "rum" to "Romeno",
    "hu" to "Húngaro",    "hun" to "Húngaro",
    "da" to "Dinamarquês","dan" to "Dinamarquês",
    "fi" to "Finlandês",  "fin" to "Finlandês",
    "no" to "Norueguês",  "nor" to "Norueguês",
    "he" to "Hebraico",   "heb" to "Hebraico",
    "vi" to "Vietnamita", "vie" to "Vietnamita",
    "ms" to "Malaio",     "msa" to "Malaio",   "may" to "Malaio"
)

private fun resolveLanguageName(code: String): String? {
    val lower = code.lowercase(Locale.US)
    return LANGUAGE_NAMES[lower]
        ?: LANGUAGE_NAMES[lower.substringBefore("-")]  // "pt-BR" → tenta "pt"
}

private fun cleanTrackName(raw: String): String {
    val bracketed = Regex("\\[([^]]+)]").find(raw)?.groupValues?.get(1)
    val candidate = bracketed ?: raw.substringAfterLast("-").trim()
    return resolveLanguageName(candidate) ?: candidate.replaceFirstChar { it.uppercaseChar() }
}

private fun formatTime(ms: Long): String {
    if (ms < 0) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

@Composable
fun PlayerScreen(
    navController: NavController,
    streamUrl: String,
    channelName: String,
    episodeQueue: List<PlayQueueItem> = emptyList(),
    queueIndex: Int = 0
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()


    var isBuffering  by remember { mutableStateOf(true) }
    var hasError     by remember { mutableStateOf(false) }
    var isPlaying    by remember { mutableStateOf(false) }
    var isSeekReload by remember { mutableStateOf(false) }

    var savedPositionMs    by remember { mutableStateOf(-1L) }
    var resumeDecisionMade by remember { mutableStateOf(false) }
    val showResumeDialog = savedPositionMs >= VodPreferences.RESUME_THRESHOLD_MS && !resumeDecisionMade

    var controlsVisible  by remember { mutableStateOf(true) }
    var currentMs        by remember { mutableStateOf(0L) }
    var durationMs       by remember { mutableStateOf(0L) }
    var isSeeking        by remember { mutableStateOf(false) }
    var seekPreviewMs    by remember { mutableStateOf(0L) }
    var dpadSeekTick     by remember { mutableStateOf(0) }  // incrementado a cada dpadSeek para resetar auto-hide

    data class TrackInfo(val listIdx: Int, val group: Tracks.Group, val name: String)
    var showTrackDialog        by remember { mutableStateOf(false) }
    var audioTracks            by remember { mutableStateOf<List<TrackInfo>>(emptyList()) }
    var subtitleTracks         by remember { mutableStateOf<List<TrackInfo>>(emptyList()) }
    var selectedAudioIdx       by remember { mutableStateOf(-1) }
    var selectedSubtitleIdx    by remember { mutableStateOf(-1) }  // -1 = desligado

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
            volume = 1f
        }
    }

    fun refreshTracks() {
        val tracks = exoPlayer.currentTracks
        val aList = mutableListOf<TrackInfo>()
        val sList = mutableListOf<TrackInfo>()
        var selA = -1
        var selS = -1
        tracks.groups.forEach { group ->
            when (group.type) {
                C.TRACK_TYPE_AUDIO -> if (group.isSupported) {
                    val fmt  = group.getTrackFormat(0)
                    val name = fmt.language?.let { resolveLanguageName(it) ?: it.uppercase(Locale.US) }
                        ?: fmt.label?.let { cleanTrackName(it) }
                        ?: "Áudio ${aList.size + 1}"
                    if (group.isSelected) selA = aList.size
                    aList.add(TrackInfo(aList.size, group, name))
                }
                C.TRACK_TYPE_TEXT -> if (group.isSupported) {
                    val fmt  = group.getTrackFormat(0)
                    val name = fmt.language?.let { resolveLanguageName(it) ?: it.uppercase(Locale.US) }
                        ?: fmt.label?.let { cleanTrackName(it) }
                        ?: "Legenda ${sList.size + 1}"
                    if (group.isSelected) selS = sList.size
                    sList.add(TrackInfo(sList.size, group, name))
                }
            }
        }
        audioTracks         = aList
        subtitleTracks      = sList
        selectedAudioIdx    = selA
        selectedSubtitleIdx = selS
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> isBuffering = true
                    Player.STATE_READY -> {
                        isBuffering  = false
                        isSeekReload = false
                        hasError     = false
                        if (savedPositionMs >= VodPreferences.RESUME_THRESHOLD_MS && !resumeDecisionMade) {
                            exoPlayer.pause()
                        }
                    }
                    Player.STATE_ENDED -> {
                        isBuffering = false
                        isPlaying   = false
                        val next = episodeQueue.getOrNull(queueIndex + 1)
                        CoroutineScope(Dispatchers.IO).launch {
                            VodPreferences.clearPosition(context, streamUrl)
                            VodPreferences.removeFromContinueWatching(context, streamUrl)
                            VodPreferences.markWatched(context, streamUrl)
                            if (next != null) {
                                val seriesName = next.name.substringBefore(" · ")
                                VodPreferences.addToContinueWatching(
                                    context,
                                    M3UChannel(name = next.name, url = next.url, logo = next.seriesId, group = seriesName, type = ChannelType.SERIES)
                                )
                            }
                        }
                        if (next != null) {
                            navController.navigate(
                                Screen.Player.createRoute(next.url, next.name, episodeQueue, queueIndex + 1)
                            ) { popUpTo(Screen.Player.route) { inclusive = true } }
                        } else {
                            navController.navigateUp()
                        }
                    }
                    else -> {}
                }
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onPlayerError(error: PlaybackException) {
                isBuffering  = false
                isSeekReload = false
                hasError     = true
            }
        }
        exoPlayer.addListener(listener)
        exoPlayer.setMediaItem(MediaItem.fromUri(streamUrl))
        exoPlayer.prepare()
        exoPlayer.play()

        onDispose {
            val posMs   = currentMs
            val decided = resumeDecisionMade
            exoPlayer.removeListener(listener)
            exoPlayer.stop()
            exoPlayer.release()
            if (decided && posMs > VodPreferences.RESUME_THRESHOLD_MS) {
                CoroutineScope(Dispatchers.IO).launch {
                    VodPreferences.savePosition(context, streamUrl, posMs)
                }
            }
        }
    }

    LaunchedEffect(streamUrl) {
        savedPositionMs = VodPreferences.getPosition(context, streamUrl)
        if (savedPositionMs < VodPreferences.RESUME_THRESHOLD_MS) {
            resumeDecisionMade = true
        } else if (!isBuffering && !hasError) {
            exoPlayer.pause()
        }
    }

    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            delay(500)
            if (!isSeeking && !isBuffering && !hasError) {
                val pos = exoPlayer.currentPosition
                if (pos >= 0) currentMs = pos
                val dur = exoPlayer.duration
                if (dur > 0 && dur != C.TIME_UNSET) durationMs = dur
                tick++
                if (resumeDecisionMade && tick % 10 == 0) {
                    VodPreferences.savePosition(context, streamUrl, currentMs)
                }
            }
        }
    }

    LaunchedEffect(isBuffering) {
        if (!isBuffering && !hasError) {
            delay(400)
            refreshTracks()
        }
    }

    LaunchedEffect(controlsVisible, isPlaying, isSeeking, dpadSeekTick) {
        if (controlsVisible && isPlaying && !isSeeking) {
            delay(AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    val interactionSource        = remember { MutableInteractionSource() }
    val focusRequester           = remember { FocusRequester() }
    val audioFocusRequester      = remember { FocusRequester() }
    val replay10FocusRequester   = remember { FocusRequester() }
    val playPauseFocusRequester  = remember { FocusRequester() }
    val forward10FocusRequester  = remember { FocusRequester() }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceIn(0L, if (durationMs > 0) durationMs else Long.MAX_VALUE)
        isSeekReload    = true
        hasError        = false
        controlsVisible = true
        exoPlayer.seekTo(target)
        exoPlayer.play()
        currentMs = target
    }

    fun seekBy(deltaMs: Long) = seekTo(currentMs + deltaMs)

    fun dpadSeek(deltaMs: Long) {
        if (durationMs <= 0) return
        val newTime = (currentMs + deltaMs).coerceIn(0L, durationMs)
        exoPlayer.seekTo(newTime)
        currentMs       = newTime
        controlsVisible = true
        dpadSeekTick++  // reset do timer de auto-hide
    }

    fun goToQueueItem(item: PlayQueueItem, newIndex: Int) {
        if (resumeDecisionMade) {
            scope.launch { VodPreferences.savePosition(context, streamUrl, currentMs) }
        }
        navController.navigate(Screen.Player.createRoute(item.url, item.name, episodeQueue, newIndex)) {
            popUpTo(Screen.Player.route) { inclusive = true }
        }
    }

    fun togglePlayPause() {
        if (isPlaying) {
            exoPlayer.pause()
            if (resumeDecisionMade) scope.launch { VodPreferences.savePosition(context, streamUrl, currentMs) }
        } else {
            exoPlayer.play()
        }
        controlsVisible = true
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    // Quando os controlos escondem (botões desaparecem), devolve o foco ao Box
    // para que o D-pad volte a funcionar para seek/play-pause.
    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        // LEFT/RIGHT sempre seekam quando o root Box tem foco
                        // (quando um botão filho tem foco, o seu onKeyEvent consome antes de chegar aqui)
                        Key.DirectionRight -> { dpadSeek(SEEK_STEP_MS); true }
                        Key.DirectionLeft  -> { dpadSeek(-SEEK_STEP_MS); true }
                        Key.DirectionCenter, Key.Enter -> {
                            if (!controlsVisible) {
                                // 1.º clique: mostra controlos e foca o botão play/pause
                                controlsVisible = true
                                scope.launch {
                                    delay(60)
                                    try { playPauseFocusRequester.requestFocus() } catch (_: Exception) {}
                                }
                            } else {
                                // Root Box tem foco e controlos estão visíveis → toggle play/pause
                                // (quando um botão filho tem foco, o seu onClick consome o ENTER antes de chegar aqui)
                                togglePlayPause()
                            }
                            true  // SEMPRE consumir — ENTER nunca deve propagar para o sistema
                        }
                        Key.DirectionUp -> {
                            if (!controlsVisible) controlsVisible = true
                            if (audioTracks.isNotEmpty() || subtitleTracks.isNotEmpty()) {
                                scope.launch {
                                    delay(60)
                                    try { audioFocusRequester.requestFocus() } catch (_: Exception) {}
                                }
                            }
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        // ── PlayerView (ExoPlayer) ────────────────────────────────────────
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player       = exoPlayer
                    useController = false
                    resizeMode   = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(-1f)
                .focusProperties { canFocus = false }
                .clickable(interactionSource = interactionSource, indication = null) {
                    controlsVisible = !controlsVisible
                }
        )

        // ── Buffering ─────────────────────────────────────────────────────
        if (isBuffering && !hasError) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AccentPrimary)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (isSeekReload) "A carregar..." else "A ligar ao stream...",
                        color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp
                    )
                }
            }
        }

        // ── Erro ──────────────────────────────────────────────────────────
        if (hasError) {
            Box(
                Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("❌", fontSize = 48.sp)
                    Text("Erro ao reproduzir", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(channelName, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                    Button(
                        onClick = {
                            hasError    = false
                            isBuffering = true
                            exoPlayer.setMediaItem(MediaItem.fromUri(streamUrl))
                            exoPlayer.prepare()
                            exoPlayer.play()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
                    ) { Text("Tentar novamente") }
                }
            }
        }

        // ── Continuar de onde ficou? ──────────────────────────────────────
        if (showResumeDialog) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Continuar a assistir?") },
                text  = { Text("Já tinhas chegado a ${formatTime(savedPositionMs)} neste vídeo.") },
                confirmButton = {
                    TextButton(onClick = {
                        resumeDecisionMade = true
                        seekTo(savedPositionMs)
                    }) { Text("Continuar") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        resumeDecisionMade = true
                        exoPlayer.play()
                    }) { Text("Recomeçar") }
                }
            )
        }

        // ── Controlos centrais ────────────────────────────────────────────
        if (controlsVisible && !isBuffering && !hasError) {
            fun focusVideo() = try { focusRequester.requestFocus() } catch (_: Exception) {}
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { seekBy(-SEEK_STEP_MS) },
                    modifier = Modifier
                        .size(56.dp)
                        .focusRequester(replay10FocusRequester)
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (event.key) {
                                Key.DirectionRight -> { try { playPauseFocusRequester.requestFocus() } catch (_: Exception) {}; true }
                                Key.DirectionDown  -> { focusVideo(); true }
                                else -> false
                            }
                        }
                ) {
                    Icon(Icons.Default.Replay10, "Retroceder 10s", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                IconButton(
                    onClick = { togglePlayPause() },
                    modifier = Modifier
                        .size(72.dp)
                        .focusRequester(playPauseFocusRequester)
                        .background(Color.Black.copy(alpha = 0.45f), shape = androidx.compose.foundation.shape.CircleShape)
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (event.key) {
                                Key.DirectionLeft  -> { try { replay10FocusRequester.requestFocus() } catch (_: Exception) {}; true }
                                Key.DirectionRight -> { try { forward10FocusRequester.requestFocus() } catch (_: Exception) {}; true }
                                Key.DirectionDown  -> { focusVideo(); true }
                                else -> false
                            }
                        }
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (isPlaying) "Pausar" else "Reproduzir",
                        tint = Color.White, modifier = Modifier.size(40.dp)
                    )
                }
                IconButton(
                    onClick = { seekBy(SEEK_STEP_MS) },
                    modifier = Modifier
                        .size(56.dp)
                        .focusRequester(forward10FocusRequester)
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (event.key) {
                                Key.DirectionLeft  -> { try { playPauseFocusRequester.requestFocus() } catch (_: Exception) {}; true }
                                Key.DirectionDown  -> { focusVideo(); true }
                                else -> false
                            }
                        }
                ) {
                    Icon(Icons.Default.Forward10, "Avançar 10s", tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }
        }

        // ── Barra de progresso ────────────────────────────────────────────
        if (controlsVisible && !isBuffering && !hasError && durationMs > 0) {
            val displayMs = if (isSeeking) seekPreviewMs else currentMs
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Slider(
                    value = displayMs.toFloat(),
                    onValueChange = { isSeeking = true; seekPreviewMs = it.toLong() },
                    onValueChangeFinished = { isSeeking = false; seekTo(seekPreviewMs) },
                    valueRange = 0f..durationMs.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = AccentPrimary,
                        activeTrackColor = AccentPrimary,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth().height(28.dp)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(displayMs), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(formatTime(durationMs), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                }
            }
        }

        // ── Botão voltar ──────────────────────────────────────────────────
        if (controlsVisible || hasError) {
            IconButton(
                onClick = {
                    if (resumeDecisionMade) {
                        scope.launch { VodPreferences.savePosition(context, streamUrl, currentMs) }
                    }
                    navController.navigateUp()
                },
                modifier = Modifier
                    .focusProperties { canFocus = false }  // D-pad não foca este botão; usa BACK do controlo
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = TextLight)
            }
        }

        // ── Topo direito: áudio/legendas + nome ──────────────────────────
        if (!isBuffering && !hasError && controlsVisible) {
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (audioTracks.isNotEmpty() || subtitleTracks.isNotEmpty()) {
                    IconButton(
                        onClick = { refreshTracks(); showTrackDialog = true; controlsVisible = true },
                        modifier = Modifier
                            .size(36.dp)
                            .focusRequester(audioFocusRequester)
                            .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown) {
                                    when (event.key) {
                                        Key.DirectionDown -> {
                                            try { focusRequester.requestFocus() } catch (_: Exception) {}
                                            true
                                        }
                                        else -> false
                                    }
                                } else false
                            }
                    ) {
                        Icon(Icons.Default.Tune, "Áudio e Legendas", tint = TextLight, modifier = Modifier.size(20.dp))
                    }
                }
                Text(
                    text = channelName,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        // ── Diálogo de Áudio e Legendas ───────────────────────────────────
        if (showTrackDialog) {
            val firstTrackFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                try { firstTrackFocus.requestFocus() } catch (_: Exception) {}
            }
            Dialog(onDismissRequest = { showTrackDialog = false }) {
                Card(
                    modifier = Modifier.width(300.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "Áudio e Legendas",
                            fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White
                        )
                        Spacer(Modifier.height(8.dp))

                        if (audioTracks.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.AudioFile, null, tint = AccentPrimary, modifier = Modifier.size(16.dp))
                                Text("Áudio", color = AccentPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(4.dp))
                            audioTracks.forEachIndexed { aIdx, track ->
                                TrackSelectionRow(
                                    name = track.name,
                                    isSelected = selectedAudioIdx == aIdx,
                                    modifier = if (aIdx == 0) Modifier.focusRequester(firstTrackFocus) else Modifier,
                                    onClick = {
                                        selectedAudioIdx = aIdx
                                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                            .addOverride(TrackSelectionOverride(track.group.mediaTrackGroup, listOf(0)))
                                            .build()
                                    }
                                )
                            }
                        }

                        if (subtitleTracks.isNotEmpty()) {
                            if (audioTracks.isNotEmpty()) Spacer(Modifier.height(10.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.Subtitles, null, tint = AccentPrimary, modifier = Modifier.size(16.dp))
                                Text("Legendas", color = AccentPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(4.dp))
                            TrackSelectionRow(
                                name = "Desligado",
                                isSelected = selectedSubtitleIdx == -1,
                                modifier = if (audioTracks.isEmpty()) Modifier.focusRequester(firstTrackFocus) else Modifier,
                                onClick = {
                                    selectedSubtitleIdx = -1
                                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                        .build()
                                }
                            )
                            subtitleTracks.forEachIndexed { sIdx, track ->
                                TrackSelectionRow(
                                    name = track.name,
                                    isSelected = selectedSubtitleIdx == sIdx,
                                    onClick = {
                                        selectedSubtitleIdx = sIdx
                                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                            .addOverride(TrackSelectionOverride(track.group.mediaTrackGroup, listOf(0)))
                                            .build()
                                    }
                                )
                            }
                        }

                        if (audioTracks.isEmpty() && subtitleTracks.isEmpty()) {
                            Text(
                                "Nenhuma faixa disponível neste stream.",
                                color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = { showTrackDialog = false },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Fechar", color = AccentPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackSelectionRow(
    name: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    isSelected -> AccentPrimary.copy(alpha = 0.18f)
                    focused    -> Color.White.copy(alpha = 0.08f)
                    else       -> Color.Transparent
                }
            )
            .border(
                width = if (focused) 2.dp else 0.dp,
                color  = if (focused) AccentPrimary else Color.Transparent,
                shape  = RoundedCornerShape(8.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            name,
            color    = if (isSelected) AccentPrimary else TextLight,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        if (isSelected) {
            Icon(
                Icons.Default.Check, null,
                tint     = AccentPrimary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
