package com.estudiofenix.cerca.familia.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.estudiofenix.cerca.familia.data.Billing
import com.estudiofenix.cerca.familia.data.friendlyError
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt

/** Lo que es de pago (lo mismo que PREMIUM_FEATURES en server/src/billing.js). */
private val FEATURES = listOf(
    "📍" to "Zonas seguras: aviso al momento si sale de casa o del barrio",
    "🗺️" to "Historial de recorridos: por dónde ha ido",
    "📞" to "Contestar sola: si no llega al teléfono, se contesta en manos libres",
    "🔔" to "Timbres fuertes y siempre a todo volumen",
)

/** "21 nov" */
private fun dayFmt(ts: Long): String = SimpleDateFormat("d MMM", spanish).format(Date(ts))

/**
 * Aviso arriba de la ficha: cuando quedan pocos días del mes gratis o ya se ha acabado.
 * Con la suscripción pagada (o un servidor sin pagos) no sale nada.
 */
@Composable
fun PlanBanner(vm: SeniorViewModel) {
    val plan = vm.plan
    val (text, urgent) = when {
        plan.isFree -> "Se ha acabado el mes gratis: las zonas seguras, el historial, contestar sola y los timbres fuertes están desactivados." to true
        plan.isTrial && plan.trialEndsAt != null && (plan.trialDaysLeft ?: 99) <= 7 ->
            "Quedan ${plan.trialDaysLeft} ${if (plan.trialDaysLeft == 1) "día" else "días"} del mes gratis." to false
        else -> return
    }
    Surface(
        color = if (urgent) FamiliaColors.warning.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(8.dp))
            if (urgent) Button(onClick = { vm.paywall = true }) { Text("Suscribirme") }
            else TextButton(onClick = { vm.paywall = true }) { Text("Ver planes") }
        }
    }
}

/**
 * En lugar de una opción de pago, cuando no hay plan: qué es y el botón para suscribirse.
 * La configuración que hubiera se guarda: al suscribirse vuelve tal cual.
 */
@Composable
fun PremiumLock(vm: SeniorViewModel, title: String, text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🔒  $title", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Hint(text)
            Button(onClick = { vm.paywall = true }) { Text("Ver planes") }
        }
    }
}

/** La suscripción en la pestaña Ajustes: en qué plan está y, si paga, cómo gestionarlo en Google Play. */
@Composable
fun SubscriptionSection(vm: SeniorViewModel) {
    val plan = vm.plan
    if (!plan.billingEnabled) return
    val context = LocalContext.current
    SectionTitle("Suscripción")
    SectionCard {
        when {
            plan.isPaid -> {
                Text("✅ Suscripción activa", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                plan.premiumUntil?.let {
                    Hint(if (plan.autoRenew) "Se renueva sola el ${dayFmt(it)}." else "Cancelada: sigue activa hasta el ${dayFmt(it)}.")
                }
                Hint("La paga una persona y vale para toda la familia.")
                OutlinedButton(onClick = { openPlaySubscriptions(context) }) { Text("Gestionar en Google Play") }
            }
            plan.isTrial && plan.trialEndsAt == null -> {
                Text("🎁 Mes gratis", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Hint("Empieza al conectar su móvil. Sin tarjeta: al acabar decides si te suscribes.")
            }
            plan.isTrial -> {
                Text("🎁 Mes gratis: quedan ${plan.trialDaysLeft} días", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Hint("Hasta el ${dayFmt(plan.trialEndsAt!!)}. Sin tarjeta: al acabar decides si te suscribes.")
                OutlinedButton(onClick = { vm.paywall = true }) { Text("Ver planes") }
            }
            else -> {
                Text("Sin suscripción", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Hint("Su móvil sigue funcionando: llamadas, contactos, SOS y «¿dónde está ahora?».")
                Button(onClick = { vm.paywall = true }) { Text("Suscribirme") }
            }
        }
    }
}

/**
 * La ventana de los planes: qué incluye, los precios de Google Play (mensual y anual) y comprar.
 * El precio y la renovación se dicen claros antes de pagar, como pide Google Play.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallSheet(vm: SeniorViewModel) {
    if (!vm.paywall) return
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var offers by remember { mutableStateOf<List<Billing.Offer>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        try {
            offers = Billing.offers()
        } catch (e: Exception) {
            error = friendlyError(e)
        }
    }

    ModalBottomSheet(onDismissRequest = { if (!vm.purchasing) vm.paywall = false }, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Cuida de ${vm.detail?.name ?: "tu familiar"} con todo",
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            FEATURES.forEach { (icon, text) ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(icon)
                    Spacer(Modifier.width(12.dp))
                    Text(text, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Hint("Una sola suscripción vale para toda la familia de ${vm.detail?.name ?: "esta persona"}, también en el panel web. " +
                "Sin suscripción, su móvil sigue funcionando: llamadas, contactos, SOS y «¿dónde está ahora?».")

            val list = offers
            when {
                error != null -> {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { attempt++ }) { Text("Reintentar") }
                }
                list == null -> Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
                else -> {
                    val monthly = list.firstOrNull { it.period == "P1M" }
                    list.forEach { offer ->
                        val saving = if (offer.yearly && monthly != null && monthly.priceMicros > 0) {
                            (100 - offer.priceMicros * 100.0 / (monthly.priceMicros * 12)).roundToInt().takeIf { it >= 5 }
                        } else null
                        Button(
                            onClick = {
                                val activity = context.findActivity() ?: return@Button
                                vm.startPurchasing()
                                Billing.buy(activity, offer, vm.detail?.billingRef, vm.seniorId)?.let { problem ->
                                    vm.stopPurchasing()
                                    vm.toast(problem)
                                }
                            },
                            enabled = !vm.purchasing,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) {
                            Text(
                                "${offer.price} ${offer.periodLabel}" + (saving?.let { "  ·  ahorras un $it %" } ?: ""),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    if (vm.purchasing) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Hint("Comprobando el pago…")
                        }
                    }
                    Hint(
                        "Se cobra en tu cuenta de Google Play y se renueva sola hasta que la canceles. " +
                            "Puedes cancelarla cuando quieras en Google Play → Pagos y suscripciones.",
                    )
                }
            }
            TextButton(onClick = { vm.restorePurchases() }, enabled = !vm.purchasing, modifier = Modifier.fillMaxWidth()) {
                Text("Ya pagué: recuperar compra", textAlign = TextAlign.Center)
            }
        }
    }
}

/** Abre las suscripciones de Google Play (para cancelar o cambiar de plan). */
fun openPlaySubscriptions(context: Context) {
    val url = "https://play.google.com/store/account/subscriptions?sku=${Billing.PRODUCT_ID}&package=${context.packageName}"
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
