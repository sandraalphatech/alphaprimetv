package com.velvetiptv.app.data

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import java.net.NetworkInterface

object DeviceUtils {

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

    fun getDeviceModel(context: Context): String {
        val uiManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        if (uiManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return "Android TV"

        val manufacturer = Build.MANUFACTURER.lowercase()
        if (manufacturer.contains("amazon")) return "Fire TV"

        val size = context.resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK
        return if (size >= Configuration.SCREENLAYOUT_SIZE_LARGE) "Tablet" else "Android"
    }
}
