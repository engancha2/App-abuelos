package com.estudiofenix.cerca.familia.data

import com.estudiofenix.cerca.data.Config
import com.estudiofenix.cerca.data.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : IOException(message)

/** El mensaje que se enseña cuando algo falla: el del servidor si lo hay; si no, uno que se entienda. */
fun friendlyError(e: Throwable): String = when (e) {
    is ApiException -> e.message ?: "Error ${e.code}"
    is IOException -> "No se puede conectar con el servidor. Comprueba la conexión a internet."
    else -> e.message ?: "Algo ha fallado"
}

/** La API de la familia del servidor de Cerca (la misma que usa el panel web, con token en vez de cookie). */
object FamilyApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS) // las fotos
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    fun url(path: String) = Session.serverUrl + path

    /** Dirección completa de una foto del servidor ("/uploads/…"), para cargarla. */
    fun photoUrl(path: String?): String? = path?.let(::url)

    private fun body(build: JsonObjectBuilder.() -> Unit): String = buildJsonObject(build).toString()

    private suspend fun <T> call(
        method: String,
        path: String,
        body: String? = null,
        out: KSerializer<T>? = null,
        auth: Boolean = true,
    ): T? {
        // El servidor no acepta un POST/PUT JSON vacío: se manda "{}"
        val payload: RequestBody? = when {
            body != null -> body.toRequestBody(JSON_TYPE)
            method in setOf("POST", "PUT", "PATCH") -> "{}".toRequestBody(JSON_TYPE)
            else -> null
        }
        return execute(Request.Builder().url(url(path)).method(method, payload), out, auth)
    }

    private suspend fun <T> execute(builder: Request.Builder, out: KSerializer<T>?, auth: Boolean): T? = withContext(Dispatchers.IO) {
        if (auth) Session.token?.let { builder.header("Authorization", "Bearer $it") }
        client.newCall(builder.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val msg = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                // La sesión ha caducado o se ha cerrado en otro sitio: a volver a entrar
                if (auth && res.code == 401) Session.signOut()
                throw ApiException(res.code, msg ?: "Error ${res.code}")
            }
            out?.let { json.decodeFromString(it, text) }
        }
    }

    // ---------------------------------------------------------------- Cuenta
    suspend fun meta(): Meta = call("GET", "/api/meta", out = Meta.serializer(), auth = false)!!

    /** Crea la cuenta y deja la sesión abierta. */
    suspend fun register(email: String, name: String, password: String) {
        val token = call("POST", "/api/auth/register", body {
            put("email", email.trim()); put("name", name.trim()); put("password", password); put("client", "app")
        }, TokenResponse.serializer(), auth = false)!!.token
        startSession(token)
    }

    suspend fun login(email: String, password: String) {
        val token = call("POST", "/api/auth/login", body {
            put("email", email.trim()); put("password", password); put("client", "app")
        }, TokenResponse.serializer(), auth = false)!!.token
        startSession(token)
    }

    private suspend fun startSession(token: String) {
        val user = execute(
            Request.Builder().url(url("/api/me")).header("Authorization", "Bearer $token"),
            User.serializer(), auth = false,
        )!!
        Session.signIn(token, user)
    }

    /** Cierra la sesión; con [fcmToken], este móvil deja de recibir los avisos. Aunque falle, se sale. */
    suspend fun logout(fcmToken: String? = null) {
        runCatching { call<Unit>("POST", "/api/auth/logout", body { fcmToken?.let { put("fcmToken", it) } }) }
        Session.signOut()
    }

    suspend fun registerFcm(token: String) {
        call<Unit>("POST", "/api/push/fcm", body { put("token", token) })
    }

    // ---------------------------------------------------------------- Personas mayores
    suspend fun seniors(): List<SeniorSummary> = call("GET", "/api/seniors", out = ListSerializer(SeniorSummary.serializer()))!!

    suspend fun createSenior(name: String): Long =
        call("POST", "/api/seniors", body { put("name", name.trim()) }, IdResponse.serializer())!!.id

    suspend fun senior(id: Long): SeniorDetail = call("GET", "/api/seniors/$id", out = SeniorDetail.serializer())!!

    suspend fun rename(id: Long, name: String) {
        call<Unit>("PATCH", "/api/seniors/$id", body { put("name", name.trim()) })
    }

    suspend fun deleteSenior(id: Long) {
        call<Unit>("DELETE", "/api/seniors/$id")
    }

    /** Guarda la configuración; el servidor la valida, sube la versión y avisa al móvil. */
    suspend fun saveConfig(id: Long, config: Config): Int =
        call("PUT", "/api/seniors/$id/config", json.encodeToString(Config.serializer(), config), VersionResponse.serializer())!!.version

    suspend fun setPin(id: Long, pin: String) {
        call<Unit>("POST", "/api/seniors/$id/pin", body { put("pin", pin) })
    }

    suspend fun pairCode(id: Long): PairCode = call("POST", "/api/seniors/$id/pair-code", out = PairCode.serializer())!!

    suspend fun unpair(id: Long) {
        call<Unit>("POST", "/api/seniors/$id/unpair")
    }

    /** "¿Dónde está ahora?": live = conectado ahora; pushed = despertado por Firebase. */
    suspend fun locate(id: Long): LocateResponse = call("POST", "/api/seniors/$id/locate", out = LocateResponse.serializer())!!

    // ---------------------------------------------------------------- Timbres
    private var ringtonesCache: List<Ringtone>? = null

    suspend fun ringtones(): List<Ringtone> = ringtonesCache
        ?: call("GET", "/api/ringtones", out = ListSerializer(Ringtone.serializer()), auth = false)!!.also { ringtonesCache = it }

    /** El sonido de un timbre, para escucharlo en este móvil. */
    fun ringtoneUrl(id: String): String = url("/ringtones/$id.wav")

    /** "Hacerlo sonar en su móvil": suena unos segundos el timbre ya guardado. */
    suspend fun ringTest(id: Long): LocateResponse = call("POST", "/api/seniors/$id/ring-test", out = LocateResponse.serializer())!!

    suspend fun locations(id: Long, since: Long): List<LocationPoint> =
        call("GET", "/api/seniors/$id/locations?since=$since", out = ListSerializer(LocationPoint.serializer()))!!

    suspend fun alerts(id: Long): List<Alert> = call("GET", "/api/seniors/$id/alerts", out = ListSerializer(Alert.serializer()))!!

    suspend fun ackAlerts(id: Long) {
        call<Unit>("POST", "/api/seniors/$id/alerts/ack")
    }

    /** Da acceso a otro familiar (tiene que haber creado antes su cuenta). */
    suspend fun addMember(id: Long, email: String) {
        call<Unit>("POST", "/api/seniors/$id/members", body { put("email", email.trim()) })
    }

    suspend fun removeMember(id: Long, userId: Long) {
        call<Unit>("DELETE", "/api/seniors/$id/members/$userId")
    }

    /** Sube la foto de un contacto (JPEG ya recortado y reducido). Devuelve la ruta para ponerla en el contacto. */
    suspend fun uploadPhoto(id: Long, jpeg: ByteArray): String {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("photo", "photo.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        return execute(Request.Builder().url(url("/api/seniors/$id/photo")).post(form), PhotoResponse.serializer(), auth = true)!!.photo
    }
}
