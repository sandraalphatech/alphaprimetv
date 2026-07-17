package com.velvetiptv.app.ui.screens.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.velvetiptv.app.data.ChannelType
import com.velvetiptv.app.data.IPTVPreferences
import com.velvetiptv.app.data.M3UChannel
import com.velvetiptv.app.data.M3URepository
import com.velvetiptv.app.data.VodPreferences
import com.velvetiptv.app.data.XtreamApi
import com.velvetiptv.app.ui.navigation.Screen
import com.velvetiptv.app.ui.screens.tv.ErrorMessage
import com.velvetiptv.app.ui.screens.tv.LoadingMessage
import com.velvetiptv.app.ui.screens.tv.NotConfiguredMessage
import com.velvetiptv.app.ui.theme.AccentPrimary
import com.velvetiptv.app.ui.theme.DarkBackground
import com.velvetiptv.app.ui.theme.SurfaceDark
import com.velvetiptv.app.ui.theme.TextLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Nomes das categorias especiais (não vêm do M3U/Xtream — são geridas localmente).
private const val CATEGORY_CONTINUE_WATCHING = "Continuar a Assistir"
private const val CATEGORY_FAVORITES         = "Favoritos"

// Marca os M3UChannel sintéticos que representam uma SÉRIE (não um episódio
// reproduzível directamente) — o "url" guarda o series_id da API Xtream em vez
// de um link de stream. Ao clicar, navega-se para a lista de episódios.
private const val SERIES_MARKER_PREFIX = "xtseries:"

// Remove o prefixo redundante "FILMES:"/"SÉRIES:" do nome da categoria — já se
// sabe pelo ecrã em que se está, não é preciso repetir em cada linha.
private val CATEGORY_PREFIX_REGEX = Regex("""^(FILMES|S[ÉE]RIES)\s*:\s*""", RegexOption.IGNORE_CASE)
private fun displayCategoryName(name: String): String =
    if (name == CATEGORY_CONTINUE_WATCHING || name == CATEGORY_FAVORITES) name
    else CATEGORY_PREFIX_REGEX.replace(name, "").ifBlank { name }

private sealed class VodLoadState {
    object Loading : VodLoadState()
    data class Success(val groups: List<Pair<String, List<M3UChannel>>>) : VodLoadState()
    data class Error(val message: String) : VodLoadState()
}

// Sobrevive à destruição do composable durante a navegação, tal como TVSessionState.
private class VodSessionState {
    var groups:          List<Pair<String, List<M3UChannel>>> = emptyList()
    var configKey:       String = ""
    var selectedCategory: String? = null
}
private val moviesSession = VodSessionState()
private val seriesSession = VodSessionState()

