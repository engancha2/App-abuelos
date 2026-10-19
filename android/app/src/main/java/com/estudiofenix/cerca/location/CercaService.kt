package com.estudiofenix.cerca.location

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.estudiofenix.cerca.CercaApp
import com.estudiofenix.cerca.R
import com.estudiofenix.cerca.data.Api
import com.estudiofenix.cerca.data.LocationConfig
import com.estudiofenix.cerca.data.Store
import com.estudiofenix.cerca.data.Sync
import com.estudiofenix.cerca.data.WsMessage
import com.estudiofenix.cerca.data.json
import com.estudiofenix.cerca.home.HomeActivity
import com.estudiofenix.cerca.setup.DeviceChecks
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Servicio en primer plano (notificación fija) que:
 *  - envía la ubicación según el modo configurado (continuo / cada X / al salir de zona),
 *  - mantiene un WebSocket con el servidor para "¿Dónde está?" y cambios de configuración al momento,
 *  - hace un latido cada 10 minutos.
 * Solo corre si el modo de ubicación no es "off"; con "off", WorkManager hace los latidos.
 */
class CercaService : LifecycleService() {

    companion object {
        const val ACTION_REFRESH = "com.estudiofenix.cerca.REFRESH"
        const val ACTION_LOCATE_NOW = "com.estudiofenix.cerca.LOCATE_NOW"
        private const val NOTIFICATION_ID = 10
        private const val TAG = "CercaService"

        private fun shouldRun(ctx: Context): Boolean =
            Store.isPaired &&
                Store.config.value.location.mode != "off" &&
                // Con solo "mientras se usa la app", Android 14+ no deja arrancarlo desde segundo plano
                DeviceChecks.locationPermission(ctx) == "always"

        /** Arranca, refresca o para el servicio según la configuración actual. Seguro de llamar siempre. */
        fun ensure(ctx: Context) {
            val intent = Intent(ctx, CercaService::class.java).setAction(ACTION_REFRESH)
            if (shouldRun(ctx)) {
                try {
                    ContextCompat.startForegroundService(ctx, intent)
                } catch (e: Exception) {
                    // Android 12+ puede impedir arrancarlo desde segundo plano (p. ej. sin "batería sin restricciones")
                    Log.w(TAG, "No se pudo arrancar el servicio: ${e.message}")
                }
            } else {
                ctx.stopService(Intent(ctx, CercaService::class.java))
            }
        }

        fun locateNow(ctx: Context) {
            if (!shouldRun(ctx)) {
                CercaApp.scope.launch { Locator.locateAndSend(ctx, "request") }
                return
            }
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, CercaService::class.java).setAction(ACTION_LOCATE_NOW))
            }.onFailure { CercaApp.scope.launch { Locator.locateAndSend(ctx, "request") } }
        }
    }

    private lateinit var fused: FusedLocationProviderClient
    private var activeRequestKey: String? = null
    private var activeGeofencesKey: String? = null
    private var lastUploadAt = 0L

    private var socket: WebSocket? = null
    private var socketJob: Job? = null
    private var reconnectDelayMs = 5_000L
    private var stopped = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { handleFix(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        goForeground()
        connectSocket()
        lifecycleScope.launch {
            while (isActive) {
                delay(10 * 60_000L)
                Sync.syncNow(applicationContext)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        goForeground() // obligatorio tras cada startForegroundService()
        if (!shouldRun(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        applyMode()
        if (intent?.action == ACTION_LOCATE_NOW) {
            lifecycleScope.launch { Locator.locateAndSend(applicationContext, "request") }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        fused.removeLocationUpdates(callback)
        LocationServices.getGeofencingClient(this).removeGeofences(GeofenceReceiver.pendingIntent(this))
        socket?.close(1000, "bye")
        socketJob?.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Notificación fija
    private fun goForeground() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, HomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CercaApp.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Cerca está activo")
            .setContentText("Tu familia puede ayudarte si lo necesitas")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            // Sin permiso de ubicación "Permitir siempre", Android no deja arrancarlo en segundo plano
            Log.w(TAG, "startForeground: ${e.message}")
            stopSelf()
        }
    }

    // ---------------------------------------------------------------- Ubicación
    private fun requestFor(cfg: LocationConfig): Pair<String, LocationRequest> {
        val outside = Store.zoneState == "out"
        val (priority, intervalMs) = when (cfg.mode) {
            "continuous" -> Priority.PRIORITY_HIGH_ACCURACY to cfg.continuousSeconds * 1000L
            "interval" -> Priority.PRIORITY_HIGH_ACCURACY to cfg.intervalMinutes * 60_000L
            // Zona: dentro, comprobación tranquila cada 5 min (wifi/antenas); fuera, GPS frecuente
            else -> if (outside) Priority.PRIORITY_HIGH_ACCURACY to cfg.outsideZoneSeconds * 1000L
                    else Priority.PRIORITY_BALANCED_POWER_ACCURACY to 5 * 60_000L
        }
        val key = "${cfg.mode}:$priority:$intervalMs"
        val request = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setMaxUpdateDelayMillis(0)
            .setWaitForAccurateLocation(false)
            .build()
        return key to request
    }

    @SuppressLint("MissingPermission")
    private fun applyMode() {
        val cfg = Store.config.value.location
        val (key, request) = requestFor(cfg)
        if (key != activeRequestKey) {
            fused.removeLocationUpdates(callback)
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
            activeRequestKey = key
            Log.i(TAG, "Modo de ubicación: $key")
        }
        registerGeofences()
    }

    @SuppressLint("MissingPermission")
    private fun registerGeofences() {
        val zones = Store.config.value.location.zones
        val key = zones.joinToString { "${it.id}:${it.lat}:${it.lng}:${it.radius}" }
        if (key == activeGeofencesKey) return
        val client = LocationServices.getGeofencingClient(this)
        val pi = GeofenceReceiver.pendingIntent(this)
        client.removeGeofences(pi)
        activeGeofencesKey = key
        if (zones.isEmpty() || DeviceChecks.locationPermission(this) != "always") return
        // Las geovallas de Google Play Services despiertan al móvil al cruzar el borde aunque Xiaomi
        // haya dormido el servicio; son un acelerador, la decisión la toma ZoneTracker.
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofences(zones.map {
                Geofence.Builder()
                    .setRequestId(it.id)
                    .setCircularRegion(it.lat, it.lng, it.radius.toFloat())
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT or Geofence.GEOFENCE_TRANSITION_ENTER)
                    .setNotificationResponsiveness(60_000)
                    .build()
            })
            .build()
        client.addGeofences(request, pi).addOnFailureListener { Log.w(TAG, "Geovallas: ${it.message}") }
    }

    private fun handleFix(loc: Location) {
        val cfg = Store.config.value.location
        val change = ZoneTracker.evaluate(this, loc)
        if (change != null) applyMode() // cambia la frecuencia al salir/entrar

        val now = System.currentTimeMillis()
        val upload = when (cfg.mode) {
            "continuous" -> true
            "interval" -> now - lastUploadAt >= cfg.intervalMinutes * 60_000L - 30_000L
            "zone" -> Store.zoneState == "out" || change != null
            else -> false
        }
        if (upload) {
            lastUploadAt = now
            lifecycleScope.launch { Sync.sendLocation(Locator.toFix(applicationContext, loc, cfg.mode)) }
        }
    }

    // ---------------------------------------------------------------- WebSocket en vivo
    private fun connectSocket() {
        val token = Store.token ?: return
        val request = Request.Builder()
            .url(Api.wsUrl())
            .header("Authorization", "Bearer $token")
            .build()
        socket = Api.client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectDelayMs = 5_000L
                Log.i(TAG, "Conectado al servidor")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { json.decodeFromString(WsMessage.serializer(), text) }.getOrNull() ?: return
                when (msg.type) {
                    "locate" -> lifecycleScope.launch { Locator.locateAndSend(applicationContext, "request") }
                    "config", "hello" -> {
                        val version = msg.version ?: msg.configVersion
                        if (version != null && version != Store.configVersion) {
                            lifecycleScope.launch { Sync.syncNow(applicationContext, forceConfig = true) }
                        }
                    }
                }
            }

            // OkHttp solo llama a onClosed cuando nosotros también cerramos: hay que responder al cierre del servidor
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = scheduleReconnect(code)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket: ${t.message}")
                scheduleReconnect(response?.code ?: 0)
            }
        })
    }

    private fun scheduleReconnect(code: Int) {
        // 4001/401: desemparejado. 4000: otra conexión de este móvil la ha sustituido. En ambos casos no insistir.
        if (stopped || code == 4000 || code == 4001 || code == 401) return
        socketJob?.cancel()
        socketJob = lifecycleScope.launch {
            delay(reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(5 * 60_000L)
            connectSocket()
        }
    }
}
