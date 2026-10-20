package com.estudiofenix.cerca.ui

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Vibración corta y "clic" al pulsar cualquier botón: con dedos poco sensibles hay que notar que se ha pulsado.
 * No depende de "vibrar al tocar" ni de "sonidos al tocar" del sistema (en Xiaomi suelen venir quitados);
 * con el móvil en silencio, el clic no suena (va por el volumen del sistema) pero sí vibra.
 */
object Tap {
    private fun vibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)

    fun feedback(ctx: Context) {
        runCatching { vibrator(ctx)?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)) }
        // Con volumen explícito suena aunque los "sonidos al tocar" estén desactivados
        runCatching { ctx.getSystemService(AudioManager::class.java)?.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f) }
    }

    /** Más larga, para lo importante: el SOS al activarse o el menú de la familia al abrirse. */
    fun strong(ctx: Context) {
        runCatching { vibrator(ctx)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 220), -1)) }
    }
}

/** Para usar en un onClick: `val tap = rememberTap()` y luego `onClick = { tap(); … }`. */
@Composable
fun rememberTap(): () -> Unit {
    val ctx = LocalContext.current
    return remember(ctx) { { Tap.feedback(ctx.applicationContext) } }
}
