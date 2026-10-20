package com.estudiofenix.cerca.familia.ui.tabs

import android.content.Context
import android.content.Intent
import android.util.Patterns
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.estudiofenix.cerca.data.AlertsConfig
import com.estudiofenix.cerca.data.UiConfig
import com.estudiofenix.cerca.familia.data.DeviceStatus
import com.estudiofenix.cerca.familia.data.FamilyApi
import com.estudiofenix.cerca.familia.data.Member
import com.estudiofenix.cerca.familia.data.SeniorDetail
import com.estudiofenix.cerca.familia.data.Session
import com.estudiofenix.cerca.familia.ui.Hint
import com.estudiofenix.cerca.familia.ui.SectionCard
import com.estudiofenix.cerca.familia.ui.SectionTitle
import com.estudiofenix.cerca.familia.ui.SeniorViewModel
import com.estudiofenix.cerca.familia.ui.StatusDot
import com.estudiofenix.cerca.familia.ui.ago
import com.estudiofenix.cerca.familia.ui.spanish
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * Pestaña «Ajustes», como tabSettings del panel: su móvil (emparejar), la pantalla, el PIN, los avisos,
 * la familia, el nombre y, para quien lo creó, eliminarlo. [onDeleted]: tras eliminar a la persona, volver a la lista.
 * Pantalla y avisos van al borrador (se envían con "Guardar"); lo demás se hace al momento en el servidor.
 */
@Composable
fun SettingsTab(vm: SeniorViewModel, onDeleted: () -> Unit) {
    val detail = vm.detail ?: return
    val draft = vm.draft ?: return
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 96.dp),
    ) {
        PhoneSection(vm, detail, locationOn = draft.location.mode != "off")
        ScreenSection(vm, draft.ui)
        PinSection(vm, detail.config.hasPin)
        AlertSettingsSection(vm, draft.alerts)
        MembersSection(vm, detail)
        NameSection(vm, detail.name)
        if (detail.isOwner) DangerSection(vm, detail.name, onDeleted)
    }
}

// ---------------------------------------------------------------- Móvil

private enum class PhoneAsk { NEW_CODE, UNPAIR }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneSection(vm: SeniorViewModel, detail: SeniorDetail, locationOn: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var ask by remember { mutableStateOf<PhoneAsk?>(null) }
    var busy by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val code = detail.pairCode
    val expires = detail.pairExpires

    // Mientras hay un código, cada 3 s se mira si su móvil ya lo ha usado (como pollPairing del panel).
    // Solo con la app a la vista; al volver a ella se sigue mirando.
    LaunchedEffect(code, expires) {
        if (code == null || expires == null) return@LaunchedEffect
        val wasPaired = detail.paired
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                if (now >= expires) break
                delay(3_000)
                try {
                    vm.reload()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    continue // sin conexión: se vuelve a probar en 3 s
                }
                val fresh = vm.detail ?: continue
                // Al usarse, el servidor borra el código. Si ya había otro móvil, que no sea que ha caducado sin más.
                if (fresh.paired && fresh.pairCode == null && (!wasPaired || System.currentTimeMillis() < expires)) {
                    vm.toast("¡Móvil emparejado!")
                    break
                }
            }
        }
    }

    fun newCode() {
        if (busy) return
        busy = true
        vm.launch {
            try {
                FamilyApi.pairCode(vm.seniorId)
                vm.reload()
            } finally {
                busy = false
            }
        }
    }

    fun unpair() {
        if (busy) return
        busy = true
        vm.launch {
            try {
                FamilyApi.unpair(vm.seniorId)
                vm.reload()
                vm.toast("Móvil desemparejado")
            } finally {
                busy = false
            }
        }
    }

    SectionTitle("Móvil")
    SectionCard {
        if (detail.paired) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(detail.online)
                Spacer(Modifier.width(10.dp))
                Text(detail.device?.model ?: "Móvil emparejado", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Hint("Visto ${ago(detail.lastSeen)}")
            }
            detail.status?.let { StatusPills(it, locationOn) }
        }

        // El código: siempre que no esté emparejado y, si lo está, mientras se cambia de móvil
        if (!detail.paired || code != null) {
            if (detail.paired) {
                Text("Para cambiar de móvil, instala Cerca en el nuevo y, en el asistente, introduce este código. El actual sigue funcionando hasta entonces.")
            } else {
                Text(buildAnnotatedString {
                    append("Instala la app ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Cerca") }
                    append(" en su móvil y, en el asistente, introduce este código:")
                })
            }
            if (code != null && expires != null) {
                if (now < expires) {
                    PairCodeBox(code)
                    Text(
                        expiresIn(expires, now),
                        Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Hint("Esperando a que su móvil lo use…")
                    }
                    // Por si se le dicta por teléfono o WhatsApp a quien esté con él
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = { shareCode(context, code) }) { Text("Compartir el código") }
                    }
                } else {
                    Hint("El código ha caducado. Genera otro.")
                }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (detail.paired && code == null) {
                OutlinedButton(onClick = { ask = PhoneAsk.NEW_CODE }, enabled = !busy) { Text("Emparejar otro móvil") }
            } else {
                Button(onClick = { newCode() }, enabled = !busy) { Text(if (code != null) "Generar otro código" else "Generar código") }
            }
            if (detail.paired) DangerButton("Desemparejar", enabled = !busy) { ask = PhoneAsk.UNPAIR }
        }
    }

    when (ask) {
        PhoneAsk.NEW_CODE -> ConfirmDialog(
            title = "¿Emparejar otro móvil?",
            text = "El móvil actual dejará de estar conectado en cuanto se empareje el nuevo.",
            confirm = "Generar código",
            onConfirm = { ask = null; newCode() },
            onDismiss = { ask = null },
        )
        PhoneAsk.UNPAIR -> ConfirmDialog(
            title = "¿Desemparejar el móvil?",
            text = "El móvil dejará de recibir cambios y de enviar su ubicación. Para volver a conectarlo hará falta un código nuevo.",
            confirm = "Desemparejar",
            danger = true,
            onConfirm = { ask = null; unpair() },
            onDismiss = { ask = null },
        )
        null -> {}
    }
}

