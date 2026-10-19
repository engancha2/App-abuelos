package com.estudiofenix.cerca.home

import android.content.Context
import android.util.Log
import com.estudiofenix.cerca.CercaApp
import com.estudiofenix.cerca.calls.CallManager
import com.estudiofenix.cerca.data.AlertRequest
import com.estudiofenix.cerca.data.Api
import com.estudiofenix.cerca.data.Store
import com.estudiofenix.cerca.data.Sync
import com.estudiofenix.cerca.location.Locator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Sos {
    /**
     * Llama al contacto de emergencia y, en paralelo, manda la alerta con la ubicación a la familia.
     * La alerta se reintenta durante unos minutos si no hay cobertura.
     */
    fun trigger(ctx: Context) {
        val app = ctx.applicationContext
        val contact = Store.config.value.sosContact
        if (contact != null) CallManager.placeCall(ctx, contact.phone)

        CercaApp.scope.launch {
            val loc = Locator.current(app, timeoutMs = 25_000)
            if (loc != null) runCatching { Sync.sendLocation(Locator.toFix(app, loc, "sos")) }
            val req = AlertRequest("sos", loc?.latitude, loc?.longitude)
            for (attempt in 1..10) {
                try {
                    withContext(Dispatchers.IO) { Api.alert(req) }
                    return@launch
                } catch (e: Exception) {
                    Log.w("Sos", "Intento $attempt fallido: ${e.message}")
                    delay(20_000L * attempt)
                }
            }
        }
    }
}
