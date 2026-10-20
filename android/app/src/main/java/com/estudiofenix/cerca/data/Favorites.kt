package com.estudiofenix.cerca.data

import android.content.Context

/**
 * Favoritos = los contactos grandes del inicio (Config.contacts). Se guardan en el servidor,
 * así la familia los ve y los puede cambiar; el móvil solo pide añadir o quitar.
 */
object Favorites {
    fun isFavorite(cfg: Config, number: String): Boolean = cfg.contactForNumber(number) != null

    /** Añade o quita un favorito y trae la configuración nueva. Devuelve el mensaje de error, o null si ha ido bien. */
    suspend fun set(ctx: Context, name: String, number: String, favorite: Boolean): String? {
        if (!Store.isPaired) return "Este móvil aún no está conectado con la familia."
        return try {
            Api.setFavorite(name, number, favorite)
            Sync.syncNow(ctx.applicationContext, forceConfig = true)
            null
        } catch (e: ApiException) {
            e.message ?: "No se ha podido guardar"
        } catch (e: Exception) {
            "No hay conexión. Inténtalo más tarde."
        }
    }
}
