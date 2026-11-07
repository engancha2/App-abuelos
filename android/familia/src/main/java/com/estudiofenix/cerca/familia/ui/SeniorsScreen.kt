package com.estudiofenix.cerca.familia.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.estudiofenix.cerca.familia.data.FamilyApi
import com.estudiofenix.cerca.familia.data.SeniorSummary
import com.estudiofenix.cerca.familia.data.Session
import com.estudiofenix.cerca.familia.data.friendlyError
import kotlinx.coroutines.launch

/** Inicio: las personas mayores que cuida esta cuenta. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeniorsScreen(onOpen: (id: Long, tab: String) -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var seniors by remember { mutableStateOf<List<SeniorSummary>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    suspend fun load() {
        loading = true
        try {
            seniors = FamilyApi.seniors()
            error = null
        } catch (e: Exception) {
            error = friendlyError(e)
        } finally {
            loading = false
        }
    }
    // Al volver a la app (o de la ficha de alguien) se actualiza: batería, avisos, conexión…
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { load() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Find My Grandpa") },
                actions = {
                    IconButton(onClick = { menu = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Cerrar sesión (${Session.email.orEmpty()})") },
                            // Con el token, este móvil deja de recibir los avisos de esta cuenta
                            onClick = { menu = false; scope.launch { FamilyApi.logout(Session.fcmToken) } },
                        )
                        DropdownMenuItem(
                            text = { Text("Privacidad") },
                            onClick = { menu = false; openUrl(context, FamilyApi.privacyUrl()) },
                        )
                        DropdownMenuItem(
                            text = { Text("Borrar mi cuenta", color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; deleting = true },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }) { Text("＋  Añadir familiar") }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = loading && seniors != null,
            onRefresh = { scope.launch { load() } },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val list = seniors
            when {
                list == null && error != null -> EmptyState("No se ha podido cargar", error, "Reintentar") { scope.launch { load() } }
                list == null -> Loading()
                list.isEmpty() -> EmptyState(
                    "👵👴  Añade a tu familiar",
                    "Después instala Grandpa en su móvil y conéctalo con el código que te daremos.",
                    "Añadir familiar",
                ) { adding = true }
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(list, key = { it.id }) { s -> SeniorCard(s) { onOpen(s.id, if (s.paired) "mapa" else "ajustes") } }
                    if (error != null) item { Hint(error!!) }
                }
            }
        }
    }

    if (deleting) {
        DeleteAccountDialog(
            onDismiss = { deleting = false },
            // Al borrarla se cierra la sesión y la app vuelve sola a la pantalla de entrar
            onDeleted = { mustCancel ->
                deleting = false
                if (mustCancel) openPlaySubscriptions(context)
            },
        )
    }

    if (adding) {
        AddSeniorDialog(
            onDismiss = { adding = false },
            onAdd = { name ->
                scope.launch {
                    try {
                        val id = FamilyApi.createSenior(name)
                        adding = false
                        onOpen(id, "ajustes") // lo primero: generar el código para su móvil
                    } catch (e: Exception) {
                        snackbar.showSnackbar(friendlyError(e))
                    }
                }
            },
        )
    }
}

@Composable
private fun SeniorCard(s: SeniorSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(s.online)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val status = s.status
                Text(
                    when {
                        !s.paired -> "Móvil sin conectar"
                        s.online -> "Conectado" + (status?.battery?.let { " · 🔋 $it %" + if (status.charging) " ⚡" else "" } ?: "")
                        else -> "Visto ${ago(s.lastSeen)}" + (status?.battery?.let { " · 🔋 $it %" } ?: "")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if ((status?.battery ?: 100) <= 20 && status?.charging != true) FamiliaColors.danger
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (s.pendingAlerts > 0) Badge { Text("${s.pendingAlerts}") }
        }
    }
}

/**
 * Borrar la cuenta (Google Play lo exige dentro de la app). Pide la contraseña y explica qué se borra.
 * [onDeleted] recibe true si pagaba una suscripción: hay que cancelarla en Google Play, no se cancela sola.
 */
@Composable
private fun DeleteAccountDialog(onDismiss: () -> Unit, onDeleted: (mustCancelSubscription: Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Borrar mi cuenta") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Se borra tu cuenta y, de las personas que creaste, las que no tengan más familia en la app, con todos sus datos " +
                    "(su móvil se desconecta). Las que sí tengan, pasan a esa familia. No se puede deshacer.")
                Text("Si pagas una suscripción, cancélala en Google Play: borrar la cuenta no la cancela.", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    password, { password = it; error = null }, label = { Text("Tu contraseña") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = error != null, supportingText = { error?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotEmpty() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            onDeleted(FamilyApi.deleteAccount(password))
                        } catch (e: Exception) {
                            error = friendlyError(e)
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text("Borrar para siempre", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Abre una página en el navegador. */
fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Composable
private fun AddSeniorDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Añadir familiar") },
        text = {
            OutlinedTextField(name, { name = it }, label = { Text("Nombre (p. ej. Abuela Rosa)") }, singleLine = true)
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onAdd(name) }) { Text("Añadir") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
