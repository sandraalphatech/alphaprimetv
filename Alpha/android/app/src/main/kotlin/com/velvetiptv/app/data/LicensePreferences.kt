package com.velvetiptv.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

const val TRIAL_DURATION_MS = 7L * 24 * 60 * 60 * 1000

// Sentinel para plano vitalício — valor maior que qualquer timestamp real,
// mas menor que Long.MAX_VALUE para evitar overflow em aritmética de datas.
const val LIFETIME_SENTINEL = Long.MAX_VALUE / 2

object LicensePreferences {
    private val INSTALL_DATE        = longPreferencesKey("install_date_ms")
    private val LICENSE_EXPIRES_AT  = longPreferencesKey("license_expires_at_ms")
    private val SUPABASE_REGISTERED = booleanPreferencesKey("supabase_registered")

    suspend fun getOrInitInstallDate(context: Context): Long {
        val existing = context.iptvDataStore.data.first()[INSTALL_DATE]
        if (existing != null) return existing
        val now = System.currentTimeMillis()
        context.iptvDataStore.edit { it[INSTALL_DATE] = now }
        return now
    }

    suspend fun getExpiresAt(context: Context): Long? =
        context.iptvDataStore.data.first()[LICENSE_EXPIRES_AT]

    // Salva a validade. null = limpa (sem licença válida armazenada).
    suspend fun setExpiresAt(context: Context, millis: Long?) {
        context.iptvDataStore.edit {
            if (millis != null) it[LICENSE_EXPIRES_AT] = millis
            else it.remove(LICENSE_EXPIRES_AT)
        }
    }

    fun isTrialActive(installDate: Long): Boolean =
        System.currentTimeMillis() - installDate < TRIAL_DURATION_MS

    // Licença válida = data ainda não expirou OU é o sentinel vitalício.
    fun isLicenseValid(expiresAt: Long?): Boolean =
        expiresAt != null && (expiresAt >= LIFETIME_SENTINEL || System.currentTimeMillis() < expiresAt)

    fun trialDaysLeft(installDate: Long): Int {
        val oneDayMs = 24 * 60 * 60 * 1000L
        val remaining = TRIAL_DURATION_MS - (System.currentTimeMillis() - installDate)
        if (remaining <= 0) return 0
        return ((remaining + oneDayMs - 1) / oneDayMs).toInt().coerceAtMost(7)
    }

    // Converte o "expiresAt" do backend para millis.
    // null/vazio → LIFETIME_SENTINEL (plano vitalício, sem data de expiração).
    // Formato inválido → null (não salva nada, evita sobrescrever dado correto).
    fun parseExpiresAt(expiresAtStr: String?): Long? {
        if (expiresAtStr.isNullOrBlank()) return LIFETIME_SENTINEL
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd"
        )
        for (pattern in patterns) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                if (pattern.endsWith("'Z'")) fmt.timeZone = TimeZone.getTimeZone("UTC")
                val parsed = fmt.parse(expiresAtStr)?.time
                if (parsed != null) return parsed
            } catch (_: Exception) { /* tenta o próximo formato */ }
        }
        return null
    }

    fun formatExpiresAt(millis: Long): String =
        if (millis >= LIFETIME_SENTINEL) "Vitalício"
        else SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(millis)

    suspend fun isSupabaseRegistered(context: Context): Boolean =
        context.iptvDataStore.data.first()[SUPABASE_REGISTERED] == true

    suspend fun setSupabaseRegistered(context: Context) {
        context.iptvDataStore.edit { it[SUPABASE_REGISTERED] = true }
    }
}
