package com.velvetiptv.app.ui.screens.tv

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.util.VLCVideoLayout
import com.velvetiptv.app.data.ActivationApiClient
import com.velvetiptv.app.data.ChannelType
import com.velvetiptv.app.data.EPGRepository
import com.velvetiptv.app.data.IPTVPreferences
import com.velvetiptv.app.data.M3UChannel
import com.velvetiptv.app.data.M3URepository
import com.velvetiptv.app.data.ParentalCategoriesRequest
import com.velvetiptv.app.data.ParentalPreferences
import com.velvetiptv.app.data.VodPreferences
import com.velvetiptv.app.ui.navigation.Screen
import com.velvetiptv.app.ui.screens.activation.AlphaLogoMini
import com.velvetiptv.app.ui.screens.activation.getDeviceKey
import com.velvetiptv.app.ui.screens.activation.getMacAddress
import com.velvetiptv.app.ui.theme.AccentPrimary
import com.velvetiptv.app.ui.theme.DarkBackground
import com.velvetiptv.app.ui.theme.SurfaceDark
import com.velvetiptv.app.ui.theme.TextLight
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

sealed class LoadState {
    object Loading : LoadState()
    data class Success(val channels: List<M3UChannel>) : LoadState()
    data class Error(val message: String) : LoadState()
}

// Sobrevive à destruição do composable durante a navegação (Navigation Compose
// remove o TVScreen da composição ao entrar no PlayerScreen). Permite retomar
// sem spinner e na posição exacta do canal seleccionado.
private object TVSessionState {
    var channels:    List<M3UChannel> = emptyList()
    var selectedUrl: String           = ""
    var configKey:   String           = ""
    var epgUrl:      String           = ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TVScreen(navController: NavController? = null) {
    val context = LocalContext.current

    val m3uUrl       by IPTVPreferences.getM3UUrl(context).collectAsState(initial = "")
    val xtreamServer by IPTVPreferences.getXtreamServer(context).collectAsState(initial = "")
    val xtreamUser   by IPTVPreferences.getXtreamUser(context).collectAsState(initial = "")
    val xtreamPass   by IPTVPreferences.getXtreamPass(context).collectAsState(initial = "")
    val connType     by IPTVPreferences.getConnectionType(context).collectAsState(initial = "m3u")

    val isConfigured = m3uUrl.isNotBlank() || xtreamServer.isNotBlank()

    // Os valores acima vêm do DataStore (async) e começam vazios até à 1ª
    // emissão real — sem isto, "isConfigured" ficava false por um instante
    // mesmo quando já existe lista, mostrando "Nenhuma lista" antes de tentar
    // carregar. Só decide entre "configurado"/"não configurado" depois desse
    // primeiro valor real chegar.
    var configLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        IPTVPreferences.getActivePlaylist(context).first()
        configLoaded = true
    }

    var search          by remember { mutableStateOf("") }
    var loadState       by remember {
        mutableStateOf<LoadState>(
            if (TVSessionState.channels.isNotEmpty()) LoadState.Success(TVSessionState.channels)
            else                                      LoadState.Loading
        )
    }
    var selectedChannel by remember {
        mutableStateOf<M3UChannel?>(
            TVSessionState.channels.find { it.url == TVSessionState.selectedUrl }
        )
    }
    var retryKey        by remember { mutableStateOf(0) }
    // URL do EPG como estado Compose — permite LaunchedEffect reagir à mudança
    // e disparar o load mesmo quando o guard de cache faz return@LaunchedEffect.
    var epgUrl          by remember { mutableStateOf(TVSessionState.epgUrl) }

    // Carregar EPG sempre que o URL fica disponível (M3U ou Xtream auto-derivado).
    // Este LaunchedEffect é independente do carregamento de canais — corre mesmo
    // quando o guard de cache faz return@LaunchedEffect no bloco abaixo.
    LaunchedEffect(epgUrl) {
        if (epgUrl.isNotBlank()) EPGRepository.loadIfNeeded(epgUrl)
    }

    // Para ligações Xtream Codes o EPG está sempre em /xmltv.php — não vem no M3U.
    // Derivar o URL automaticamente assim que as credenciais estão disponíveis.
    //
    // Também cobre o caso comum de colar um link Xtream (get.php?username=...)
    // diretamente na aba "URL M3U" — aí connType fica "m3u" e as credenciais
    // Xtream nunca são preenchidas, por isso o EPG nunca era derivado e ficava
    // sempre "Sem dados de programação". Extraímos username/password/host do
    // próprio m3uUrl nesse caso.
    //
    // O delay dá prioridade ao url-tvg que possa vir no cabeçalho do M3U
    // (ver batch.epgUrl em baixo) — só deriva se continuar em branco depois disso.
    LaunchedEffect(connType, xtreamServer, xtreamUser, xtreamPass, m3uUrl) {
        if (epgUrl.isNotBlank()) return@LaunchedEffect
        delay(800)
        if (epgUrl.isNotBlank()) return@LaunchedEffect

        val auto = when {
            connType == "xtream" && xtreamServer.isNotBlank() ->
                "${xtreamServer.trimEnd('/')}/xmltv.php?username=$xtreamUser&password=$xtreamPass"

            m3uUrl.contains("username=") && m3uUrl.contains("password=") -> try {
                val uri  = android.net.Uri.parse(m3uUrl)
                val user = uri.getQueryParameter("username")
                val pass = uri.getQueryParameter("password")
                val port = uri.port.takeIf { it > 0 }?.let { ":$it" } ?: ""
                if (!user.isNullOrBlank() && !pass.isNullOrBlank() && uri.host != null)
                    "${uri.scheme}://${uri.host}$port/xmltv.php?username=$user&password=$pass"
                else null
            } catch (_: Exception) { null }

            else -> null
        }

        if (auto != null) {
            epgUrl = auto
            TVSessionState.epgUrl = auto
        }
    }

