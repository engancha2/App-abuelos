package com.estudiofenix.cerca.calls

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import com.estudiofenix.cerca.data.Phone
import com.estudiofenix.cerca.data.PhoneBook

/**
 * WhatsApp desde Cerca: chat y videollamada.
 *
 * WhatsApp no tiene una forma oficial de empezar una videollamada desde otra app, pero apunta en la agenda
 * de Android, para cada contacto que lo usa, una fila "Videollamada" (la que sale en la ficha de Contactos).
 * Abrir esa fila con WhatsApp empieza la videollamada directamente. Solo existe si el contacto está guardado
 * en la agenda del móvil; si no, se abre el chat (y la videollamada es la cámara de arriba).
 */
object WhatsApp {
    private data class App(val pkg: String, val videoMime: String)

    private val APPS = listOf(
        App("com.whatsapp", "vnd.android.cursor.item/vnd.com.whatsapp.video.call"),
        App("com.whatsapp.w4b", "vnd.android.cursor.item/vnd.com.whatsapp.w4b.video.call"), // WhatsApp Business
    )

    /** [videoCall] es la fila de la agenda para la videollamada (null si el contacto no está en la agenda). */
    data class Info(val pkg: String, val videoCall: Uri?)

    private fun installed(ctx: Context, pkg: String) =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /**
     * Si WhatsApp está instalado y, si se puede, la fila de videollamada de alguno de esos números.
     * null si no hay WhatsApp. Lee la agenda: llamar fuera del hilo principal.
     */
    fun info(ctx: Context, numbers: List<String>): Info? {
        val apps = APPS.filter { installed(ctx, it.pkg) }
        if (apps.isEmpty()) return null
        val keys = numbers.mapNotNull(Phone::matchKey).toSet()
        if (keys.isNotEmpty() && PhoneBook.allowed(ctx)) {
            for (app in apps) {
                findVideoRow(ctx, app.videoMime, keys)?.let { return Info(app.pkg, it) }
            }
        }
        return Info(apps.first().pkg, null)
    }

    private fun findVideoRow(ctx: Context, mime: String, keys: Set<String>): Uri? = runCatching {
        ctx.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID, ContactsContract.Data.DATA1),
            "${ContactsContract.Data.MIMETYPE} = ?", arrayOf(mime), null,
        )?.use { c ->
            while (c.moveToNext()) {
                // DATA1 es su identificador de WhatsApp: "34600112233@s.whatsapp.net"
                val jid = c.getString(1) ?: continue
                if (Phone.matchKey(jid.substringBefore('@')) in keys) {
                    return@use ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, c.getLong(0))
                }
            }
            null
        }
    }.getOrNull()

    /** Empieza la videollamada; si no se puede (no está en la agenda), abre el chat y explica dónde está la cámara. */
    fun videoCall(ctx: Context, info: Info, number: String) {
        val row = info.videoCall
        if (row != null) {
            val mime = APPS.first { it.pkg == info.pkg }.videoMime
            val ok = runCatching {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(row, mime).setPackage(info.pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            if (ok) return
        }
        if (openChat(ctx, number)) {
            Toast.makeText(ctx, "Para la videollamada, pulsa la cámara 📹 de arriba", Toast.LENGTH_LONG).show()
        }
    }

    /** Abre el chat de WhatsApp con ese número, para escribirle. */
    fun openChat(ctx: Context, number: String): Boolean {
        var digits = number.filter { it.isDigit() }
        if (number.trim().startsWith("00")) digits = digits.drop(2)
        else if (!number.trim().startsWith("+") && digits.length == 9) digits = "34$digits" // número español sin prefijo
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (app in APPS) {
            try {
                ctx.startActivity(Intent(intent).setPackage(app.pkg))
                return true
            } catch (_: Exception) { }
        }
        return false
    }
}
