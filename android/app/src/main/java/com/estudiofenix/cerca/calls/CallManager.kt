package com.estudiofenix.cerca.calls

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import com.estudiofenix.cerca.data.Phone
import com.estudiofenix.cerca.setup.DeviceChecks
import com.estudiofenix.cerca.setup.SetupActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/** Estado de las llamadas en curso, compartido entre el InCallService y la pantalla de llamada. */
object CallManager {

    data class Snapshot(val calls: List<Call> = emptyList(), val version: Int = 0) {
        /** La llamada que se enseña: primero la que suena, luego la activa, luego la que marca. */
        val primary: Call?
            get() = calls.firstOrNull { it.stateCompat == Call.STATE_RINGING }
                ?: calls.firstOrNull { it.stateCompat == Call.STATE_ACTIVE }
                ?: calls.firstOrNull { it.stateCompat != Call.STATE_DISCONNECTED }
    }

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    private val _audio = MutableStateFlow<CallAudioState?>(null)
    val audio: StateFlow<CallAudioState?> = _audio.asStateFlow()

    private var service: WeakReference<InCallService>? = null

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = publish()
        override fun onDetailsChanged(call: Call, details: Call.Details) = publish()
    }

    private fun publish(calls: List<Call> = _state.value.calls) {
        _state.value = Snapshot(calls, _state.value.version + 1)
    }

    fun attach(svc: InCallService?) { service = svc?.let { WeakReference(it) } }

    fun add(call: Call) {
        call.registerCallback(callback)
        publish(_state.value.calls + call)
    }

    fun remove(call: Call) {
        call.unregisterCallback(callback)
        publish(_state.value.calls - call)
    }

    fun onAudio(state: CallAudioState?) { _audio.value = state }

    fun answer(call: Call) = call.answer(VideoProfile.STATE_AUDIO_ONLY)

    fun hangUp(call: Call) {
        if (call.stateCompat == Call.STATE_RINGING) call.reject(false, null) else call.disconnect()
    }

    @Suppress("DEPRECATION") // setAudioRoute sigue funcionando y es lo más compatible con Android 10–15
    fun setSpeaker(on: Boolean) {
        service?.get()?.setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE)
    }

    val speakerOn: Boolean get() = _audio.value?.route == CallAudioState.ROUTE_SPEAKER

    // ---------------------------------------------------------------- Llamar
    /** Llama directamente (somos la app de teléfono). Si falta el permiso, abre el asistente. */
    fun placeCall(ctx: Context, number: String): Boolean {
        val dial = Phone.dialable(number)
        if (dial.isEmpty()) return false
        if (!DeviceChecks.granted(ctx, Manifest.permission.CALL_PHONE)) {
            ctx.startActivity(Intent(ctx, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        return try {
            ctx.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", dial, null), Bundle())
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /** Abre el chat de WhatsApp con el contacto (WhatsApp no permite iniciar videollamadas desde otra app). */
    fun openWhatsApp(ctx: Context, number: String): Boolean {
        var digits = number.filter { it.isDigit() }
        if (number.trim().startsWith("00")) digits = digits.drop(2)
        else if (!number.trim().startsWith("+") && digits.length == 9) digits = "34$digits" // número español sin prefijo
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try {
                ctx.startActivity(Intent(intent).setPackage(pkg))
                return true
            } catch (_: Exception) { }
        }
        return false
    }
}

@Suppress("DEPRECATION")
val Call.stateCompat: Int
    get() = if (Build.VERSION.SDK_INT >= 31) details.state else state

val Call.number: String?
    get() = details?.handle?.schemeSpecificPart
