package com.velvetiptv.app.ui.screens.activation

import android.content.Context
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.velvetiptv.app.data.ActivationApiClient
import com.velvetiptv.app.data.DeviceCheckRequest
import com.velvetiptv.app.data.LicensePreferences
import com.velvetiptv.app.ui.navigation.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.NetworkInterface

const val ACTIVATION_URL = "https://spiffy-mochi-c2fbdc.netlify.app/activate"

fun generateQrBitmap(content: String, size: Int = 512): Bitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 1)
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) for (y in 0 until size)
        bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return bmp
}

fun getMacAddress(): String = try {
    val intf = NetworkInterface.getNetworkInterfaces()?.toList()
        ?.firstOrNull { it.name.equals("wlan0", true) || it.name.startsWith("eth") }
    val mac = intf?.hardwareAddress
    if (mac != null && mac.isNotEmpty()) mac.joinToString(":") { "%02x".format(it) }
    else "00:00:00:00:00:00"
} catch (_: Exception) { "00:00:00:00:00:00" }

fun getDeviceKey(context: Context): String {
    val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "000000"
    val digits = id.filter { it.isDigit() }.take(6).padEnd(6, '0')
    return digits.ifEmpty { id.take(6).uppercase() }
}

@Composable
fun ActivationScreen(navController: NavController) {
    val context    = LocalContext.current
    val config     = LocalConfiguration.current
    val isWide     = config.screenWidthDp > config.screenHeightDp

    var macAddress  by remember { mutableStateOf("--:--:--:--:--:--") }
    var deviceKey   by remember { mutableStateOf("------") }
    var qrBitmap    by remember { mutableStateOf<Bitmap?>(null) }
    var isActivated by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val mac = withContext(Dispatchers.IO) { getMacAddress() }
        val key = withContext(Dispatchers.IO) { getDeviceKey(context) }
        val qr  = withContext(Dispatchers.IO) {
            try { generateQrBitmap("$ACTIVATION_URL?mac=$mac&key=$key") } catch (_: Exception) { null }
        }
        macAddress = mac
        deviceKey  = key
        qrBitmap   = qr
    }

    // Verifica periodicamente no backend se a licença já foi paga e ativada.
    LaunchedEffect(macAddress, deviceKey) {
        if (macAddress == "--:--:--:--:--:--") return@LaunchedEffect
        while (!isActivated) {
            try {
                val response = ActivationApiClient.api.checkDevice(
                    DeviceCheckRequest(mac = macAddress, deviceKey = deviceKey)
                )
                if (response.activated) {
                    isActivated = true
                    // Guarda a validade da licença (1 ano por padrão, ou a data
                    // devolvida pelo backend) para o app saber, no próximo
                    // arranque, que pode ir direto ao menu sem reativar.
                    val expiresAtMillis = LicensePreferences.parseExpiresAt(response.expiresAt)
                    LicensePreferences.setExpiresAt(context, expiresAtMillis)
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Activation.route) { inclusive = true }
                    }
                }
            } catch (_: Exception) {
                // Sem rede ou backend indisponível — tenta de novo no próximo ciclo.
            }
            delay(4000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF080810))
    ) {
        StarBackground()

        if (isWide) {
            // ── TV / Landscape ────────────────────────────────────────────
            TVActivationLayout(
                macAddress   = macAddress,
                deviceKey    = deviceKey,
                qrBitmap     = qrBitmap,
                isActivated  = isActivated
            )
        } else {
            // ── Portrait / Mobile ─────────────────────────────────────────
            MobileActivationLayout(
                macAddress   = macAddress,
                deviceKey    = deviceKey,
                qrBitmap     = qrBitmap,
                isActivated  = isActivated
            )
        }
    }
}

