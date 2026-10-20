// Lo que comparten las dos apps: la configuración de la persona mayor y su formato en el servidor.
// Si cambia aquí, cambia a la vez en Cerca y en Cerca Familia (y hay que mantenerlo igual que server/src/db.js).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.estudiofenix.cerca.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 26 // la app de la familia admite móviles más antiguos que la del mayor
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(libs.kotlinx.serialization.json)
}