// ── Ecrã de Filmes/Séries — categorias numa lista lateral (com contagem) e ────
// grid de posters à direita para a categoria seleccionada. Mesma navegação em
// duas camadas usada em TV ao Vivo (ChannelGrouping/groupByCategory).
@Composable
fun VodGridScreen(
    navController: NavController?,
    type: ChannelType,
    title: String
) {
    val context = LocalContext.current
    val session = if (type == ChannelType.MOVIE) moviesSession else seriesSession

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

    var loadState by remember {
        mutableStateOf<VodLoadState>(
            if (session.groups.isNotEmpty()) VodLoadState.Success(session.groups)
            else VodLoadState.Loading
        )
    }
    var retryKey by remember { mutableStateOf(0) }
    var search   by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(session.selectedCategory) }

    LaunchedEffect(m3uUrl, xtreamServer, retryKey) {
        if (!isConfigured) return@LaunchedEffect
        val cfgKey = "$connType|$m3uUrl|$xtreamServer|$xtreamUser|$xtreamPass"
        if (loadState is VodLoadState.Success && cfgKey == session.configKey) return@LaunchedEffect
        session.configKey = cfgKey
        loadState = VodLoadState.Loading

        try {
            // Para Filmes e Séries, tenta primeiro a API Xtream real (player_api.php)
            // — devolve um "stream_icon"/"cover" próprio do painel, que (ao contrário
            // do tvg-logo genérico do M3U) normalmente não está sujeito ao geo-bloqueio
            // do CDN de logos partilhado entre fornecedores. Para Séries, devolve um
            // item por SÉRIE (não por episódio) — os episódios só se carregam ao abrir.
            // Se falhar ou vier vazio, cai para o M3U (episódios individuais).
            val xtreamResult = when (type) {
                ChannelType.MOVIE  -> tryLoadMoviesFromXtreamApi(connType, m3uUrl, xtreamServer, xtreamUser, xtreamPass)
                ChannelType.SERIES -> tryLoadSeriesFromXtreamApi(connType, m3uUrl, xtreamServer, xtreamUser, xtreamPass)
                else -> null
            }

            val collected: List<M3UChannel>
            if (xtreamResult != null && xtreamResult.isNotEmpty()) {
                collected = xtreamResult
            } else {
                val fromM3u = mutableListOf<M3UChannel>()
                val seenUrls = mutableSetOf<String>()

                M3URepository.loadChannelsFlow(
                    context      = context,
                    m3uUrl       = m3uUrl,
                    xtreamServer = xtreamServer,
                    xtreamUser   = xtreamUser,
                    xtreamPass   = xtreamPass,
                    connType     = connType
                ).collect { batch ->
                    if (batch.isNetworkReset) {
                        fromM3u.clear()
                        seenUrls.clear()
                    }
                    for (ch in batch.channels) {
                        if (ch.type == type && ch.url.isNotBlank() && seenUrls.add(ch.url)) {
                            fromM3u.add(ch)
                        }
                    }
                }
                collected = fromM3u
            }

            val grouped = withContext(Dispatchers.Default) {
                collected.groupBy { it.group.ifBlank { "Outros" } }
                    .toList()
                    .sortedBy { it.first.lowercase() }
            }
            session.groups = grouped
            loadState = VodLoadState.Success(grouped)

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadState = VodLoadState.Error(e.message ?: "Erro ao carregar conteúdo")
        }
    }

    Box(Modifier.fillMaxSize().background(DarkBackground)) {
        when {
            !configLoaded -> LoadingMessage(message = if (type == ChannelType.SERIES) "A carregar séries..." else "A carregar filmes...")
            !isConfigured -> NotConfiguredMessage(onBack = if (navController != null) { { navController.navigateUp() } } else null)
            loadState is VodLoadState.Loading -> LoadingMessage(message = if (type == ChannelType.SERIES) "A carregar séries..." else "A carregar filmes...")
            loadState is VodLoadState.Error -> ErrorMessage(
                (loadState as VodLoadState.Error).message,
                onRetry = { loadState = VodLoadState.Loading; retryKey++ }
            )
            loadState is VodLoadState.Success -> {
                val groups = (loadState as VodLoadState.Success).groups
                if (groups.isEmpty()) {
                    EmptyVodMessage(type)
                } else {
                    val scope = rememberCoroutineScope()
                    val favorites by VodPreferences.favorites(context, type)
                        .collectAsState(initial = emptyList())
                    val continueWatching by VodPreferences.continueWatching(context, type)
                        .collectAsState(initial = emptyList())
                    val favoriteUrls = favorites.map { it.url }.toSet()

                    // "Continuar a Assistir" e "Favoritos" aparecem primeiro quando têm
                    // conteúdo — geridos localmente, não vêm do M3U/Xtream. Sem remember:
                    // a lista (groups) é grande mas estável entre recomposições normais,
                    // e isto evita qualquer risco de ficar agarrado a um valor antigo de
                    // favorites/continueWatching quando só essas duas mudam.
                    // Para séries, "Continuar a Assistir" armazena episódios individuais,
                    // mas mostramos apenas 1 card por série (o mais recente), usando o
                    // campo "group" como nome da série e "logo" como seriesId para navegação.
                    val continueWatchingDisplay = if (type == ChannelType.SERIES) {
                        val seen = mutableSetOf<String>()
                        continueWatching.mapNotNull { ch ->
                            val key = ch.group.ifBlank { ch.url }
                            if (seen.add(key)) ch.copy(name = ch.group.ifBlank { ch.name.substringBefore(" · ") })
                            else null
                        }
                    } else continueWatching

                    val allGroups = buildList {
                        if (continueWatchingDisplay.isNotEmpty()) add(CATEGORY_CONTINUE_WATCHING to continueWatchingDisplay)
                        if (favorites.isNotEmpty()) add(CATEGORY_FAVORITES to favorites)
                        addAll(groups)
                    }

                    // Selecciona a 1.ª categoria por omissão quando ainda não há nenhuma.
                    LaunchedEffect(allGroups.map { it.first }) {
                        if (selectedCategory == null || allGroups.none { it.first == selectedCategory }) {
                            selectedCategory = allGroups.first().first
                            session.selectedCategory = selectedCategory
                        }
                    }

                    val totalCount = groups.sumOf { it.second.size }
                    val categoryItems = allGroups.firstOrNull { it.first == selectedCategory }?.second ?: emptyList()

                    // Pesquisa é global (todas as categorias deste tipo), substitui a grelha normal.
                    val searchResults = remember(groups, search) {
                        if (search.isBlank()) null
                        else groups.flatMap { it.second }.filter { it.name.contains(search, ignoreCase = true) }
                    }

                    val gridTitle = if (search.isNotBlank()) "Resultados" else displayCategoryName(selectedCategory ?: "")
                    val gridItems = searchResults ?: categoryItems

                    // Estado da grelha recriado a cada categoria/pesquisa nova — sem
                    // isto o scroll da categoria anterior mantinha-se ao trocar,
                    // abrindo a categoria nova "a meio" ou "no fim".
                    val gridState = remember(selectedCategory, search) { LazyGridState() }

                    fun onItemClick(ch: M3UChannel) {
                        if (ch.url.startsWith(SERIES_MARKER_PREFIX)) {
                            // Catálogo de séries — abre a lista de episódios
                            val seriesId = ch.url.removePrefix(SERIES_MARKER_PREFIX)
                            navController?.navigate(Screen.SeriesEpisodes.createRoute(seriesId, ch.name))
                        } else if (type == ChannelType.SERIES && ch.logo.isNotBlank()) {
                            // "Continuar a Assistir" de uma série — o campo logo guarda o seriesId.
                            // Navega PRIMEIRO para a lista de episódios (fica no back stack), depois
                            // para o player: o botão "voltar" do player leva à lista atualizada.
                            // Como as animações estão desligadas, a transição é instantânea.
                            navController?.navigate(Screen.SeriesEpisodes.createRoute(ch.logo, ch.group.ifBlank { ch.name }))
                            navController?.navigate(Screen.Player.createRoute(ch.url, ch.name))
                        } else {
                            scope.launch { VodPreferences.addToContinueWatching(context, ch) }
                            navController?.navigate(Screen.Player.createRoute(ch.url, ch.name))
                        }
                    }
                    fun onToggleFavoriteClick(ch: M3UChannel) {
                        scope.launch { VodPreferences.toggleFavorite(context, ch) }
                    }

                    // Em retrato num telemóvel (mais alto que largo), 260dp fixos para a
                    // lista lateral espremiam demais a grelha de pôsteres. Nesse caso
                    // empilha: grelha em cima, lista embaixo, meio a meio. Em paisagem/
                    // tablet/TV mantém-se o layout original (lista à esquerda, grelha à
                    // direita).
                    val config = LocalConfiguration.current
                    val isPortraitMobile = config.screenHeightDp > config.screenWidthDp

                    if (isPortraitMobile) {
                        Column(Modifier.fillMaxSize()) {
                            ContentGrid(
                                modifier         = Modifier.fillMaxWidth().weight(1f),
                                gridTitle        = gridTitle,
                                gridItems        = gridItems,
                                gridState        = gridState,
                                favoriteUrls     = favoriteUrls,
                                onItemClick      = ::onItemClick,
                                onToggleFavorite = ::onToggleFavoriteClick
                            )
                            CategorySidebar(
                                modifier         = Modifier.fillMaxWidth().weight(1f),
                                title            = title,
                                totalCount       = totalCount,
                                categories       = allGroups,
                                selectedCategory = selectedCategory,
                                search           = search,
                                onSearchChange   = { search = it },
                                onCategoryClick  = { name -> selectedCategory = name; session.selectedCategory = name },
                                onBack           = { navController?.navigateUp() },
                                showBack         = navController != null
                            )
                        }
                    } else {
                        Row(Modifier.fillMaxSize()) {
                            CategorySidebar(
                                modifier         = Modifier.width(260.dp).fillMaxHeight(),
                                title            = title,
                                totalCount       = totalCount,
                                categories       = allGroups,
                                selectedCategory = selectedCategory,
                                search           = search,
                                onSearchChange   = { search = it },
                                onCategoryClick  = { name -> selectedCategory = name; session.selectedCategory = name },
                                onBack           = { navController?.navigateUp() },
                                showBack         = navController != null
                            )
                            ContentGrid(
                                modifier         = Modifier.weight(1f).fillMaxHeight(),
                                gridTitle        = gridTitle,
                                gridItems        = gridItems,
                                gridState        = gridState,
                                favoriteUrls     = favoriteUrls,
                                onItemClick      = ::onItemClick,
                                onToggleFavorite = ::onToggleFavoriteClick
                            )
                        }
                    }
                }
            }
        }
    }
}