    // Modo ecrã inteiro — activa/desactiva sem navegar para outro ecrã.
    // O mesmo VLC instance do TVScreen é usado, sem criar um segundo player.
    // O BackHandler abaixo intercede o botão voltar quando fullscreen = true.
    var isFullscreen    by remember { mutableStateOf(false) }

    // Referência ao RecyclerView para foco inicial e scroll-to-selected
    var channelRV by remember { mutableStateOf<RecyclerView?>(null) }
    var initialFocusDone by remember { mutableStateOf(false) }

    // Scroll para o canal seleccionado ao voltar do ecrã inteiro.
    // rememberUpdatedState garante que o observer lê sempre os valores actuais,
    // mesmo que a lambda tenha sido capturada antes destes serem preenchidos.
    val currentChannelRV      = rememberUpdatedState(channelRV)
    val currentSelectedUrl    = rememberUpdatedState(selectedChannel?.url)
    val currentLoadState      = rememberUpdatedState(loadState)
    val lifecycleOwner        = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val rv       = currentChannelRV.value   ?: return@LifecycleEventObserver
                val url      = currentSelectedUrl.value ?: return@LifecycleEventObserver
                val channels = (currentLoadState.value as? LoadState.Success)?.channels
                               ?: return@LifecycleEventObserver
                val idx = channels.indexOfFirst { it.url == url }
                if (idx >= 0)
                    (rv.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(idx, 120)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── VLC — player primário para Live TV ───────────────────────────────────
    //
    // ExoPlayer é instável com trocas rápidas de canal em streams IPTV.
    // LibVLC (o mesmo que usa o Dream TV) é muito mais robusto:
    //   • Suporta HLS, MPEG-TS, RTSP, HTTP sem configuração especial
    //   • Recupera automaticamente de erros de rede
    //   • stop() + play() são thread-safe e não crasham com trocas rápidas
    val libVLC = remember {
        LibVLC(context, arrayListOf(
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--rtsp-tcp",
            "--network-caching=1500",
            "--live-caching=1500",
            "--audio-time-stretch"   // sincronização de áudio suave em streams live
        ))
    }
    val mediaPlayer = remember {
        org.videolan.libvlc.MediaPlayer(libVLC).also { it.volume = 100 }
    }

    var isBuffering by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf("") }

    // Scope e holder para debounce de 100ms em trocas rápidas com D-pad
    val tvScope      = rememberCoroutineScope()
    val switchHolder = remember { object { var job: kotlinx.coroutines.Job? = null } }

    // Listener de eventos VLC — entregues na main thread pelo LibVLC
    DisposableEffect(mediaPlayer) {
        val listener = org.videolan.libvlc.MediaPlayer.EventListener { event ->
            when (event.type) {
                org.videolan.libvlc.MediaPlayer.Event.Playing -> {
                    isBuffering = false
                    playerError = ""
                }
                org.videolan.libvlc.MediaPlayer.Event.Buffering -> {
                    // event.buffering: 0.0 → 100.0 (%)
                    isBuffering = event.buffering < 100f
                }
                org.videolan.libvlc.MediaPlayer.Event.EncounteredError -> {
                    isBuffering = false
                    playerError = "⚠️  Erro ao reproduzir, escolha outra opção do mesmo canal"
                }
                org.videolan.libvlc.MediaPlayer.Event.EndReached -> {
                    // Stream terminou (pouco comum em live TV) — não é erro
                    isBuffering = false
                }
            }
        }
        mediaPlayer.setEventListener(listener)
        onDispose {
            mediaPlayer.setEventListener(null)
            mediaPlayer.stop()
            mediaPlayer.detachViews()
            mediaPlayer.release()
            libVLC.release()
        }
    }

    // ── Trocar canal ──────────────────────────────────────────────────────────
    //
    // VLC é thread-safe e stop() é rápido/seguro — não crashaa como o ExoPlayer.
    // Mantemos um debounce de 100ms para evitar carga desnecessária quando o
    // utilizador navega muito rápido com o D-pad (cancela trocas intermédias).
    fun playChannel(ch: M3UChannel) {
        if (ch.url == selectedChannel?.url) return   // já está a reproduzir
        selectedChannel = ch    // UI reage imediatamente (nome, highlight)
        playerError = ""
        isBuffering = true

        switchHolder.job?.cancel()
        switchHolder.job = tvScope.launch {
            try {
                delay(100)   // debounce: ignora canais intermédios em navegação rápida
                mediaPlayer.stop()
                val media = Media(libVLC, android.net.Uri.parse(ch.url)).apply {
                    addOption(":network-caching=1500")
                    addOption(":live-caching=1500")
                }
                mediaPlayer.media = media
                media.release()   // libVLC fez a sua própria cópia — libertar a nossa
                mediaPlayer.play()
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Troca mais rápida que 100ms — a próxima já tomou conta → ok
            } catch (_: Exception) {
                if (selectedChannel?.url == ch.url) {
                    playerError = "⚠️  Erro ao reproduzir, escolha outra opção do mesmo canal"
                    isBuffering = false
                }
            }
        }
    }

    // Persistir o URL do canal seleccionado para restaurar após navegação.
    LaunchedEffect(selectedChannel?.url) {
        selectedChannel?.url?.let { TVSessionState.selectedUrl = it }
    }

    // ── Foco inicial quando a lista aparece ───────────────────────────────────
    LaunchedEffect(loadState) {
        if (loadState is LoadState.Success && !initialFocusDone) {
            delay(300)
            channelRV?.requestFocus()
            initialFocusDone = true
        }
    }

