package com.velvetiptv.app.ui.screens.home

import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.velvetiptv.app.data.ActivationApiClient
import com.velvetiptv.app.data.DeviceCheckRequest
import com.velvetiptv.app.data.LicensePreferences
import com.velvetiptv.app.data.TRIAL_DURATION_MS
import com.velvetiptv.app.ui.navigation.Screen
import com.velvetiptv.app.data.DeviceUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

@Composable
fun HomeMenuScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var validityText by remember { mutableStateOf<String?>(null) }
    var macAddress by remember { mutableStateOf("") }
    var deviceKeyVal by remember { mutableStateOf("") }
    var isRefreshing by remember { mutableStateOf(false) }

    suspend fun refreshFromBackend(mac: String, key: String) {
        val installDate = LicensePreferences.getOrInitInstallDate(context)
        var expiresAt = LicensePreferences.getExpiresAt(context)

        fun updateLabel() {
            validityText = when {
                LicensePreferences.isLicenseValid(expiresAt) ->
                    "Acesso válido até ${LicensePreferences.formatExpiresAt(expiresAt!!)}"
                LicensePreferences.isTrialActive(installDate) -> {
                    val trialEnd = installDate + TRIAL_DURATION_MS
                    "Teste gratuito - Válido até: ${LicensePreferences.formatExpiresAt(trialEnd)}"
                }
                else -> "Acesso expirado"
            }
        }
        updateLabel()

        try {
            val response = ActivationApiClient.api.checkDevice(
                DeviceCheckRequest(mac = mac, deviceKey = key)
            )
            if (response.activated) {
                val millis = LicensePreferences.parseExpiresAt(response.expiresAt)
                LicensePreferences.setExpiresAt(context, millis)
                expiresAt = millis
            } else {
                LicensePreferences.setExpiresAt(context, null)
                expiresAt = null
            }
            updateLabel()
        } catch (_: Exception) {
            // Sem rede — mantém a validade guardada localmente.
        }
    }

    LaunchedEffect(Unit) {
        macAddress = withContext(Dispatchers.IO) { DeviceUtils.getMacAddress(context) }
        deviceKeyVal = withContext(Dispatchers.IO) { DeviceUtils.getDeviceKey(context) }
        refreshFromBackend(macAddress, deviceKeyVal)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF080810))
    ) {
        SpaceBackground()

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            // Logo Alpha Prime no topo
            AlphaPrimeHeader()

            Spacer(modifier = Modifier.height(16.dp))

            // Botões circulares
            MenuButtons(navController)

            Spacer(modifier = Modifier.height(12.dp))

            validityText?.let { text ->
                Text(
                    text,
                    fontSize = 12.sp,
                    color = Color(0xFFD4A843),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Botão Atualizar — sincroniza o status de licença com o servidor
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(enabled = !isRefreshing) {
                        scope.launch {
                            isRefreshing = true
                            refreshFromBackend(macAddress, deviceKeyVal)
                            isRefreshing = false
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        color = Color.White.copy(alpha = 0.5f),
                        strokeWidth = 1.5.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Atualizar",
                        tint = Color.White.copy(alpha = 0.45f),
                        modifier = Modifier.size(12.dp)
                    )
                }
                Text(
                    "Atualizar",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.45f)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Identificação do dispositivo — útil para suporte/diagnóstico,
            // mesma info mostrada na tela de Ativação.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(bottom = 32.dp)
            ) {
                Text("MAC: $macAddress", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
                Text("Device Key: $deviceKeyVal", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun AlphaPrimeHeader() {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(com.velvetiptv.app.R.drawable.logo_alpha_prime),
        contentDescription = "Alpha Prime",
        modifier = Modifier.size(200.dp),
        contentScale = androidx.compose.ui.layout.ContentScale.Fit
    )
}

@Composable
private fun MenuButtons(navController: NavController) {
    val context = LocalContext.current
    val items = listOf(
        MenuItem("TV ao Vivo", Icons.Default.LiveTv,      Color(0xFF7B35C4), route = Screen.TV.route),
        MenuItem("Filmes",     Icons.Default.Movie,        Color(0xFFD4A843), route = Screen.Movies.route),
        MenuItem("Séries",     Icons.Default.VideoLibrary, Color(0xFF2196F3), route = Screen.Series.route),
        MenuItem("Listas",     Icons.Default.List,         Color(0xFF10b981), route = Screen.Settings.route),
        // "Sair" fecha o app inteiro — única forma de sair, já que não há
        // barra/launcher do sistema visível neste modo de TV.
        MenuItem("Sair", Icons.Default.ExitToApp, Color(0xFFef4444), onClick = {
            (context as? Activity)?.finishAffinity()
        }),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { item ->
            MenuCircleButton(
                item = item,
                onClick = { item.route?.let { navController.navigate(it) } ?: item.onClick?.invoke() }
            )
        }
    }
}

data class MenuItem(
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val route: String? = null,
    val onClick: (() -> Unit)? = null
)

@Composable
private fun MenuCircleButton(item: MenuItem, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .background(Color(0xFF1a1a2e))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            // Brilho colorido atrás
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                item.color.copy(alpha = 0.25f),
                                Color.Transparent
                            )
                        )
                    )
            )
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = item.color,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = item.label,
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
            maxLines = 2,
            lineHeight = 13.sp
        )
    }
}

@Composable
private fun SpaceBackground() {
    val stars = remember {
        List(120) {
            Triple(
                Random.nextFloat(),   // x
                Random.nextFloat(),   // y
                Random.nextFloat()    // tamanho
            )
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Gradiente de fundo
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF12082A), Color(0xFF080810)),
                center = Offset(w * 0.5f, h * 0.35f),
                radius = w * 0.9f
            )
        )

        // Estrelas
        stars.forEach { (x, y, s) ->
            drawCircle(
                color = Color.White.copy(alpha = 0.3f + s * 0.5f),
                radius = 0.8f + s * 1.5f,
                center = Offset(x * w, y * h)
            )
        }

        // Glow dourado subtil no topo (logo)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFFD4A843).copy(alpha = 0.06f), Color.Transparent),
                center = Offset(w * 0.5f, h * 0.28f),
                radius = w * 0.5f
            ),
            radius = w * 0.5f,
            center = Offset(w * 0.5f, h * 0.28f)
        )

        // Glow roxo subtil no centro
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF7B35C4).copy(alpha = 0.08f), Color.Transparent),
                center = Offset(w * 0.5f, h * 0.62f),
                radius = w * 0.6f
            ),
            radius = w * 0.6f,
            center = Offset(w * 0.5f, h * 0.62f)
        )
    }
}
