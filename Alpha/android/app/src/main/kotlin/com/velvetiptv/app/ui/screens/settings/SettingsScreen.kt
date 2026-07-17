package com.velvetiptv.app.ui.screens.settings

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.velvetiptv.app.data.ActivationApiClient
import com.velvetiptv.app.data.IPTVPlaylist
import com.velvetiptv.app.data.IPTVPreferences
import com.velvetiptv.app.data.ParentalPreferences
import com.velvetiptv.app.data.ParentalSetPinRequest
import com.velvetiptv.app.data.PlaylistCreateRequest
import com.velvetiptv.app.data.PlaylistDeleteRequest
import com.velvetiptv.app.data.DeviceUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.velvetiptv.app.ui.theme.AccentPrimary
import com.velvetiptv.app.ui.theme.AccentSecondary
import com.velvetiptv.app.ui.theme.DarkBackground
import com.velvetiptv.app.ui.theme.SurfaceDark
import com.velvetiptv.app.ui.theme.TextLight
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: androidx.navigation.NavController? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val playlists by IPTVPreferences.getPlaylists(context).collectAsState(initial = emptyList())
    val activeId by IPTVPreferences.getActivePlaylistId(context).collectAsState(initial = null)

    var selectedTab by remember { mutableStateOf(0) }
    var listName by remember { mutableStateOf("") }
    var m3uUrl by remember { mutableStateOf("") }
    var xtreamServer by remember { mutableStateOf("") }
    var xtreamUser by remember { mutableStateOf("") }
    var xtreamPass by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var saveSuccess by remember { mutableStateOf(false) }
    var deviceMac by remember { mutableStateOf("") }
    var deviceKeyVal by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf("") }
    var protectPlaylist by remember { mutableStateOf(false) }
    var playlistPin by remember { mutableStateOf("") }
    var pickedFileName by remember { mutableStateOf<String?>(null) }
    var pickedFileUri by remember { mutableStateOf<Uri?>(null) }

    // Sincroniza as listas com o servidor em loop, enquanto a tela estiver aberta,
    // para refletir quase em tempo real o que for alterado na dashboard (ou noutro
    // dispositivo) sem precisar sair e voltar à tela.
    LaunchedEffect(Unit) {
        val mac = withContext(Dispatchers.IO) { DeviceUtils.getMacAddress(context) }
        val key = withContext(Dispatchers.IO) { DeviceUtils.getDeviceKey(context) }
        deviceMac = mac
        deviceKeyVal = key

        while (true) {
            try {
                val remote = ActivationApiClient.api.syncPlaylists(mac, key)
                IPTVPreferences.syncFromServer(context, remote)
            } catch (_: Exception) {
                // Sem rede ou dispositivo ainda não ativado — mantém as listas locais e tenta de novo.
            }
            delay(3000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configurações", color = TextLight) },
                navigationIcon = {
                    if (navController != null) {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = TextLight)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        },
        containerColor = DarkBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(DarkBackground)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Listas existentes
            Text(
                "Minhas Listas",
                style = MaterialTheme.typography.titleMedium,
                color = TextLight
            )

            if (playlists.isEmpty()) {
                Text(
                    "Nenhuma lista configurada ainda.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextLight.copy(alpha = 0.6f)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    playlists.forEach { playlist ->
                        PlaylistRow(
                            playlist = playlist,
                            isActive = playlist.id == activeId,
                            onSelect = {
                                scope.launch {
                                    IPTVPreferences.setActivePlaylist(context, playlist.id)
                                    try {
                                        ActivationApiClient.api.activatePlaylist(
                                            playlist.id, PlaylistDeleteRequest(deviceMac, deviceKeyVal)
                                        )
                                    } catch (_: Exception) { /* sincroniza na próxima abertura */ }
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    IPTVPreferences.removePlaylist(context, playlist.id)
                                    try {
                                        ActivationApiClient.api.deletePlaylist(
                                            playlist.id, PlaylistDeleteRequest(deviceMac, deviceKeyVal)
                                        )
                                    } catch (_: Exception) { /* sincroniza na próxima abertura */ }
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Título secção
            Text(
                "Adicionar Lista IPTV",
                style = MaterialTheme.typography.titleMedium,
                color = TextLight
            )

            IPTVTextField(
                value = listName,
                onValueChange = { listName = it; saveSuccess = false },
                label = "Nome da lista",
                placeholder = "Ex: Minha Lista Principal",
                keyboardType = KeyboardType.Text,
                leadingIcon = null
            )

            // Tabs: URL / File / Conta Xtream / Other M3u8 — iguais às da dashboard
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = SurfaceDark,
                contentColor = AccentPrimary
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("URL") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("File") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Conta Xtream") }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("Other M3u8") }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            val filePicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri == null) return@rememberLauncherForActivityResult
                pickedFileName = queryFileName(context, uri) ?: "lista.m3u"
                pickedFileUri = uri
            }

            when (selectedTab) {
                0, 3 -> {
                    // URL e Other M3u8 usam o mesmo campo — só muda o rótulo/tipo enviado.
                    IPTVTextField(
                        value = m3uUrl,
                        onValueChange = { m3uUrl = it; saveSuccess = false },
                        label = "URL da lista de reprodução",
                        placeholder = "http://exemplo.com/lista.m3u",
                        leadingIcon = { Icon(Icons.Default.Link, contentDescription = null, tint = AccentPrimary) }
                    )
                    IPTVTextField(
                        value = epgUrl,
                        onValueChange = { epgUrl = it; saveSuccess = false },
                        label = "URL do EPG (opcional)",
                        placeholder = "http://exemplo.com/epg.xml",
                        leadingIcon = null
                    )

                    PlaylistProtectToggle(
                        protectPlaylist = protectPlaylist,
                        pin = playlistPin,
                        onProtectChange = { protectPlaylist = it; if (!it) playlistPin = "" },
                        onPinChange = { playlistPin = it }
                    )

                    TvButton(
                        onClick = {
                            scope.launch {
                                val name = listName.trim().ifBlank { "Lista ${playlists.size + 1}" }
                                val url = m3uUrl.trim()
                                val epg = epgUrl.trim()
                                val protect = protectPlaylist
                                val pin = if (protect) playlistPin.ifBlank { null } else null
                                val localId = IPTVPreferences.addM3UPlaylist(context, name, url, epg)
                                listName = ""; m3uUrl = ""; epgUrl = ""; protectPlaylist = false; playlistPin = ""
                                saveSuccess = true
                                // Tenta sincronizar com servidor; retenta após delay
                                // para dar tempo ao register-trial completar.
                                val req = PlaylistCreateRequest(
                                    deviceMac, deviceKeyVal, name,
                                    type = if (selectedTab == 3) "other" else "url",
                                    url = url, epgUrl = epg,
                                    protectPlaylist = protect,
                                    pin = pin
                                )
                                var synced = false
                                for (attempt in 1..3) {
                                    try {
                                        val response = ActivationApiClient.api.createPlaylist(req)
                                        IPTVPreferences.adoptServerId(context, localId, response.playlist.id)
                                        synced = true
                                        break
                                    } catch (_: Exception) {
                                        if (attempt < 3) delay(3000)
                                    }
                                }
                            }
                        },
                        enabled = m3uUrl.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Carregar Lista", modifier = Modifier.padding(vertical = 4.dp))
                    }
                }

                1 -> {
                    // File — carregada e lida diretamente do armazenamento do dispositivo;
                    // não é enviada ao servidor (fica só neste dispositivo).
                    OutlinedButton(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentPrimary)
                    ) {
                        Icon(Icons.Default.Storage, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(pickedFileName ?: "Escolher ficheiro (.m3u / .m3u8)")
                    }

                    TvButton(
                        onClick = {
                            val uri = pickedFileUri ?: return@TvButton
                            scope.launch {
                                val name = listName.trim().ifBlank { pickedFileName ?: "Lista ${playlists.size + 1}" }
                                val localPath = withContext(Dispatchers.IO) { copyToLocalFile(context, uri, name) }
                                if (localPath != null) {
                                    IPTVPreferences.addM3UPlaylist(context, name, "file://$localPath")
                                    listName = ""; pickedFileName = null; pickedFileUri = null
                                    saveSuccess = true
                                }
                            }
                        },
                        enabled = pickedFileUri != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Carregar Lista (Ficheiro)", modifier = Modifier.padding(vertical = 4.dp))
                    }
                }

                else -> {
                    // Conta Xtream
                    IPTVTextField(
                        value = xtreamServer,
                        onValueChange = { xtreamServer = it; saveSuccess = false },
                        label = "Servidor",
                        placeholder = "http://servidor.com:8080",
                        leadingIcon = { Icon(Icons.Default.Storage, contentDescription = null, tint = AccentPrimary) }
                    )
                    IPTVTextField(
                        value = xtreamUser,
                        onValueChange = { xtreamUser = it; saveSuccess = false },
                        label = "Utilizador",
                        placeholder = "username",
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = AccentPrimary) }
                    )
                    IPTVTextField(
                        value = xtreamPass,
                        onValueChange = { xtreamPass = it; saveSuccess = false },
                        label = "Senha",
                        placeholder = "••••••••",
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = AccentPrimary) },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardType = KeyboardType.Password,
                        trailingIcon = {
                            TextButton(onClick = { showPassword = !showPassword }) {
                                Text(if (showPassword) "Ocultar" else "Mostrar", color = AccentPrimary)
                            }
                        }
                    )
                    IPTVTextField(
                        value = epgUrl,
                        onValueChange = { epgUrl = it; saveSuccess = false },
                        label = "URL do EPG (opcional)",
                        placeholder = "http://exemplo.com/epg.xml",
                        leadingIcon = null
                    )

                    PlaylistProtectToggle(
                        protectPlaylist = protectPlaylist,
                        pin = playlistPin,
                        onProtectChange = { protectPlaylist = it; if (!it) playlistPin = "" },
                        onPinChange = { playlistPin = it }
                    )

                    TvButton(
                        onClick = {
                            scope.launch {
                                val name = listName.trim().ifBlank { "Lista Xtream ${playlists.size + 1}" }
                                val server = xtreamServer.trim()
                                val user = xtreamUser.trim()
                                val pass = xtreamPass.trim()
                                val epg = epgUrl.trim()
                                val protect = protectPlaylist
                                val pin = if (protect) playlistPin.ifBlank { null } else null
                                val localId = IPTVPreferences.addXtreamPlaylist(context, name, server, user, pass, epg)
                                listName = ""; xtreamServer = ""; xtreamUser = ""; xtreamPass = ""; epgUrl = ""; protectPlaylist = false; playlistPin = ""
                                saveSuccess = true
                                val req = PlaylistCreateRequest(
                                    deviceMac, deviceKeyVal, name, "xtream",
                                    server = server, username = user, password = pass, epgUrl = epg,
                                    protectPlaylist = protect,
                                    pin = pin
                                )
                                for (attempt in 1..3) {
                                    try {
                                        val response = ActivationApiClient.api.createPlaylist(req)
                                        IPTVPreferences.adoptServerId(context, localId, response.playlist.id)
                                        break
                                    } catch (_: Exception) {
                                        if (attempt < 3) delay(3000)
                                    }
                                }
                            }
                        },
                        enabled = xtreamServer.isNotBlank() && xtreamUser.isNotBlank() && xtreamPass.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Adicionar Xtream Codes", modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }

            if (saveSuccess) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1a3a2a))) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10b981))
                        Text("Lista adicionada com sucesso!", color = TextLight, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            ParentalLockSection()

            Spacer(modifier = Modifier.height(24.dp))

            AppFooter()
        }
    }
}