/** Lo que cuenta el móvil: si Cerca es la pantalla de inicio y la app de teléfono, la ubicación y la versión. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusPills(st: DeviceStatus, locationOn: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Pill(if (st.isDefaultHome) "Pantalla de inicio ✓" else "No es la pantalla de inicio", st.isDefaultHome)
        Pill(if (st.isDefaultDialer) "App de teléfono ✓" else "No es la app de teléfono", st.isDefaultDialer)
        if (locationOn) {
            val ok = st.locationPermission == "always" && st.locationEnabled
            Pill(if (ok) "Ubicación ✓" else "Problema con la ubicación", ok)
        }
        if (st.appVersion.isNotBlank()) Pill("v${st.appVersion}", null)
    }
}

/** El código en muy grande y en dos grupos ("123 456"), para leerlo de un vistazo. */
@Composable
private fun PairCodeBox(code: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Text(
            spacedCode(code),
            Modifier.fillMaxWidth().padding(vertical = 20.dp),
            fontSize = 44.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 3.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun spacedCode(code: String) = code.chunked(3).joinToString(" ")

/** "Caduca en 25 min" (el código dura media hora). */
private fun expiresIn(expires: Long, now: Long): String {
    val min = ((expires - now + 59_999) / 60_000).coerceAtLeast(1)
    return if (min < 60) "Caduca en $min min" else "Caduca a las ${SimpleDateFormat("HH:mm", spanish).format(Date(expires))}"
}

private fun shareCode(context: Context, code: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, "Instala la app Cerca en el móvil y, en el asistente, introduce este código: ${spacedCode(code)}")
    context.startActivity(Intent.createChooser(send, "Compartir el código"))
}

// ---------------------------------------------------------------- Pantalla

@Composable
private fun ScreenSection(vm: SeniorViewModel, ui: UiConfig) {
    SectionTitle("Pantalla")
    SectionCard {
        Text(buildAnnotatedString {
            append("Tamaño del texto: ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("${(ui.fontScale * 100).roundToInt()} %") }
        })
        Slider(
            value = ui.fontScale,
            // De 0,1 en 0,1 y redondeado, para que no se guarde 1.2000001
            onValueChange = { v ->
                val scale = (v * 10).roundToInt() / 10f
                vm.edit { it.copy(ui = it.ui.copy(fontScale = scale)) }
            },
            valueRange = 0.8f..1.6f,
            steps = 7,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Hint("80 %")
            Hint("160 %")
        }
        HorizontalDivider()
        SwitchRow("Alto contraste", "Fondo negro y letras blancas, para quien ve poco.", ui.highContrast) { v ->
            vm.edit { it.copy(ui = it.ui.copy(highContrast = v)) }
        }
        SwitchRow("Mostrar la fecha completa bajo la hora", null, ui.showDate) { v ->
            vm.edit { it.copy(ui = it.ui.copy(showDate = v)) }
        }
        SwitchRow(
            "Botón «Contactos» en el inicio",
            "Abre la agenda completa del móvil. Los de la pestaña Contactos salen siempre en grande en el inicio.",
            ui.showContacts,
        ) { v -> vm.edit { it.copy(ui = it.ui.copy(showContacts = v)) } }
        SwitchRow(
            "Botón «Mensajes» (SMS)",
            "Añade «Mensajes» en el inicio y «Mensaje» en cada contacto. Usa la app de SMS de su móvil.",
            ui.showSms,
        ) { v -> vm.edit { it.copy(ui = it.ui.copy(showSms = v)) } }
        SwitchRow(
            "Rueda ⚙ del «Menú de la familia» en Aplicaciones",
            "Quítala cuando ya esté todo configurado. El menú se sigue abriendo manteniendo pulsada la hora 3 segundos (con el PIN, si lo hay).",
            ui.showMenuButton,
        ) { v -> vm.edit { it.copy(ui = it.ui.copy(showMenuButton = v)) } }
    }
}

/** Fila con interruptor; se puede tocar en cualquier parte de la fila. */
@Composable
private fun SwitchRow(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Hint(hint)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

// ---------------------------------------------------------------- PIN

/** El PIN no va en el borrador: se manda al momento por su propia llamada (el servidor solo guarda el hash). */
@Composable
private fun PinSection(vm: SeniorViewModel, hasPin: Boolean) {
    val focus = LocalFocusManager.current
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val valid = pin.length in 4..8

    fun send() {
        if (!valid || busy) return
        busy = true
        focus.clearFocus()
        vm.launch {
            try {
                FamilyApi.setPin(vm.seniorId, pin)
                pin = ""
                vm.reload()
                vm.toast("PIN guardado")
            } finally {
                busy = false
            }
        }
    }

    SectionTitle("PIN de ajustes")
    val warn = MaterialTheme.colorScheme.secondary
    Text(
        buildAnnotatedString {
            append("Protege la salida del launcher y los ajustes del móvil. ")
            if (hasPin) append("Ya hay un PIN puesto.")
            else withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = warn)) { append("Aún no hay PIN: cualquiera puede salir del launcher.") }
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                modifier = Modifier.weight(1f),
                label = { Text(if (hasPin) "Nuevo PIN" else "PIN") },
                placeholder = { Text("4 a 8 números") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { send() }),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { send() }, enabled = valid && !busy) { Text(if (hasPin) "Cambiar PIN" else "Poner PIN") }
        }
    }
}

