package com.estudiofenix.cerca.familia.ui.tabs

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.estudiofenix.cerca.data.CallsConfig
import com.estudiofenix.cerca.familia.data.FamilyApi
import com.estudiofenix.cerca.familia.data.Ringtone
import com.estudiofenix.cerca.familia.ui.Hint
import com.estudiofenix.cerca.familia.ui.SectionCard
import com.estudiofenix.cerca.familia.ui.SectionTitle
import com.estudiofenix.cerca.familia.ui.SeniorViewModel

/** Las tres opciones del filtro de llamadas entrantes: valor, título y explicación (las del panel). */
private val CALL_FILTERS = listOf(
    Triple("all", "Todo el mundo", "Suenan todas las llamadas, como en un móvil normal."),
    Triple("silence_unknown", "Desconocidos en silencio",
        "Las llamadas de números que no están en Contactos entran sin sonar. Quedan en el registro."),
    Triple("block_unknown", "Solo contactos",
        "Se rechazan las llamadas de números desconocidos. La mejor protección contra estafas telefónicas."),
)

/** Pestaña «Llamadas», como tabCalls del panel: quién puede llamarle y qué pasa si no contesta. */
@Composable
fun CallsTab(vm: SeniorViewModel) {
    val calls = vm.draft?.calls ?: return
    fun change(update: (CallsConfig) -> CallsConfig) = vm.edit { it.copy(calls = update(it.calls)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
        SectionTitle("¿Quién puede llamar?")
        Hint("Se aplica a las llamadas entrantes. Los contactos del inicio siempre suenan.")
        Spacer(Modifier.height(12.dp))
        SectionCard(Modifier.selectableGroup()) {
            CALL_FILTERS.forEach { (value, title, desc) ->
                CallChoice(title, desc, selected = calls.filter == value) { change { it.copy(filter = value) } }
            }
        }
        Spacer(Modifier.height(12.dp))
        SectionCard {
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                    .toggleable(calls.allowRepeated, role = Role.Checkbox) { on -> change { it.copy(allowRepeated = on) } }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(checked = calls.allowRepeated, onCheckedChange = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Dejar pasar si insiste", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Hint("Si un número desconocido llama 2 veces en 5 minutos, la segunda suena (por si es una urgencia: médico, hospital, vecino…).")
                }
            }
        }

        SectionTitle("Si no contesta")
        Text(
            buildAnnotatedString {
                append(
                    "Por si se cae y no llega al móvil: si alguien de Contactos le llama varias veces seguidas (en 15 minutos) " +
                        "y no contesta, la última se contesta sola con manos libres y el volumen al máximo. Antes suena unos " +
                        "segundos, pita al abrir el micrófono y os llega un aviso. ",
                )
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Actívalo solo con su permiso.") }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        SectionCard(Modifier.selectableGroup()) {
            Text("Contestar sola en manos libres", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            // 0 = nunca; de 2 a 5 como en el panel. El servidor admite hasta 10: si ya hay otro valor, también sale.
            val current = calls.autoAnswerOnCall
            val options = (listOf(0, 2, 3, 4, 5) + listOfNotNull(current.takeIf { it >= 2 })).distinct().sorted()
            options.forEach { n ->
                CallChoice(
                    title = if (n == 0) "Nunca (desactivado)" else "A la ${n}ª llamada seguida (tras ${n - 1} sin respuesta)",
                    desc = null,
                    selected = current == n,
                ) { change { it.copy(autoAnswerOnCall = n) } }
            }
        }

        RingtoneSection(vm, calls) { update -> change(update) }
    }
}

/**
 * «Timbre»: timbres fuertes para quien oye poco (los fabrica el servidor). Se pueden escuchar aquí y, ya guardado,
 * hacerlo sonar en su móvil para comprobar que lo oye. Su móvil lo pone como tono de llamada.
 */
@Composable
private fun RingtoneSection(vm: SeniorViewModel, calls: CallsConfig, change: ((CallsConfig) -> CallsConfig) -> Unit) {
    val tones by produceState<List<Ringtone>?>(null) { value = runCatching { FamilyApi.ringtones() }.getOrNull() }
    var playing by remember { mutableStateOf<String?>(null) }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    fun stop() {
        player.value?.release()
        player.value = null
        playing = null
    }
    DisposableEffect(Unit) { onDispose { stop() } }
    fun play(id: String) {
        stop()
        playing = id
        player.value = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            setDataSource(FamilyApi.ringtoneUrl(id))
            setOnPreparedListener { it.start() }
            setOnCompletionListener { stop() }
            setOnErrorListener { _, _, _ -> stop(); vm.toast("No se ha podido reproducir el timbre"); true }
            prepareAsync()
        }
    }

    SectionTitle("Timbre")
    Hint("Timbres fuertes y fáciles de oír. Su móvil lo pone como tono de llamada en cuanto recibe el cambio.")
    if (vm.detail?.status?.ringtoneAllowed == false && calls.ringtone != "system") {
        Spacer(Modifier.height(8.dp))
        Text(
            "Falta un permiso en su móvil: menú de la familia → Asistente → «8. Timbre» → activar «Modificar ajustes del sistema».",
            color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold,
        )
    }
    Spacer(Modifier.height(12.dp))
    SectionCard(Modifier.selectableGroup()) {
        val list = tones
        if (list == null) Hint("Cargando timbres…")
        list?.forEach { tone ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    CallChoice(tone.name, tone.description, selected = calls.ringtone == tone.id) {
                        change { it.copy(ringtone = tone.id) }
                    }
                }
                if (tone.id != "system") {
                    OutlinedButton(onClick = { if (playing == tone.id) stop() else play(tone.id) }) {
                        Text(if (playing == tone.id) "■ Parar" else "▶ Escuchar")
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    SectionCard {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                .toggleable(calls.ringMax, role = Role.Checkbox) { on -> change { it.copy(ringMax = on) } }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(checked = calls.ringMax, onCheckedChange = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Siempre a todo volumen", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Hint("Su móvil sube el timbre al máximo cada vez que se sincroniza, aunque lo hayan bajado o puesto en silencio.")
            }
        }
        Button(
            onClick = {
                if (vm.dirty) vm.toast("Guarda antes los cambios: suena el timbre ya guardado")
                else vm.launch {
                    val r = FamilyApi.ringTest(vm.seniorId)
                    vm.toast(if (r.live || r.pushed) "Sonando en su móvil…" else "Su móvil no está conectado ahora mismo")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("🔔  Hacerlo sonar en su móvil") }
        Hint("Suena unos segundos el timbre guardado, para comprobar que lo oye.")
    }
}

/** Una opción de radio con su explicación debajo; toda la fila se puede tocar. */
@Composable
private fun CallChoice(title: String, desc: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = if (desc == null) Alignment.CenterVertically else Alignment.Top,
        horizontalArrangement = Arrangement.Start,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontWeight = if (desc != null) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (desc != null) Hint(desc)
        }
    }
}