    // ── Carregar canais ────────────────────────────────────────────────────────
    //
    // Acumula canais em silêncio durante o carregamento da rede.
    // Actualiza o ecrã APENAS 2 vezes: ao mostrar a cache + no lote final.
    // Sem isto, 116+ recomposições do LazyColumn com 58K itens → ANR/OOM.
    LaunchedEffect(m3uUrl, xtreamServer, retryKey) {
        if (!isConfigured) return@LaunchedEffect
        val cfgKey = "$connType|$m3uUrl|$xtreamServer|$xtreamUser|$xtreamPass"
        // Se já há channels para esta configuração, não recarregar.
        // Cobre o retorno do PlayerScreen quando o composable foi recriado.
        if (loadState is LoadState.Success && cfgKey == TVSessionState.configKey) return@LaunchedEffect
        TVSessionState.configKey = cfgKey
        loadState = LoadState.Loading
        initialFocusDone = false
        try {
            val tvList   = mutableListOf<M3UChannel>()
            val seenUrls = mutableSetOf<String>()
            var cacheShown = false

            M3URepository.loadChannelsFlow(
                context      = context,
                m3uUrl       = m3uUrl,
                xtreamServer = xtreamServer,
                xtreamUser   = xtreamUser,
                xtreamPass   = xtreamPass,
                connType     = connType
            ).collect { batch ->

                if (batch.isNetworkReset) {
                    tvList.clear()
                    seenUrls.clear()
                }

                // Guardar URL do EPG logo que aparece (vem no 1.º batch com dados)
                if (batch.epgUrl.isNotBlank() && epgUrl != batch.epgUrl) {
                    epgUrl = batch.epgUrl          // dispara LaunchedEffect(epgUrl)
                    TVSessionState.epgUrl = batch.epgUrl
                }

                for (ch in batch.channels) {
                    if (ch.type == ChannelType.TV && ch.url.isNotBlank() && seenUrls.add(ch.url)) {
                        tvList.add(ch)
                    }
                }

                val showNow = (!cacheShown && !batch.isNetworkReset && tvList.isNotEmpty())
                           || batch.isFinalBatch

                if (showNow) {
                    val snap = tvList.toList()
                    if (selectedChannel == null && snap.isNotEmpty()) playChannel(snap.first())
                    loadState = LoadState.Success(snap)
                    if (batch.isFinalBatch) TVSessionState.channels = snap
                    else cacheShown = true
                }
            }

            if (tvList.isNotEmpty() && loadState !is LoadState.Success) {
                val snap = tvList.toList()
                if (selectedChannel == null) playChannel(snap.first())
                loadState = LoadState.Success(snap)
                TVSessionState.channels = snap
            }


        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (loadState is LoadState.Loading)
                loadState = LoadState.Error(e.message ?: "Erro ao carregar canais")
        }
    }

    val all = (loadState as? LoadState.Success)?.channels ?: emptyList()
    var filtered by remember { mutableStateOf<List<M3UChannel>>(emptyList()) }

    // Favoritos de TV — categoria especial no topo da lista
    val favTvList by VodPreferences.favorites(context, ChannelType.TV).collectAsState(initial = emptyList())

    // Navegação em duas camadas: primeiro as categorias (group-title do M3U,
    // ex.: "GLOBO CAPITAIS", "PREMIERE"), depois os canais dentro da categoria
    // escolhida. selectedCategory == null → mostra a lista de categorias.
    val categories = remember(all, favTvList) {
        val favUrls = favTvList.map { it.url }.toSet()
        val favChannels = all.filter { it.url in favUrls }
        val base = groupByCategory(all)
        if (favChannels.isEmpty()) base
        else listOf(ChannelCategory("⭐ Favoritos", favChannels, canLock = false)) + base
    }
    var selectedCategory by remember { mutableStateOf<String?>(null) }

    // Job explícito para filtro — substitui LaunchedEffect(search).
    // LaunchedEffect era cancelado por recomposições concorrentes (troca de canal,
    // eventos VLC) e deixava de responder a partir da 2.ª / 3.ª busca.
    // Com Job.cancel() + tvScope.launch() o controlo é totalmente manual e nunca
    // fica preso, independentemente do número de buscas.
    val searchHolder = remember { object { var job: kotlinx.coroutines.Job? = null } }

    fun triggerSearch(q: String) {
        searchHolder.job?.cancel()
        if (q.isBlank()) { filtered = emptyList(); return }
        searchHolder.job = tvScope.launch {
            delay(200)
            val snapshot = all   // captura a lista actual neste momento
            filtered = withContext(Dispatchers.Default) {
                snapshot.filter {
                    it.name.contains(q, ignoreCase = true) ||
                    it.group.contains(q, ignoreCase = true)
                }
            }
        }
    }

    // Canais da categoria seleccionada (camada 2). Nulo/ausente → lista de categorias.
    val categoryChannels = remember(categories, selectedCategory) {
        categories.firstOrNull { it.name == selectedCategory }?.channels ?: emptyList()
    }

    // Categorias bloqueadas por senha — usado para marcar o cadeado na lista
    // de categorias e para decidir se é preciso pedir a senha ao entrar.
    val lockedCategories by ParentalPreferences.lockedCategories(context).collectAsState(initial = emptySet())