@Composable
private fun AppFooter() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Versão 1.0.0", style = MaterialTheme.typography.bodySmall, color = TextLight.copy(alpha = 0.6f))
        Text(
            "© 2026 Alpha Prime. Todos os direitos reservados.",
            style = MaterialTheme.typography.bodySmall,
            color = TextLight.copy(alpha = 0.6f)
        )
        val uriHandler = LocalUriHandler.current
        Text(
            "Termos de Uso e Politicas de Privacidade - Documentos Legais",
            style = MaterialTheme.typography.bodySmall,
            color = TextLight.copy(alpha = 0.6f),
            modifier = Modifier.clickable {
                uriHandler.openUri("https://www.alphaprimetv.com/legal.html")
            }
        )
    }
}

@Composable
private fun PlaylistRow(
    playlist: IPTVPlaylist,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    var rowFocused by remember { mutableStateOf(false) }
    var deleteFocused by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) Color(0xFF1a3a2a) else SurfaceDark
        ),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (rowFocused || deleteFocused) 2.dp else 0.dp,
                color = if (deleteFocused) Color(0xFFEF4444)
                        else if (rowFocused) Color(0xFFD4A843)
                        else Color.Transparent,
                shape = MaterialTheme.shapes.medium
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Área de seleção — ocupa todo o espaço menos a lixeira
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect() }
                    .onFocusChanged { rowFocused = it.isFocused }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    if (isActive) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (isActive) Color(0xFF10b981) else TextLight.copy(alpha = 0.4f)
                )
                Column {
                    Text(playlist.name, style = MaterialTheme.typography.bodyMedium, color = TextLight)
                    Text(
                        if (playlist.type == "xtream") "Xtream Codes: ${playlist.xtreamServer}"
                        else "M3U: ${playlist.m3uUrl.take(40)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextLight.copy(alpha = 0.6f)
                    )
                    if (isActive) {
                        Text("Ativa", style = MaterialTheme.typography.labelSmall, color = Color(0xFF10b981))
                    }
                }
            }
            // Lixeira — focusável independentemente pelo D-pad
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .onFocusChanged { deleteFocused = it.isFocused }
                    .padding(end = 4.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remover",
                    tint = if (deleteFocused) Color(0xFFEF4444) else AccentSecondary
                )
            }
        }
    }
}

