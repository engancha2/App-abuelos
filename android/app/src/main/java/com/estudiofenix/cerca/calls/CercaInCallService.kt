package com.estudiofenix.cerca.calls

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.estudiofenix.cerca.CercaApp
import com.estudiofenix.cerca.R
import com.estudiofenix.cerca.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Android nos entrega aquí todas las llamadas (entrantes y salientes) porque somos la app de teléfono
 * predeterminada. Enseñamos nuestra propia pantalla de llamada, grande y sencilla.
 */
class CercaInCallService : InCallService() {

    companion object {
        private const val NOTIF_ID = 20
        const val ACTION_ANSWER = "com.estudiofenix.cerca.ANSWER"
        const val ACTION_HANGUP = "com.estudiofenix.cerca.HANGUP"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        CallManager.attach(this)
        // La notificación sigue al estado de la llamada principal
        scope.launch {
            CallManager.state.collectLatest { snapshot ->
                val call = snapshot.primary
                if (call == null) cancelNotification() else showNotification(call)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        CallManager.attach(null)
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        CallManager.add(call)
        openCallScreen()
    }

    override fun onCallRemoved(call: Call) {
        CallManager.remove(call)
    }

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        CallManager.onAudio(audioState)
    }

    private fun openCallScreen() {
        // Si el launcher está en pantalla (lo normal), Android deja abrir la actividad directamente.
        // Si no, la notificación de pantalla completa la abre (con el móvil bloqueado, por ejemplo).
        try {
            startActivity(InCallActivity.intent(this))
        } catch (e: Exception) {
            Log.w("InCallService", "No se pudo abrir la pantalla de llamada: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- Notificación de llamada
    private fun showNotification(call: Call) {
        val contact = Store.config.value.contactForNumber(call.number)
        val name = contact?.name ?: call.number?.takeIf { it.isNotBlank() } ?: "Número oculto"
        val person = Person.Builder().setName(name).setImportant(contact != null).build()
        val ringing = call.stateCompat == Call.STATE_RINGING

        val fullScreen = PendingIntent.getActivity(
            this, 0, InCallActivity.intent(this), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hangUp = actionIntent(ACTION_HANGUP, 1)

        val builder = NotificationCompat.Builder(this, if (ringing) CercaApp.CHANNEL_INCOMING else CercaApp.CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(name)
            .setContentText(if (ringing) "Te está llamando" else "Llamada en curso")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(fullScreen)
            .addPerson(person)
        if (ringing) {
            // CallStyle exige pantalla completa o servicio en primer plano: solo la usamos al sonar
            // "Contestar" abre directamente la pantalla de llamada: desde Android 12 un receiver lanzado por una
            // notificación no puede abrir actividades (trampolín bloqueado)
            val answer = PendingIntent.getActivity(
                this, 2, InCallActivity.intent(this).setAction(ACTION_ANSWER),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(person, hangUp, answer))
                .setFullScreenIntent(fullScreen, true)
        } else {
            builder.addAction(0, "Colgar", hangUp).setUsesChronometer(true)
                .setWhen(call.details.connectTimeMillis.takeIf { it > 0 } ?: System.currentTimeMillis())
        }

        val notification: Notification = builder.build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification)
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        this, requestCode, Intent(this, CallActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancelNotification() {
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }
}

/** Botones "Contestar" / "Colgar" de la notificación. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val call = CallManager.state.value.primary ?: return
        when (intent.action) {
            CercaInCallService.ACTION_ANSWER -> CallManager.answer(call)
            CercaInCallService.ACTION_HANGUP -> CallManager.hangUp(call)
        }
    }
}