    // Sincroniza a senha (hash) e as categorias bloqueadas com a dashboard — assim,
    // redefinir a senha ou desbloquear uma categoria por lá reflete aqui em poucos
    // segundos, mesmo com a pessoa já dentro do ecrã de TV ao Vivo.
    var parentalMac by remember { mutableStateOf("") }
    var parentalDeviceKey by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val mac = withContext(Dispatchers.IO) { getMacAddress() }
        val key = withContext(Dispatchers.IO) { getDeviceKey(context) }
        parentalMac = mac
        parentalDeviceKey = key
        while (true) {
            try {
                val remote = ActivationApiClient.api.syncParental(mac, key)
                ParentalPreferences.adoptServerState(context, remote.pinHash, remote.lockedCategories)
            } catch (_: Exception) {
                // Sem rede — mantém o estado local e tenta de novo no próximo ciclo.
            }
            delay(5000)
        }
    }

    // Linhas exibidas:
    //  - a pesquisar → lista plana de canais correspondentes em TODAS as categorias;
    //  - dentro de uma categoria → canais dessa categoria;
    //  - no topo → lista de categorias.
    val displayedRows: List<ChannelRow> = when {
        search.isNotBlank()        -> filtered.map { ChannelRow.Item(it) }
        selectedCategory != null   -> categoryChannels.map { ChannelRow.Item(it) }
        else                       -> categories.map {
            ChannelRow.Category(it.name, it.channels.size, locked = it.name.ifBlank { "Sem categoria" } in lockedCategories, canLock = it.canLock)
        }
    }

    // Ao sair do TVScreen, o LibVLC mantém o último frame na surface por mais
    // um instante do que o resto da UI (a surface nativa não desaparece no
    // mesmo frame que os composables Compose) — sem isto via-se um flash do
    // vídeo a ecrã inteiro entre o painel e o menu principal.
    // Este overlay preto cobre essa surface de imediato, antes de navegar.
    var isExiting by remember { mutableStateOf(false) }

    fun exitToMenu() {
        isExiting = true
        // Parar e desligar a surface do VLC já, em vez de esperar pelo onDispose
        // (que só corre quando o composable sai de composição — um frame tarde
        // de mais, o que deixava o último frame do vídeo visível a ecrã inteiro
        // por instante antes do menu principal aparecer).
        switchHolder.job?.cancel()
        mediaPlayer.stop()
        mediaPlayer.detachViews()
        navController?.navigateUp()
    }

    // Sair do ecrã inteiro com o botão voltar do sistema.
    // Intercede ANTES do NavController, por isso não sai do TVScreen.
    BackHandler(enabled = isFullscreen) { isFullscreen = false }

    // Dentro de uma categoria, o botão voltar do sistema sobe um nível
    // (volta à lista de categorias) em vez de saír do TVScreen.
    BackHandler(enabled = !isFullscreen && selectedCategory != null) { selectedCategory = null }

    // Na lista de categorias (topo), o botão voltar do sistema sai do TVScreen —
    // interceptado para também cobrir o vídeo antes da transição (ver isExiting acima).
    BackHandler(enabled = !isFullscreen && selectedCategory == null) { exitToMenu() }

    // ── Bloqueio por senha (categorias "adultos" ou outras que se queira
    // restringir) ────────────────────────────────────────────────────────────
    val hasParentalPin by ParentalPreferences.hasPin(context).collectAsState(initial = false)

    // Categorias já desbloqueadas nesta sessão — depois de introduzir a senha
    // uma vez para entrar numa categoria, não volta a pedir até saír da app
    // (continua bloqueada para a próxima pessoa que abrir o ecrã do início).
    var unlockedThisSession by remember { mutableStateOf(setOf<String>()) }

    fun categoryKeyOf(group: String) = group.ifBlank { "Sem categoria" }
    fun isCategoryLocked(group: String): Boolean {
        val key = categoryKeyOf(group)
        return key in lockedCategories && key !in unlockedThisSession
    }

    // Diálogo a pedir a senha — null quando escondido.
    var pinPromptCategory by remember { mutableStateOf<String?>(null) }
    var pinPromptAction   by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pinInput          by remember { mutableStateOf("") }
    var pinError          by remember { mutableStateOf(false) }
    var showNoPinDialog   by remember { mutableStateOf(false) }

    fun requestPin(category: String, onSuccess: () -> Unit) {
        if (!hasParentalPin) { showNoPinDialog = true; return }
        pinPromptCategory = category
        pinPromptAction   = {
            unlockedThisSession = unlockedThisSession + category
            onSuccess()
        }
        pinInput = ""
        pinError = false
    }

    // Clique simples → reproduzir canal.
    // Duplo clique → ecrã inteiro (mesmo VLC instance, sem navegar para outro ecrã).
    fun onChannelSingle(ch: M3UChannel) {
        if (isCategoryLocked(ch.group)) requestPin(categoryKeyOf(ch.group)) { playChannel(ch) }
        else playChannel(ch)
    }

    fun onChannelDouble(ch: M3UChannel) {
        fun openFullscreen() { playChannel(ch); isFullscreen = true }
        if (isCategoryLocked(ch.group)) requestPin(categoryKeyOf(ch.group)) { openFullscreen() }
        else openFullscreen()
    }

    // Clique numa categoria: entra nela e já reproduz o primeiro canal da lista,
    // mantendo a pessoa a poder escolher outro canal dentro da mesma categoria.
    fun enterCategory(cat: ChannelRow.Category) {
        selectedCategory = cat.name
        categories.firstOrNull { it.name == cat.name }?.channels?.firstOrNull()?.let { playChannel(it) }
    }

    fun onCategoryClick(cat: ChannelRow.Category) {
        if (cat.locked && cat.name !in unlockedThisSession) requestPin(cat.name) { enterCategory(cat) }
        else enterCategory(cat)
    }

    // Cadeado no cabeçalho da categoria: bloquear é livre (qualquer um pode
    // restringir mais), mas desbloquear exige a senha — senão o cadeado não
    // protegia nada (bastava tocar de novo para o tirar).
    fun pushLockedCategories(categories: Set<String>) {
        tvScope.launch {
            try {
                ActivationApiClient.api.pushParentalCategories(
                    ParentalCategoriesRequest(parentalMac, parentalDeviceKey, categories.toList())
                )
            } catch (_: Exception) { /* sincroniza no próximo ciclo de polling */ }
        }
    }

    fun onCategoryLockToggle(cat: ChannelRow.Category) {
        if (!hasParentalPin) { showNoPinDialog = true; return }
        if (!cat.locked) {
            tvScope.launch { pushLockedCategories(ParentalPreferences.setCategoryLocked(context, cat.name, true)) }
        } else {
            requestPin(cat.name) {
                tvScope.launch { pushLockedCategories(ParentalPreferences.setCategoryLocked(context, cat.name, false)) }
            }
        }
    }

    // Botão voltar do cabeçalho do painel: dentro de uma categoria sobe um nível;
    // na lista de categorias sai do TVScreen (comportamento original).
    fun onPanelBack() {
        if (selectedCategory != null) selectedCategory = null
        else exitToMenu()
    }

    // Scroll para o canal seleccionado quando o RecyclerView fica disponível.
    // Cobre o caso em que o TVScreen foi re-criado (Navigation Compose remove o
    // composable anterior da composição após a transição para PlayerScreen).
    // Se o canal pertence a outra categoria, entra nela primeiro para que a
    // linha exista na lista.
    LaunchedEffect(channelRV, all) {
        val rv = channelRV ?: return@LaunchedEffect
        delay(150)
        // Só rola se já estiver dentro de uma categoria — ao abrir TV ao Vivo
        // pela primeira vez, mostra sempre a lista de categorias no topo.
        val cat = selectedCategory ?: return@LaunchedEffect
        val url = selectedChannel?.url?.takeIf { it.isNotBlank() }
                  ?: TVSessionState.selectedUrl.takeIf { it.isNotBlank() }
                  ?: return@LaunchedEffect
        val rows = categories.firstOrNull { it.name == cat }
                   ?.channels?.map { ChannelRow.Item(it) } ?: return@LaunchedEffect
        val idx = rows.indexOfFirst { row -> row is ChannelRow.Item && row.channel.url == url }
        if (idx >= 0) (rv.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(idx, 120)
    }

    // ── UI ─────────────────────────────────────────────────────────────────────
    //
    // Em retrato num telemóvel, 240dp fixos para a lista espremiam demais o
    // preview+EPG ao lado — nesse caso empilha tudo (preview no topo, lista e
    // EPG lado a lado por baixo). Calculado aqui em cima porque também define
    // o tamanho exacto da superfície de vídeo, logo abaixo.
    val config = LocalConfiguration.current
    val isPortraitMobile = config.screenHeightDp > config.screenWidthDp
    val previewHeightFraction = 0.6f

    // O VLCVideoLayout vive como filho DIRECTO do root Box — nunca é recriado.
    // Mudar isFullscreen altera apenas os overlays acima dele, sem tocar na surface
    // do VLC. Recriar o VLCVideoLayout (ou chamá-lo em branches distintos) destruía
    // a surface → VLC crashava imediatamente ao entrar em ecrã inteiro.
    Box(Modifier.fillMaxSize().background(Color.Black)) {

        // Surface VLC — persistente, mas dimensionada para coincidir exactamente
        // com a área visível do preview em cada modo. O VLC centra o vídeo (com
        // letterbox) dentro dos LIMITES desta view — se ela for maior do que a
        // caixa de preview visível (ex.: fillMaxSize sempre), o vídeo acaba
        // centrado no ecrã inteiro em vez de dentro da caixa, sobrando espaço
        // vazio ou vazando para a lista/EPG por baixo.
        val videoModifier = when {
            isFullscreen     -> Modifier.fillMaxSize()
            isPortraitMobile -> Modifier.fillMaxWidth().fillMaxHeight(previewHeightFraction)
            else             -> Modifier
                .padding(start = 240.dp)
                .fillMaxWidth()
                .fillMaxHeight(previewHeightFraction)
        }
        AndroidView(
            factory = { ctx ->
                VLCVideoLayout(ctx).also { layout ->
                    mediaPlayer.attachViews(layout, null, false, false)
                }
            },
            modifier = videoModifier
        )

        when {
            !configLoaded -> LoadingMessage()
            !isConfigured -> NotConfiguredMessage(onBack = if (navController != null) ::exitToMenu else null)
            loadState is LoadState.Loading -> LoadingMessage()
            loadState is LoadState.Error -> ErrorMessage(
                (loadState as LoadState.Error).message,
                onRetry = { loadState = LoadState.Loading; selectedChannel = null; retryKey++ }
            )
            loadState is LoadState.Success -> {
                if (!isFullscreen) {
                    // ── Modo painel ───────────────────────────────────────────
                    // Em retrato num telemóvel (mais alto que largo), 240dp fixos
                    // para a lista espremiam demais o preview+EPG ao lado. Nesse
                    // caso empilha: preview no topo (largura toda), lista e EPG
                    // lado a lado por baixo. Em paisagem/tablet/TV mantém-se o
                    // layout original (lista à esquerda, preview+EPG à direita).
                    if (isPortraitMobile) {
                        Column(Modifier.fillMaxSize()) {
                            // Mesma fracção (previewHeightFraction) usada para dimensionar
                            // a superfície VLC acima — têm de coincidir, senão o vídeo não
                            // preenche esta caixa correctamente.
                            ChannelPreviewBox(
                                modifier        = Modifier.fillMaxWidth().weight(previewHeightFraction),
                                isBuffering     = isBuffering,
                                playerError     = playerError,
                                selectedChannel = selectedChannel,
                                onExpand        = { isFullscreen = true }
                            )
                            Row(Modifier.fillMaxWidth().weight(1f - previewHeightFraction)) {
                                ChannelPanel(
                                    modifier         = Modifier.weight(1f).fillMaxHeight(),
                                    rows             = displayedRows,
                                    totalChannels    = all.size,
                                    categoryName     = selectedCategory,
                                    selectedUrl      = selectedChannel?.url ?: "",
                                    search           = search,
                                    onSearchChange   = { search = it; triggerSearch(it) },
                                    onChannelClick   = ::onChannelSingle,
                                    onChannelDouble  = ::onChannelDouble,
                                    onChannelFocused = { ch -> if (!isCategoryLocked(ch.group)) playChannel(ch) },
                                    onChannelLongClick = { ch -> tvScope.launch { VodPreferences.toggleFavorite(context, ch) } },
                                    favoriteUrls     = favTvList.map { it.url }.toSet(),
                                    onCategoryClick  = ::onCategoryClick,
                                    onCategoryLockToggle = ::onCategoryLockToggle,
                                    onBack           = ::onPanelBack,
                                    showBack         = navController != null || selectedCategory != null,
                                    onRVReady        = { channelRV = it }
                                )
                                EPGGrid(
                                    modifier    = Modifier.weight(1f).fillMaxHeight(),
                                    tvgId       = selectedChannel?.tvgId ?: ""
                                )
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxSize()) {
                            // Lista — fundo opaco cobre o vídeo nesta zona
                            ChannelPanel(
                                modifier         = Modifier.width(240.dp).fillMaxHeight(),
                                rows             = displayedRows,
                                totalChannels    = all.size,
                                categoryName     = selectedCategory,
                                selectedUrl      = selectedChannel?.url ?: "",
                                search           = search,
                                onSearchChange   = { search = it; triggerSearch(it) },
                                onChannelClick   = ::onChannelSingle,
                                onChannelDouble  = ::onChannelDouble,
                                // Pré-visualização ao focar com D-pad — ignora canais de
                                // categorias bloqueadas em vez de pedir a senha só de passagem.
                                onChannelFocused = { ch -> if (!isCategoryLocked(ch.group)) playChannel(ch) },
                                onChannelLongClick = { ch -> tvScope.launch { VodPreferences.toggleFavorite(context, ch) } },
                                favoriteUrls     = favTvList.map { it.url }.toSet(),
                                onCategoryClick  = ::onCategoryClick,
                                onCategoryLockToggle = ::onCategoryLockToggle,
                                onBack           = ::onPanelBack,
                                showBack         = navController != null || selectedCategory != null,
                                onRVReady        = { channelRV = it }
                            )
                            Column(Modifier.weight(1f).fillMaxHeight()) {
                                ChannelPreviewBox(
                                    modifier        = Modifier.fillMaxWidth().weight(previewHeightFraction),
                                    isBuffering     = isBuffering,
                                    playerError     = playerError,
                                    selectedChannel = selectedChannel,
                                    onExpand        = { isFullscreen = true }
                                )
                                // EPG — fundo opaco cobre o vídeo nesta zona
                                EPGGrid(
                                    modifier    = Modifier.fillMaxWidth().weight(1f - previewHeightFraction),
                                    tvgId       = selectedChannel?.tvgId ?: ""
                                )
                            }
                        }
                    }
                } else {
                    // ── Ecrã inteiro — vídeo puro; toca no ecrã para ver canal/
                    // programa actual e os botões de canal anterior/seguinte ──
                    // Voltar: botão físico do controlo remoto (BackHandler acima).

                    // Lista usada para navegar entre canais — a categoria actual
                    // quando se está dentro de uma, senão a lista completa.
                    val navList = if (selectedCategory != null) categoryChannels else all
                    val currentIndex = remember(navList, selectedChannel?.url) {
                        navList.indexOfFirst { it.url == selectedChannel?.url }
                    }
                    // Circular — chegar ao fim volta ao início (e vice-versa), para
                    // nunca ficar "preso" sem conseguir avançar/recuar mais.
                    val prevChannel = currentIndex.takeIf { it >= 0 && navList.isNotEmpty() }
                        ?.let { navList[(it - 1 + navList.size) % navList.size] }
                    val nextChannel = currentIndex.takeIf { it >= 0 && navList.isNotEmpty() }
                        ?.let { navList[(it + 1) % navList.size] }

                    // Pede a senha antes de saltar para um canal de categoria bloqueada.
                    fun goToChannel(ch: M3UChannel) {
                        if (isCategoryLocked(ch.group)) requestPin(categoryKeyOf(ch.group)) { playChannel(ch) }
                        else playChannel(ch)
                    }

                    var fsControlsVisible by remember { mutableStateOf(false) }
                    // Com erro, o rodapé fica visível sempre — sem depender de tocar
                    // no ecrã nem de nenhum efeito assíncrono. É a única forma de
                    // escolher outro canal quando o actual não reproduz.
                    val showFooter = fsControlsVisible || playerError.isNotEmpty()

                    LaunchedEffect(fsControlsVisible, playerError) {
                        // Não esconder sozinho enquanto houver erro.
                        if (fsControlsVisible && playerError.isEmpty()) {
                            delay(4000)
                            fsControlsVisible = false
                        }
                    }
                    val fsInteractionSource = remember { MutableInteractionSource() }

                    // Camada de toque — cobre todo o ecrã para alternar os controlos.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = fsInteractionSource,
                                indication = null,
                                onClick = { fsControlsVisible = !fsControlsVisible }
                            )
                    )

                    if (isBuffering && playerError.isEmpty()) {
                        Box(Modifier.fillMaxSize().background(Color.Black),
                            contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                AlphaLogoMini(size = 84.dp)
                                Spacer(Modifier.height(18.dp))
                                CircularProgressIndicator(color = AccentPrimary)
                                Spacer(Modifier.height(10.dp))
                                Text("A ligar ao stream...", color = Color.White.copy(0.8f), fontSize = 14.sp)
                            }
                        }
                    }
                    if (playerError.isNotEmpty()) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(0.9f)),
                            contentAlignment = Alignment.Center) {
                            Text(playerError, color = Color.White, fontSize = 14.sp,
                                textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
                        }
                    }

                    // ── Botão voltar (top-left) — sai do ecrã inteiro ──────────────
                    // Visível nas mesmas condições que o rodapé (ao tocar ou com erro),
                    // pra não depender do botão físico do controlo remoto em
                    // smartphones onde não há botão físico de retorno garantido.
                    if (showFooter) {
                        IconButton(
                            onClick = { isFullscreen = false },
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                                .size(44.dp)
                        ) {
                            Icon(
                                Icons.Default.ArrowBack,
                                contentDescription = "Sair do ecrã inteiro",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    // ── Rodapé: nome do canal + programa actual + anterior/seguinte ──
                    // Aparece também com erro (não só ao tocar) — é a única forma de
                    // saír de um canal que não reproduz sem voltar ao painel.
                    if (showFooter && !isBuffering) {
                        val tvgId = selectedChannel?.tvgId ?: ""
                        val programs = remember(tvgId) { EPGRepository.getProgramsFor(tvgId) }
                        val nowMs = System.currentTimeMillis()
                        val currentProgram = remember(programs) {
                            programs.firstOrNull { nowMs in it.startMs..it.stopMs }
                        }

                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                                    )
                                )
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            IconButton(
                                onClick = { prevChannel?.let { goToChannel(it) } },
                                enabled = prevChannel != null,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    Icons.Default.SkipPrevious, "Canal anterior",
                                    tint = if (prevChannel != null) Color.White else Color.White.copy(alpha = 0.3f),
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    selectedChannel?.name ?: "",
                                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    currentProgram?.title ?: "Sem informação de programação",
                                    color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { nextChannel?.let { goToChannel(it) } },
                                enabled = nextChannel != null,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    Icons.Default.SkipNext, "Próximo canal",
                                    tint = if (nextChannel != null) Color.White else Color.White.copy(alpha = 0.3f),
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Cobre tudo (vídeo incluído) durante a transição de saída — ver isExiting acima.
        if (isExiting) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }
    }

    // ── Diálogo a pedir a senha — para ver canais ou desbloquear uma categoria ──
    pinPromptCategory?.let { categoryName ->
        AlertDialog(
            onDismissRequest = { pinPromptCategory = null },
            title = { Text("Categoria bloqueada") },
            text = {
                Column {
                    Text("\"$categoryName\" está protegida por senha.")
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { pinInput = it; pinError = false },
                        label = { Text("Senha") },
                        singleLine = true,
                        isError = pinError,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    if (pinError) {
                        Spacer(Modifier.height(4.dp))
                        Text("Senha incorrecta", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    tvScope.launch {
                        if (ParentalPreferences.verifyPin(context, pinInput)) {
                            val action = pinPromptAction
                            pinPromptCategory = null
                            pinPromptAction = null
                            action?.invoke()
                        } else {
                            pinError = true
                        }
                    }
                }) { Text("Entrar") }
            },
            dismissButton = {
                TextButton(onClick = { pinPromptCategory = null }) { Text("Cancelar") }
            }
        )
    }

    // ── Sem senha definida ainda — tentou bloquear/desbloquear sem ter uma ──
    if (showNoPinDialog) {
        AlertDialog(
            onDismissRequest = { showNoPinDialog = false },
            title = { Text("Defina uma senha primeiro") },
            text = { Text("Para bloquear canais por senha, configure uma senha em Configurações.") },
            confirmButton = {
                TextButton(onClick = {
                    showNoPinDialog = false
                    navController?.navigate(Screen.Settings.route)
                }) { Text("Ir a Configurações") }
            },
            dismissButton = {
                TextButton(onClick = { showNoPinDialog = false }) { Text("Fechar") }
            }
        )
    }
}

// ─── Box de preview (vídeo) com overlay de status/nome do canal ───────────────
// Duplo toque em qualquer ponto (incluindo sobre o nome do canal) expande para
// ecrã inteiro — alternativa ao botão de expandir, mais rápida no toque.
@Composable
private fun ChannelPreviewBox(
    modifier: Modifier,
    isBuffering: Boolean,
    playerError: String,
    selectedChannel: M3UChannel?,
    onExpand: () -> Unit
) {
    Box(
        modifier.pointerInput(Unit) {
            detectTapGestures(onDoubleTap = { onExpand() })
        }
    ) {
        if (isBuffering && playerError.isEmpty()) {
            // Tela de pré-apresentação — mesmo logo do menu principal,
            // em vez de ecrã preto vazio enquanto se escolhe o canal.
            Box(Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AlphaLogoMini(size = 56.dp)
                    Spacer(Modifier.height(14.dp))
                    CircularProgressIndicator(color = AccentPrimary, modifier = Modifier.size(28.dp))
                }
            }
        }
        if (playerError.isNotEmpty()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.9f)),
                contentAlignment = Alignment.Center) {
                Text(playerError, color = Color.White, fontSize = 13.sp,
                    textAlign = TextAlign.Center, lineHeight = 20.sp,
                    modifier = Modifier.padding(16.dp))
            }
        }
        if (selectedChannel != null && !isBuffering && playerError.isEmpty()) {
            Text(
                selectedChannel.name,
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopStart).padding(8.dp)
                    .background(Color.Black.copy(0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
        IconButton(
            onClick = onExpand,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(36.dp)
        ) {
            Icon(Icons.Default.Fullscreen, "Ecrã inteiro",
                tint = Color.White.copy(0.7f), modifier = Modifier.size(20.dp))
        }
    }
}

// ─── Painel de canais (RecyclerView — abordagem Dream TV) ─────────────────────
//
// Substituído LazyColumn por RecyclerView por três razões:
//  1. RecyclerView recicla views nativas sem overhead de recomposição Compose
//  2. ListAdapter + DiffUtil calcula diferenças em thread de fundo
//  3. Bitmaps de logos descartados em onViewRecycled → sem acumulação OOM
@Composable
private fun ChannelPanel(
    modifier         : Modifier = Modifier.width(240.dp).fillMaxHeight(),
    rows             : List<ChannelRow>,
    totalChannels    : Int,
    categoryName     : String?,
    selectedUrl      : String,
    search           : String,
    onSearchChange   : (String) -> Unit,
    onChannelClick   : (M3UChannel) -> Unit,
    onChannelDouble  : (M3UChannel) -> Unit,
    onChannelFocused : (M3UChannel) -> Unit,
    onChannelLongClick: (M3UChannel) -> Unit = {},
    favoriteUrls     : Set<String> = emptySet(),
    onCategoryClick  : (ChannelRow.Category) -> Unit,
    onCategoryLockToggle: (ChannelRow.Category) -> Unit,
    onBack           : () -> Unit,
    showBack         : Boolean,
    onRVReady        : (RecyclerView) -> Unit
) {
    val adapter = remember {
        ChannelAdapter(
            onChannelClick       = onChannelClick,
            onChannelDouble      = onChannelDouble,
            onChannelFocus       = onChannelFocused,
            onChannelLongClick   = onChannelLongClick,
            onCategoryClick      = onCategoryClick,
            onCategoryLockToggle = onCategoryLockToggle
        )
    }

    // Submeter lista ao adapter (DiffUtil corre em thread de fundo)
    LaunchedEffect(rows) { adapter.submitList(rows) }

    // Actualizar canal seleccionado sem rebind completo (payload parcial)
    LaunchedEffect(selectedUrl) { adapter.selectedUrl = selectedUrl }

    // Actualizar estrelas sem rebind completo
    LaunchedEffect(favoriteUrls) { adapter.favoriteUrls = favoriteUrls }

    Column(modifier.background(Color(0xFF0d0d1a))) {

        // Cabeçalho (Compose — estático, sem impacto de performance)
        Column(
            Modifier.background(SurfaceDark).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showBack) {
                    IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ArrowBack, null, tint = TextLight, modifier = Modifier.size(18.dp))
                    }
                }
                Text(
                    categoryName ?: "TV ao Vivo", color = TextLight, fontWeight = FontWeight.Bold,
                    fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${if (categoryName != null) rows.size else totalChannels}",
                    color = AccentPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(DarkBackground)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, null, tint = TextLight.copy(0.4f), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                BasicTextField(
                    value = search, onValueChange = onSearchChange, singleLine = true,
                    textStyle = TextStyle(color = TextLight, fontSize = 13.sp),
                    cursorBrush = SolidColor(AccentPrimary),
                    decorationBox = { inner ->
                        if (search.isEmpty()) Text("Pesquisar...", color = TextLight.copy(0.3f), fontSize = 13.sp)
                        inner()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Lista de canais — RecyclerView nativo (estável com 58K+ itens)
        // weight(1f) em vez de fillMaxSize() — garante que o RV ocupa apenas
        // o espaço restante após o cabeçalho/busca, sem sobrepor os elementos acima.
        AndroidView(
            factory = { ctx ->
                RecyclerView(ctx).apply {
                    layoutManager = LinearLayoutManager(ctx)
                    this.adapter  = adapter
                    setHasFixedSize(false)
                    itemAnimator  = null
                    setBackgroundColor(0xFF0d0d1a.toInt())
                    setItemViewCacheSize(20)
                    clipToPadding = true
                    clipChildren  = true
                    // Sem isto, o efeito "stretch" do overscroll (Android 12+) desenha
                    // o primeiro item por cima da pesquisa ao puxar a lista para baixo.
                    overScrollMode = View.OVER_SCROLL_NEVER
                }.also { onRVReady(it) }
            },
            modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds()
        )
    }
}


// ─── EPG ─────────────────────────────────────────────────────────────────────
@Composable
fun EPGGrid(modifier: Modifier = Modifier, tvgId: String = "") {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val now     = System.currentTimeMillis()

    // Recompor quando o canal muda ou a cada 30 s (para avançar o destaque)
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tvgId) {
        while (true) { delay(30_000); tick++ }
    }

    val programs = remember(tvgId, tick) { EPGRepository.getProgramsFor(tvgId) }

    val rows: List<Triple<String, String, Boolean>> = remember(programs, tick) {
        programs.map { p ->
            Triple(timeFmt.format(p.startMs), p.title, now in p.startMs..p.stopMs)
        }
    }

    // Índice do programa actual para scroll inicial
    val currentIdx = rows.indexOfFirst { it.third }.coerceAtLeast(0)
    val listState  = rememberLazyListState()
    LaunchedEffect(tvgId, currentIdx) {
        if (currentIdx > 0) listState.scrollToItem((currentIdx - 1).coerceAtLeast(0))
    }

    Column(modifier.background(Color(0xFF0a0a18))) {
        Row(
            Modifier.fillMaxWidth().background(SurfaceDark).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Programação", color = TextLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(timeFmt.format(now), color = AccentPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (EPGRepository.isLoading) "A carregar programação..."
                    else "Sem dados de programação",
                    color = TextLight.copy(0.35f), fontSize = 12.sp
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                itemsIndexed(rows, key = { i, _ -> i }) { _, (time, program, isCurrent) ->
                    EpgRow(time, program, isCurrent)
                }
            }
        }
    }
}