// Grelha de pôsteres da categoria/pesquisa actual — extraída para ser reutilizada
// tanto no layout em coluna (retrato/mobile) como em linha (paisagem/tablet/TV).
@Composable
private fun ContentGrid(
    modifier         : Modifier,
    gridTitle        : String,
    gridItems        : List<M3UChannel>,
    gridState        : LazyGridState,
    favoriteUrls     : Set<String>,
    onItemClick      : (M3UChannel) -> Unit,
    onToggleFavorite : (M3UChannel) -> Unit
) {
    Column(modifier) {
        Text(
            gridTitle,
            color = TextLight,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp)
        )
        if (gridItems.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nenhum resultado encontrado", color = TextLight.copy(0.4f))
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(130.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(gridItems, key = { it.url }) { ch ->
                    // GridCells.Adaptive estica cada coluna para preencher o espaço
                    // disponível, mas o PosterCard tem largura fixa (130dp) — sem
                    // centralizar aqui, o card fica "colado" à esquerda da coluna
                    // (mais larga), sobrando espaço vazio do lado direito.
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        PosterCard(
                            channel    = ch,
                            isFavorite = ch.url in favoriteUrls,
                            onClick    = { onItemClick(ch) },
                            onToggleFavorite = { onToggleFavorite(ch) }
                        )
                    }
                }
            }
        }
    }
}

