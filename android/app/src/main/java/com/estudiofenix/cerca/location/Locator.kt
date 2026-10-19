package com.estudiofenix.cerca.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.util.Log
import com.estudiofenix.cerca.CercaApp
import com.estudiofenix.cerca.data.AlertRequest
import com.estudiofenix.cerca.data.Api
import com.estudiofenix.cerca.data.LocationFix
import com.estudiofenix.cerca.data.Store
import com.estudiofenix.cerca.data.Sync
import com.estudiofenix.cerca.data.Zone
import com.estudiofenix.cerca.setup.DeviceChecks
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

object Locator {
    /** Posición actual (GPS) con tiempo máximo; si no hay, la última conocida. */
    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context, timeoutMs: Long = 20_000): Location? {
        if (DeviceChecks.locationPermission(ctx) == "none") return null
        val client = LocationServices.getFusedLocationProviderClient(ctx)
        val cts = CancellationTokenSource()
        val fresh = withTimeoutOrNull(timeoutMs) {
            runCatching { client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await() }.getOrNull()
        }
        if (fresh == null) cts.cancel()
        return fresh ?: runCatching { client.lastLocation.await() }.getOrNull()
    }

    fun toFix(ctx: Context, loc: Location, source: String) = LocationFix(
        lat = loc.latitude,
        lng = loc.longitude,
        accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
        battery = DeviceChecks.battery(ctx).first,
        source = source,
        ts = loc.time,
    )

    /** "¿Dónde está?" desde el panel. */
    suspend fun locateAndSend(ctx: Context, source: String) {
        val loc = current(ctx) ?: run { Log.w("Locator", "Sin posición disponible"); return }
        Sync.sendLocation(toFix(ctx, loc, source))
        ZoneTracker.evaluate(ctx, loc)
    }
}

/**
 * Decide si está dentro o fuera de las zonas seguras, con margen por la precisión del GPS
 * para no mandar falsas alarmas cuando la señal es mala (dentro de casa, por ejemplo).
 */
object ZoneTracker {
    private const val MAX_ACCURACY_M = 150f

    sealed interface Change {
        data class Exit(val zone: Zone) : Change
        data class Enter(val zone: Zone) : Change
    }

    fun evaluate(ctx: Context, loc: Location): Change? {
        val zones = Store.config.value.location.zones
        if (zones.isEmpty()) return null
        val accuracy = if (loc.hasAccuracy()) loc.accuracy else MAX_ACCURACY_M
        if (accuracy > MAX_ACCURACY_M) return null

        val distances = zones.map { z ->
            val out = FloatArray(1)
            Location.distanceBetween(loc.latitude, loc.longitude, z.lat, z.lng, out)
            z to out[0]
        }
        val inside = distances.firstOrNull { (z, d) -> d <= z.radius }?.first
        val clearlyOutside = distances.all { (z, d) -> d - accuracy > z.radius }
        val nearest = distances.minByOrNull { (z, d) -> d - z.radius }!!.first

        val previous = Store.zoneState
        val change: Change? = when {
            inside != null && previous != "in" -> {
                Store.zoneState = "in"
                Store.lastZoneName = inside.name
                if (previous == "out") Change.Enter(inside) else null   // desde "desconocido" no avisamos
            }
            inside == null && clearlyOutside && previous != "out" -> {
                Store.zoneState = "out"
                // Desde "desconocido" (primera posición o zonas recién editadas) no avisamos
                if (previous == "in") Change.Exit(zones.firstOrNull { it.name == Store.lastZoneName } ?: nearest) else null
            }
            else -> null
        }
        when (change) {
            is Change.Exit -> CercaApp.scope.launch { sendAlert("zone_exit", loc, change.zone.name) }
            is Change.Enter -> CercaApp.scope.launch { sendAlert("zone_enter", loc, change.zone.name) }
            null -> {}
        }
        return change
    }

    private suspend fun sendAlert(type: String, loc: Location, zoneName: String) {
        // Reintenta unas veces: una salida de zona no se puede perder por un corte de red
        repeat(5) { attempt ->
            try {
                Api.alert(AlertRequest(type, loc.latitude, loc.longitude, zoneName))
                return
            } catch (e: Exception) {
                Log.w("ZoneTracker", "Alerta $type fallida (${attempt + 1}): ${e.message}")
                kotlinx.coroutines.delay(15_000L * (attempt + 1))
            }
        }
    }
}