@Composable
fun EpgRow(time: String, program: String, isCurrent: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (isCurrent) AccentPrimary.copy(0.12f) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            time,
            color = if (isCurrent) AccentPrimary else TextLight.copy(0.4f),
            fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(40.dp)
        )
        if (isCurrent) Box(Modifier.size(5.dp).background(AccentPrimary, RoundedCornerShape(3.dp)))
        else Spacer(Modifier.width(5.dp))
        Text(
            program, fontSize = 12.sp, modifier = Modifier.weight(1f),
            color = if (isCurrent) Color.White else TextLight.copy(0.65f),
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        if (isCurrent) Text(
            "AO VIVO", color = AccentPrimary, fontSize = 9.sp,
            fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp
        )
    }
    @Suppress("DEPRECATION")
    Divider(color = Color.White.copy(0.04f), thickness = 0.5.dp)
}


// ─── Estados ──────────────────────────────────────────────────────────────────
@Composable
fun LoadingMessage(modifier: Modifier = Modifier, message: String = "A carregar...") {
    Box(modifier.fillMaxSize().background(DarkBackground), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(color = AccentPrimary)
            Text(message, color = TextLight.copy(0.6f))
        }
    }
}

@Composable
fun ErrorMessage(message: String, modifier: Modifier = Modifier, onRetry: () -> Unit = {}) {
    Box(modifier.fillMaxSize().background(DarkBackground), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text("❌", fontSize = 48.sp)
            Text("Erro ao carregar canais", color = TextLight, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(message, color = TextLight.copy(0.5f), fontSize = 12.sp, textAlign = TextAlign.Center)
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)) {
                Text("Tentar novamente")
            }
        }
    }
}

@Composable
fun NotConfiguredMessage(modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    Box(modifier.fillMaxSize().background(DarkBackground), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.LiveTv, null, modifier = Modifier.size(72.dp), tint = AccentPrimary.copy(0.5f))
            Spacer(Modifier.height(16.dp))
            Text("Nenhuma lista configurada", style = MaterialTheme.typography.titleMedium, color = TextLight)
            Spacer(Modifier.height(8.dp))
            Text(
                "Vá a Configurações e adicione\numa lista M3U ou Xtream Codes",
                style = MaterialTheme.typography.bodyMedium,
                color = TextLight.copy(0.6f),
                textAlign = TextAlign.Center
            )
        }
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = TextLight)
            }
        }
    }
}
