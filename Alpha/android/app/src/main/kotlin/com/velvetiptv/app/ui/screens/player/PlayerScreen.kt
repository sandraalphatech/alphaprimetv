package com.velvetiptv.app.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import com.velvetiptv.app.data.VodPreferences
import com.velvetiptv.app.ui.navigation.PlayQueueItem
import com.velvetiptv.app.ui.navigation.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.util.VLCVideoLayout
import com.velvetiptv.app.ui.theme.AccentPrimary
import com.velvetiptv.app.ui.theme.TextLight
import java.util.Locale

private const val SEEK_STEP_MS    = 10_000L
private const val AUTO_HIDE_MS    = 4_000L
private const val SEEK_TIMEOUT_MS = 15_000L

private val LANGUAGE_NAMES = mapOf(
    "portuguese" to "Português",
    "english"    to "Inglês",
    "spanish"    to "Espanhol",
    "korean"     to "Coreano",
    "japanese"   to "Japonês",
    "french"     to "Francês",
    "german"     to "Alemão",
    "italian"    to "Italiano",
    "chinese"    to "Chinês",
    "arabic"     to "Árabe",
    "russian"    to "Russo",
    "dutch"      to "Holandês",
    "turkish"    to "Turco",
    "hindi"      to "Hindi",
    "thai"       to "Tailandês",
    "polish"     to "Polaco",
    "swedish"    to "Sueco",
    "norwegian"  to "Norueguês",
    "danish"     to "Dinamarquês",
    "finnish"    to "Finlandês",
    "greek"      to "Grego",
    "hebrew"     to "Hebraico",
    "czech"      to "Checo",
    "hungarian"  to "Húngaro",
    "romanian"   to "Romeno",
    "catalan"    to "Catalão",
    "ukrainian"  to "Ucraniano"
)

