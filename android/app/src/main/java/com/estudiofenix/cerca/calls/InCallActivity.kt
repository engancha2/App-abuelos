package com.estudiofenix.cerca.calls

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.telecom.Call
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estudiofenix.cerca.data.Store
import com.estudiofenix.cerca.ui.Avatar
import com.estudiofenix.cerca.ui.BigButton
import com.estudiofenix.cerca.ui.CercaTheme
import com.estudiofenix.cerca.ui.Palette
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
            val audio by CallManager.audio.collectAsStateWithLifecycle()
            val call = snapshot.primary

            LaunchedEffect(call) { if (call == null) finishAndRemoveTask() }
            BackHandler { /* el botón atrás no cuelga ni esconde la llamada por error */ }

            val state = call?.stateCompat
            val speaker = audio?.route == android.telecom.CallAudioState.ROUTE_SPEAKER
            LaunchedEffect(state, speaker) { updateProximity(state == Call.STATE_ACTIVE && !speaker) }

            CercaTheme(highContrast = cfg.ui.highContrast, fontScale = cfg.ui.fontScale) {
                if (call != null) CallScreen(call, state ?: Call.STATE_NEW, speaker)
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
private fun CallScreen(call: Call, state: Int, speaker: Boolean) {
    val cfg = Store.config.value
    val number = call.number
    val contact = cfg.contactForNumber(number)
    val name = contact?.name ?: number?.takeIf { it.isNotBlank() } ?: "Número oculto"
    var showKeypad by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Avatar(contact?.photo, name, 180.dp)
        Spacer(Modifier.height(20.dp))
        Text(name, fontSize = 40.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground)
        if (contact == null && number != null) {
            Text("No está en tus contactos", fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when (state) {
                Call.STATE_RINGING -> "Te está llamando"
                Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_NEW -> "Llamando…"
                Call.STATE_ACTIVE -> ""
                Call.STATE_HOLDING -> "En espera"
                Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> "Llamada terminada"
                else -> ""
            },
            fontSize = 26.sp, color = MaterialTheme.colorScheme.onBackground,
        )
        if (state == Call.STATE_ACTIVE) CallTimer(call)

        Spacer(Modifier.weight(1f))

        if (showKeypad && state == Call.STATE_ACTIVE) {
            DtmfPad(call)
            Spacer(Modifier.height(12.dp))
        }

        when (state) {
            Call.STATE_RINGING -> {
                // Botones grandes y separados: nada de deslizar
                BigButton("📞  Contestar", Palette.green, height = 120.dp) { CallManager.answer(call) }
                Spacer(Modifier.height(28.dp))
                BigButton("Colgar", Palette.red) { CallManager.hangUp(call) }
            }
            Call.STATE_ACTIVE, Call.STATE_HOLDING -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { CallManager.setSpeaker(!speaker) }, modifier = Modifier.weight(1f).height(72.dp)) {
                        Text(if (speaker) "🔊 Altavoz SÍ" else "🔈 Altavoz", fontSize = 22.sp,
                            color = MaterialTheme.colorScheme.onBackground)
                    }
                    OutlinedButton(onClick = { showKeypad = !showKeypad }, modifier = Modifier.weight(1f).height(72.dp)) {
                        Text(if (showKeypad) "Ocultar" else "Teclado", fontSize = 22.sp, color = MaterialTheme.colorScheme.onBackground)
                    }
                }
                Spacer(Modifier.height(20.dp))
                BigButton("Colgar", Palette.red, height = 110.dp) { CallManager.hangUp(call) }
            }
            else -> BigButton("Colgar", Palette.red, height = 110.dp) { CallManager.hangUp(call) }
        }
        Spacer(Modifier.height(12.dp))
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

/** Teclado para menús telefónicos ("pulse 1 para…"), p. ej. al pedir cita en el médico. */
@Composable
private fun DtmfPad(call: Call) {
    val rows = listOf("123", "456", "789", "*0#")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { c ->
                    OutlinedButton(
                        onClick = { call.playDtmfTone(c); call.stopDtmfTone() },
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text(c.toString(), fontSize = 26.sp, color = Color.Unspecified) }
                }
            }
        }
    }
}
