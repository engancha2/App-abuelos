package com.estudiofenix.cerca.familia.data

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.estudiofenix.cerca.familia.BuildConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * La suscripción con Google Play (Play Billing Library).
 *
 * El mes gratis no pasa por aquí: lo cuenta el servidor desde que se empareja su móvil, sin tarjeta.
 * Aquí solo se compra la suscripción (mensual o anual, los "planes básicos" del producto [PRODUCT_ID]
 * en Play Console). Cada compra es para una persona mayor: se le pasa a Google su id (obfuscatedProfileId)
 * y el servidor la comprueba con Google, la confirma y le da el plan a toda su familia.
 */
object Billing {
    val PRODUCT_ID: String = BuildConfig.PREMIUM_PRODUCT_ID

    /** Lo que pasa al volver de la ventana de pago de Google. */
    sealed interface Event {
        data class Purchased(val purchases: List<Purchase>) : Event
        data object Cancelled : Event
        data class Failed(val message: String) : Event
    }

    /** Un plan que se puede comprar: "4,99 €" cada "P1M" (mes) o "P1Y" (año). */
    data class Offer(
        val basePlanId: String,
        val price: String,
        val period: String,
        val priceMicros: Long,
        val offerToken: String,
        val product: ProductDetails,
    ) {
        val yearly: Boolean get() = period == "P1Y"
        val periodLabel: String get() = when (period) {
            "P1M" -> "al mes"
            "P3M" -> "cada 3 meses"
            "P6M" -> "cada 6 meses"
            "P1Y" -> "al año"
            else -> period
        }
    }

    private lateinit var client: BillingClient
    private val connecting = Mutex()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun init(ctx: Context) {
        client = BillingClient.newBuilder(ctx.applicationContext)
            .setListener { result, purchases ->
                when (result.responseCode) {
                    BillingClient.BillingResponseCode.OK -> _events.tryEmit(Event.Purchased(purchases.orEmpty()))
                    BillingClient.BillingResponseCode.USER_CANCELED -> _events.tryEmit(Event.Cancelled)
                    // Ya la tenía (p. ej. compró en otro móvil): que se recupere
                    BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED ->
                        _events.tryEmit(Event.Failed("Ya tienes esta suscripción. Pulsa «Ya pagué: recuperar compra»."))
                    else -> _events.tryEmit(Event.Failed(message(result)))
                }
            }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
    }

    /** Conecta con Google Play (la primera vez); false si en este móvil no hay Google Play o falla. */
    private suspend fun ready(): Boolean = connecting.withLock {
        if (client.isReady) return@withLock true
        suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }

    /** Los planes a la venta, el mensual primero. Lanza una excepción con un mensaje que se entiende si no se pueden ver. */
    suspend fun offers(): List<Offer> {
        if (!ready()) throw IllegalStateException("No se puede conectar con Google Play. Comprueba que este móvil tiene Google Play y conexión.")
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build(),
            ),
        ).build()
        val details: List<ProductDetails> = suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { result, found ->
                if (!cont.isActive) return@queryProductDetailsAsync
                if (result.responseCode == BillingClient.BillingResponseCode.OK) cont.resume(found.productDetailsList)
                else cont.resume(emptyList())
            }
        }
        return details.flatMap { product ->
            product.subscriptionOfferDetails.orEmpty()
                // Solo los planes básicos: la prueba gratis no es de Google, es nuestra (sin tarjeta)
                .filter { it.offerId == null }
                .mapNotNull { offer ->
                    val phase = offer.pricingPhases.pricingPhaseList.lastOrNull() ?: return@mapNotNull null
                    Offer(offer.basePlanId, phase.formattedPrice, phase.billingPeriod, phase.priceAmountMicros, offer.offerToken, product)
                }
        }.sortedBy { if (it.yearly) 1 else 0 }
            .ifEmpty { throw IllegalStateException("Las suscripciones aún no están disponibles. Inténtalo más tarde.") }
    }

    /** Abre la ventana de pago de Google. El resultado llega por [events]. */
    fun buy(activity: Activity, offer: Offer, accountRef: String?, seniorId: Long): String? {
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(offer.product)
                        .setOfferToken(offer.offerToken)
                        .build(),
                ),
            )
            .apply { accountRef?.let { setObfuscatedAccountId(it) } }
            .setObfuscatedProfileId(seniorId.toString())
            .build()
        val result = client.launchBillingFlow(activity, params)
        return if (result.responseCode == BillingClient.BillingResponseCode.OK) null else message(result)
    }

    /** Las suscripciones de esta cuenta de Google en este móvil. */
    suspend fun purchases(): List<Purchase> {
        if (!ready()) return emptyList()
        return suspendCancellableCoroutine { cont ->
            client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
            ) { result, list ->
                if (cont.isActive) cont.resume(if (result.responseCode == BillingClient.BillingResponseCode.OK) list else emptyList())
            }
        }
    }

    /** Para qué persona es una compra (la que se le dijo a Google al comprar). */
    fun seniorOf(purchase: Purchase): Long? = purchase.accountIdentifiers?.obfuscatedProfileId?.toLongOrNull()

    /**
     * Al abrir la app: si alguna compra no llegó al servidor (sin conexión, app cerrada…), se manda ahora.
     * Importante: Google devuelve el dinero de las compras que no se confirman en 3 días.
     */
    suspend fun syncPending() {
        purchases()
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }
            .forEach { p -> seniorOf(p)?.let { id -> runCatching { FamilyApi.attachSubscription(id, p.purchaseToken) } } }
    }

    private fun message(result: BillingResult): String = when (result.responseCode) {
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        BillingClient.BillingResponseCode.NETWORK_ERROR -> "No se puede conectar con Google Play. Comprueba la conexión a internet."
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> "Google Play no permite pagar en este móvil o con esta cuenta."
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> "Esta suscripción no está disponible ahora mismo."
        else -> "No se ha podido completar el pago (${result.responseCode}). Inténtalo de nuevo."
    }
}
