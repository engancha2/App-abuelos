package com.estudiofenix.cerca.familia.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.Purchase
import com.estudiofenix.cerca.data.Config
import com.estudiofenix.cerca.familia.data.Billing
import com.estudiofenix.cerca.familia.data.FamilyApi
import com.estudiofenix.cerca.familia.data.Plan
import com.estudiofenix.cerca.familia.data.SeniorDetail
import com.estudiofenix.cerca.familia.data.friendlyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Una persona mayor abierta en la app. Todas las pestañas comparten esto:
 * - [detail]: lo último que ha dicho el servidor (estado del móvil, apps, familia…).
 * - [draft]: la configuración que se está editando. Las pestañas la cambian con [edit] y nada se envía
 *   hasta pulsar "Guardar" ([save]), igual que en el panel web.
 * - [launch]: para cualquier otra llamada al servidor; si falla, sale un mensaje y no hay que hacer nada más.
 */
class SeniorViewModel(val seniorId: Long) : ViewModel() {

    var detail by mutableStateOf<SeniorDetail?>(null)
        private set
    var draft by mutableStateOf<Config?>(null)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var saving by mutableStateOf(false)
        private set

    /** Hay cambios sin guardar (si se deshacen a mano, deja de haberlos). */
    val dirty: Boolean get() = draft != null && draft != detail?.config

    private val _messages = Channel<String>(Channel.BUFFERED)
    /** Mensajes cortos para la barra de abajo ("Guardado y enviado al móvil", errores…). */
    val messages = _messages.receiveAsFlow()

    /** La ventana de la suscripción (planes y precios) está abierta. */
    var paywall by mutableStateOf(false)

    /** Comprando: desde que se abre el pago de Google hasta que el servidor lo ha comprobado. */
    var purchasing by mutableStateOf(false)
        private set

    val plan: Plan get() = detail?.plan ?: Plan()

    /** Tiene lo de pago (mes gratis o suscripción). Con un servidor sin pagos, siempre. */
    val premium: Boolean get() = plan.premium

    init {
        refresh()
        // Lo que pasa al volver del pago de Google. Solo las compras de esta persona (puede haber varias abiertas).
        viewModelScope.launch {
            Billing.events.collect { event ->
                when (event) {
                    is Billing.Event.Purchased -> event.purchases
                        .filter { (Billing.seniorOf(it) ?: seniorId) == seniorId }
                        .forEach { onPurchase(it) }
                    Billing.Event.Cancelled -> purchasing = false
                    is Billing.Event.Failed -> { purchasing = false; toast(event.message) }
                }
            }
        }
    }

    fun startPurchasing() {
        purchasing = true
    }

    fun stopPurchasing() {
        purchasing = false
    }

    private fun onPurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            purchasing = false
            paywall = false
            toast("El pago está pendiente. La suscripción se activará cuando Google lo confirme.")
            return
        }
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        launch {
            try {
                FamilyApi.attachSubscription(seniorId, purchase.purchaseToken)
                reload()
                paywall = false
                toast("¡Suscripción activada! Ya vuelve todo a su móvil")
            } finally {
                purchasing = false
            }
        }
    }

    /** "Ya pagué": manda al servidor las suscripciones de esta cuenta de Google para esta persona. */
    fun restorePurchases() {
        launch {
            purchasing = true
            try {
                val bought = Billing.purchases().filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                if (bought.isEmpty()) {
                    toast("No hay ninguna suscripción con la cuenta de Google de este móvil")
                    return@launch
                }
                // La que se compró para esta persona primero; si no, la que haya (p. ej. de alguien que se borró)
                val sorted = bought.sortedBy { if (Billing.seniorOf(it) == seniorId) 0 else 1 }
                var lastError: Exception? = null
                for (p in sorted) {
                    try {
                        FamilyApi.attachSubscription(seniorId, p.purchaseToken)
                        reload()
                        if (premium) {
                            paywall = false
                            toast("Suscripción recuperada")
                            return@launch
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastError = e
                    }
                }
                toast(lastError?.let(::friendlyError) ?: "La suscripción de este móvil ya no está activa")
            } finally {
                purchasing = false
            }
        }
    }

    fun toast(message: String) {
        _messages.trySend(message)
    }

    /** Ejecuta algo contra el servidor; si falla, lo cuenta con un mensaje. */
    fun launch(block: suspend () -> Unit): Job = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toast(friendlyError(e))
        }
    }

    /** Vuelve a pedir los datos. Los cambios sin guardar se conservan. */
    fun refresh(): Job = viewModelScope.launch {
        try {
            reload()
            loadError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (detail == null) loadError = friendlyError(e) else toast(friendlyError(e))
        }
    }

    // Sube con cada guardado: una recarga que salió antes y responde después traería datos viejos
    private var generation = 0

    /** Como [refresh], pero para usar dentro de [launch] (los errores los gestiona quien llama). */
    suspend fun reload() {
        val asked = generation
        val fresh = FamilyApi.senior(seniorId)
        if (asked != generation) return
        val keep = dirty
        detail = fresh
        if (!keep) draft = fresh.config
    }

    /** Cambia la configuración en edición, p. ej. `vm.edit { it.copy(calls = it.calls.copy(filter = "all")) }`. */
    fun edit(change: (Config) -> Config) {
        draft = draft?.let(change)
    }

    fun discard() {
        draft = detail?.config
    }

    fun save() {
        val cfg = draft ?: return
        if (saving) return
        // Lo mismo que rechazaría el servidor, pero dicho antes y con una explicación
        if (cfg.location.mode == "zone" && cfg.location.zones.isEmpty()) {
            toast("El modo «solo si sale de una zona» necesita al menos una zona: añádela en Ubicación tocando el mapa")
            return
        }
        launch {
            saving = true
            generation++
            try {
                FamilyApi.saveConfig(seniorId, cfg)
                // El servidor la normaliza (teléfonos, ids…): a partir de ahora, la suya
                val fresh = FamilyApi.senior(seniorId)
                detail = fresh
                draft = fresh.config
                toast(if (fresh.reachable) "Guardado y enviado al móvil" else "Guardado. Le llegará al móvil cuando se conecte")
            } finally {
                saving = false
            }
        }
    }
}