// Tenta carregar os filmes pela API Xtream real (player_api.php). Devolve null
// se não for uma ligação Xtream (sem credenciais derivadas de nenhuma forma) ou
// se o pedido falhar — nesses casos o chamador cai de volta para o M3U.
private suspend fun tryLoadMoviesFromXtreamApi(
    connType: String, m3uUrl: String, xtreamServer: String, xtreamUser: String, xtreamPass: String
): List<M3UChannel>? {
    val creds = XtreamApi.resolveCreds(connType, m3uUrl, xtreamServer, xtreamUser, xtreamPass) ?: return null

    return try {
        val (streams, categories) = coroutineScope {
            val s = async { XtreamApi.getVodStreams(creds) }
            val c = async { XtreamApi.getVodCategories(creds) }
            s.await() to c.await()
        }
        if (streams.isEmpty()) return null
        streams.map { item ->
            M3UChannel(
                name  = item.name,
                url   = XtreamApi.vodPlayUrl(creds, item),
                logo  = item.icon,
                group = categories[item.categoryId] ?: "Outros",
                type  = ChannelType.MOVIE
            )
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

// Tenta carregar a lista de SÉRIES (uma entrada por série, não por episódio)
// pela API Xtream real. Os episódios só se carregam ao abrir uma série
// (ver SeriesEpisodesScreen) — aqui só precisamos do series_id e da capa.
private suspend fun tryLoadSeriesFromXtreamApi(
    connType: String, m3uUrl: String, xtreamServer: String, xtreamUser: String, xtreamPass: String
): List<M3UChannel>? {
    val creds = XtreamApi.resolveCreds(connType, m3uUrl, xtreamServer, xtreamUser, xtreamPass) ?: return null

    return try {
        // Paralelizar as duas chamadas — reduz o tempo de espera para o máximo
        // das duas em vez da soma (get_series + get_series_categories sequencial).
        val (series, categories) = coroutineScope {
            val s = async { XtreamApi.getSeries(creds) }
            val c = async { XtreamApi.getSeriesCategories(creds) }
            s.await() to c.await()
        }
        if (series.isEmpty()) return null
        series.map { item ->
            M3UChannel(
                name  = item.name,
                url   = "$SERIES_MARKER_PREFIX${item.seriesId}",
                logo  = item.cover,
                group = categories[item.categoryId] ?: "Outros",
                type  = ChannelType.SERIES
            )
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

// ─── Painel lateral de categorias (lista + contagem + pesquisa) ───────────────
@Composable
private fun CategorySidebar(
    modifier         : Modifier = Modifier.width(260.dp).fillMaxHeight(),
    title            : String,
    totalCount       : Int,
    categories       : List<Pair<String, List<M3UChannel>>>,
    selectedCategory : String?,
    search           : String,
    onSearchChange   : (String) -> Unit,
    onCategoryClick  : (String) -> Unit,
    onBack           : () -> Unit,
    showBack         : Boolean
) {
    Column(modifier.background(Color(0xFF0d0d1a))) {
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
                    title, color = TextLight, fontWeight = FontWeight.Bold,
                    fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text("$totalCount", color = AccentPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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

        // O LazyColumn usa "key" para identificar cada linha pelo nome da
        // categoria — isso mantém a categoria já visível ancorada na mesma
        // posição do ecrã quando a lista muda (evita saltos ao fazer scroll).
        // Mas tem um efeito secundário: quando "Continuar a Assistir" ou
        // "Favoritos" aparecem pela primeira vez (inseridos no topo), essa
        // ancoragem fazia o scroll ficar onde estava — as novas linhas
        // ficavam por cima, fora da vista, em vez de aparecerem logo à vista.
        // Por isso aqui forçamos o scroll de volta ao topo sempre que as
        // categorias especiais do início da lista aparecem/desaparecem.
        val listState = rememberLazyListState()
        val leadingSpecialKey = categories.take(2).joinToString("|") { it.first }
        LaunchedEffect(leadingSpecialKey) {
            listState.scrollToItem(0)
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
            items(categories, key = { it.first }) { (name, items) ->
                CategoryRow(
                    name       = name,
                    count      = items.size,
                    isSelected = name == selectedCategory,
                    onClick    = { onCategoryClick(name) }
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(name: String, count: Int, isSelected: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (isSelected) AccentPrimary.copy(0.18f) else Color.Transparent)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(3.dp).height(18.dp)
                .background(if (isSelected) AccentPrimary else Color.Transparent, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(10.dp))
        if (name == CATEGORY_CONTINUE_WATCHING || name == CATEGORY_FAVORITES) {
            Icon(
                if (name == CATEGORY_CONTINUE_WATCHING) Icons.Default.History else Icons.Default.Star,
                null, tint = AccentPrimary, modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            displayCategoryName(name),
            color = if (isSelected) Color.White else TextLight.copy(0.85f),
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            "$count",
            color = if (isSelected) AccentPrimary else TextLight.copy(0.4f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
    @Suppress("DEPRECATION")
    Divider(color = Color.White.copy(0.04f), thickness = 0.5.dp)
}

@Composable
private fun PosterCard(
    channel          : M3UChannel,
    isFavorite       : Boolean,
    onClick          : () -> Unit,
    onToggleFavorite : () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .width(130.dp)
            .clip(RoundedCornerShape(10.dp)),
    ) {
        Box(
            modifier = Modifier
                .width(130.dp)
                .height(185.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceDark)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (channel.logo.isNotBlank() && channel.logo.startsWith("http")) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(channel.logo)
                        .crossfade(200)
                        .size(260, 370)   // 2× o tamanho dp para ecrãs de alta densidade
                        .build(),
                    contentDescription = channel.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                ) {
                    when (painter.state) {
                        is AsyncImagePainter.State.Loading -> {
                            // Shimmer enquanto carrega — melhor que caixa vazia
                            Box(
                                Modifier.fillMaxSize()
                                    .background(
                                        androidx.compose.ui.graphics.Brush.verticalGradient(
                                            listOf(SurfaceDark, SurfaceDark.copy(alpha = 0.4f), SurfaceDark)
                                        )
                                    )
                            )
                        }
                        is AsyncImagePainter.State.Error -> PosterFallback(channel.name)
                        else -> SubcomposeAsyncImageContent()
                    }
                }
            } else {
                PosterFallback(channel.name)
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(AccentPrimary.copy(0.85f), RoundedCornerShape(50))
                    .padding(5.dp)
            ) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
            val favInteraction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(0.55f), RoundedCornerShape(50))
                    .clickable(interactionSource = favInteraction, indication = null, onClick = onToggleFavorite)
                    .padding(6.dp)
            ) {
                Icon(
                    if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    null,
                    tint = if (isFavorite) AccentPrimary else Color.White.copy(0.8f),
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        Text(
            channel.name,
            color = TextLight,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(top = 6.dp).width(130.dp)
        )
    }
}

// Capa indisponível (sem tvg-logo, ou o servidor de imagens do fornecedor
// bloqueou o pedido) — mostra o título em vez de deixar a caixa vazia.
@Composable
private fun PosterFallback(name: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Movie, null, tint = TextLight.copy(0.25f), modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            name,
            color = TextLight.copy(0.6f),
            fontSize = 11.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun EmptyVodMessage(type: ChannelType) {
    val label = if (type == ChannelType.MOVIE) "filmes" else "séries"
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.BrokenImage, null, modifier = Modifier.size(64.dp), tint = AccentPrimary.copy(0.4f))
            Spacer(Modifier.height(12.dp))
            Text("Nenhum(a) $label encontrado(a) na lista", color = TextLight.copy(0.6f), textAlign = TextAlign.Center)
        }
    }
}
