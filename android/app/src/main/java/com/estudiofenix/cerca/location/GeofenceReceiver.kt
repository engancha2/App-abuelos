package com.estudiofenix.cerca.location

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Google Play Services avisa al cruzar el borde de una zona: pedimos una posición precisa ya. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CercaService.locateNow(context.applicationContext)
    }

    companion object {
        fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, GeofenceReceiver::class.java),
            // Las geovallas necesitan un PendingIntent mutable: Play Services rellena los datos del evento
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }
}
