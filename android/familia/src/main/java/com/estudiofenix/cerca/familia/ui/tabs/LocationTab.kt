package com.estudiofenix.cerca.familia.ui.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.estudiofenix.cerca.data.LocationConfig
import com.estudiofenix.cerca.data.Zone
import com.estudiofenix.cerca.familia.ui.FamiliaColors
import com.estudiofenix.cerca.familia.ui.Hint
import com.estudiofenix.cerca.familia.ui.SectionCard
import com.estudiofenix.cerca.familia.ui.SectionTitle
import com.estudiofenix.cerca.familia.ui.SeniorViewModel
import com.estudiofenix.cerca.familia.ui.spanish
import com.estudiofenix.cerca.familia.ui.timeFmt
import kotlin.math.roundToInt

// Los mismos límites que sanitizeConfig en el servidor (server/src/util.js)
private val OUTSIDE_SECONDS = 15..600
private val INTERVAL_MINUTES = 1..240
private val CONTINUOUS_SECONDS = 10..300
private val RETENTION_DAYS = 1..90
private val ZONE_RADIUS = 100..20_000
private const val NEW_ZONE_RADIUS = 300.0
private const val MAX_ZONES = 10      // el servidor guarda como mucho 10
private const val ZONE_NAME_MAX = 40

/** Pestaña «Ubicación»: cuándo envía su posición, las zonas seguras y cuánto se guarda, como `tabLocation` del panel. */
@Composable
fun LocationTab(vm: SeniorViewModel) {
    val loc = vm.draft?.location ?: return
    val last = vm.detail?.lastLocation
    val seniorName = vm.detail?.name.orEmpty()
    fun edit(change: (LocationConfig) -> LocationConfig) = vm.edit { it.copy(location = change(it.location)) }
    // Dónde se ha tocado el mapa, mientras se pide el nombre de la zona
    var adding by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    // Se abre en la primera zona; si no hay, donde está; si tampoco, España entera
    val center = loc.zones.firstOrNull()?.let { it.lat to it.lng } ?: last?.let { it.lat to it.lng }
    val mapState = rememberOsmMapState(
        lat = center?.first ?: OsmMapDefaults.LAT,
        lng = center?.second ?: OsmMapDefaults.LNG,
        zoom = if (center != null) 15.0 else OsmMapDefaults.ZOOM,
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- Modo
        SectionTitle("¿Cuándo envía su ubicación?")
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeOption(
                selected = loc.mode == "zone",
                title = "Solo si sale de una zona",
                description = "Avisa cuando sale de casa, del barrio… y mientras esté fuera envía su posición con frecuencia. " +
                    "Lo que menos batería gasta.",
                onSelect = { edit { it.copy(mode = "zone") } },
            ) {
                NumberField("Mientras está fuera, cada", "segundos", loc.outsideZoneSeconds, OUTSIDE_SECONDS) { v ->
                    edit { it.copy(outsideZoneSeconds = v) }
                }
            }
            ModeOption(
                selected = loc.mode == "interval",
                title = "Cada cierto tiempo",
                description = "Envía su posición periódicamente, esté donde esté.",
                onSelect = { edit { it.copy(mode = "interval") } },
            ) {
                NumberField("Cada", "minutos", loc.intervalMinutes, INTERVAL_MINUTES) { v -> edit { it.copy(intervalMinutes = v) } }
            }
            ModeOption(
                selected = loc.mode == "continuous",
                title = "Continuamente",
                description = "Seguimiento casi en tiempo real. Gasta bastante batería: útil en momentos concretos (si se ha perdido).",
                onSelect = { edit { it.copy(mode = "continuous") } },
            ) {
                NumberField("Cada", "segundos", loc.continuousSeconds, CONTINUOUS_SECONDS) { v -> edit { it.copy(continuousSeconds = v) } }
            }
            ModeOption(
                selected = loc.mode == "off",
                title = "Desactivada",
                description = "No se envía ninguna ubicación. El SOS sí incluye la posición si la hay.",
                onSelect = { edit { it.copy(mode = "off") } },
            )
        }
        // El servidor no guarda este modo sin zonas: mejor avisar antes de que pulse "Guardar"
        if (loc.mode == "zone" && loc.zones.isEmpty()) {
            Warning("Este modo necesita al menos una zona segura. Añádela tocando el mapa de abajo; si no, no se podrá guardar.")
        }

        // ---- Zonas seguras
        SectionTitle("Zonas seguras")
        Hint(
            "Toca el mapa para añadir una zona (acércalo con dos dedos para ponerla en su sitio). " +
                "En los modos \"cada cierto tiempo\" y \"continuo\" también te avisa si sale de ellas." +
                if (last != null) " El marcador es su última posición." else ""
        )
        val pin = last?.let { MapPin(it.lat, it.lng, title = seniorName, snippet = timeFmt(it.ts)) }
        OsmMap(
            mapState,
            Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(12.dp)),
            zones = loc.zones,
            pin = pin,
            onTap = { lat, lng ->
                if (loc.zones.size >= MAX_ZONES) vm.toast("Como mucho puede haber $MAX_ZONES zonas")
                else adding = lat to lng
            },
        )
        if (loc.zones.isEmpty()) {
            Hint("Sin zonas todavía.", Modifier.padding(vertical = 8.dp))
        }
        loc.zones.forEach { zone ->
            key(zone.id) {
                ZoneRow(
                    zone,
                    onRadius = { r ->
                        edit { l -> l.copy(zones = l.zones.map { if (it.id == zone.id) it.copy(radius = r.toDouble()) else it }) }
                    },
                    onRemove = { edit { l -> l.copy(zones = l.zones.filterNot { it.id == zone.id }) } },
                )
            }
        }

        // ---- Privacidad
        SectionTitle("Privacidad")
        NumberField("Borrar el historial de posiciones pasados", "días", loc.retentionDays, RETENTION_DAYS) { v ->
            edit { it.copy(retentionDays = v) }
        }
    }

    adding?.let { (lat, lng) ->
        AddZoneDialog(
            suggested = if (loc.zones.isEmpty()) "Casa" else "",
            onDismiss = { adding = null },
            onAdd = { name ->
                adding = null
                edit { l ->
                    val ids = l.zones.map { it.id }.toSet()
                    val zone = Zone(id = uid(ids), name = name.take(ZONE_NAME_MAX), lat = lat, lng = lng, radius = NEW_ZONE_RADIUS)
                    l.copy(zones = l.zones + zone)
                }
            },
        )
    }
}

