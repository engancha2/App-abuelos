package com.estudiofenix.cerca.familia.ui.tabs

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.estudiofenix.cerca.data.LocationConfig
import com.estudiofenix.cerca.familia.data.FamilyApi
import com.estudiofenix.cerca.familia.data.LocationPoint
import com.estudiofenix.cerca.familia.data.friendlyError
import com.estudiofenix.cerca.familia.ui.EmptyState
import com.estudiofenix.cerca.familia.ui.FamiliaColors
import com.estudiofenix.cerca.familia.ui.Hint
import com.estudiofenix.cerca.familia.ui.SectionCard
import com.estudiofenix.cerca.familia.ui.SeniorViewModel
import com.estudiofenix.cerca.familia.ui.StatusDot
import com.estudiofenix.cerca.familia.ui.ago
import com.estudiofenix.cerca.familia.ui.timeFmt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.osmdroid.util.GeoPoint
import java.util.Locale
import kotlin.math.roundToInt

/** El selector del recorrido: horas hacia atrás y su texto, los mismos que en el panel. */
private val RANGES = listOf(3 to "Últimas 3 horas", 24 to "24 horas", 72 to "3 días", 168 to "Última semana")

private const val POLL_MS = 10_000L
private const val FAST_POLL_MS = 3_000L   // durante el minuto siguiente a "¿Dónde está ahora?"
private const val FAST_WINDOW_MS = 60_000L

/** "Modo actual: …", con las mismas etiquetas que el panel. */
private fun modeLabel(loc: LocationConfig): String = when (loc.mode) {
    "continuous" -> "Continua (cada ${loc.continuousSeconds} s)"
    "interval" -> "Cada ${loc.intervalMinutes} min"
    "zone" -> "Solo si sale de una zona"
    else -> "Desactivada"
}

/**
 * Pestaña «Mapa»: dónde está, su recorrido y las zonas seguras, como `tabMap` del panel.
 * Mientras se ve, se actualiza sola cada 10 s (cada 3 s durante el minuto siguiente a pedir la ubicación).
 */
