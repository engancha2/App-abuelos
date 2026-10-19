package com.estudiofenix.cerca.data

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : IOException(message)

object Api {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)   // mantiene vivo el WebSocket a través de Traefik
        .retryOnConnectionFailure(true)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun url(path: String) = Store.serverUrl + path

    private suspend fun <T> call(
        method: String,
        path: String,
        body: String?,
        out: KSerializer<T>?,
        auth: Boolean = true,
    ): T? = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url(path))
        if (auth) builder.header("Authorization", "Bearer ${Store.token ?: throw ApiException(401, "Sin emparejar")}")
        builder.method(method, body?.toRequestBody(JSON))
        client.newCall(builder.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val msg = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                throw ApiException(res.code, msg ?: "Error ${res.code}")
            }
            out?.let { json.decodeFromString(it, text) }
        }
    }

    suspend fun pair(serverUrl: String, code: String): PairResponse {
        Store.serverUrl = serverUrl
        val body = buildJsonObject {
            put("code", code)
            put("device", buildJsonObject {
                put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("android", Build.VERSION.RELEASE)
            })
        }.toString()
        return call("POST", "/api/device/pair", body, PairResponse.serializer(), auth = false)!!
    }

    suspend fun config(): ConfigResponse = call("GET", "/api/device/config", null, ConfigResponse.serializer())!!

    suspend fun heartbeat(hb: Heartbeat): HeartbeatResponse =
        call("POST", "/api/device/heartbeat", json.encodeToString(Heartbeat.serializer(), hb), HeartbeatResponse.serializer())!!

    suspend fun uploadLocations(fixes: List<LocationFix>) {
        call<Unit>("POST", "/api/device/locations", json.encodeToString(ListSerializer(LocationFix.serializer()), fixes), null)
    }

    suspend fun alert(req: AlertRequest) {
        call<Unit>("POST", "/api/device/alert", json.encodeToString(AlertRequest.serializer(), req), null)
    }

    suspend fun download(path: String, dest: File) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url(path)).build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw ApiException(res.code, "No se pudo descargar $path")
            val tmp = File(dest.parentFile, "${dest.name}.tmp")
            res.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(dest)
        }
    }

    fun wsUrl(): String = url("/api/device/ws").replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
}
