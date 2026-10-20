pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Cerca"
include(":app")      // Cerca: el móvil de la persona mayor
include(":familia")  // Cerca Familia: la app de los hijos
include(":core")     // lo que comparten las dos (la configuración y su formato)
