package com.estudiofenix.cerca

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.estudiofenix.cerca.data.Store
import com.estudiofenix.cerca.data.Sync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CercaApp : Application() {

    companion object {
        /** Ámbito de la app para trabajos que no deben morir con una pantalla (SOS, alertas). */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        const val CHANNEL_SERVICE = "service"
        const val CHANNEL_INCOMING = "incoming_call"
        const val CHANNEL_ONGOING = "ongoing_call"
    }

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        createChannels()
        Sync.schedule(this)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_SERVICE, "Servicio activo", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Aviso fijo mientras Cerca comparte la ubicación con la familia"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_INCOMING, "Llamadas entrantes", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Pantalla de llamada entrante"
                    setSound(null, null) // el tono lo pone el sistema (Telecom)
                    enableVibration(false)
                },
                NotificationChannel(CHANNEL_ONGOING, "Llamada en curso", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                },
            ),
        )
    }
}