/** Una de las cuatro opciones de modo; la elegida enseña debajo su campo ([extra]). */
@Composable
private fun ModeOption(
    selected: Boolean,
    title: String,
    description: String,
    onSelect: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 0.dp else 1.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Hint(description)
            }
        }
        // Fuera de la fila seleccionable, para que tocar el campo no vuelva a elegir la opción
        if (selected && extra != null) {
            Box(Modifier.padding(start = 48.dp, end = 12.dp, bottom = 12.dp)) { extra() }
        }
    }
}

/** Una zona de la lista: su nombre, su radio (editable) y "Quitar". */
@Composable
private fun ZoneRow(zone: Zone, onRadius: (Int) -> Unit, onRemove: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(zone.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(
                onClick = onRemove,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Quitar") }
        }
        NumberField("Radio", "metros", zone.radius.roundToInt(), ZONE_RADIUS, onChange = onRadius)
    }
}

/**
 * Campo numérico con sus límites: el texto encima ("Cada") y la unidad dentro ("minutos"), que se lee seguido.
 * Solo cambia la configuración con valores válidos; si se deja fuera de rango (o vacío), al salir del campo
 * se ajusta al límite más cercano (o vuelve al valor que tenía).
 */
@Composable
private fun NumberField(
    label: String,
    unit: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val number = text.toIntOrNull()
    val valid = number != null && number in range
    val focus = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        OutlinedTextField(
            value = text,
            onValueChange = { typed ->
                text = typed.filter(Char::isDigit).take(6)
                text.toIntOrNull()?.takeIf { it in range && it != value }?.let(onChange)
            },
            suffix = { Text(unit) },
            supportingText = { Text("Entre ${fmt(range.first)} y ${fmt(range.last)}") },
            isError = !valid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.width(200.dp).onFocusChanged { state ->
                if (!state.isFocused && !valid) {
                    val fixed = number?.coerceIn(range) ?: value
                    text = fixed.toString()
                    if (fixed != value) onChange(fixed)
                }
            },
        )
    }
}

/** Aviso en naranja dentro de la pestaña. */
@Composable
private fun Warning(text: String) {
    Surface(
        color = FamiliaColors.warning.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, FamiliaColors.warning),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Text("⚠️  $text", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Pide el nombre de la zona nueva (la primera vez propone "Casa"). */
@Composable
private fun AddZoneDialog(suggested: String, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(suggested) }
    val focus = remember { FocusRequester() }
    val ok = name.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nueva zona segura") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("Tendrá ${NEW_ZONE_RADIUS.roundToInt()} m de radio; luego lo puedes cambiar en la lista.")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(ZONE_NAME_MAX) },
                    label = { Text("Nombre de la zona") },
                    placeholder = { Text("p. ej. Casa, Barrio, Centro de día") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (ok) onAdd(name.trim()) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                // Dentro del diálogo, para que el campo ya exista cuando se le pide el foco
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onAdd(name.trim()) }) { Text("Añadir") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** "20.000" */
private fun fmt(n: Int): String = String.format(spanish, "%,d", n)

/** Id corto y aleatorio para una zona nueva, como uid() del panel (8 letras y números), sin repetir los que ya hay. */
private fun uid(taken: Set<String>): String {
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    while (true) {
        val id = (1..8).map { chars.random() }.joinToString("")
        if (id !in taken) return id
    }
}
