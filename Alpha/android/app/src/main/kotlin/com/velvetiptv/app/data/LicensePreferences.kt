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
const val DEFAULT_LICENSE_VALIDITY_MS = 365L * 24 * 60 * 60 * 1000

// Controla o período de teste grátis (7 dias a partir da instalação) e a
// validade da licença paga (recebida do backend em /api/device/check,
// padronizada em 1 ano quando o servidor não devolve "expiresAt").
object LicensePreferences {
    private val INSTALL_DATE         = longPreferencesKey("install_date_ms")
    private val LICENSE_EXPIRES_AT   = longPreferencesKey("license_expires_at_ms")
    private val SUPABASE_REGISTERED  = booleanPreferencesKey("supabase_registered")

    suspend fun getOrInitInstallDate(context: Context): Long {
        val existing = context.iptvDataStore.data.first()[INSTALL_DATE]
        if (existing != null) return existing
        val now = System.currentTimeMillis()
        context.iptvDataStore.edit { it[INSTALL_DATE] = now }
        return now
    }

    suspend fun getExpiresAt(context: Context): Long? =
        context.iptvDataStore.data.first()[LICENSE_EXPIRES_AT]

    suspend fun setExpiresAt(context: Context, millis: Long) {
        context.iptvDataStore.edit { it[LICENSE_EXPIRES_AT] = millis }
    }

    fun isTrialActive(installDate: Long): Boolean =
        System.currentTimeMillis() - installDate < TRIAL_DURATION_MS

    fun isLicenseValid(expiresAt: Long?): Boolean =
        expiresAt != null && System.currentTimeMillis() < expiresAt

    fun trialDaysLeft(installDate: Long): Int {
        val oneDayMs = 24 * 60 * 60 * 1000L
        val remaining = TRIAL_DURATION_MS - (System.currentTimeMillis() - installDate)
        if (remaining <= 0) return 0
        // Arredonda para cima: nos primeiros segundos após instalar, "remaining"
        // é ligeiramente menor que 7 dias exatos — divisão inteira normal
        // truncava isso para 6. Arredondar para cima mostra 7 no dia 0 e só
        // desce para 6 depois que um dia cheio (24h) tiver realmente passado.
        return ((remaining + oneDayMs - 1) / oneDayMs).toInt().coerceAtMost(7)
    }

    // Converte o "expiresAt" devolvido pelo backend (string em vários formatos
    // possíveis) para millis. Sem data válida do servidor, assume 1 ano a
    // partir de agora — validade padrão pedida enquanto o backend não envia
    // a data exata da ativação.
    fun parseExpiresAt(expiresAtStr: String?): Long {
        if (!expiresAtStr.isNullOrBlank()) {
            val patterns = listOf(
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd"
            )
            for (pattern in patterns) {
                try {
                    val fmt = SimpleDateFormat(pattern, Locale.US)
                    if (pattern.endsWith("'Z'")) fmt.timeZone = TimeZone.getTimeZone("UTC")
                    return fmt.parse(expiresAtStr)?.time ?: continue
                } catch (_: Exception) { /* tenta o próximo formato */ }
            }
        }
        return System.currentTimeMillis() + DEFAULT_LICENSE_VALIDITY_MS
    }

    fun formatExpiresAt(millis: Long): String =
        SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(millis)

    suspend fun isSupabaseRegistered(context: Context): Boolean =
        context.iptvDataStore.data.first()[SUPABASE_REGISTERED] == true

    suspend fun setSupabaseRegistered(context: Context) {
        context.iptvDataStore.edit { it[SUPABASE_REGISTERED] = true }
    }
}
