package com.estudiofenix.cerca.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.estudiofenix.cerca.calls.CallManager
import com.estudiofenix.cerca.data.Apps
import com.estudiofenix.cerca.data.Config
import com.estudiofenix.cerca.data.Contact
import com.estudiofenix.cerca.data.Store
import com.estudiofenix.cerca.setup.DeviceChecks
import com.estudiofenix.cerca.ui.Avatar
import com.estudiofenix.cerca.ui.BigButton
import com.estudiofenix.cerca.ui.CenteredMessage
import com.estudiofenix.cerca.ui.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val spanish = Locale("es", "ES")

/** Mantener pulsado [millis] para activar. Evita SOS y menús abiertos sin querer. */
private fun Modifier.holdToTrigger(
    millis: Int,
    progress: Animatable<Float, AnimationVector1D>,
    scope: kotlinx.coroutines.CoroutineScope,
    onTrigger: () -> Unit,
) = composed {
    // La lambda cambia en cada recomposición (y la animación recompone): no puede ser la clave de pointerInput,
    // o el gesto se reinicia a mitad de pulsación.
    val trigger by rememberUpdatedState(onTrigger)
    pointerInput(Unit) {
    detectTapGestures(onPress = {
        val job = scope.launch {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(millis, easing = LinearEasing))
            trigger()
        }
        try {
            tryAwaitRelease()
        } finally {
            // También si el gesto se cancela: que no se dispare solo
            job.cancel()
            scope.launch { progress.snapTo(0f) }
        }
    })
    }
}

// ====================================================================== INICIO
@Composable
fun HomeScreen(
    cfg: Config,
    paired: Boolean,
    onContact: (Contact) -> Unit,
    onDialer: () -> Unit,
    onApps: () -> Unit,
    onSos: () -> Unit,
    onAdmin: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var now by remember { mutableStateOf(Date()) }
    var battery by remember { mutableStateOf(DeviceChecks.battery(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            battery = DeviceChecks.battery(ctx)
            delay(1000L * (60 - (System.currentTimeMillis() / 1000 % 60)).coerceAtLeast(1L))
        }
    }
    val adminHold = remember { Animatable(0f) }
    val sosHold = remember { Animatable(0f) }
    var showPin by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(horizontal = 16.dp),
    ) {
        // ---- Reloj (mantener pulsado 3 s = menú de familia) ----
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp)
                .holdToTrigger(3000, adminHold, scope) { if (cfg.pinHash == null) onAdmin() else showPin = true },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                SimpleDateFormat("HH:mm", spanish).format(now),
                fontSize = 84.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground,
            )
            if (cfg.ui.showDate) {
                Text(
                    SimpleDateFormat("EEEE, d 'de' MMMM", spanish).format(now).replaceFirstChar { it.titlecase(spanish) },
                    fontSize = 24.sp, color = MaterialTheme.colorScheme.onBackground,
                )
            }
            val (level, charging) = battery
            if (level != null) {
                Text(
                    "Batería ${level}%" + if (charging) " · cargando" else "",
                    fontSize = 20.sp,
                    color = if (level <= 20 && !charging) Palette.red else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    fontWeight = if (level <= 20 && !charging) FontWeight.Bold else FontWeight.Normal,
                )
            }
            if (adminHold.value > 0f) {
                Box(Modifier.fillMaxWidth(adminHold.value).height(4.dp).background(MaterialTheme.colorScheme.primary))
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- Contactos ----
        Box(Modifier.weight(1f)) {
            if (cfg.contacts.isEmpty()) {
                CenteredMessage(
                    if (paired) "Aún no hay contactos" else "Hola",
                    if (paired) "Tu familia los añadirá desde su panel." else "Pide a tu familia que configure este móvil.",
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    items(cfg.contacts, key = { it.id }) { c -> ContactTile(c) { onContact(c) } }
                }
            }
        }

        // ---- Botones fijos ----
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onDialer, modifier = Modifier.weight(1f).height(72.dp), shape = RoundedCornerShape(20.dp)) {
                Text("Teléfono", fontSize = 22.sp, color = MaterialTheme.colorScheme.onBackground)
            }
            OutlinedButton(onClick = onApps, modifier = Modifier.weight(1f).height(72.dp), shape = RoundedCornerShape(20.dp)) {
                Text("Aplicaciones", fontSize = 22.sp, color = MaterialTheme.colorScheme.onBackground, maxLines = 1)
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- SOS: mantener pulsado 2 s ----
        Box(
            Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(24.dp)).background(Palette.red.copy(alpha = 0.85f))
                .holdToTrigger(2000, sosHold, scope) { onSos() },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(sosHold.value).align(Alignment.CenterStart).background(Palette.red))
            Text(
                if (sosHold.value > 0f) "Sigue pulsando…" else "SOS  ·  mantén pulsado",
                fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White,
            )
        }
        Spacer(Modifier.height(12.dp))
    }

    if (showPin) PinDialog(onDismiss = { showPin = false }, onOk = { showPin = false; onAdmin() })
}

