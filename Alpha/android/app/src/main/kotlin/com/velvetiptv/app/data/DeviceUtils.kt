package com.velvetiptv.app.data

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import java.net.NetworkInterface

private val FAKE_MAC_PREFIXES = setOf("00:00:00:00:00:00", "02:00:00:00:00:00")

object DeviceUtils {

    // Android 6+ bloqueia acesso ao MAC real por privacidade — interfaces retornam
    // 00:00:00:00:00:00 ou 02:00:00:00:00:00 em todos os dispositivos modernos.
    // Quando isso ocorre, derivamos um MAC estável do ANDROID_ID para exibição.
    fun getMacAddress(context: Context): String {
        val raw = try {
            val intf = NetworkInterface.getNetworkInterfaces()?.toList()
                ?.firstOrNull { it.name.equals("wlan0", true) || it.name.startsWith("eth") }
            val mac = intf?.hardwareAddress
            if (mac != null && mac.isNotEmpty()) mac.joinToString(":") { "%02x".format(it) }
            else "00:00:00:00:00:00"
        } catch (_: Exception) { "00:00:00:00:00:00" }

        return if (raw in FAKE_MAC_PREFIXES) deriveMacFromAndroidId(context) else raw
    }

    fun getDeviceKey(context: Context): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.uppercase()
            ?: "UNKNOWN"
    }

    // Gera um MAC formatado estável a partir do ANDROID_ID — único por dispositivo,
    // mesmo em TVs e emuladores que não expõem o MAC real.
    private fun deriveMacFromAndroidId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: return "00:00:00:00:00:00"
        val hash = androidId.hashCode().toLong() and 0xFFFFFFFFFFL
        return String.format(
            "%02x:%02x:%02x:%02x:%02x:%02x",
            (hash shr 32) and 0xFE, // bit menos significativo 0 = unicast
            (hash shr 24) and 0xFF,
            (hash shr 16) and 0xFF,
            (hash shr 8)  and 0xFF,
            hash          and 0xFF,
            androidId.length and 0xFF
        )
    }

    fun getDeviceModel(context: Context): String {
        val uiManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        if (uiManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return "Android TV"

        val manufacturer = Build.MANUFACTURER.lowercase()
        if (manufacturer.contains("amazon")) return "Fire TV"

        val size = context.resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK
        return if (size >= Configuration.SCREENLAYOUT_SIZE_LARGE) "Tablet" else "Android"
    }
}
