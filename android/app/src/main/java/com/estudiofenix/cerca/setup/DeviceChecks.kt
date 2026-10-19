package com.estudiofenix.cerca.setup

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat

/** Comprobaciones del estado del móvil que usan el asistente, el panel y el latido. */
object DeviceChecks {

    fun granted(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    fun isDefaultHome(ctx: Context): Boolean =
        ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)

    fun isDefaultDialer(ctx: Context): Boolean =
        ctx.getSystemService(TelecomManager::class.java).defaultDialerPackage == ctx.packageName

    /** "always" | "foreground" | "none" */
    fun locationPermission(ctx: Context): String = when {
        !granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) -> "none"
        granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> "always"
        else -> "foreground"
    }

    fun locationEnabled(ctx: Context): Boolean =
        ctx.getSystemService(LocationManager::class.java).isLocationEnabled

    fun callPermissions(ctx: Context): Boolean =
        granted(ctx, Manifest.permission.CALL_PHONE) && granted(ctx, Manifest.permission.READ_PHONE_STATE)

    fun notificationsAllowed(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun fullScreenAllowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun ignoringBatteryOptimizations(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    fun battery(ctx: Context): Pair<Int?, Boolean> {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        return level to bm.isCharging
    }

    val isXiaomi: Boolean
        get() = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")

    // ---------------------------------------------------------------- Pantallas de ajustes de Xiaomi
    // No hay API para leer estos ajustes, así que el asistente abre la pantalla y la familia confirma.

    /** Autoinicio (Seguridad → Permisos → Autoinicio). */
    fun xiaomiAutostartIntents(ctx: Context): List<Intent> = listOf(
        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
        appDetails(ctx),
    )

    /** Ahorro de batería de la app → "Sin restricciones". */
    fun xiaomiBatteryIntents(ctx: Context): List<Intent> = listOf(
        Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
            .putExtra("package_name", ctx.packageName)
            .putExtra("package_label", "Cerca"),
        appDetails(ctx),
    )

    /** Permisos extra de MIUI/HyperOS: "Mostrar en pantalla de bloqueo" y "Ventanas emergentes en segundo plano". */
    fun xiaomiOtherPermissionsIntents(ctx: Context): List<Intent> = listOf(
        Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", ctx.packageName),
        Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
            .putExtra("extra_pkgname", ctx.packageName),
        appDetails(ctx),
    )

    fun appDetails(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

    fun fullScreenSettings(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}"))
        else appDetails(ctx)

    /** Abre el primer intent que exista en este móvil. */
    fun startFirst(ctx: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            try {
                ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) { /* siguiente */ }
        }
        return false
    }
}
