package com.estudiofenix.cerca.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Misma forma que DEFAULT_CONFIG en el servidor (server/src/db.js). */
@Serializable
data class Config(
    val contacts: List<Contact> = emptyList(),
    val sosContactId: String? = null,
    val calls: CallsConfig = CallsConfig(),
    val location: LocationConfig = LocationConfig(),
    val apps: List<String> = emptyList(),
    val ui: UiConfig = UiConfig(),
    val pinHash: String? = null,
    val alerts: AlertsConfig = AlertsConfig(),
) {
    val sosContact: Contact? get() = contacts.firstOrNull { it.id == sosContactId } ?: contacts.firstOrNull()

    fun contactForNumber(number: String?): Contact? {
        val key = Phone.matchKey(number) ?: return null
        return contacts.firstOrNull { Phone.matchKey(it.phone) == key }
    }
}

@Serializable
data class Contact(
    val id: String,
    val name: String,
    val phone: String,
    val photo: String? = null,
    val whatsapp: Boolean = false,
)

@Serializable
data class CallsConfig(
    val filter: String = "all",           // all | silence_unknown | block_unknown
    val allowRepeated: Boolean = true,
)

@Serializable
data class LocationConfig(
    val mode: String = "off",             // off | continuous | interval | zone
    val continuousSeconds: Int = 30,
    val intervalMinutes: Int = 15,
    val outsideZoneSeconds: Int = 60,
    val zones: List<Zone> = emptyList(),
    val retentionDays: Int = 7,
)

@Serializable
data class Zone(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radius: Double,
)

@Serializable
data class UiConfig(
    val fontScale: Float = 1f,
    val highContrast: Boolean = false,
    val showDate: Boolean = true,
)

@Serializable
data class AlertsConfig(
    val lowBatteryPercent: Int = 15,
    val offlineHours: Int = 6,
)

// ---- Mensajes de la API ----

@Serializable
data class PairResponse(val token: String, val seniorId: Long, val name: String)

@Serializable
data class ConfigResponse(val version: Int, val seniorId: Long, val name: String, val config: Config)

@Serializable
data class HeartbeatResponse(val configVersion: Int, val locate: Boolean = false)

@Serializable
data class LocationFix(
    val lat: Double,
    val lng: Double,
    val accuracy: Float? = null,
    val battery: Int? = null,
    val source: String = "gps",
    val ts: Long,
)

@Serializable
data class AppEntry(val pkg: String, val label: String)

@Serializable
data class Heartbeat(
    val battery: Int?,
    val charging: Boolean,
    val isDefaultHome: Boolean,
    val isDefaultDialer: Boolean,
    val locationPermission: String,   // always | foreground | none
    val locationEnabled: Boolean,
    val appVersion: String,
    val apps: List<AppEntry>? = null,
)

@Serializable
data class AlertRequest(
    val type: String,
    val lat: Double? = null,
    val lng: Double? = null,
    val zoneName: String? = null,
)

@Serializable
data class WsMessage(val type: String, val version: Int? = null, val configVersion: Int? = null)

val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

object Phone {
    /** Compara por los últimos 9 dígitos: +34 600 11 22 33 == 600112233. */
    fun matchKey(number: String?): String? {
        val digits = number?.filter { it.isDigit() } ?: return null
        if (digits.length < 3) return null
        return digits.takeLast(9)
    }

    fun dialable(number: String): String = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
}
