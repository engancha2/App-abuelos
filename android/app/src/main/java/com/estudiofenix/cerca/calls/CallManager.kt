package com.estudiofenix.cerca.calls

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.OutcomeReceiver
import android.provider.Telephony
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.InCallService
import android.telecom.PhoneAccountHandle
import android.telecom.PhoneAccountSuggestion
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.PhoneNumberUtils
import androidx.annotation.RequiresApi
import androidx.core.os.BundleCompat
import com.estudiofenix.cerca.data.Config
import com.estudiofenix.cerca.data.Phone
import com.estudiofenix.cerca.data.PhoneBook
import com.estudiofenix.cerca.data.Store
import com.estudiofenix.cerca.setup.DeviceChecks
import com.estudiofenix.cerca.setup.SetupActivity
import java.lang.ref.WeakReference
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Estado de las llamadas en curso, compartido entre el InCallService y la pantalla de llamada. */
object CallManager {

    data class Snapshot(val calls: List<Call> = emptyList(), val version: Int = 0) {
        /**
         * La llamada que se enseña: primero la que suena, luego la activa, luego la que se está marcando
         * y, por último, la que está en espera (antes salía la de espera aunque se estuviera marcando otra).
         */
        val primary: Call?
            get() = calls.filter { it.stateCompat != Call.STATE_DISCONNECTED }.minByOrNull { priority(it.stateCompat) }

        /** Las demás llamadas vivas, p. ej. la que queda en espera al contestar una segunda. */
        val others: List<Call>
            get() {
                val main = primary
                return calls.filter { it != main && it.stateCompat != Call.STATE_DISCONNECTED }
            }
    }

    /** Quién es, para enseñarlo: los contactos de la familia primero, después la agenda del móvil y, si no, el número. */
    data class Who(
        val name: String,
        val photo: String? = null,      // foto del servidor (favoritos)
        val photoUri: String? = null,   // foto de la agenda de Android
        val favorite: Boolean = false,
        val inPhoneBook: Boolean = false,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /**
     * Si el audio va por el altavoz ("manos libres"). Android 14+ lo cuenta con CallEndpoint; por el aviso antiguo
     * (CallAudioState) Android 16 seguía diciendo "auricular" con el altavoz puesto, y el manos libres de
     * "contestar sola" creía que ya estaba y no insistía.
     */
    private val _speaker = MutableStateFlow(false)
    val speaker: StateFlow<Boolean> = _speaker.asStateFlow()
    private var endpoints: List<CallEndpoint> = emptyList() // salidas posibles (Android 14+)

    /** Nombre y foto en la agenda de Android de los números de las llamadas en curso. Solo para enseñarlos. */
    private val _callers = MutableStateFlow<Map<String, PhoneBook.Caller>>(emptyMap())
    val callers: StateFlow<Map<String, PhoneBook.Caller>> = _callers.asStateFlow()

    private var service: WeakReference<InCallService>? = null

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = publish()
        override fun onDetailsChanged(call: Call, details: Call.Details) = publish()
    }

    private fun publish(calls: List<Call> = _state.value.calls) {
        _state.value = Snapshot(calls, _state.value.version + 1)
    }

    fun attach(svc: InCallService?) {
        service = svc?.let { WeakReference(it) }
        // Telecom suelta el servicio cuando ya no hay llamadas: que no quede ninguna vieja a la vista
        if (svc == null) {
            _state.value.calls.forEach { it.unregisterCallback(callback) }
            publish(emptyList())
            _speaker.value = false
            endpoints = emptyList()
        }
    }

    fun add(call: Call) {
        // Primera llamada de una tanda: los nombres buscados para las anteriores ya no hacen falta
        if (_state.value.calls.isEmpty()) _callers.value = emptyMap()
        call.registerCallback(callback)
        publish(_state.value.calls + call)
    }

    fun remove(call: Call) {
        call.unregisterCallback(callback)
        val rest = _state.value.calls - call
        // Que la siguiente llamada no empiece creyendo que el altavoz está puesto
        if (rest.isEmpty()) _speaker.value = false
        publish(rest)
    }

    fun onAudio(state: CallAudioState?) {
        if (Build.VERSION.SDK_INT < 34) _speaker.value = state?.route == CallAudioState.ROUTE_SPEAKER
    }

    @RequiresApi(34)
    fun onEndpoint(endpoint: CallEndpoint) {
        _speaker.value = endpoint.endpointType == CallEndpoint.TYPE_SPEAKER
    }

    @RequiresApi(34)
    fun onEndpoints(available: List<CallEndpoint>) {
        endpoints = available
    }

    /**
     * Busca el número en la agenda del móvil, fuera del hilo principal. Los favoritos ya tienen nombre:
     * solo se buscan si la familia no les ha puesto foto, para usar la de la agenda.
     */
    suspend fun lookUpCaller(ctx: Context, number: String?) {
        if (number.isNullOrBlank() || number in _callers.value) return
        val favorite = Store.config.value.contactForNumber(number)
        if (favorite != null && favorite.photo != null) return
        val found = withContext(Dispatchers.IO) { PhoneBook.lookup(ctx, number) } ?: return
        _callers.update { it + (number to found) }
    }

    fun who(cfg: Config, number: String?, book: Map<String, PhoneBook.Caller> = _callers.value): Who {
        val entry = number?.let { book[it] }
        cfg.contactForNumber(number)?.let { c ->
            return Who(c.name, photo = c.photo, photoUri = entry?.photo.takeIf { c.photo == null }, favorite = true)
        }
        if (entry != null) return Who(entry.name, photoUri = entry.photo, inPhoneBook = true)
        return Who(number?.takeIf { it.isNotBlank() }?.let(::pretty) ?: "Número oculto")
    }

