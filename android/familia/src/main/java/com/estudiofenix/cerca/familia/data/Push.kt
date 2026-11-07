package com.estudiofenix.cerca.familia.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.estudiofenix.cerca.familia.BuildConfig
import com.estudiofenix.cerca.familia.MainActivity
import com.estudiofenix.cerca.familia.R
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Los avisos de la familia (SOS, salida de zona, batería…) con Firebase Cloud Messaging: llegan aunque la app esté cerrada.
 *
 * Firebase se inicia a mano con los datos de android/gradle.properties (firebase.*), sin google-services.json.
 * Si no están, la app funciona igual y los avisos se ven al abrirla (y en el panel web).
 * El servidor manda cada aviso al canal "sos" (el SOS) o "avisos" (lo demás), que se crean aquí.
 */
object Push {
    const val CHANNEL_SOS = "sos"
    const val CHANNEL_ALERTS = "avisos"

    val enabled: Boolean get() = BuildConfig.FIREBASE_APP_ID.isNotBlank()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(ctx: Context) {
        createChannels(ctx)
        if (!enabled || FirebaseApp.getApps(ctx).isNotEmpty()) return
        FirebaseApp.initializeApp(
            ctx,
            FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .build(),
        )
    }

    private fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_SOS, "SOS", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Cuando tu familiar pulsa el botón SOS"
                    // Suena como una alarma para que se oiga aunque el móvil esté en vibración
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
                    )
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
                },
                NotificationChannel(CHANNEL_ALERTS, "Avisos", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Salidas de zona, batería baja, móvil sin conexión…"
                },
            ),
        )
    }

    /** Al entrar en la cuenta: este móvil recibirá los avisos de las personas de esta cuenta. */
    suspend fun register() {
        if (!enabled) return
        val messaging = FirebaseMessaging.getInstance()
        // Firebase 26 avisa del token con register() → onRegistered(); getToken() (ya obsoleto) aún lo da al momento.
        // Se usan los dos: mandar el mismo token dos veces al servidor no pasa nada.
        runCatching { messaging.register().await() }
        @Suppress("DEPRECATION")
        val token = runCatching { messaging.token.await() }.getOrNull() ?: Session.fcmToken ?: return
        Session.fcmToken = token
        FamilyApi.registerFcm(token)
    }

    /** Firebase ha cambiado el token de este móvil: se lo decimos al servidor si hay sesión. */
    internal fun onNewToken(token: String) {
        Session.fcmToken = token
        if (Session.token != null) scope.launch { runCatching { FamilyApi.registerFcm(token) } }
    }

    /**
     * Con la app abierta, Firebase no enseña la notificación: la enseñamos nosotros igual que lo haría él.
     * (Con la app cerrada la enseña Android en el canal que dice el servidor.)
     */
    internal fun show(ctx: Context, msg: RemoteMessage) {
        val n = msg.notification ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val sos = msg.data["type"] == "sos"
        val seniorId = msg.data["seniorId"]?.toLongOrNull()
        val open = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { seniorId?.let { putExtra(MainActivity.EXTRA_SENIOR_ID, it.toString()) } }
        val pending = PendingIntent.getActivity(
            ctx, (msg.data["alertId"] ?: msg.messageId).hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(ctx, if (sos) CHANNEL_SOS else CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(n.title)
            .setContentText(n.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (sos) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(ctx).notify((msg.data["alertId"] ?: msg.messageId).hashCode(), notification)
    }
}

/** Recibe los avisos de Firebase (declarado en el AndroidManifest). */
class FamiliaMessagingService : FirebaseMessagingService() {
    // Firebase 26: el token nuevo llega aquí…
    override fun onRegistered(token: String) = Push.onNewToken(token)

    // …y en versiones anteriores, aquí
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) = Push.onNewToken(token)

    override fun onMessageReceived(message: RemoteMessage) = Push.show(applicationContext, message)
}
