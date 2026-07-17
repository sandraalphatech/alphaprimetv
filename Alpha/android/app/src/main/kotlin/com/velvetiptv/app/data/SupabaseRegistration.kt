package com.velvetiptv.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SupabaseRegistration {

    private const val TAG = "SupabaseReg"

    // Registra o dispositivo via backend (alphaprime.tv), que por sua vez
    // insere no Supabase usando a service_role key. Idempotente: chamadas
    // repetidas para o mesmo mac+devicekey são ignoradas silenciosamente.
    suspend fun registerIfNeeded(context: Context, installDate: Long) {
        withContext(Dispatchers.IO) {
            try {
                val mac    = DeviceUtils.getMacAddress(context)
                val key    = DeviceUtils.getDeviceKey(context)
                val modelo = DeviceUtils.getDeviceModel(context)

                Log.d(TAG, "Registrando trial: mac=$mac key=$key modelo=$modelo")

                val response = ActivationApiClient.api.registerTrial(
                    RegisterTrialRequest(
                        mac_address        = mac,
                        devicekey          = key,
                        modelo_dispositivo = modelo
                    )
                )

                if (response.success) {
                    Log.d(TAG, "Trial registrado com sucesso. Validade: ${response.validade}")
                } else {
                    Log.e(TAG, "Backend retornou success=false")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao registrar trial: ${e.message}", e)
            }
        }
    }
}