// ---------------------------------------------------------------- Avisos

@Composable
private fun AlertSettingsSection(vm: SeniorViewModel, alerts: AlertsConfig) {
    SectionTitle("Avisos")
    SectionCard {
        NumberRow("Avisar con la batería por debajo de (%)", alerts.lowBatteryPercent, 5..50) { v ->
            vm.edit { it.copy(alerts = it.alerts.copy(lowBatteryPercent = v)) }
        }
        NumberRow("Avisar si el móvil no conecta en (horas)", alerts.offlineHours, 1..72) { v ->
            vm.edit { it.copy(alerts = it.alerts.copy(offlineHours = v)) }
        }
    }
}

/**
 * Número con sus límites (los de sanitizeConfig del servidor). Al borrador solo pasa un valor válido;
 * si se deja a medias o fuera de rango, al salir del campo vuelve al último bueno.
 */
@Composable
private fun NumberRow(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    val focus = LocalFocusManager.current
    var text by remember { mutableStateOf(value.toString()) }
    // Si cambia desde fuera (Descartar, Guardar…), se enseña el nuevo
    LaunchedEffect(value) { if (text.toIntOrNull() != value) text = value.toString() }
    val ok = text.toIntOrNull()?.let { it in range } == true

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label)
            Text(
                if (ok) "De ${range.first} a ${range.last}" else "Tiene que estar entre ${range.first} y ${range.last}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = { t ->
                text = t.filter(Char::isDigit).take(3)
                text.toIntOrNull()?.takeIf { it in range }?.let(onChange)
            },
            modifier = Modifier.width(96.dp).onFocusChanged { if (!it.isFocused) text = value.toString() },
            singleLine = true,
            isError = !ok,
            textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        )
    }
}

// ---------------------------------------------------------------- Familia