    /** El número con espacios, que se lee mejor: 600 11 22 33. */
    fun pretty(number: String): String =
        runCatching { PhoneNumberUtils.formatNumber(number, Locale.getDefault().country.ifEmpty { "ES" }) }.getOrNull()
            ?: number

    fun answer(call: Call) = call.answer(VideoProfile.STATE_AUDIO_ONLY)

    fun hangUp(call: Call) {
        if (call.stateCompat == Call.STATE_RINGING) call.reject(false, null) else call.disconnect()
    }

    /** Manos libres sí o no. Al quitarlo vuelve al manos libres del coche (Bluetooth) si lo hay; si no, al auricular. */
    fun setSpeaker(on: Boolean) {
        val svc = service?.get() ?: return
        if (Build.VERSION.SDK_INT >= 34) {
            val types = if (on) listOf(CallEndpoint.TYPE_SPEAKER)
            else listOf(CallEndpoint.TYPE_BLUETOOTH, CallEndpoint.TYPE_WIRED_HEADSET, CallEndpoint.TYPE_EARPIECE)
            val target = types.firstNotNullOfOrNull { type -> endpoints.firstOrNull { it.endpointType == type } }
            if (target != null) {
                svc.requestCallEndpointChange(target, svc.mainExecutor, object : OutcomeReceiver<Void, CallEndpointException> {
                    override fun onResult(result: Void?) {}
                    override fun onError(error: CallEndpointException) = legacyRoute(svc, on)
                })
                return
            }
        }
        legacyRoute(svc, on)
    }

    @Suppress("DEPRECATION") // la forma de Android 10–13; en 14+ solo si aún no hay lista de salidas
    private fun legacyRoute(svc: InCallService, on: Boolean) {
        svc.setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE)
    }

    val speakerOn: Boolean get() = _speaker.value

    /**
     * Móviles con dos SIM y "preguntar siempre": Android deja la llamada esperando a que la app de teléfono
     * pregunte con qué tarjeta. Devuelve las tarjetas con su nombre ("Movistar") o "Tarjeta 1".
     */
    @SuppressLint("MissingPermission") // se comprueba READ_PHONE_STATE antes de usarlo
    fun phoneAccounts(ctx: Context, call: Call): List<Pair<PhoneAccountHandle, String>> {
        val telecom = ctx.getSystemService(TelecomManager::class.java)
        val extras = call.details.intentExtras
        val offered = when {
            extras == null -> emptyList()
            Build.VERSION.SDK_INT >= 31 -> BundleCompat
                .getParcelableArrayList(extras, Call.EXTRA_SUGGESTED_PHONE_ACCOUNTS, PhoneAccountSuggestion::class.java)
                .orEmpty().map { it.phoneAccountHandle }
            else -> legacyAccounts(extras)
        }
        // Si Android no las manda con la llamada, todas las tarjetas que pueden llamar
        val handles = offered.ifEmpty {
            if (DeviceChecks.granted(ctx, Manifest.permission.READ_PHONE_STATE)) {
                runCatching { telecom.callCapablePhoneAccounts }.getOrNull().orEmpty()
            } else emptyList()
        }
        val labels = handles.mapIndexed { i, h ->
            runCatching { telecom.getPhoneAccount(h)?.label?.toString()?.trim() }.getOrNull()
                ?.takeIf { it.isNotEmpty() } ?: "Tarjeta ${i + 1}"
        }
        // Dos tarjetas de la misma compañía: se numeran para poder distinguirlas
        return handles.mapIndexed { i, h ->
            h to if (labels.count { it == labels[i] } > 1) "${labels[i]} (${i + 1})" else labels[i]
        }
    }

    @Suppress("DEPRECATION") // la lista de Android 10 y 11; desde el 12, EXTRA_SUGGESTED_PHONE_ACCOUNTS
    private fun legacyAccounts(extras: Bundle): List<PhoneAccountHandle> =
        BundleCompat.getParcelableArrayList(extras, Call.AVAILABLE_PHONE_ACCOUNTS, PhoneAccountHandle::class.java).orEmpty()

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
        } catch (_: Exception) {
            // SecurityException sin permiso; algunos sistemas lanzan otras si el teléfono no está disponible
            false
        }
    }

    /**
     * Abre la app de mensajes (SMS) del móvil; con [number], directamente la conversación con esa persona.
     * Cerca no es la app de SMS: usa la que tenga el móvil (Mensajes de Google, la de Xiaomi…).
     */
    fun openSms(ctx: Context, number: String? = null): Boolean {
        val intent = if (number != null) {
            Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", Phone.dialable(number), null))
        } else {
            Telephony.Sms.getDefaultSmsPackage(ctx)?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
                ?: Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)
        }
        return try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false // ninguna app de mensajes
        }
    }

}

/** Orden de [CallManager.Snapshot.primary]: cuanto más bajo, antes se enseña. */
private fun priority(state: Int) = when (state) {
    Call.STATE_RINGING -> 0
    Call.STATE_ACTIVE -> 1
    Call.STATE_HOLDING -> 3
    else -> 2 // marcando, conectando, eligiendo tarjeta…
}

@Suppress("DEPRECATION")
val Call.stateCompat: Int
    get() = if (Build.VERSION.SDK_INT >= 31) details.state else state

val Call.number: String?
    get() = details?.handle?.schemeSpecificPart
