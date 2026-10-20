package com.estudiofenix.cerca.setup

import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estudiofenix.cerca.calls.Ringer
import com.estudiofenix.cerca.data.Api
import com.estudiofenix.cerca.data.Store
import com.estudiofenix.cerca.data.Sync
import com.estudiofenix.cerca.location.CercaService
import com.estudiofenix.cerca.ui.cercaSystemBars
import com.estudiofenix.cerca.ui.CercaTheme
import com.estudiofenix.cerca.ui.Palette
import kotlinx.coroutines.launch

/**
 * Asistente para la familia. Es una lista de pasos con su estado (✅/❌) que se recalcula al volver
 * de cada pantalla de ajustes, así se puede hacer en cualquier orden y repetir cuando algo se rompa.
 */
class SetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cercaSystemBars(highContrast = false) // el asistente siempre va en claro: iconos oscuros aunque el sistema esté en oscuro
        setContent {
            CercaTheme { SetupScreen(onDone = ::finish) }
        }
    }

    override fun onResume() {
        super.onResume()
        Store.bump() // recomprobar todo al volver de los ajustes
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun SetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val revision by Store.revision.collectAsStateWithLifecycle()
    val roleManager = remember { ctx.getSystemService(RoleManager::class.java) }
    val refresh = { Store.bump(); CercaService.ensure(ctx.applicationContext) }
    // Cuando Android ya no enseña la ventana de un permiso (se denegó dos veces), solo queda la pantalla de la app
    val appSettings: () -> Unit = { DeviceChecks.startFirst(ctx, listOf(DeviceChecks.appDetails(ctx))) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    val activityResult = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }

    // Recalculado en cada "revision" (al volver de ajustes, al conceder permisos…)
    val paired = remember(revision) { Store.isPaired }
    val phonePerms = remember(revision) {
        DeviceChecks.callPermissions(ctx) &&
            DeviceChecks.granted(ctx, Manifest.permission.READ_CONTACTS) &&
            (Build.VERSION.SDK_INT < 33 || DeviceChecks.granted(ctx, Manifest.permission.POST_NOTIFICATIONS))
    }
    val dialer = remember(revision) { DeviceChecks.isDefaultDialer(ctx) }
    val home = remember(revision) { DeviceChecks.isDefaultHome(ctx) }
    val location = remember(revision) { DeviceChecks.locationPermission(ctx) }
    val battery = remember(revision) { DeviceChecks.ignoringBatteryOptimizations(ctx) }
    val fullScreen = remember(revision) { DeviceChecks.fullScreenAllowed(ctx) }
    val ringtone = remember(revision) { Ringer.allowed(ctx) }

    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Configurar Cerca", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text(
                "Hazlo tú (la familia) con el móvil en la mano. Puedes volver aquí cuando quieras " +
                    "manteniendo pulsada la hora 3 segundos en la pantalla de inicio.",
                fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            )
        }

        item { PairStep(paired) }

        item {
            Step("2. Permisos de teléfono y avisos", phonePerms,
                "Para poder llamar, ver la agenda y enseñar las llamadas entrantes.", settings = appSettings) {
                val list = mutableListOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CONTACTS)
                if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
                permissions.launch(list.toTypedArray())
            }
        }
        item {
            Step("3. App de teléfono", dialer, "Las llamadas pasan por Cerca: pantalla grande y filtro de desconocidos.") {
                activityResult.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
            }
        }
        item {
            Step("4. Pantalla de inicio", home, "Al pulsar el botón de inicio siempre aparece Cerca.") {
                if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    activityResult.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                } else {
                    DeviceChecks.startFirst(ctx, listOf(Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_SETTINGS)))
                }
            }
        }
        item {
            Step(
                "5. Ubicación «Permitir siempre»", location == "always",
                when (location) {
                    "none" -> "Primero concede la ubicación; después pide «Permitir todo el tiempo»."
                    "foreground" -> "Falta «Permitir todo el tiempo». Se abrirá la pantalla de permisos: elige esa opción."
                    else -> "Para avisarte si sale de la zona y para el botón SOS."
                },
                actionLabel = if (location == "foreground") "Permitir siempre" else "Conceder",
                settings = appSettings,
            ) {
                if (location == "none") {
                    permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                } else {
                    // En Android 11+ esto abre la pantalla de permisos de la app
                    permissions.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                }
            }
        }
        item {
            Step("6. Batería sin restricciones", battery, "Para que Android no cierre Cerca para ahorrar batería.") {
                try {
                    activityResult.launch(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
                    )
                } catch (_: ActivityNotFoundException) {
                    // Algunas capas de fabricante quitan esta ventana: la lista general de batería o la de la app
                    DeviceChecks.startFirst(
                        ctx, listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), DeviceChecks.appDetails(ctx)),
                    )
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            item {
                Step("7. Llamadas a pantalla completa", fullScreen, "Para que la llamada entrante ocupe toda la pantalla con el móvil bloqueado.") {
                    // Si el sistema no tiene esa pantalla, la de la app (antes se cerraba Cerca)
                    DeviceChecks.startFirst(ctx, listOf(DeviceChecks.fullScreenSettings(ctx), DeviceChecks.appDetails(ctx)))
                }
            }
        }

        item {
            Step("8. Timbre", ringtone, "Para que la familia pueda poner un timbre fuerte, de los que se oyen bien: " +
                "busca Cerca y activa «Modificar ajustes del sistema».") {
                DeviceChecks.startFirst(ctx, listOf(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")),
                    DeviceChecks.appDetails(ctx),
                ))
            }
        }

        if (DeviceChecks.isXiaomi) {
            item {
                Text("Pasos de Xiaomi", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 8.dp))
                Text(
                    "HyperOS/MIUI cierra las apps por su cuenta. Estos ajustes no se pueden comprobar desde la app: " +
                        "ábrelos, actívalos y marca la casilla.",
                    fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                )
            }
            item {
                ManualStep("xiaomi_autostart", "Autoinicio", "Activa «Autoinicio» para Cerca.", revision) {
                    DeviceChecks.startFirst(ctx, DeviceChecks.xiaomiAutostartIntents(ctx))
                }
            }
            item {
                ManualStep("xiaomi_battery", "Ahorro de batería", "Elige «Sin restricciones».", revision) {
                    DeviceChecks.startFirst(ctx, DeviceChecks.xiaomiBatteryIntents(ctx))
                }
            }
            item {
                ManualStep(
                    "xiaomi_popups", "Otros permisos",
                    "Activa «Mostrar en pantalla de bloqueo» y «Mostrar ventanas emergentes mientras se ejecuta en segundo plano».",
                    revision,
                ) { DeviceChecks.startFirst(ctx, DeviceChecks.xiaomiOtherPermissionsIntents(ctx)) }
            }
            item {
                ManualStep(
                    "xiaomi_lock", "Bloquear en recientes",
                    "Abre la vista de apps recientes, mantén pulsada la tarjeta de Cerca y toca el candado.", revision, action = null,
                )
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text(if (paired && phonePerms && dialer && home) "Terminar" else "Más tarde", fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun PairStep(paired: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(Store.serverUrl) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmUnpair by remember { mutableStateOf(false) }

    StepCard("1. Conectar con la familia", paired) {
        if (paired) {
            Text("Conectado como «${Store.seniorName}».", fontSize = 16.sp)
            // Se confirma: un toque sin querer borraría los contactos y ajustes de la familia
            OutlinedButton(onClick = { confirmUnpair = true }) { Text("Desconectar este móvil") }
        } else {
            Text("En el panel web, abre a tu familiar → Ajustes → «Generar código».", fontSize = 16.sp)
            OutlinedTextField(server, { server = it.trim() }, label = { Text("Servidor") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(code, { v -> code = v.filter { it.isDigit() }.take(6) }, label = { Text("Código de 6 números") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = Palette.red, fontSize = 15.sp) }
            Button(
                enabled = !busy && code.length == 6 && server.startsWith("http"),
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            val res = Api.pair(server, code)
                            Store.token = res.token
                            Store.seniorId = res.seniorId
                            Store.seniorName = res.name
                            Sync.syncNow(ctx.applicationContext, forceConfig = true)
                            Sync.schedule(ctx.applicationContext)
                            Store.bump()
                        } catch (e: Exception) {
                            error = e.message ?: "No se pudo conectar"
                        } finally {
                            busy = false
                        }
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Conectar")
            }
        }
    }

    if (confirmUnpair) AlertDialog(
        onDismissRequest = { confirmUnpair = false },
        title = { Text("¿Desconectar este móvil?") },
        text = {
            Text("Se borrarán de este móvil los contactos y los ajustes de la familia. " +
                "Para volver a conectarlo hará falta un código nuevo del panel.")
        },
        confirmButton = {
            TextButton(onClick = {
                confirmUnpair = false
                Store.unpair(); Store.bump(); CercaService.ensure(ctx.applicationContext)
            }) { Text("Desconectar", color = Palette.red) }
        },
        dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancelar") } },
    )
}

@Composable
private fun Step(
    title: String,
    ok: Boolean,
    body: String,
    actionLabel: String = "Activar",
    settings: (() -> Unit)? = null,
    action: () -> Unit,
) {
    StepCard(title, ok) {
        Text(body, fontSize = 16.sp)
        if (!ok) Button(onClick = action) { Text(actionLabel) }
        if (!ok && settings != null) OutlinedButton(onClick = settings) { Text("No sale la ventana: abrir ajustes de la app") }
    }
}

@Composable
private fun ManualStep(key: String, title: String, body: String, revision: Int, action: (() -> Unit)?) {
    val done = remember(revision) { Store.manualStepDone(key) }
    StepCard(title, done) {
        Text(body, fontSize = 16.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (action != null) OutlinedButton(onClick = action) { Text("Abrir ajustes") }
            Checkbox(checked = done, onCheckedChange = { Store.setManualStep(key, it) })
            Text("Hecho")
        }
    }
}

@Composable
private fun StepCard(title: String, ok: Boolean, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text((if (ok) "✅  " else "⬜  ") + title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}
