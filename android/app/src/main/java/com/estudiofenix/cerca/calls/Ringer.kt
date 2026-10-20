package com.estudiofenix.cerca.calls

import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import com.estudiofenix.cerca.data.Api
import com.estudiofenix.cerca.data.CallsConfig
import com.estudiofenix.cerca.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * El timbre de las llamadas. Lo elige la familia y los sonidos los fabrica el servidor (server/src/ringtones.js).
 * Cerca no suena por su cuenta: pone el elegido como tono de llamada del móvil, y Android hace lo de siempre
 * (silencio, "no molestar", vibración, el filtro de desconocidos…).
 * Para cambiar el tono hace falta el permiso especial "Modificar ajustes del sistema" (paso del asistente).
 */
object Ringer {
    private const val TAG = "Ringer"
    private val mutex = Mutex()

    /** Cerca puede cambiar el tono de llamada del móvil. */
    fun allowed(ctx: Context): Boolean = Settings.System.canWrite(ctx)

    /** Pone el timbre y el volumen de la configuración. Se llama en cada sincronización: si no cambia nada, no hace nada. */
    suspend fun apply(ctx: Context, calls: CallsConfig) = mutex.withLock {
        if (calls.ringMax) maxVolume(ctx)
        if (!allowed(ctx)) return@withLock // el latido se lo cuenta a la familia (ringtoneAllowed = false)
        try {
            if (calls.ringtone == "system") restoreOriginal(ctx) else setTone(ctx, calls.ringtone)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo poner el timbre ${calls.ringtone}: ${e.message}")
        }
    }

    private suspend fun setTone(ctx: Context, id: String) {
        if (Store.appliedRingtone == id) return
        val uri = toneUri(ctx, id) ?: return
        // La primera vez, se guarda el que tenía para poder volver a él si la familia elige "El del móvil"
        if (Store.appliedRingtone == null) {
            Store.originalRingtone = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_RINGTONE)?.toString().orEmpty()
        }
        RingtoneManager.setActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_RINGTONE, uri)
        Store.appliedRingtone = id
        Log.i(TAG, "Timbre puesto: $id")
    }

    private fun restoreOriginal(ctx: Context) {
        if (Store.appliedRingtone == null) return // nunca lo hemos cambiado: es el suyo
        val original = Store.originalRingtone?.takeIf { it.isNotEmpty() }?.let(Uri::parse)
        RingtoneManager.setActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_RINGTONE, original)
        Store.appliedRingtone = null
        Store.originalRingtone = null
        Log.i(TAG, "Vuelve el timbre que tenía el móvil")
    }

    /** El archivo del timbre en la carpeta de tonos del móvil (Ringtones/Cerca); si no está, se descarga del servidor. */
    private suspend fun toneUri(ctx: Context, id: String): Uri? = withContext(Dispatchers.IO) {
        val name = "cerca_$id.wav"
        val resolver = ctx.contentResolver
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        resolver.query(collection, arrayOf(MediaStore.Audio.Media._ID), "${MediaStore.Audio.Media.DISPLAY_NAME} = ?", arrayOf(name), null)
            ?.use { c -> if (c.moveToFirst()) return@withContext Uri.withAppendedPath(collection, c.getLong(0).toString()) }

        val tmp = File(ctx.cacheDir, name)
        Api.download("/ringtones/$id.wav", tmp)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.TITLE, "Cerca · $id")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_RINGTONES}/Cerca")
            put(MediaStore.Audio.Media.IS_RINGTONE, 1)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return@withContext null
        resolver.openOutputStream(uri)?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        tmp.delete()
        uri
    }

    /** Timbre a todo volumen y, si lo habían puesto en silencio o en vibración sin querer, que vuelva a sonar. */
    private fun maxVolume(ctx: Context) {
        val audio = ctx.getSystemService(AudioManager::class.java) ?: return
        // Con "no molestar" Android no deja tocar el volumen ni el modo: se respeta
        runCatching { if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) audio.ringerMode = AudioManager.RINGER_MODE_NORMAL }
        runCatching { audio.setStreamVolume(AudioManager.STREAM_RING, audio.getStreamMaxVolume(AudioManager.STREAM_RING), 0) }
            .onFailure { Log.w(TAG, "No se pudo subir el volumen del timbre: ${it.message}") }
    }

    /** "Hacerlo sonar en su móvil": la familia comprueba que se oye. Unos segundos, al volumen del timbre. */
    suspend fun preview(ctx: Context, id: String, seconds: Int = 8) {
        if (Store.config.value.calls.ringMax) maxVolume(ctx)
        val uri = if (id == "system") {
            Store.originalRingtone?.takeIf { it.isNotEmpty() && Store.appliedRingtone != null }?.let(Uri::parse)
                ?: RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_RINGTONE)
        } else {
            runCatching { toneUri(ctx, id) }.getOrNull()
        } ?: return
        val player = withContext(Dispatchers.Main) {
            runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    setDataSource(ctx, uri)
                    isLooping = true
                    prepare()
                    start()
                }
            }.onFailure { Log.w(TAG, "No se pudo hacer sonar el timbre: ${it.message}") }.getOrNull()
        } ?: return
        delay(seconds * 1000L)
        withContext(Dispatchers.Main) { runCatching { player.stop() }; player.release() }
    }
}