// Botão com borda amarela visível ao receber foco via controle remoto de TV.
@Composable
private fun TvButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = MaterialTheme.shapes.small
            ),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (focused) AccentPrimary.copy(alpha = 0.85f) else AccentPrimary
        ),
        content = content
    )
}

@Composable
private fun IPTVTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardType: KeyboardType = KeyboardType.Uri
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = TextLight.copy(alpha = 0.4f)) },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AccentPrimary,
            unfocusedBorderColor = SurfaceDark,
            focusedTextColor = TextLight,
            unfocusedTextColor = TextLight,
            focusedLabelColor = AccentPrimary,
            unfocusedLabelColor = TextLight.copy(alpha = 0.6f),
            cursorColor = AccentPrimary
        )
    )
}

// ── Proteção de lista com senha ───────────────────────────────────────────────
@Composable
private fun PlaylistProtectToggle(
    protectPlaylist: Boolean,
    pin: String,
    onProtectChange: (Boolean) -> Unit,
    onPinChange: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Proteger lista com senha", color = TextLight, style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = protectPlaylist,
            onCheckedChange = onProtectChange,
            colors = SwitchDefaults.colors(checkedThumbColor = AccentPrimary, checkedTrackColor = AccentPrimary.copy(alpha = 0.4f))
        )
    }
    if (protectPlaylist) {
        IPTVTextField(
            value = pin,
            onValueChange = onPinChange,
            label = "Senha da lista",
            placeholder = "Mínimo 4 dígitos",
            keyboardType = KeyboardType.NumberPassword,
            visualTransformation = PasswordVisualTransformation(),
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = AccentPrimary) }
        )
    }
}

