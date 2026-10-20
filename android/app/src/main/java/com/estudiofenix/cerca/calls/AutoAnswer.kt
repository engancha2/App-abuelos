package com.estudiofenix.cerca.calls

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.telecom.Call
import android.telecom.DisconnectCause
import android.util.Log
import com.estudiofenix.cerca.CercaApp
import com.estudiofenix.cerca.data.AlertRequest
import com.estudiofenix.cerca.data.Api
import com.estudiofenix.cerca.data.Phone
import com.estudiofenix.cerca.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob

/**
 * "Contestar sola": si un favorito (la familia) llama varias veces seguidas y nadie contesta, la siguiente
 * llamada se contesta sola con manos libres y el volumen al máximo. Es para cuando la persona mayor se ha caído
 * y no llega al móvil. Funciona sin internet: solo cuenta las llamadas perdidas en este móvil.
 *
 * Para que no sirva para escuchar a escondidas: viene desactivado, nunca contesta a la primera, solo a
 * favoritos, primero suena un rato, pita al abrir el micrófono, se ve en la pantalla y avisa a la familia.
 */
object AutoAnswer {
    /** Las llamadas tienen que ser seguidas: todas en este tiempo. */
    const val WINDOW_MS = 15 * 60_000L
    /** Antes de contestar sola, suena: si puede cogerla, que la coja él. */
    private const val RING_FIRST_MS = 8_000L
    private const val TAG = "AutoAnswer"

    /** La llamada que se ha contestado sola, para enseñarlo en la pantalla de llamada. */
    data class Info(val call: Call, val name: String, val times: Int)

    private val _current = MutableStateFlow<Info?>(null)
    val current: StateFlow<Info?> = _current.asStateFlow()

    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun onCallAdded(ctx: Context, call: Call) {
        if (call.details.callDirection != Call.Details.DIRECTION_INCOMING) return
        val cfg = Store.config.value
        val times = cfg.calls.autoAnswerOnCall
        if (times < 2) return
        val contact = cfg.contactForNumber(call.number) ?: return
        val key = Phone.matchKey(call.number) ?: return
        // Esta es la llamada número "times": antes tiene que haber times - 1 perdidas seguidas
        if (Store.missedFavoriteCount(key, System.currentTimeMillis(), WINDOW_MS) < times - 1) return

        main.launch {
            delay(RING_FIRST_MS)
            if (call.stateCompat != Call.STATE_RINGING) return@launch
            // Con otra llamada en curso no se corta nada: suena como siempre
            if (CallManager.state.value.calls.any { it != call && it.stateCompat == Call.STATE_ACTIVE }) return@launch
            Log.i(TAG, "Contestando sola la llamada de ${contact.name} ($times seguidas)")
            _current.value = Info(call, contact.name, times)
            call.registerCallback(object : Call.Callback() {
                override fun onStateChanged(c: Call, state: Int) {
                    if (state == Call.STATE_ACTIVE) main.launch { onConnected(ctx, c, contact.name, times) }
                    if (state == Call.STATE_ACTIVE || state == Call.STATE_DISCONNECTED) c.unregisterCallback(this)
                }
            })
            CallManager.answer(call)
        }
    }

    fun onCallRemoved(call: Call) {
        if (_current.value?.call == call) _current.value = null
        val key = Phone.matchKey(call.number) ?: return
        if (call.details.callDirection != Call.Details.DIRECTION_INCOMING) {
            Store.clearMissedFavorite(key) // si devuelve la llamada, es que está bien
            return
        }
        if (Store.config.value.contactForNumber(call.number) == null) return
        if (call.details.disconnectCause?.code == DisconnectCause.MISSED) {
            Store.registerMissedFavorite(key, System.currentTimeMillis(), WINDOW_MS)
        } else {
            Store.clearMissedFavorite(key) // la ha cogido o la ha rechazado él
        }
    }

    private suspend fun onConnected(ctx: Context, call: Call, name: String, times: Int) {
        val audio = ctx.getSystemService(AudioManager::class.java)
        // Justo después de contestar, Android coloca el audio de la llamada y lo devuelve al auricular:
        // se pide el altavoz hasta que se queda puesto (como mucho unos 6 s)
        for (i in 0 until 12) {
            if (call.stateCompat != Call.STATE_ACTIVE) return
            if (CallManager.speakerOn) break
            CallManager.setSpeaker(true)
            delay(500)
        }
        // El volumen, ya con el altavoz puesto: Android guarda uno para cada salida (antes se subía el del auricular)
        maxVolume(audio)
        // Un pitido avisa de que el micrófono está abierto
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 100) }.getOrNull()
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 600)
        delay(1_000)
        tone?.release()
        // Unos segundos más vigilando: si vuelve al auricular, se vuelve a poner el altavoz y su volumen
        repeat(10) {
            if (call.stateCompat != Call.STATE_ACTIVE) return
            if (!CallManager.speakerOn) {
                CallManager.setSpeaker(true)
                delay(400)
            }
            maxVolume(audio)
            delay(600)
        }

        CercaApp.scope.launch {
            runCatching { Api.alert(AlertRequest("auto_answer", contactName = name)) }
                .onFailure { Log.w(TAG, "No se pudo avisar a la familia: ${it.message}") }
        }
        Log.i(TAG, "Manos libres ${if (CallManager.speakerOn) "puesto" else "NO puesto"} ($name, $times llamadas)")
    }

    private fun maxVolume(audio: AudioManager) {
        runCatching {
            audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0)
        }.onFailure { Log.w(TAG, "No se pudo subir el volumen: ${it.message}") }
    }
}
