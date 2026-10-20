package com.estudiofenix.cerca.data

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.estudiofenix.cerca.BuildConfig
import com.estudiofenix.cerca.calls.Ringer
import com.estudiofenix.cerca.location.CercaService
import com.estudiofenix.cerca.location.Locator
import com.estudiofenix.cerca.setup.DeviceChecks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private const val TAG = "CercaSync"

object Sync {
    private val mutex = Mutex()
    private val uploadMutex = Mutex()

    /**
     * Latido + configuración + fotos + cola de ubicaciones.
     * Lo llaman el servicio (cada 10 min y al recibir "config"), WorkManager (respaldo cada 15 min) y la UI.
     */
    suspend fun syncNow(ctx: Context, forceConfig: Boolean = false) = mutex.withLock {
        if (!Store.isPaired) return@withLock
        try {
            val hb = Api.heartbeat(withContext(Dispatchers.Default) { buildHeartbeat(ctx) })
            if (forceConfig || hb.configVersion != Store.configVersion) pullConfig(ctx)
            if (hb.locate) Locator.locateAndSend(ctx, "request")
            flushLocations()
            // El timbre elegido por la familia (y el volumen, si lo han bajado)
            Ringer.apply(ctx, Store.config.value.calls)
        } catch (e: ApiException) {
            if (e.code == 401) {
                Log.w(TAG, "El servidor ya no reconoce este móvil: se desempareja")
                Store.unpair()
                Store.bump()
            } else Log.w(TAG, "Sync fallido: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Sync fallido: ${e.message}")
        }
        CercaService.ensure(ctx)
    }

    private suspend fun pullConfig(ctx: Context) {
        val res = Api.config()
        // Primero las fotos, para que la pantalla de inicio nunca muestre un hueco
        for (c in res.config.contacts) {
            val photo = c.photo ?: continue
            val dest = java.io.File(Store.photosDir, photo.substringAfterLast('/'))
            if (!dest.exists()) runCatching { Api.download(photo, dest) }.onFailure { Log.w(TAG, "Foto: ${it.message}") }
        }
        val keep = res.config.contacts.mapNotNull { it.photo?.substringAfterLast('/') }.toSet()
        Store.photosDir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }

        val old = Store.config.value
        Store.saveConfig(res.config)
        Store.configVersion = res.version
        Store.seniorName = res.name
        // Si cambian las zonas, el estado dentro/fuera se recalcula con la siguiente posición.
        // El servicio aplica el nuevo modo en CercaService.ensure(), al final de syncNow().
        if (old.location.zones != res.config.location.zones) Store.zoneState = null
        Store.bump()
    }

    private fun buildHeartbeat(ctx: Context): Heartbeat {
        val (battery, charging) = DeviceChecks.battery(ctx)
        val apps = Apps.launchable(ctx)
        val hash = apps.hashCode()
        val sendApps = hash != Store.lastAppsHash
        if (sendApps) Store.lastAppsHash = hash
        return Heartbeat(
            battery = battery,
            charging = charging,
            isDefaultHome = DeviceChecks.isDefaultHome(ctx),
            isDefaultDialer = DeviceChecks.isDefaultDialer(ctx),
            locationPermission = DeviceChecks.locationPermission(ctx),
            locationEnabled = DeviceChecks.locationEnabled(ctx),
            appVersion = BuildConfig.VERSION_NAME,
            apps = if (sendApps) apps else null,
            ringtoneAllowed = Ringer.allowed(ctx),
        )
    }

    /** Guarda la posición en la cola y la intenta subir. Sin cobertura se queda en la cola. */
    suspend fun sendLocation(fix: LocationFix) {
        Store.enqueue(fix)
        flushLocations()
    }

    suspend fun flushLocations() = uploadMutex.withLock {
        while (true) {
            val batch = Store.readQueue().take(200)
            if (batch.isEmpty()) return@withLock
            try {
                Api.uploadLocations(batch)
                Store.dropFromQueue(batch.size)
            } catch (e: Exception) {
                Log.w(TAG, "Ubicaciones en cola (${batch.size}): ${e.message}")
                return@withLock
            }
        }
    }

    // ---------------------------------------------------------------- WorkManager
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        wm.enqueueUniquePeriodicWork(
            "cerca-sync",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(net).build(),
        )
    }

    fun runOnce(ctx: Context) {
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "cerca-sync-now",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(net).build(),
        )
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Sync.syncNow(applicationContext)
        return Result.success()
    }
}