@Composable
fun MapTab(vm: SeniorViewModel) {
    val detail = vm.detail ?: return
    if (!detail.paired) {
        EmptyState("Su móvil aún no está emparejado", "Primero empareja el móvil desde la pestaña Ajustes.")
        return
    }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val last = detail.lastLocation
    val zones = vm.draft?.location?.zones.orEmpty()

    var hours by rememberSaveable { mutableIntStateOf(24) }
    var track by remember { mutableStateOf<List<LocationPoint>?>(null) }   // null = aún cargando
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) } // para que "hace X min" no se quede viejo
    var failing by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var fastUntil by remember { mutableLongStateOf(0L) }
    // Al pedir "¿Dónde está ahora?": la hora de la posición que había entonces, para saber cuándo llega la nueva
    var askedAfter by remember { mutableStateOf<Long?>(null) }
    // La posición en la que ya se ha centrado el mapa (null = aún ninguna)
    var shownFix by remember { mutableStateOf(last?.ts) }

    // Se abre ya en su posición; si aún no hay, en su primera zona; si tampoco, España entera
    val firstZone = zones.firstOrNull()
    val mapState = rememberOsmMapState(
        lat = last?.lat ?: firstZone?.lat ?: OsmMapDefaults.LAT,
        lng = last?.lng ?: firstZone?.lng ?: OsmMapDefaults.LNG,
        zoom = if (last != null) 16.0 else if (firstZone != null) 15.0 else OsmMapDefaults.ZOOM,
    )

    // Actualización periódica, solo mientras la pestaña se ve y la app está en primer plano
    LaunchedEffect(lifecycle, hours, fastUntil) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try {
                    if (!vm.saving) vm.reload()
                    track = FamilyApi.locations(vm.seniorId, System.currentTimeMillis() - hours * 3_600_000L)
                    failing = false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Un solo aviso aunque siga fallando cada 10 s
                    if (!failing) vm.toast(friendlyError(e))
                    failing = true
                }
                now = System.currentTimeMillis()
                delay(if (now < fastUntil) FAST_POLL_MS else POLL_MS)
            }
        }
    }

    // Cuando llega una posición nueva: la primera vez se centra; si la ha pedido, se va a ella;
    // y si el usuario no ha tocado el mapa, lo sigue. Si lo ha movido, se queda donde lo ha dejado.
    LaunchedEffect(last) {
        val p = last ?: return@LaunchedEffect
        if (p.ts == shownFix) return@LaunchedEffect
        val first = shownFix == null
        val asked = askedAfter?.let { p.ts > it } == true
        shownFix = p.ts
        when {
            first -> mapState.moveTo(p.lat, p.lng, zoom = 16.0)
            asked -> {
                mapState.touched = false
                mapState.moveTo(p.lat, p.lng, zoom = 16.0, animate = true)
            }
            !mapState.touched -> mapState.moveTo(p.lat, p.lng, animate = true)
        }
        if (asked) askedAfter = null
    }

    val locate: () -> Unit = {
        locating = true
        vm.launch {
            try {
                val r = FamilyApi.locate(vm.seniorId)
                vm.toast(
                    if (r.live || r.pushed) "Pidiendo la ubicación… aparecerá en unos segundos"
                    else "El móvil no está conectado ahora; se la pediremos en cuanto se conecte"
                )
                askedAfter = vm.detail?.lastLocation?.ts ?: 0L
                now = System.currentTimeMillis()
                fastUntil = now + FAST_WINDOW_MS
            } finally {
                locating = false
            }
        }
    }

    val seen = remember(now, detail.online, detail.lastSeen) {
        if (detail.online) "Conectado" else "Visto ${ago(detail.lastSeen)}"
    }
    val fixText = remember(now, last) {
        last?.let { p ->
            "Última posición ${ago(p.ts)}" + (p.accuracy?.takeIf { it > 0 }?.let { " · ±${it.roundToInt()} m" } ?: "")
        } ?: "Aún no hay posiciones"
    }
    val waiting = askedAfter != null && now < fastUntil

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- Estado y "¿Dónde está ahora?"
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(detail.online)
                Text(seen, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                detail.status?.battery?.let { BatteryPill(it, detail.status.charging) }
            }
            Hint(fixText)
            if (waiting) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Hint("Esperando su posición…")
                }
            }
            Button(onClick = locate, enabled = !locating, modifier = Modifier.fillMaxWidth()) {
                if (locating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("¿Dónde está ahora?")
            }
        }

        // ---- El mapa
        val trackPoints = remember(track) { track.orEmpty().map { GeoPoint(it.lat, it.lng) } }
        val pin = last?.let { MapPin(it.lat, it.lng, it.accuracy, title = detail.name, snippet = timeFmt(it.ts)) }
        Box(Modifier.fillMaxWidth().height(380.dp).clip(RoundedCornerShape(12.dp))) {
            OsmMap(mapState, Modifier.fillMaxSize(), zones = zones, track = trackPoints, pin = pin)
            if (last != null) {
                FilledTonalButton(
                    onClick = {
                        mapState.touched = false // vuelve a seguirla
                        mapState.moveTo(last.lat, last.lng, zoom = 16.0, animate = true)
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                ) { Text("◎ Centrar") }
            }
        }

        if (last != null) {
            OutlinedButton(
                onClick = { if (!openDirections(context, last, detail.name)) vm.toast("No hay ninguna app de mapas ni navegador en este móvil") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cómo llegar") }
        }

        // ---- Recorrido
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Recorrido", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RANGES.forEach { (h, label) ->
                    FilterChip(selected = hours == h, onClick = { hours = h }, label = { Text(label) })
                }
            }
            val points = track
            Hint(
                when {
                    points == null -> "Cargando el recorrido…"
                    points.size > 1 -> "La línea naranja es su recorrido: ${points.size} posiciones."
                    points.size == 1 -> "Solo hay una posición en este periodo."
                    else -> "No hay posiciones en este periodo."
                }
            )
        }

        // ---- Modo de ubicación
        val saved = modeLabel(detail.config.location)
        val pending = vm.draft?.location?.let(::modeLabel)
        Text(
            buildAnnotatedString {
                append("Modo actual: ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(saved) }
                append(". Lo cambias en la pestaña Ubicación.")
                if (pending != null && pending != saved) append(" Tienes sin guardar: $pending.")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "🔋 80 % ⚡", en rojo si queda poca y no está cargando. */
@Composable
private fun BatteryPill(battery: Int, charging: Boolean) {
    val low = battery <= 20 && !charging
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            "🔋 $battery %" + if (charging) " ⚡" else "",
            style = MaterialTheme.typography.labelLarge,
            color = if (low) FamiliaColors.danger else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/**
 * "Cómo llegar": abre la app de mapas del móvil (Google Maps u otra) en su última posición; desde ahí se piden
 * las indicaciones. Si no hay ninguna, Google Maps en el navegador. Devuelve false si no se ha podido abrir nada.
 */
private fun openDirections(context: Context, p: LocationPoint, name: String): Boolean {
    val ll = String.format(Locale.US, "%.6f,%.6f", p.lat, p.lng)
    val label = Uri.encode(name.filterNot { it == '(' || it == ')' })
    return listOf("geo:$ll?q=$ll($label)", "https://www.google.com/maps/dir/?api=1&destination=$ll").any { uri ->
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }.isSuccess
    }
}
