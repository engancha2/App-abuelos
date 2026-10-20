package com.estudiofenix.cerca.familia.ui.tabs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * La foto de un contacto lista para subir, como resizeImage() del panel: recorte cuadrado centrado,
 * [size]×[size] y JPEG con calidad [quality]. Respeta la orientación EXIF (las fotos de la cámara
 * suelen venir "tumbadas" con la marca de girarlas). Se hace fuera del hilo principal.
 *
 * Si falla, el mensaje es para la familia ("No se pudo leer la imagen"), no uno de conexión.
 */
internal suspend fun contactPhotoJpeg(context: Context, uri: Uri, size: Int = 512, quality: Int = 85): ByteArray =
    withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver

            // 1) Solo las medidas, para no cargar en memoria una foto de 50 megapíxeles
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("No se pudo leer la imagen")

            // 2) Reducida de golpe (potencias de 2) sin bajar del tamaño final por el lado corto
            var sample = 1
            while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
            val src = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: error("No se pudo leer la imagen")

            val orientation = runCatching {
                resolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
            }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

            // 3) Cuadrado del centro → girado según EXIF → a [size]×[size], sobre blanco (por si es un PNG con transparencias)
            val side = min(src.width, src.height).toFloat()
            val matrix = Matrix().apply {
                postTranslate(-src.width / 2f, -src.height / 2f)
                postConcat(exifMatrix(orientation))
                postScale(size / side, size / side)
                postTranslate(size / 2f, size / 2f)
            }
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            Canvas(out).apply {
                drawColor(Color.WHITE)
                drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            }
            src.recycle()

            ByteArrayOutputStream().use { bytes ->
                if (!out.compress(Bitmap.CompressFormat.JPEG, quality, bytes)) error("No se pudo procesar la foto")
                out.recycle()
                bytes.toByteArray()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw IllegalStateException("La foto es demasiado grande")
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            // FileNotFoundException, SecurityException…: que no parezca un fallo de conexión
            throw IllegalStateException("No se pudo leer la imagen")
        }
    }

/** El giro o volteo que pide la marca EXIF, alrededor del origen (como hacen Glide o Coil). */
private fun exifMatrix(orientation: Int): Matrix = Matrix().apply {
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> { setRotate(180f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
    }
}