@Composable
private fun ContactTile(c: Contact, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(c.photo, c.name, 112.dp)
            Spacer(Modifier.height(8.dp))
            Text(
                c.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// ====================================================================== CONTACTO
@Composable
fun ContactScreen(c: Contact, onBack: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Avatar(c.photo, c.name, 220.dp)
        Spacer(Modifier.height(16.dp))
        Text(c.name, fontSize = 44.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.weight(1f))
        BigButton("📞  Llamar", Palette.green, height = 110.dp) {
            CallManager.placeCall(ctx, c.phone)
            onBack()
        }
        if (c.whatsapp) {
            Spacer(Modifier.height(16.dp))
            BigButton("WhatsApp", Color(0xFF128C7E)) { CallManager.openWhatsApp(ctx, c.phone) }
        }
        Spacer(Modifier.height(16.dp))
        BigButton("Volver", MaterialTheme.colorScheme.surfaceVariant, textColor = MaterialTheme.colorScheme.onSurfaceVariant,
            height = 72.dp, onClick = onBack)
    }
}

// ====================================================================== APLICACIONES
@Composable
fun AppsScreen(allowed: List<String>, showAll: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val apps = remember(allowed, showAll) {
        if (showAll) Apps.launchable(ctx).map { it.pkg to it.label }
        else allowed.mapNotNull { pkg -> Apps.label(ctx, pkg)?.let { pkg to it } }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(16.dp)) {
        Text(if (showAll) "Todas las aplicaciones" else "Mis aplicaciones", fontSize = 32.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 12.dp))
        Box(Modifier.weight(1f)) {
            if (apps.isEmpty()) {
                CenteredMessage("No hay aplicaciones", "Tu familia puede elegir cuáles aparecen aquí.")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(if (showAll) 3 else 2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(apps, key = { it.first }) { (pkg, label) ->
                        val icon = remember(pkg) { Apps.icon(ctx, pkg)?.toBitmap(192, 192)?.asImageBitmap() }
                        Column(
                            Modifier.clip(RoundedCornerShape(20.dp)).clickable { Apps.launch(ctx, pkg) }
                                .background(MaterialTheme.colorScheme.surface).padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (icon != null) Image(icon, label, Modifier.size(if (showAll) 56.dp else 88.dp))
                            Text(label, fontSize = if (showAll) 14.sp else 22.sp, textAlign = TextAlign.Center, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        BigButton("Volver", MaterialTheme.colorScheme.surfaceVariant, textColor = MaterialTheme.colorScheme.onSurfaceVariant,
            height = 72.dp, onClick = onBack)
    }
}

// ====================================================================== MENÚ FAMILIA
@Composable
fun AdminScreen(
    revision: Int,
    onBack: () -> Unit,
    onSetup: () -> Unit,
    onAndroidSettings: () -> Unit,
    onChooseLauncher: () -> Unit,
    onAllApps: () -> Unit,
    onSync: () -> Unit,
) {
    val ctx = LocalContext.current
    val checks = remember(revision) {
        listOf(
            "Emparejado" to Store.isPaired,
            "Pantalla de inicio" to DeviceChecks.isDefaultHome(ctx),
            "App de teléfono" to DeviceChecks.isDefaultDialer(ctx),
            "Ubicación siempre" to (DeviceChecks.locationPermission(ctx) == "always"),
            "Batería sin restricciones" to DeviceChecks.ignoringBatteryOptimizations(ctx),
        )
    }
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Menú de la familia", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            if (Store.isPaired) Text("Móvil de ${Store.seniorName} · ${Store.serverUrl}", fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
        }
        items(checks) { (label, ok) ->
            Text((if (ok) "✅  " else "❌  ") + label, fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground)
        }
        item { AdminButton("Asistente de configuración", onSetup) }
        item { AdminButton("Sincronizar ahora", onSync) }
        item { AdminButton("Todas las aplicaciones", onAllApps) }
        item { AdminButton("Ajustes de Android", onAndroidSettings) }
        item { AdminButton("Cambiar la pantalla de inicio", onChooseLauncher) }
        item {
            Spacer(Modifier.height(8.dp))
            BigButton("Volver", Palette.brand, height = 72.dp, onClick = onBack)
        }
    }
}

@Composable
private fun AdminButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(16.dp)) {
        Text(text, fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun PinDialog(onDismiss: () -> Unit, onOk: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PIN de la familia") },
        text = {
            OutlinedTextField(
                value = pin,
                onValueChange = { v -> pin = v.filter { it.isDigit() }.take(8); error = false },
                singleLine = true,
                isError = error,
                supportingText = { if (error) Text("PIN incorrecto") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
        },
        confirmButton = { TextButton(onClick = { if (Store.checkPin(pin)) onOk() else error = true }) { Text("Entrar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
