package com.estudiofenix.cerca.calls

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.estudiofenix.cerca.ui.Tap

/**
 * Vibración y tono al pulsar una tecla: con dedos poco sensibles hay que notar que la tecla ha entrado.
 * No depende de los ajustes de "vibrar al tocar" del sistema, que en Xiaomi suelen venir desactivados.
 */
class KeyFeedback(private val ctx: Context) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
    private val audio = ctx.getSystemService(AudioManager::class.java)
    // Algunos móviles no dejan crear el generador de tonos: entonces solo vibra
    private val tones: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_DTMF, 80) }.getOrNull()

    /** [key] es la cifra pulsada (suena su tono); null para "Volver" o "Borrar": el clic de los demás botones. */
    fun press(key: Char? = null) {
        val tone = key?.let { DTMF[it] }
        if (tone == null) {
            // Antes solo vibraban un instante y sin sonido: no se notaba que se habían pulsado
            Tap.feedback(ctx)
            return
        }
        runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)) }
        // Con el móvil en silencio o en vibración, no suena
        if (audio?.ringerMode == AudioManager.RINGER_MODE_NORMAL) runCatching { tones?.startTone(tone, 150) }
    }

    /** Solo vibrar: para las teclas de dentro de una llamada, cuyo tono ya lo pone la propia llamada. */
    fun vibrate() {
        runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    fun release() {
        tones?.release()
    }

    private companion object {
        val DTMF = mapOf(
            '0' to ToneGenerator.TONE_DTMF_0, '1' to ToneGenerator.TONE_DTMF_1, '2' to ToneGenerator.TONE_DTMF_2,
            '3' to ToneGenerator.TONE_DTMF_3, '4' to ToneGenerator.TONE_DTMF_4, '5' to ToneGenerator.TONE_DTMF_5,
            '6' to ToneGenerator.TONE_DTMF_6, '7' to ToneGenerator.TONE_DTMF_7, '8' to ToneGenerator.TONE_DTMF_8,
            '9' to ToneGenerator.TONE_DTMF_9, '*' to ToneGenerator.TONE_DTMF_S, '#' to ToneGenerator.TONE_DTMF_P,
        )
    }
}

@Composable
fun rememberKeyFeedback(): KeyFeedback {
    val ctx = LocalContext.current
    val feedback = remember { KeyFeedback(ctx.applicationContext) }
    DisposableEffect(feedback) { onDispose { feedback.release() } }
    return feedback
}
