package com.estudiofenix.cerca.familia.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.estudiofenix.cerca.familia.ui.tabs.AlertsTab
import com.estudiofenix.cerca.familia.ui.tabs.AppsTab
import com.estudiofenix.cerca.familia.ui.tabs.CallsTab
import com.estudiofenix.cerca.familia.ui.tabs.ContactsTab
import com.estudiofenix.cerca.familia.ui.tabs.LocationTab
import com.estudiofenix.cerca.familia.ui.tabs.MapTab
import com.estudiofenix.cerca.familia.ui.tabs.SettingsTab

/** Las pestañas, en el mismo orden que en el panel web. */
private val TABS = listOf(
    "mapa" to "Mapa", "avisos" to "Avisos", "contactos" to "Contactos", "llamadas" to "Llamadas",
    "ubicacion" to "Ubicación", "apps" to "Apps", "ajustes" to "Ajustes",
)

/** La ficha de una persona mayor: pestañas arriba y, si hay cambios, la barra de "Guardar" abajo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeniorScreen(seniorId: Long, tab: String, onTab: (String) -> Unit, onBack: () -> Unit) {
    val vm: SeniorViewModel = viewModel(key = "senior-$seniorId") { SeniorViewModel(seniorId) }
    val snackbar = remember { SnackbarHostState() }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val leave = { if (vm.dirty) confirmLeave = true else onBack() }
    BackHandler(onBack = leave)

    val detail = vm.detail
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(detail?.name ?: "") },
                    navigationIcon = { IconButton(onClick = leave) { Text("←", style = MaterialTheme.typography.titleLarge) } },
                    actions = { IconButton(onClick = { vm.refresh() }) { Text("⟳", style = MaterialTheme.typography.titleLarge) } },
                )
                val selected = TABS.indexOfFirst { it.first == tab }.coerceAtLeast(0)
                ScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp) {
                    TABS.forEachIndexed { i, (key, label) ->
                        Tab(selected = i == selected, onClick = { onTab(key) }, text = {
                            val pending = detail?.pendingAlerts ?: 0
                            if (key == "avisos" && pending > 0) BadgedBox(badge = { Badge { Text("$pending") } }) { Text(label) }
                            else Text(label)
                        })
                    }
                }
            }
        },
        bottomBar = { if (vm.dirty) SaveBar(vm) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                detail == null && vm.loadError != null -> EmptyState("No se ha podido cargar", vm.loadError, "Reintentar") { vm.refresh() }
                detail == null || vm.draft == null -> Loading()
                else -> when (tab) {
                    "avisos" -> AlertsTab(vm)
                    "contactos" -> ContactsTab(vm)
                    "llamadas" -> CallsTab(vm)
                    "ubicacion" -> LocationTab(vm)
                    "apps" -> AppsTab(vm)
                    "ajustes" -> SettingsTab(vm, onDeleted = onBack)
                    else -> MapTab(vm)
                }
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Tienes cambios sin guardar") },
            text = { Text("Si sales ahora, no llegarán a su móvil.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; vm.save() }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false; vm.discard(); onBack() }) { Text("Salir sin guardar") } },
        )
    }
}

@Composable
private fun SaveBar(vm: SeniorViewModel) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Cambios sin guardar", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { vm.discard() }, enabled = !vm.saving) { Text("Descartar") }
            Button(onClick = { vm.save() }, enabled = !vm.saving) {
                if (vm.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Guardar y enviar")
            }
        }
    }
}
