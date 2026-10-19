package com.estudiofenix.cerca.data

import android.content.Context
import android.content.SharedPreferences
import com.estudiofenix.cerca.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.security.MessageDigest

/**
 * Todo lo que el móvil guarda en local. La app funciona sin conexión con la última
 * configuración recibida: contactos, fotos, filtro de llamadas y zonas.
 */
object Store {
    private lateinit var prefs: SharedPreferences
    private lateinit var dir: File
    private val configFile get() = File(dir, "config.json")
    private val queueFile get() = File(dir, "location-queue.json")
    val photosDir get() = File(dir, "photos").apply { mkdirs() }

    private val _config = MutableStateFlow(Config())
    val config: StateFlow<Config> = _config.asStateFlow()

    /** Se incrementa cuando cambia algo que la UI debe recomprobar (emparejado, permisos…). */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("cerca", Context.MODE_PRIVATE)
        dir = context.filesDir
        runCatching { _config.value = json.decodeFromString(Config.serializer(), configFile.readText()) }
    }

    fun bump() { _revision.value++ }

    // ---- Emparejamiento ----
    var serverUrl: String
        get() = prefs.getString("serverUrl", null) ?: BuildConfig.SERVER_URL
        set(v) = prefs.edit().putString("serverUrl", v.trim().trimEnd('/')).apply()

    var token: String?
        get() = prefs.getString("token", null)
        set(v) = prefs.edit().putString("token", v).apply()

    var seniorId: Long
        get() = prefs.getLong("seniorId", 0)
        set(v) = prefs.edit().putLong("seniorId", v).apply()

    var seniorName: String
        get() = prefs.getString("seniorName", "") ?: ""
        set(v) = prefs.edit().putString("seniorName", v).apply()

    var configVersion: Int
        get() = prefs.getInt("configVersion", 0)
        set(v) = prefs.edit().putInt("configVersion", v).apply()

    val isPaired get() = token != null

    fun unpair() {
        prefs.edit().remove("token").remove("seniorId").remove("configVersion").remove("zoneState")
            .remove("lastAppsHash").apply()
        saveConfig(Config())
    }

    fun saveConfig(cfg: Config) {
        configFile.writeText(json.encodeToString(Config.serializer(), cfg))
        _config.value = cfg
    }

    /** Archivo local de la foto de un contacto (descargada en la sincronización). */
    fun photoFile(photo: String?): File? =
        photo?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { File(photosDir, it) }?.takeIf { it.exists() }

    // ---- PIN ----
    fun checkPin(pin: String): Boolean {
        val hash = config.value.pinHash ?: return true
        val digest = MessageDigest.getInstance("SHA-256").digest("cerca:$seniorId:$pin".toByteArray())
        return digest.joinToString("") { "%02x".format(it) } == hash
    }

    // ---- Estado de zonas: "in", "out" o null (desconocido) ----
    var zoneState: String?
        get() = prefs.getString("zoneState", null)
        set(v) = prefs.edit().putString("zoneState", v).apply()

    var lastZoneName: String?
        get() = prefs.getString("lastZoneName", null)
        set(v) = prefs.edit().putString("lastZoneName", v).apply()

    // ---- Pasos manuales del asistente (no se pueden comprobar por código en Xiaomi) ----
    fun manualStepDone(key: String) = prefs.getBoolean("step_$key", false)
    fun setManualStep(key: String, done: Boolean) { prefs.edit().putBoolean("step_$key", done).apply(); bump() }

    // ---- Lista de apps enviada (para no reenviarla en cada latido) ----
    var lastAppsHash: Int
        get() = prefs.getInt("lastAppsHash", 0)
        set(v) = prefs.edit().putInt("lastAppsHash", v).apply()

    // ---- Llamadas repetidas de desconocidos ("dejar pasar si insiste") ----
    fun registerUnknownCall(key: String, now: Long): Boolean {
        val last = prefs.getLong("unk_$key", 0)
        prefs.edit().putLong("unk_$key", now).apply()
        return now - last < 5 * 60_000
    }

    // ---- Cola de ubicaciones pendientes de subir (sin cobertura) ----
    private val fixList = ListSerializer(LocationFix.serializer())

    @Synchronized
    fun enqueue(fix: LocationFix) {
        val list = readQueue().toMutableList()
        list += fix
        queueFile.writeText(json.encodeToString(fixList, list.takeLast(2000)))
    }

    @Synchronized
    fun readQueue(): List<LocationFix> =
        runCatching { json.decodeFromString(fixList, queueFile.readText()) }.getOrDefault(emptyList())

    @Synchronized
    fun dropFromQueue(count: Int) {
        val rest = readQueue().drop(count)
        if (rest.isEmpty()) queueFile.delete() else queueFile.writeText(json.encodeToString(fixList, rest))
    }
}
