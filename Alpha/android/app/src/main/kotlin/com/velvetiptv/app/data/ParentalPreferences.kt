package com.velvetiptv.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import java.security.MessageDigest

// Bloqueio por senha de categorias de canais (ex.: "Adultos") — a senha nunca
// é guardada em texto simples (só o hash), e é exigida tanto para ver canais
// de uma categoria bloqueada como para desbloqueá-la outra vez.
object ParentalPreferences {
    private val PIN_HASH          = stringPreferencesKey("parental_pin_hash")
    private val LOCKED_CATEGORIES = stringPreferencesKey("parental_locked_categories")

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(pin.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun hasPin(context: Context): Flow<Boolean> =
        context.iptvDataStore.data.map { !it[PIN_HASH].isNullOrBlank() }

    suspend fun setPin(context: Context, pin: String) {
        context.iptvDataStore.edit { prefs -> prefs[PIN_HASH] = hash(pin) }
    }

    suspend fun verifyPin(context: Context, pin: String): Boolean {
        val saved = context.iptvDataStore.data.first()[PIN_HASH] ?: return false
        return pin.isNotBlank() && saved == hash(pin)
    }

    // Remover a senha também desbloqueia tudo — sem senha o bloqueio deixa de
    // fazer sentido (ficaria preso para sempre, sem forma de o desfazer).
    suspend fun removePin(context: Context) {
        context.iptvDataStore.edit { prefs ->
            prefs.remove(PIN_HASH)
            prefs[LOCKED_CATEGORIES] = "[]"
        }
    }

    fun lockedCategories(context: Context): Flow<Set<String>> =
        context.iptvDataStore.data.map { prefs ->
            try {
                val arr = JSONArray(prefs[LOCKED_CATEGORIES] ?: "[]")
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            } catch (_: Exception) { emptySet() }
        }

    suspend fun setCategoryLocked(context: Context, category: String, locked: Boolean): Set<String> {
        var result = emptySet<String>()
        context.iptvDataStore.edit { prefs ->
            val current = try {
                val arr = JSONArray(prefs[LOCKED_CATEGORIES] ?: "[]")
                (0 until arr.length()).map { arr.getString(it) }.toMutableSet()
            } catch (_: Exception) { mutableSetOf() }
            if (locked) current.add(category) else current.remove(category)
            prefs[LOCKED_CATEGORIES] = JSONArray(current.toList()).toString()
            result = current
        }
        return result
    }

    // O servidor é a fonte da verdade para a senha e as categorias bloqueadas —
    // assim, redefinir a senha ou desbloquear uma categoria pela dashboard reflete
    // aqui (a aplicação real do bloqueio continua sendo feita dentro do próprio app).
    suspend fun adoptServerState(context: Context, pinHash: String, lockedCategories: List<String>) {
        context.iptvDataStore.edit { prefs ->
            if (pinHash.isNotBlank()) prefs[PIN_HASH] = pinHash else prefs.remove(PIN_HASH)
            prefs[LOCKED_CATEGORIES] = JSONArray(lockedCategories).toString()
        }
    }
}