@Composable
private fun MembersSection(vm: SeniorViewModel, detail: SeniorDetail) {
    val focus = LocalFocusManager.current
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Member?>(null) }
    val me = Session.email
    val valid = Patterns.EMAIL_ADDRESS.matcher(email).matches()

    fun invite() {
        if (!valid || busy) return
        busy = true
        focus.clearFocus()
        vm.launch {
            try {
                FamilyApi.addMember(vm.seniorId, email)
                email = ""
                vm.reload()
                vm.toast("Familiar añadido")
            } finally {
                busy = false
            }
        }
    }

    SectionTitle("Familiares con acceso")
    SectionCard {
        detail.members.forEach { m ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.name + if (me.equals(m.email, ignoreCase = true)) " (tú)" else "", fontWeight = FontWeight.Medium)
                    Hint(m.email)
                }
                when {
                    m.role == "owner" -> Pill("Responsable", null)
                    // Solo quien lo creó puede quitar a los demás
                    detail.isOwner -> TextButton(
                        onClick = { removing = m },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Quitar") }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it.trim() },
                modifier = Modifier.weight(1f),
                label = { Text("Email de otro familiar") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { invite() }),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { invite() }, enabled = valid && !busy) { Text("Invitar") }
        }
        Hint("La otra persona tiene que haber creado antes su cuenta en Cerca Familia (o en el panel web).")
    }

    removing?.let { m ->
        ConfirmDialog(
            title = "¿Quitar a ${m.name}?",
            text = "Dejará de ver a ${detail.name} y de recibir sus avisos.",
            confirm = "Quitar",
            danger = true,
            onConfirm = {
                removing = null
                vm.launch {
                    FamilyApi.removeMember(vm.seniorId, m.id)
                    vm.reload()
                    vm.toast("${m.name} ya no tiene acceso")
                }
            },
            onDismiss = { removing = null },
        )
    }
}

// ---------------------------------------------------------------- Nombre

@Composable
private fun NameSection(vm: SeniorViewModel, name: String) {
    val focus = LocalFocusManager.current
    var text by remember(name) { mutableStateOf(name) }
    var busy by remember { mutableStateOf(false) }
    val canSave = text.isNotBlank() && text.trim() != name && !busy

    fun rename() {
        if (!canSave) return
        busy = true
        focus.clearFocus()
        vm.launch {
            try {
                FamilyApi.rename(vm.seniorId, text)
                vm.reload()
                vm.toast("Nombre cambiado")
            } finally {
                busy = false
            }
        }
    }

    SectionTitle("Nombre")
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(60) }, // como el servidor
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { rename() }),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { rename() }, enabled = canSave) { Text("Cambiar") }
        }
    }
}

// ---------------------------------------------------------------- Zona peligrosa

@Composable
private fun DangerSection(vm: SeniorViewModel, name: String, onDeleted: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    SectionTitle("Zona peligrosa")
    SectionCard {
        DangerButton("Eliminar a $name y todos sus datos", Modifier.fillMaxWidth()) { asking = true }
    }
    if (asking) DeleteDialog(vm, name, onDismiss = { asking = false }, onDeleted = onDeleted)
}

/** Hay que escribir ELIMINAR, como en el panel: no se puede deshacer. */
@Composable
private fun DeleteDialog(vm: SeniorViewModel, name: String, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val ok = typed.trim() == "ELIMINAR"
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("¿Eliminar a $name?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Se borrarán sus contactos, sus ajustes, el historial de ubicaciones y los avisos, y su móvil dejará de estar conectado. No se puede deshacer.")
                Text(buildAnnotatedString {
                    append("Escribe ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("ELIMINAR") }
                    append(" para confirmar:")
                })
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    placeholder = { Text("ELIMINAR") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = ok && !busy,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                onClick = {
                    busy = true
                    vm.launch {
                        try {
                            FamilyApi.deleteSenior(vm.seniorId)
                            vm.discard() // que no quede nada "sin guardar" de alguien que ya no existe
                            onDeleted()
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("Eliminar") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") } },
    )
}

// ---------------------------------------------------------------- Piezas comunes de esta pestaña

/** Etiqueta redondeada: verde si va bien, roja si no, neutra con [ok] = null. */
@Composable
private fun Pill(text: String, ok: Boolean?) {
    val bg = when (ok) {
        true -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        false -> MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
        null -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when (ok) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(50)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun DangerButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
    ) { Text(text) }
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (danger) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.textButtonColors(),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
