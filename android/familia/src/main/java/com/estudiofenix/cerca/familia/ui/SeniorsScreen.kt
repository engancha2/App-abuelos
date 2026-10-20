package com.estudiofenix.cerca.familia.ui

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
import androidx.compose.ui.text.font.FontWeight
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
                title = { Text("Cerca Familia") },
                actions = {
                    IconButton(onClick = { menu = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Cerrar sesión (${Session.email.orEmpty()})") },
                            onClick = { menu = false; scope.launch { FamilyApi.logout() } },
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
                    "Después instala Cerca en su móvil y conéctalo con el código que te daremos.",
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