// ── Bloqueio de canais por senha ──────────────────────────────────────────────
// Permite definir uma senha que protege categorias marcadas como bloqueadas
// (ex.: "Adultos") em TV ao Vivo — gerido aqui, aplicado lá.
@Composable
private fun ParentalLockSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // initial = null (em vez de false) evita que o instante de carregamento do
    // DataStore seja confundido com "ainda não tem senha" e force o formulário
    // de criação a aparecer mesmo quando já existe uma senha definida.
    val hasPin by ParentalPreferences.hasPin(context).collectAsState(initial = null)

    // Sincroniza com o servidor para refletir aqui o que for alterado pela dashboard
    // (ex.: senha redefinida ou removida pelo suporte).
    LaunchedEffect(Unit) {
        try {
            val mac = withContext(Dispatchers.IO) { DeviceUtils.getMacAddress(context) }
            val key = withContext(Dispatchers.IO) { DeviceUtils.getDeviceKey(context) }
            val remote = ActivationApiClient.api.syncParental(mac, key)
            ParentalPreferences.adoptServerState(context, remote.pinHash, remote.lockedCategories)
        } catch (_: Exception) {
            // Sem rede ou dispositivo ainda não ativado — mantém o estado local.
        }
    }

    var showChangeForm by remember { mutableStateOf(false) }
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var success by remember { mutableStateOf("") }
    var showRemoveDialog by remember { mutableStateOf(false) }

    fun resetForm() {
        currentPin = ""; newPin = ""; confirmPin = ""; error = ""
    }

    Text(
        "Bloqueio de Canais por Senha",
        style = MaterialTheme.typography.titleMedium,
        color = TextLight
    )
    Text(
        "Defina uma senha para proteger categorias de canais (ex.: Adultos). " +
            "Em TV ao Vivo, toque no cadeado junto de cada categoria para bloqueá-la.",
        style = MaterialTheme.typography.bodySmall,
        color = TextLight.copy(alpha = 0.6f)
    )

    // Enquanto o estado real ainda não chegou do armazenamento local, não decide
    // nada — evita mostrar por engano o formulário de "criar senha" a piscar.
    if (hasPin == null) return
    val pinExists = hasPin == true

    if (!pinExists && !showChangeForm) showChangeForm = true

    if (!showChangeForm) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1a3a2a))) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF10b981))
                Text("Senha definida", color = TextLight, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { resetForm(); success = ""; showChangeForm = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentPrimary)
            ) { Text("Alterar senha") }
            OutlinedButton(
                onClick = { showRemoveDialog = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentSecondary)
            ) { Text("Remover bloqueio") }
        }
    } else {
        if (pinExists) {
            IPTVTextField(
                value = currentPin,
                onValueChange = { currentPin = it; error = "" },
                label = "Senha actual",
                placeholder = "••••",
                keyboardType = KeyboardType.NumberPassword,
                visualTransformation = PasswordVisualTransformation()
            )
        }
        IPTVTextField(
            value = newPin,
            onValueChange = { newPin = it; error = "" },
            label = if (pinExists) "Nova senha" else "Senha",
            placeholder = "Mínimo 4 dígitos",
            keyboardType = KeyboardType.NumberPassword,
            visualTransformation = PasswordVisualTransformation()
        )
        IPTVTextField(
            value = confirmPin,
            onValueChange = { confirmPin = it; error = "" },
            label = "Confirmar senha",
            placeholder = "Repita a senha",
            keyboardType = KeyboardType.NumberPassword,
            visualTransformation = PasswordVisualTransformation()
        )

        if (error.isNotEmpty()) {
            Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        when {
                            pinExists && !ParentalPreferences.verifyPin(context, currentPin) ->
                                error = "Senha actual incorrecta"
                            newPin.length < 4 ->
                                error = "A senha deve ter pelo menos 4 dígitos"
                            newPin != confirmPin ->
                                error = "As senhas não coincidem"
                            else -> {
                                ParentalPreferences.setPin(context, newPin)
                                try {
                                    val mac = withContext(Dispatchers.IO) { DeviceUtils.getMacAddress(context) }
                                    val key = withContext(Dispatchers.IO) { DeviceUtils.getDeviceKey(context) }
                                    ActivationApiClient.api.setParentalPin(
                                        ParentalSetPinRequest(mac, key, currentPin.ifBlank { null }, newPin)
                                    )
                                } catch (_: Exception) { /* fica só local até a próxima sincronização */ }
                                resetForm()
                                success = "Senha guardada com sucesso!"
                                showChangeForm = false
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
            ) { Text(if (pinExists) "Guardar nova senha" else "Definir senha") }

            if (pinExists) {
                OutlinedButton(onClick = { resetForm(); showChangeForm = false }) { Text("Cancelar") }
            }
        }
    }

    if (success.isNotEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1a3a2a))) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10b981))
                Text(success, color = TextLight, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (showRemoveDialog) {
        var removePin by remember { mutableStateOf("") }
        var removeError by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text("Remover bloqueio") },
            text = {
                Column {
                    Text("Isto remove a senha e desbloqueia todas as categorias. Introduza a senha actual para confirmar.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = removePin,
                        onValueChange = { removePin = it; removeError = false },
                        label = { Text("Senha actual") },
                        singleLine = true,
                        isError = removeError,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    if (removeError) {
                        Text("Senha incorrecta", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (ParentalPreferences.verifyPin(context, removePin)) {
                            ParentalPreferences.removePin(context)
                            showRemoveDialog = false
                            success = "Bloqueio removido."
                        } else {
                            removeError = true
                        }
                    }
                }) { Text("Remover") }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) { Text("Cancelar") }
            }
        )
    }
}

private fun queryFileName(context: android.content.Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (_: Exception) {
        null
    }
}

// Copia o ficheiro escolhido pelo utilizador para o armazenamento interno da app —
// permissões de content:// não sobrevivem a um reinício, então guardamos uma cópia própria.
private fun copyToLocalFile(context: android.content.Context, uri: Uri, name: String): String? {
    return try {
        val dir = java.io.File(context.filesDir, "playlists").apply { mkdirs() }
        val safeName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val target = java.io.File(dir, "${System.currentTimeMillis()}_$safeName.m3u")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        target.absolutePath
    } catch (_: Exception) {
        null
    }
}
