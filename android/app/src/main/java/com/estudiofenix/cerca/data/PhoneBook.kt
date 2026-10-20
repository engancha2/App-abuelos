package com.estudiofenix.cerca.data

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract.PhoneLookup
import com.estudiofenix.cerca.setup.DeviceChecks
import android.provider.ContactsContract.CommonDataKinds.Phone as PhoneKind

/** Agenda del móvil (los contactos de Android), para la pantalla "Contactos". */
object PhoneBook {
    data class PhoneNumber(val label: String, val number: String)

    /** [thumbnail] es la foto pequeña (para la lista) y [photo], la grande (para la ficha); las dos, content://. */
    data class Entry(
        val id: Long,
        val name: String,
        val numbers: List<PhoneNumber>,
        val thumbnail: String? = null,
        val photo: String? = null,
    )

    /** Quien llama, según la agenda. Solo sirve para enseñar su nombre y su foto: no cambia qué llamadas suenan. */
    data class Caller(val name: String, val photo: String?)

    fun allowed(ctx: Context) = DeviceChecks.granted(ctx, Manifest.permission.READ_CONTACTS)

    /** Una entrada por persona, ordenadas por nombre, con sus números sin repetir. Llamar fuera del hilo principal. */
    fun load(ctx: Context): List<Entry> {
        if (!allowed(ctx)) return emptyList()
        val projection = arrayOf(
            PhoneKind.CONTACT_ID, PhoneKind.DISPLAY_NAME_PRIMARY, PhoneKind.NUMBER, PhoneKind.TYPE, PhoneKind.LABEL,
            PhoneKind.PHOTO_THUMBNAIL_URI, PhoneKind.PHOTO_URI,
        )
        class Pending(val name: String, val thumbnail: String?, val photo: String?) {
            val numbers = mutableListOf<PhoneNumber>()
        }
        val byContact = LinkedHashMap<Long, Pending>()
        ctx.contentResolver.query(
            PhoneKind.CONTENT_URI, projection, null, null,
            // El número principal primero
            "${PhoneKind.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC, ${PhoneKind.IS_SUPER_PRIMARY} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1)?.trim().orEmpty()
                val number = c.getString(2)?.trim().orEmpty()
                if (name.isEmpty() || number.isEmpty()) continue
                val label = PhoneKind.getTypeLabel(ctx.resources, c.getInt(3), c.getString(4)).toString()
                val numbers = byContact.getOrPut(c.getLong(0)) { Pending(name, c.getString(5), c.getString(6)) }.numbers
                val key = Phone.matchKey(number) ?: number
                if (numbers.none { (Phone.matchKey(it.number) ?: it.number) == key }) numbers += PhoneNumber(label, number)
            }
        }
        return byContact.map { (id, p) -> Entry(id, p.name, p.numbers, p.thumbnail, p.photo) }
    }

    /**
     * Busca un número en la agenda igual que el teléfono de Android (con o sin prefijo, con espacios…).
     * Llamar fuera del hilo principal. Sin permiso de contactos, null.
     */
    fun lookup(ctx: Context, number: String?): Caller? {
        if (number.isNullOrBlank() || !allowed(ctx)) return null
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val projection = arrayOf(PhoneLookup.DISPLAY_NAME, PhoneLookup.PHOTO_URI, PhoneLookup.PHOTO_THUMBNAIL_URI)
        return runCatching {
            ctx.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val name = c.getString(0)?.trim().orEmpty()
                if (name.isEmpty()) null else Caller(name, c.getString(1) ?: c.getString(2))
            }
        }.getOrNull()
    }
}