// Extrai o nome legível de nomes como "Track 1 - [Portuguese]" ou "Track 2 - [Korean]".
// Traduz para Português quando possível; caso contrário mantém o nome original limpo.
private fun cleanTrackName(raw: String): String {
    val bracketed = Regex("\\[([^]]+)]").find(raw)?.groupValues?.get(1)
    val candidate = bracketed ?: raw.substringAfterLast("-").trim()
    return LANGUAGE_NAMES[candidate.lowercase()] ?: candidate.replaceFirstChar { it.uppercaseChar() }
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
    // Fila de episódios da temporada actual — só para Séries (vazia para
    // Filmes). Permite os botões de avançar/voltar episódio e o auto-avançar
    // para o próximo quando este termina, sem voltar a chamar a API Xtream.
    episodeQueue: List<PlayQueueItem> = emptyList(),
    queueIndex: Int = 0
) {
    val context       = LocalContext.current
    val scope         = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val nextEpisode = episodeQueue.getOrNull(queueIndex + 1)
    val prevEpisode = episodeQueue.getOrNull(queueIndex - 1)
    var isBuffering by remember { mutableStateOf(true) }
    var hasError    by remember { mutableStateOf(false) }
    var isPlaying   by remember { mutableStateOf(true) }
    // Distingue a ligação inicial ("A ligar ao stream...") de um recarregar
    // por causa de um salto/avanço (mostra "A carregar..." em vez disso).
    var isSeekReload by remember { mutableStateOf(false) }

    // Retomar de onde ficou — perguntar em vez de simplesmente continuar ou
    // recomeçar sozinho. null = ainda não sabemos / não há nada para perguntar.
    // Começa em -1 (desconhecido) para não mostrar "Recomeçar" só por instante
    // antes da posição guardada chegar do disco.
    var savedPositionMs by remember { mutableStateOf(-1L) }
    var resumeDecisionMade by remember { mutableStateOf(false) }
    val showResumeDialog = savedPositionMs >= VodPreferences.RESUME_THRESHOLD_MS && !resumeDecisionMade

    // Controlos estilo YouTube — tocar no vídeo mostra/esconde; escondem-se sós
    // após alguns segundos enquanto está a reproduzir.
    var controlsVisible by remember { mutableStateOf(true) }

    // Posição/duração — atualizadas por polling (VLC não emite eventos de progresso).
    // isSeeking impede que o polling pise a posição enquanto se arrasta a barra.
    var currentMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekPreviewMs by remember { mutableStateOf(0L) }

    // Faixas de áudio e legendas — carregadas do LibVLC após o stream iniciar
    data class TrackInfo(val id: Int, val name: String)
    var showTrackDialog by remember { mutableStateOf(false) }
    var audioTracks by remember { mutableStateOf<List<TrackInfo>>(emptyList()) }
    var subtitleTracks by remember { mutableStateOf<List<TrackInfo>>(emptyList()) }
    var selectedAudioId by remember { mutableStateOf(-1) }
    var selectedSubtitleId by remember { mutableStateOf(-1) }

    // VLC — mesmo player do TVScreen, estável para streams IPTV
    val libVLC = remember {
        LibVLC(context, arrayListOf(
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--rtsp-tcp",
            "--network-caching=1500",
            "--live-caching=1500",
            "--file-caching=1500"
        ))
    }
    val mediaPlayer = remember { org.videolan.libvlc.MediaPlayer(libVLC) }

    // Referência ao VLCVideoLayout para re-attachar após minimizar a app
    val vlcLayoutHolder = remember { object { var layout: VLCVideoLayout? = null } }

    // Quando o app volta ao primeiro plano, re-attach da surface VLC para
    // restaurar o vídeo. Salta o primeiro ON_RESUME (retroactivo do Lifecycle
    // ao registar o observer) para evitar IllegalStateException: Already attached.
    DisposableEffect(lifecycleOwner) {
        var isInitial = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (isInitial) { isInitial = false; return@LifecycleEventObserver }
                vlcLayoutHolder.layout?.let { layout ->
                    try { mediaPlayer.detachViews() } catch (_: Throwable) {}
                    mediaPlayer.attachViews(layout, null, false, false)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Carrega as faixas disponíveis a partir do LibVLC e actualiza o estado local.
    // Chamado quando o buffering termina (stream pronto) e ao abrir o diálogo.
    fun refreshTracks() {
        // Filtra id=-1 ("Disable") do áudio — seleccioná-lo ficava sem som.
        // Só mostra as faixas reais de dublagem disponíveis no stream.
        audioTracks = mediaPlayer.audioTracks
            ?.filter { it.id != -1 }
            ?.map { TrackInfo(it.id, cleanTrackName(it.name)) }
            ?: emptyList()
        // Para legendas, filtra id=-1 porque já adicionamos "Desligado" manualmente.
        subtitleTracks = mediaPlayer.spuTracks
            ?.filter { it.id != -1 }
            ?.map { TrackInfo(it.id, cleanTrackName(it.name)) }
            ?: emptyList()
        selectedAudioId = mediaPlayer.audioTrack
        selectedSubtitleId = mediaPlayer.spuTrack
    }

    // Timeout da ligação inicial — se após 20s ainda estiver em buffering
    // sem qualquer erro (VLC pendurou silenciosamente), mostra o erro para o
    // utilizador poder tentar de novo em vez de ficar preso para sempre.
    LaunchedEffect(streamUrl) {
        delay(20_000L)
        if (isBuffering && !hasError) {
            isBuffering = false
            hasError = true
        }
    }

    LaunchedEffect(streamUrl) {
        savedPositionMs = VodPreferences.getPosition(context, streamUrl)
        if (savedPositionMs < VodPreferences.RESUME_THRESHOLD_MS) {
            resumeDecisionMade = true
        } else if (isPlaying) {
            // O Playing já tinha disparado antes desta leitura terminar — pausa
            // agora para não passar do início enquanto se mostra o diálogo.
            mediaPlayer.pause()
        }
    }

    DisposableEffect(mediaPlayer) {
        // Listener de eventos — entregues na main thread pelo LibVLC
        val listener = org.videolan.libvlc.MediaPlayer.EventListener { event ->
            when (event.type) {
                org.videolan.libvlc.MediaPlayer.Event.Playing -> {
                    isBuffering  = false
                    isSeekReload = false
                    hasError     = false
                    isPlaying    = true
                    // Ainda não respondeu ao diálogo "Continuar/Recomeçar" — pausa
                    // de imediato para não avançar a partir do início enquanto se
                    // decide (o diálogo só pode mostrar-se depois de saber a
                    // posição guardada, que chega um pouco depois do Playing).
                    if (savedPositionMs >= VodPreferences.RESUME_THRESHOLD_MS && !resumeDecisionMade) {
                        mediaPlayer.pause()
                    }
                }
                org.videolan.libvlc.MediaPlayer.Event.Paused -> {
                    isPlaying = false
                }
                org.videolan.libvlc.MediaPlayer.Event.Buffering -> {
                    isBuffering = event.buffering < 100f
                }
                org.videolan.libvlc.MediaPlayer.Event.EncounteredError -> {
                    isBuffering  = false
                    isSeekReload = false
                    hasError     = true
                }
                org.videolan.libvlc.MediaPlayer.Event.EndReached -> {
                    isBuffering = false
                    isPlaying   = false
                    scope.launch {
                        VodPreferences.clearPosition(context, streamUrl)
                        VodPreferences.removeFromContinueWatching(context, streamUrl)
                        // Fica marcado como visto — mostra o indicador na lista de
                        // episódios mesmo depois de saír de "Continuar a assistir".
                        VodPreferences.markWatched(context, streamUrl)
                    }
                    val next = episodeQueue.getOrNull(queueIndex + 1)
                    if (next != null) {
                        // Série com próximo episódio disponível — avança sozinho.
                        // popUpTo substitui o Player actual (ver goToQueueItem) para o
                        // "voltar" ir directo à lista de episódios, não ao anterior.
                        navController.navigate(Screen.Player.createRoute(next.url, next.name, episodeQueue, queueIndex + 1)) {
                            popUpTo(Screen.Player.route) { inclusive = true }
                        }
                    } else {
                        // Filme, ou último episódio da temporada — volta para a grelha.
                        navController.navigateUp()
                    }
                }
            }
        }
        mediaPlayer.setEventListener(listener)
        // O playback é iniciado no AndroidView.factory, DEPOIS de attachViews,
        // para garantir que o VLC tem uma surface antes de começar a descodificar.

        onDispose {
            mediaPlayer.setEventListener(null)
            mediaPlayer.stop()
            mediaPlayer.detachViews()
            mediaPlayer.release()
            libVLC.release()
        }
    }

    // Polling de posição/duração — 2x por segundo, suficiente para uma barra suave.
    // Grava a posição a cada ~5s para o "Continuar a assistir" funcionar mesmo
    // que a app seja fechada de repente (sem depender só do onDispose).
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            delay(500)
            if (!isSeeking && !isBuffering && !hasError) {
                currentMs  = mediaPlayer.time.coerceAtLeast(0L)
                val len    = mediaPlayer.length
                if (len > 0) durationMs = len
                tick++
                if (resumeDecisionMade && tick % 10 == 0) {
                    VodPreferences.savePosition(context, streamUrl, currentMs)
                }
            }
        }
    }

    // Após o buffering terminar, carregar as faixas com um pequeno delay para o
    // LibVLC terminar de parsear os metadados do stream.
    LaunchedEffect(isBuffering) {
        if (!isBuffering && !hasError) {
            delay(400)
            refreshTracks()
        }
    }

    // Esconder os controlos sós após inatividade, só enquanto está a reproduzir.
    LaunchedEffect(controlsVisible, isPlaying, isSeeking) {
        if (controlsVisible && isPlaying && !isSeeking) {
            delay(AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    // seekGeneration muda a cada tentativa de salto — usado para cancelar o
    // vigilante de timeout anterior quando se pede outro salto antes do
    // primeiro terminar (ou para o desarmar quando o salto teve sucesso).
    var seekGeneration by remember { mutableStateOf(0) }

    // Salta para uma posição REABRINDO o stream com ":start-time", em vez de
    // pedir um seek "ao vivo" ao demuxer (mediaPlayer.time = X). Alguns
    // ficheiros/servidores IPTV têm o índice de busca (seekhead) corrompido
    // ou não suportam pedidos de intervalo HTTP — um seek ao vivo nesses
    // casos fica preso em "A ligar..." para sempre. Reabrir do zero com
    // start-time é o que o próprio VLC faz internamente e costuma funcionar
    // onde o seek ao vivo falha.
    fun playFrom(positionMs: Long) {
        val target = positionMs.coerceIn(0L, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
        isBuffering   = true
        isSeekReload  = true
        hasError      = false
        controlsVisible = true
        seekGeneration++
        val myGeneration = seekGeneration
        try {
            val media = Media(libVLC, android.net.Uri.parse(streamUrl)).apply {
                addOption(":network-caching=1500")
                addOption(":live-caching=1500")
                if (target > 0) addOption(":start-time=${target / 1000}")
            }
            mediaPlayer.media = media
            media.release()
            mediaPlayer.play()
            currentMs = target
        } catch (_: Exception) {
            hasError     = true
            isBuffering  = false
            isSeekReload = false
            return
        }
        scope.launch {
            delay(SEEK_TIMEOUT_MS)
            // Se ainda estamos na mesma tentativa e continua tudo bloqueado em
            // "A ligar...", desiste e avisa em vez de ficar preso para sempre.
            if (seekGeneration == myGeneration && isBuffering) {
                hasError     = true
                isBuffering  = false
                isSeekReload = false
            }
        }
    }

    fun seekBy(deltaMs: Long) {
        playFrom(currentMs + deltaMs)
    }

    fun goToQueueItem(item: PlayQueueItem, newIndex: Int) {
        // Guarda a posição actual antes de saltar — se voltar a este episódio
        // mais tarde, o diálogo de "Continuar/Recomeçar" continua a funcionar.
        if (resumeDecisionMade) {
            scope.launch { VodPreferences.savePosition(context, streamUrl, currentMs) }
        }
        // popUpTo substitui o Player actual em vez de empilhar mais um por
        // cima — sem isto, o botão "voltar" ia passando por cada episódio
        // anterior em vez de voltar directamente à lista de episódios.
        navController.navigate(Screen.Player.createRoute(item.url, item.name, episodeQueue, newIndex)) {
            popUpTo(Screen.Player.route) { inclusive = true }
        }
    }

    fun togglePlayPause() {
        // Usa o estado local (actualizado pelos eventos Playing/Paused do VLC) em
        // vez de mediaPlayer.isPlaying — este lia sempre "true" neste stream,
        // fazendo cair sempre no ramo play() e nunca pausar de facto.
        if (isPlaying) {
            mediaPlayer.pause()
            if (resumeDecisionMade) scope.launch { VodPreferences.savePosition(context, streamUrl, currentMs) }
        } else {
            mediaPlayer.play()
        }
        controlsVisible = true
    }

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ── VLCVideoLayout (renderização do vídeo) ────────────────────────
        AndroidView(
            factory = { ctx ->
                VLCVideoLayout(ctx).also { layout ->
                    vlcLayoutHolder.layout = layout
                    mediaPlayer.attachViews(layout, null, false, false)
                    // Iniciar playback DEPOIS de attachViews — VLC precisa de uma
                    // surface para emitir Event.Playing; sem ela o evento nunca chega
                    // e o overlay "A ligar ao stream..." ficava eterno.
                    try {
                        val media = Media(libVLC, android.net.Uri.parse(streamUrl)).apply {
                            addOption(":network-caching=800")
                            addOption(":live-caching=800")
                        }
                        mediaPlayer.media = media
                        media.release()
                        mediaPlayer.play()
                    } catch (_: Exception) {
                        hasError    = true
                        isBuffering = false
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Toca em qualquer ponto do vídeo para mostrar/esconder os controlos.
        // zIndex negativo é essencial — sem ele esta caixa (apesar de declarada
        // antes) consumia o toque antes de chegar aos botões por cima.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(-1f)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        controlsVisible = !controlsVisible
                    }
                )
        )

        // ── Buffering ─────────────────────────────────────────────────────
        if (isBuffering && !hasError) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
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
                    Text("Erro ao reproduzir o canal", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(channelName, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                    Button(
                        onClick = {
                            hasError    = false
                            isBuffering = true
                            try {
                                val media = Media(libVLC, android.net.Uri.parse(streamUrl)).apply {
                                    addOption(":network-caching=1500")
                                    addOption(":live-caching=1500")
                                }
                                mediaPlayer.media = media
                                media.release()
                                mediaPlayer.play()
                            } catch (_: Exception) {
                                hasError = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
                    ) { Text("Tentar novamente") }
                }
            }
        }

        // ── Continuar de onde ficou? ────────────────────────────────────────
        // Mostra-se quando há uma posição guardada para este vídeo (já tinha
        // sido aberto antes) — o vídeo fica pausado no início até se escolher.
        if (showResumeDialog) {
            AlertDialog(
                onDismissRequest = { /* obriga a escolher uma das opções */ },
                title = { Text("Continuar a assistir?") },
                text  = { Text("Já tinhas chegado a ${formatTime(savedPositionMs)} neste vídeo.") },
                confirmButton = {
                    TextButton(onClick = {
                        resumeDecisionMade = true
                        playFrom(savedPositionMs)
                    }) { Text("Continuar") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        // Já está a tocar desde o início (a auto-reprodução começou
                        // em 0 antes de sabermos da posição guardada) — só retomar.
                        resumeDecisionMade = true
                        mediaPlayer.play()
                    }) { Text("Recomeçar") }
                }
            )
        }

        // ── Controlos centrais: [anterior] retroceder 10s | play/pause | avançar 10s [próximo] ──
        if (controlsVisible && !isBuffering && !hasError) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (prevEpisode != null) {
                    IconButton(
                        onClick = { goToQueueItem(prevEpisode, queueIndex - 1) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.SkipPrevious, "Episódio anterior", tint = Color.White.copy(0.85f), modifier = Modifier.size(30.dp))
                    }
                }
                IconButton(
                    onClick = { seekBy(-SEEK_STEP_MS) },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(Icons.Default.Replay10, "Retroceder 10 segundos", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                IconButton(
                    onClick = { togglePlayPause() },
                    modifier = Modifier
                        .size(72.dp)
                        .background(Color.Black.copy(alpha = 0.45f), shape = androidx.compose.foundation.shape.CircleShape)
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (isPlaying) "Pausar" else "Reproduzir",
                        tint = Color.White, modifier = Modifier.size(40.dp)
                    )
                }
                IconButton(
                    onClick = { seekBy(SEEK_STEP_MS) },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(Icons.Default.Forward10, "Avançar 10 segundos", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                if (nextEpisode != null) {
                    IconButton(
                        onClick = { goToQueueItem(nextEpisode, queueIndex + 1) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.SkipNext, "Próximo episódio", tint = Color.White.copy(0.85f), modifier = Modifier.size(30.dp))
                    }
                }
            }
        }

        // ── Barra inferior: tempo assistido | barra de progresso | duração ─
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
                    onValueChangeFinished = {
                        isSeeking = false
                        playFrom(seekPreviewMs)
                    },
                    valueRange = 0f..durationMs.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = AccentPrimary,
                        activeTrackColor = AccentPrimary,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth().height(28.dp)
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatTime(displayMs), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(formatTime(durationMs), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                }
            }
        }

        // ── Botão voltar — aparece/desaparece com os controlos ───────────
        // Também visível com erro, para o utilizador não ficar preso.
        if (controlsVisible || hasError) {
            IconButton(
                onClick = {
                    if (resumeDecisionMade) {
                        scope.launch { VodPreferences.savePosition(context, streamUrl, currentMs) }
                    }
                    navController.navigateUp()
                },
                modifier = Modifier
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = TextLight)
            }
        }

        // ── Nome do canal + botão de áudio/legendas ───────────────────────
        if (!isBuffering && !hasError && controlsVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Botão de áudio e legendas — só aparece quando há faixas disponíveis
                if (audioTracks.isNotEmpty() || subtitleTracks.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            refreshTracks()
                            showTrackDialog = true
                            controlsVisible = true
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small)
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

        // ── Diálogo de selecção de Áudio e Legendas ──────────────────────
        if (showTrackDialog) {
            AlertDialog(
                onDismissRequest = { showTrackDialog = false },
                title = {
                    Text("Áudio e Legendas", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // ── Secção de Áudio ──────────────────────────────
                        if (audioTracks.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.AudioFile, null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
                                Text("Áudio", fontWeight = FontWeight.Bold, color = AccentPrimary, fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(4.dp))
                            audioTracks.forEach { track ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            selectedAudioId = track.id
                                            mediaPlayer.setAudioTrack(track.id)
                                        }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = selectedAudioId == track.id,
                                        onClick = {
                                            selectedAudioId = track.id
                                            mediaPlayer.setAudioTrack(track.id)
                                        },
                                        colors = RadioButtonDefaults.colors(selectedColor = AccentPrimary)
                                    )
                                    Text(track.name.ifBlank { "Faixa ${track.id}" }, fontSize = 14.sp)
                                }
                            }
                        }

                        // ── Secção de Legendas ───────────────────────────
                        if (subtitleTracks.isNotEmpty()) {
                            if (audioTracks.isNotEmpty()) Spacer(Modifier.height(12.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.Subtitles, null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
                                Text("Legendas", fontWeight = FontWeight.Bold, color = AccentPrimary, fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(4.dp))
                            // Opção "Desligado"
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        selectedSubtitleId = -1
                                        mediaPlayer.setSpuTrack(-1)
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedSubtitleId == -1,
                                    onClick = {
                                        selectedSubtitleId = -1
                                        mediaPlayer.setSpuTrack(-1)
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = AccentPrimary)
                                )
                                Text("Desligado", fontSize = 14.sp)
                            }
                            subtitleTracks.forEach { track ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            selectedSubtitleId = track.id
                                            mediaPlayer.setSpuTrack(track.id)
                                        }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = selectedSubtitleId == track.id,
                                        onClick = {
                                            selectedSubtitleId = track.id
                                            mediaPlayer.setSpuTrack(track.id)
                                        },
                                        colors = RadioButtonDefaults.colors(selectedColor = AccentPrimary)
                                    )
                                    Text(track.name.ifBlank { "Legenda ${track.id}" }, fontSize = 14.sp)
                                }
                            }
                        }

                        // Mensagem se não há faixas disponíveis ainda
                        if (audioTracks.isEmpty() && subtitleTracks.isEmpty()) {
                            Text(
                                "Nenhuma faixa de áudio ou legenda disponível neste stream.",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 13.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showTrackDialog = false }) {
                        Text("Fechar", color = AccentPrimary)
                    }
                },
                containerColor = Color(0xFF1C1C1E),
                titleContentColor = Color.White,
                textContentColor = Color.White
            )
        }
    }
}
