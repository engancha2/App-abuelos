import java.util.Properties

// Cerca Familia: la app de los hijos para cuidar a distancia del móvil de la persona mayor
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Firma para Google Play: android/keystore.properties (no se sube a git). Sin ese archivo, la versión release sale sin firmar.
val signing = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

android {
    namespace = "com.estudiofenix.cerca.familia"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.estudiofenix.cerca.familia"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        // El mismo servidor que la app del mayor (android/gradle.properties o -Pcerca.serverUrl=...)
        val serverUrl = (project.findProperty("cerca.serverUrl") as String?) ?: "https://cerca.tudominio.com"
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
        // La suscripción de Google Play (Play Console → Monetizar → Suscripciones). El servidor acepta la misma (PLAY_PRODUCT_IDS).
        buildConfigField("String", "PREMIUM_PRODUCT_ID", "\"premium\"")
        // Firebase (solo para los avisos): los 4 datos de la app Android en la consola de Firebase, en android/gradle.properties.
        // Sin ellos la app funciona igual, pero los avisos solo llegan con la app abierta (y en el panel web).
        fun prop(name: String) = (project.findProperty(name) as String?).orEmpty()
        buildConfigField("String", "FIREBASE_APP_ID", "\"${prop("firebase.appId")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${prop("firebase.apiKey")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${prop("firebase.projectId")}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${prop("firebase.senderId")}\"")
    }

    signingConfigs {
        if (signing != null) create("release") {
            storeFile = rootProject.file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.tooling.preview)
    debugImplementation(libs.compose.tooling)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.osmdroid)       // mapas de OpenStreetMap, sin clave de Google
    implementation(libs.coil.compose)   // fotos de los contactos desde el servidor
    implementation(libs.play.billing)   // la suscripción, con Google Play
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)  // avisos (SOS, zonas…) aunque la app esté cerrada
    implementation(libs.kotlinx.coroutines.play.services)
}
