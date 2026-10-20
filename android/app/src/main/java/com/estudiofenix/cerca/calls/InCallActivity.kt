package com.estudiofenix.cerca.calls

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.telecom.Call
import android.telecom.DisconnectCause
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estudiofenix.cerca.data.Config
import com.estudiofenix.cerca.data.PhoneBook
import com.estudiofenix.cerca.data.Store
import com.estudiofenix.cerca.ui.Avatar
import com.estudiofenix.cerca.ui.BigButton
import com.estudiofenix.cerca.ui.cardBorder
import com.estudiofenix.cerca.ui.CercaIcons
import com.estudiofenix.cerca.ui.cercaSystemBars
import com.estudiofenix.cerca.ui.CercaTheme
import com.estudiofenix.cerca.ui.FitText
import com.estudiofenix.cerca.ui.Palette
import com.estudiofenix.cerca.ui.PhotoHeader
import com.estudiofenix.cerca.ui.rememberTap
import com.estudiofenix.cerca.ui.strongBorder
import kotlinx.coroutines.delay

class InCallActivity : ComponentActivity() {

    companion object {
        fun intent(ctx: Context) = Intent(ctx, InCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }

    private var proximityLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // No pedimos quitar el bloqueo: con PIN saldría el teclado de desbloqueo encima de "Contestar"
        handleAction(intent)

        val pm = getSystemService(PowerManager::class.java)
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "cerca:proximity")
        }

        setContent {
            val cfg by Store.config.collectAsStateWithLifecycle()
            val snapshot by CallManager.state.collectAsStateWithLifecycle()
            val speaker by CallManager.speaker.collectAsStateWithLifecycle()
            val callers by CallManager.callers.collectAsStateWithLifecycle()
            val auto by AutoAnswer.current.collectAsStateWithLifecycle()
            val call = snapshot.primary

            // Al colgar (o si cuelga el otro), "Llamada terminada" se queda un momento: si la pantalla
            // desaparece de golpe, no se entiende qué ha pasado
            val last = remember { mutableStateOf<Call?>(null) }
            SideEffect { if (call != null) last.value = call }
            LaunchedEffect(call) {
                if (call == null) {
                    if (last.value != null) delay(2_000)
                    finishAndRemoveTask()
                }
            }
            BackHandler { /* el botón atrás no cuelga ni esconde la llamada por error */ }

            val shown = call ?: last.value
            val state = call?.stateCompat ?: Call.STATE_DISCONNECTED
            LaunchedEffect(state, speaker) { updateProximity(state == Call.STATE_ACTIVE && !speaker) }
            LaunchedEffect(cfg.ui.highContrast) { cercaSystemBars(cfg.ui.highContrast) }

            CercaTheme(highContrast = cfg.ui.highContrast, fontScale = cfg.ui.fontScale) {
                if (shown != null) {
                    CallScreen(cfg, callers, shown, state, speaker, if (call != null) snapshot.others else emptyList(),
                        auto?.takeIf { it.call == shown })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAction(intent)
    }

    /** Botón "Contestar" de la notificación. */
    private fun handleAction(intent: Intent?) {
        if (intent == null || intent.action != CercaInCallService.ACTION_ANSWER) return
        intent.action = null // que no vuelva a contestar al recrear la actividad
        val call = CallManager.state.value.primary ?: return
        if (call.stateCompat == Call.STATE_RINGING) CallManager.answer(call)
    }

    private fun updateProximity(on: Boolean) {
        val lock = proximityLock ?: return
        if (on && !lock.isHeld) lock.acquire(2 * 60 * 60 * 1000L)
        if (!on && lock.isHeld) lock.release()
    }

    override fun onDestroy() {
        updateProximity(false)
        super.onDestroy()
    }
}

@Composable
private fun CallScreen(
    cfg: Config,
    callers: Map<String, PhoneBook.Caller>,
    call: Call,
    state: Int,
    speaker: Boolean,
    others: List<Call>,
    auto: AutoAnswer.Info?,
) {
    val number = call.number
    val who = CallManager.who(cfg, number, callers)
    var showKeypad by remember(call) { mutableStateOf(false) }
    var typed by remember(call) { mutableStateOf("") } // lo marcado en el teclado ("pulse 1…")
    // Al contestar una segunda llamada, la primera queda en espera
    val held = others.firstOrNull { it.stateCompat == Call.STATE_HOLDING }
    val onBackground = MaterialTheme.colorScheme.onBackground

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Con letra grande o con el teclado abierto, la foto se encoge: los botones de abajo siempre se ven
        PhotoHeader(
            maxPhoto = 180.dp,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 16.dp),
            photo = { Avatar(who.photo, who.name, it, photoUri = who.photoUri) },
        ) {
            FitText(who.name, 40.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 3, color = onBackground)
            if (!who.favorite && !who.inPhoneBook && !number.isNullOrBlank()) {
                Text("No está en tus contactos", fontSize = 20.sp, textAlign = TextAlign.Center, color = onBackground.copy(alpha = 0.7f))
            }
            Spacer(Modifier.height(8.dp))
            if (auto != null) {
                // Que se vea (y se entienda) que el micrófono está abierto y por qué
                FitText(
                    "Contestada sola en manos libres: ${auto.name} ha llamado ${auto.times} veces seguidas",
                    22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 4,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                        .padding(12.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(statusText(call, state), fontSize = 26.sp, textAlign = TextAlign.Center, color = onBackground)
            if (state == Call.STATE_ACTIVE) CallTimer(call)
            if (state == Call.STATE_RINGING && others.isNotEmpty()) {
                Text("Si contestas, la otra llamada se queda en espera.", fontSize = 20.sp, textAlign = TextAlign.Center,
                    color = onBackground, modifier = Modifier.padding(top = 8.dp))
            }
            if (state == Call.STATE_ACTIVE && held != null) {
                Text("${CallManager.who(cfg, held.number, callers).name} está en espera", fontSize = 22.sp,
                    textAlign = TextAlign.Center, color = onBackground, modifier = Modifier.padding(top = 8.dp))
            }
        }

        if (showKeypad && state == Call.STATE_ACTIVE) {
            if (typed.isNotEmpty()) {
                FitText(typed, 36.sp, Modifier.fillMaxWidth().padding(bottom = 8.dp), fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, maxLines = 1, color = onBackground)
            }
            DtmfPad(call) { typed = (typed + it).takeLast(20) }
            Spacer(Modifier.height(12.dp))
        }

        when (state) {
            Call.STATE_RINGING -> {
                // Botones grandes y separados: nada de deslizar
                BigButton("Contestar", Palette.green, height = 120.dp, icon = CercaIcons.Call) { CallManager.answer(call) }
                Spacer(Modifier.height(28.dp))
                BigButton("Colgar", Palette.red, icon = CercaIcons.CallEnd) { CallManager.hangUp(call) }
            }
            Call.STATE_SELECT_PHONE_ACCOUNT -> {
                // Móvil con dos tarjetas y ninguna elegida para llamar: Android pregunta y hay que contestar aquí.
                // Antes la llamada se quedaba parada para siempre
                val ctx = LocalContext.current
                val accounts = remember(call, call.details.intentExtras) { CallManager.phoneAccounts(ctx, call) }
                accounts.forEach { (handle, label) ->
                    BigButton(label, Palette.green, height = 96.dp, icon = CercaIcons.Call) { call.phoneAccountSelected(handle, false) }
                    Spacer(Modifier.height(16.dp))
                }
                BigButton("Colgar", Palette.red, icon = CercaIcons.CallEnd) { CallManager.hangUp(call) }
            }
            Call.STATE_ACTIVE -> {
                if (held != null) {
                    CallOption("Cambiar de llamada", CercaIcons.Call, on = false, Modifier.fillMaxWidth()) { held.unhold() }
                    Spacer(Modifier.height(12.dp))
                }
                // Dos mosaicos iguales, icono arriba y texto abajo en una línea (antes "Manos / libres" partido y torcido).
                // La misma altura fija para los dos: medirlos con IntrinsicSize cerraba la app (FitText no lo admite)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SpeakerOption(speaker, Modifier.weight(1f))
                    CallOption(if (showKeypad) "Ocultar" else "Teclado", CercaIcons.Dialpad, on = showKeypad, Modifier.weight(1f)) {
                        showKeypad = !showKeypad
                    }
                }
                Spacer(Modifier.height(20.dp))
                BigButton("Colgar", Palette.red, height = 110.dp, icon = CercaIcons.CallEnd) { CallManager.hangUp(call) }
            }
            Call.STATE_HOLDING -> {
                // Antes, si colgaba la otra llamada, esta se quedaba en espera y no había forma de recuperarla
                BigButton("Seguir hablando", Palette.green, height = 110.dp, icon = CercaIcons.Call) { call.unhold() }
                Spacer(Modifier.height(20.dp))
                BigButton("Colgar", Palette.red, icon = CercaIcons.CallEnd) { CallManager.hangUp(call) }
            }
            // Terminada: nada que pulsar, la pantalla se cierra sola enseguida
            Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> {}
            else -> {
                // Llamando: el manos libres ya se puede poner antes de que contesten
                SpeakerOption(speaker, Modifier.fillMaxWidth())
                Spacer(Modifier.height(20.dp))
                BigButton("Colgar", Palette.red, height = 110.dp, icon = CercaIcons.CallEnd) { CallManager.hangUp(call) }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

private fun statusText(call: Call, state: Int): String = when (state) {
    Call.STATE_RINGING -> "Te está llamando"
    Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_NEW -> "Llamando…"
    Call.STATE_SELECT_PHONE_ACCOUNT -> "¿Con qué tarjeta llamas?"
    Call.STATE_ACTIVE -> ""
    Call.STATE_HOLDING -> "En espera"
    Call.STATE_DISCONNECTING -> "Llamada terminada"
    Call.STATE_DISCONNECTED -> when (call.details.disconnectCause?.code) {
        DisconnectCause.BUSY -> "Comunica. Prueba más tarde."
        DisconnectCause.ERROR -> "No se ha podido llamar"
        else -> "Llamada terminada"
    }
    else -> ""
}

/** "Manos libres" (así lo llama todo el mundo, no "altavoz"). Encendido, se pone azul y dice "Activado". */
@Composable
private fun SpeakerOption(speaker: Boolean, modifier: Modifier) {
    CallOption("Manos libres", CercaIcons.Speaker, on = speaker, modifier, status = if (speaker) "Activado" else null) {
        CallManager.setSpeaker(!speaker)
    }
}

/**
 * Mosaico de la llamada (manos libres, teclado): icono arriba y texto debajo, en una sola línea.
 * Encendido, azul (distinto del verde de "Contestar" y del rojo de "Colgar") y con [status] debajo.
 */
@Composable
private fun CallOption(
    text: String,
    icon: ImageVector,
    on: Boolean,
    modifier: Modifier,
    status: String? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val tap = rememberTap()
    val content = if (on) Color.White else colors.onSurfaceVariant
    Surface(
        onClick = { tap(); onClick() },
        modifier = modifier.heightIn(min = 112.dp),
        shape = RoundedCornerShape(20.dp),
        color = if (on) Palette.chat else colors.surfaceVariant,
        contentColor = content,
        border = if (on) null else strongBorder(),
    ) {
        Column(
            Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(38.dp))
            Spacer(Modifier.height(6.dp))
            FitText(text, 22.sp, fontWeight = FontWeight.Bold, color = content, textAlign = TextAlign.Center, maxLines = 1)
            // La línea de estado siempre ocupa su sitio (vacía si no hay): así los dos mosaicos miden lo mismo
            FitText(status ?: " ", 18.sp, color = content, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

@Composable
private fun CallTimer(call: Call) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val start = call.details.connectTimeMillis.takeIf { it > 0 } ?: now
    val s = ((now - start) / 1000).coerceAtLeast(0)
    Text("%d:%02d".format(s / 60, s % 60), fontSize = 30.sp, color = MaterialTheme.colorScheme.onBackground)
}

/**
 * Teclado para menús telefónicos ("pulse 1 para…"), p. ej. al pedir cita en el médico.
 * Las mismas teclas que el teclado de marcar: grandes, grises y con la cifra en negrita.
 */
@Composable
private fun DtmfPad(call: Call, onKey: (Char) -> Unit) {
    val rows = listOf("123", "456", "789", "*0#")
    val feedback = rememberKeyFeedback()
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { c ->
                    Surface(
                        // El tono ya lo pone la llamada (y lo oye quien está al otro lado): aquí solo vibra
                        onClick = { feedback.vibrate(); call.playDtmfTone(c); call.stopDtmfTone(); onKey(c) },
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = colors.surfaceVariant,
                        contentColor = colors.onSurfaceVariant,
                        border = cardBorder(),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(c.toString(), fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
                        }
                    }
                }
            }
        }
    }
}
