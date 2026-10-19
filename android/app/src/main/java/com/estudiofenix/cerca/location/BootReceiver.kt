package com.estudiofenix.cerca.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.estudiofenix.cerca.data.Sync

/** Al encender el móvil o actualizar la app: vuelve a arrancar el servicio y la sincronización. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        CercaService.ensure(ctx)
        Sync.schedule(ctx)
        Sync.runOnce(ctx)
    }
}