// ─── Layout TV (Landscape) ────────────────────────────────────────────────────
@Composable
private fun TVActivationLayout(
    macAddress: String,
    deviceKey: String,
    qrBitmap: Bitmap?,
    isActivated: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 56.dp, vertical = 40.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── Coluna esquerda: logo + info ──────────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 40.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            AlphaLogoMini(size = 150.dp)

            Spacer(Modifier.height(4.dp))
            InfoLine("Acesse para ativar:", ACTIVATION_URL, Color.White, 12, 13)
            InfoLine("Mac Address", macAddress, Color(0xFFD4A843), 12, 20)
            InfoLine("Device Key", deviceKey, Color(0xFFD4A843), 12, 28)
            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier
                    .width(220.dp).height(50.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isActivated) Color(0xFF1FAA59) else Color(0xFF2A2A3A)),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isActivated) {
                    CircularProgressIndicator(
                        color = Color(0xFFD4A843),
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    if (isActivated) "Ativado! Entrando…" else "Aguardando pagamento…",
                    fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White
                )
            }
        }

        // ── QR Code (direita) ─────────────────────────────────────────────
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(2.dp, Color(0xFFD4A843).copy(0.6f), RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                if (qrBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    CircularProgressIndicator(
                        color = Color(0xFF7B35C4),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            Text(
                "Leia o QR Code para\nacessar a página de ativação",
                fontSize = 12.sp,
                color = Color(0xFFD4A843),
                textAlign = TextAlign.Center,
                lineHeight = 17.sp
            )
        }
    }
}

// ─── Layout Mobile (Portrait) ─────────────────────────────────────────────────
@Composable
private fun MobileActivationLayout(
    macAddress: String,
    deviceKey: String,
    qrBitmap: Bitmap?,
    isActivated: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp)
            .padding(top = 48.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        AlphaLogoMini(size = 180.dp)

        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(0.07f)))
        Spacer(Modifier.height(4.dp))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Acesse para ativar:", fontSize = 12.sp,
                color = Color.White.copy(0.5f), textAlign = TextAlign.Center)
            Text(ACTIVATION_URL, fontSize = 13.sp,
                fontWeight = FontWeight.Medium, color = Color.White, textAlign = TextAlign.Center)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Mac Address", fontSize = 12.sp, color = Color.White.copy(0.5f))
            Text(macAddress, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = Color(0xFFD4A843), letterSpacing = 1.sp, textAlign = TextAlign.Center)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Device Key", fontSize = 12.sp, color = Color.White.copy(0.5f))
            Text(deviceKey, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
                color = Color(0xFFD4A843), letterSpacing = 4.sp)
        }

        Box(
            modifier = Modifier
                .size(190.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(2.dp, Color(0xFFD4A843).copy(0.5f), RoundedCornerShape(14.dp))
                .background(Color.White)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            if (qrBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "QR Code",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                CircularProgressIndicator(
                    color = Color(0xFF7B35C4),
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        Text(
            "Leia o QR Code para\nacessar a página de ativação",
            fontSize = 12.sp,
            color = Color(0xFFD4A843),
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )

        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth().height(54.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isActivated) Color(0xFF1FAA59) else Color(0xFF2A2A3A)),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isActivated) {
                CircularProgressIndicator(
                    color = Color(0xFFD4A843),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                if (isActivated) "Ativado! Entrando…" else "Aguardando pagamento…",
                fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White
            )
        }
    }
}

// ─── Componentes auxiliares ───────────────────────────────────────────────────
@Composable
fun InfoLine(label: String, value: String, valueColor: Color, labelSize: Int, valueSize: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = labelSize.sp, color = Color.White.copy(.5f))
        Text(value, fontSize = valueSize.sp, fontWeight = FontWeight.Bold,
            color = valueColor, letterSpacing = 0.5.sp)
    }
}

@Composable
fun AlphaLogoMini(size: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(com.velvetiptv.app.R.drawable.logo_alpha_prime),
        contentDescription = "Alpha Prime",
        modifier = Modifier.size(size),
        contentScale = ContentScale.Fit
    )
}

@Composable
private fun StarBackground() {
    val stars = remember {
        List(80) { Triple(
            (0..1000).random() / 1000f,
            (0..1000).random() / 1000f,
            (0..100).random() / 100f
        )}
    }
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        drawRect(Brush.radialGradient(
            listOf(Color(0xFF16083A), Color(0xFF080810)),
            Offset(w*.5f, h*.35f), w*.9f
        ))
        stars.forEach { (x, y, s) ->
            drawCircle(Color.White.copy(.15f + s*.4f), .5f + s*1.5f, Offset(x*w, y*h))
        }
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFF7B35C4).copy(.08f), Color.Transparent),
                Offset(w*.5f, h*.5f), w*.6f),
            w*.6f, Offset(w*.5f, h*.5f)
        )
    }
}
