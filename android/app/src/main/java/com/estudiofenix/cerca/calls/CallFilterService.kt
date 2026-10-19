package com.estudiofenix.cerca.calls

import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import com.estudiofenix.cerca.data.Phone
import com.estudiofenix.cerca.data.Store

/**
 * Filtro de llamadas entrantes. Como somos la app de teléfono predeterminada, Android nos pasa
 * todas las llamadas entrantes antes de que suenen.
 */
class CallFilterService : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        val response = CallScreeningService.CallResponse.Builder()

        if (details.callDirection == Call.Details.DIRECTION_INCOMING) {
            val cfg = Store.config.value
            val number = details.handle?.schemeSpecificPart
            val known = cfg.contactForNumber(number) != null

            if (!known && cfg.calls.filter != "all") {
                val key = Phone.matchKey(number)
                // "Si insiste": un desconocido que llama 2 veces en 5 minutos pasa (puede ser una urgencia)
                val insists = cfg.calls.allowRepeated && key != null &&
                    Store.registerUnknownCall(key, System.currentTimeMillis())

                if (!insists) {
                    when (cfg.calls.filter) {
                        "block_unknown" -> response
                            .setDisallowCall(true)
                            .setRejectCall(true)
                            .setSkipCallLog(false)        // que quede en el registro para la familia
                            .setSkipNotification(false)
                        "silence_unknown" -> response.setSilenceCall(true)
                    }
                    Log.i("CallFilter", "Llamada de desconocido filtrada (${cfg.calls.filter})")
                }
            }
        }
        respondToCall(details, response.build())
    }
}
