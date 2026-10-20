package com.estudiofenix.cerca.familia.data

import com.estudiofenix.cerca.data.AppEntry
import com.estudiofenix.cerca.data.Config
import kotlinx.serialization.Serializable

// Respuestas de la API de la familia (server/src/server.js). La configuración en sí es la de :core.

@Serializable
data class Meta(val allowRegistration: Boolean = true)

@Serializable
data class TokenResponse(val token: String)

@Serializable
data class User(val id: Long, val email: String, val name: String)

/** Lo que el móvil del mayor cuenta en cada latido. */
@Serializable
data class DeviceStatus(
    val battery: Int? = null,
    val charging: Boolean = false,
    val isDefaultHome: Boolean = true,
    val isDefaultDialer: Boolean = true,
    val locationPermission: String? = null,   // always | foreground | none
    val locationEnabled: Boolean = true,
    val appVersion: String = "",
    val ringtoneAllowed: Boolean? = null,     // false: falta "Modificar ajustes del sistema" para poner el timbre
    val ts: Long? = null,
)

/** Un timbre de la biblioteca (server/src/ringtones.js). "system" = el que ya tenga su móvil. */
@Serializable
data class Ringtone(val id: String, val name: String, val description: String = "")

@Serializable
data class DeviceInfo(val model: String? = null, val android: String? = null)

@Serializable
data class LocationPoint(
    val lat: Double,
    val lng: Double,
    val accuracy: Double? = null,
    val battery: Int? = null,
    val source: String? = null,
    val ts: Long,
)

/** Una persona mayor en la lista de inicio. */
@Serializable
data class SeniorSummary(
    val id: Long,
    val name: String,
    val role: String = "member",              // owner = quien lo creó
    val paired: Boolean = false,
    val online: Boolean = false,              // con la conexión en vivo abierta ahora mismo
    val pushReady: Boolean = false,           // se le puede despertar al instante por Firebase
    val lastSeen: Long? = null,
    val status: DeviceStatus? = null,
    val device: DeviceInfo? = null,
    val lastLocation: LocationPoint? = null,
    val pendingAlerts: Int = 0,
)

@Serializable
data class Member(val id: Long, val email: String, val name: String, val role: String = "member")

/** Todo lo de una persona mayor: lo del resumen más su configuración, sus apps y su familia. */
@Serializable
data class SeniorDetail(
    val id: Long,
    val name: String,
    val role: String = "member",
    val paired: Boolean = false,
    val online: Boolean = false,
    val pushReady: Boolean = false,
    val lastSeen: Long? = null,
    val status: DeviceStatus? = null,
    val device: DeviceInfo? = null,
    val lastLocation: LocationPoint? = null,
    val pendingAlerts: Int = 0,
    val config: Config = Config(),
    val configVersion: Int = 1,
    val apps: List<AppEntry> = emptyList(),   // las apps instaladas en su móvil
    val members: List<Member> = emptyList(),
    val pairCode: String? = null,
    val pairExpires: Long? = null,
) {
    val isOwner: Boolean get() = role == "owner"
    /** Le llegan los cambios en segundos (conexión en vivo o Firebase). */
    val reachable: Boolean get() = online || pushReady
}

@Serializable
data class Alert(
    val id: Long,
    val type: String,          // sos | zone_exit | zone_enter | low_battery | offline | setup_problem | auto_answer
    val message: String,
    val lat: Double? = null,
    val lng: Double? = null,
    val ts: Long,
    val acknowledged: Int = 0, // SQLite: 0 / 1
) {
    val seen: Boolean get() = acknowledged != 0
}

@Serializable
data class PairCode(val code: String, val expires: Long)

@Serializable
data class LocateResponse(val live: Boolean = false, val pushed: Boolean = false)

@Serializable
data class IdResponse(val id: Long)

@Serializable
data class VersionResponse(val version: Int = 0)

@Serializable
data class PhotoResponse(val photo: String)
