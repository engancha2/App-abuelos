package com.estudiofenix.cerca.familia.ui.tabs

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.estudiofenix.cerca.familia.data.Alert
import com.estudiofenix.cerca.familia.data.FamilyApi
import com.estudiofenix.cerca.familia.data.friendlyError
import com.estudiofenix.cerca.familia.ui.EmptyState
import com.estudiofenix.cerca.familia.ui.FamiliaColors
import com.estudiofenix.cerca.familia.ui.Loading
import com.estudiofenix.cerca.familia.ui.SeniorViewModel
import com.estudiofenix.cerca.familia.ui.timeFmt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Icono y título corto de cada tipo de aviso (los mismos que la notificación que manda el servidor). */
private class Kind(val icon: String, val title: String)

private val KINDS = mapOf(
    "sos" to Kind("🆘", "SOS"),
    "zone_exit" to Kind("📍", "Ha salido de la zona"),
    "zone_enter" to Kind("🏠", "Ha vuelto a la zona"),
    "low_battery" to Kind("🔋", "Batería baja"),
    "offline" to Kind("📵", "Sin conexión"),
    "setup_problem" to Kind("⚠️", "Revisa el móvil"),
    "auto_answer" to Kind("📞", "Llamada contestada sola"),
)
private val OTHER = Kind("🔔", "Aviso")

/** Pestaña «Avisos»: lo que ha pasado en su móvil (SOS, zonas, batería…), lo último arriba. Igual que tabAlerts del panel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsTab(vm: SeniorViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var alerts by remember { mutableStateOf<List<Alert>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var acking by remember { mutableStateOf(false) }

    suspend fun load() {
        loading = true
        try {
            alerts = FamilyApi.alerts(vm.seniorId)
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Si ya había lista, se deja como estaba y solo se avisa
            if (alerts == null) error = friendlyError(e) else vm.toast(friendlyError(e))
        } finally {
            loading = false
        }
    }

    // Al abrir la pestaña y cuando cambia el número de avisos sin ver (el ⟳ de arriba, un aviso nuevo…).
    // Si la lista ya cuadra con ese número (p. ej. justo después de "Marcar todo como visto"), no se vuelve a pedir.
    val pending = vm.detail?.pendingAlerts ?: 0
    LaunchedEffect(pending) {
        val shown = alerts
        if (shown == null || shown.count { !it.seen } != pending) load()
    }

    fun ackAll() {
        if (acking) return
        acking = true
        vm.launch {
            try {
                FamilyApi.ackAlerts(vm.seniorId)
                load()
                vm.refresh() // para que se actualice el contador de la pestaña
            } finally {
                acking = false
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = loading && alerts != null,
        onRefresh = {
            scope.launch { load() }
            vm.refresh()
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        val list = alerts
        when {
            list == null && error != null -> EmptyState("No se han podido cargar los avisos", error, "Reintentar") { scope.launch { load() } }
            list == null -> Loading()
            // Siempre una lista, aunque esté vacía, para que se pueda deslizar hacia abajo y actualizar
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (list.isEmpty()) item(key = "empty") {
                    EmptyState("Sin avisos. ¡Todo tranquilo!", "Aquí aparecerán los SOS, las salidas de zona, la batería baja…")
                }
                if (list.any { !it.seen }) item(key = "ack") {
                    OutlinedButton(onClick = { ackAll() }, enabled = !acking, modifier = Modifier.fillMaxWidth()) {
                        if (acking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("✓  Marcar todo como visto")
                    }
                }
                items(list, key = { it.id }) { a ->
                    AlertCard(a, onMap = {
                        if (a.lat != null && a.lng != null && !openMap(context, a.lat, a.lng)) vm.toast("No hay ninguna app para ver mapas en este móvil")
                    })
                }
            }
        }
    }
}

@Composable
private fun AlertCard(alert: Alert, onMap: () -> Unit) {
    val kind = KINDS[alert.type] ?: OTHER
    val sos = alert.type == "sos"
    val unseen = !alert.seen
    val loud = sos && unseen // SOS sin ver: la tarjeta entera en rojo
    val container = when {
        loud -> FamiliaColors.danger
        unseen -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    val onContainer = if (loud) Color.White else MaterialTheme.colorScheme.onSurface
    val soft = if (loud) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
    // SOS ya visto: borde rojo. Cualquier otro sin ver: borde del color de la app.
    val border = when {
        sos && !unseen -> BorderStroke(2.dp, FamiliaColors.danger)
        unseen && !sos -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        else -> null
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer),
        border = border,
        elevation = CardDefaults.cardElevation(defaultElevation = if (unseen) 3.dp else 1.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(kind.icon, fontSize = if (sos) 36.sp else 28.sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        kind.title,
                        modifier = Modifier.weight(1f),
                        style = if (sos) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (sos && !loud) FamiliaColors.danger else onContainer,
                    )
                    if (unseen) NewTag(loud)
                }
                Text(
                    alert.message,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (unseen) FontWeight.Medium else FontWeight.Normal,
                )
                Text(timeFmt(alert.ts), style = MaterialTheme.typography.bodyMedium, color = soft)
                if (alert.lat != null && alert.lng != null) {
                    OutlinedButton(
                        onClick = onMap,
                        modifier = Modifier.padding(top = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (loud) Color.White else MaterialTheme.colorScheme.primary),
                        border = BorderStroke(1.dp, if (loud) Color.White else MaterialTheme.colorScheme.outline),
                    ) { Text("📍  Ver en el mapa") }
                }
            }
        }
    }
}

/** Etiqueta "Nuevo" de los avisos sin ver. */
@Composable
private fun NewTag(onRed: Boolean) {
    Surface(
        color = if (onRed) Color.White else MaterialTheme.colorScheme.primary,
        contentColor = if (onRed) FamiliaColors.danger else MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(50),
    ) {
        Text("Nuevo", Modifier.padding(horizontal = 10.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

/** Abre la app de mapas en ese punto; si no hay ninguna, Google Maps en el navegador. false si tampoco hay navegador. */
private fun openMap(context: Context, lat: Double, lng: Double): Boolean {
    val options = listOf(
        Intent(Intent.ACTION_VIEW, "geo:$lat,$lng?q=$lat,$lng".toUri()),
        Intent(Intent.ACTION_VIEW, "https://www.google.com/maps?q=$lat,$lng".toUri()),
    )
    for (intent in options) {
        try {
            context.startActivity(intent)
            return true
        } catch (e: ActivityNotFoundException) {
            // Se prueba la siguiente
        }
    }
    return false
}
