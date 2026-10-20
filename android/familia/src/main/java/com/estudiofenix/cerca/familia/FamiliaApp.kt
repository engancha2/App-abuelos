package com.estudiofenix.cerca.familia

import android.app.Application
import com.estudiofenix.cerca.familia.data.Session
import org.osmdroid.config.Configuration

class FamiliaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Session.init(this)
        // OpenStreetMap exige identificar la app que pide los mapas; la caché va en la carpeta de la app
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = cacheDir.resolve("osmdroid")
            osmdroidTileCache = cacheDir.resolve("osmdroid/tiles")
        }
    }
}
