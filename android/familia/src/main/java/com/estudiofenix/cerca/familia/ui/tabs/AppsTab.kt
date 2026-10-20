package com.estudiofenix.cerca.familia.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.estudiofenix.cerca.data.AppEntry
import com.estudiofenix.cerca.familia.ui.EmptyState
import com.estudiofenix.cerca.familia.ui.Hint
import com.estudiofenix.cerca.familia.ui.SectionCard
import com.estudiofenix.cerca.familia.ui.SeniorViewModel
import com.estudiofenix.cerca.familia.ui.spanish
import java.text.Normalizer

private const val APPS_HINT = "Por defecto se ven todas en «Aplicaciones». Desmarca las que quieras bloquear: " +
    "desaparecen de su móvil. Las que instale más adelante aparecerán solas."

/** Pestaña «Apps», como tabApps del panel: qué aplicaciones de su móvil se ven en «Aplicaciones». */
@Composable
fun AppsTab(vm: SeniorViewModel) {
    val draft = vm.draft ?: return
    val apps = remember(vm.detail?.apps) { vm.detail?.apps.orEmpty().distinctBy { it.pkg } }
    val blocked = remember(draft.blockedApps) { draft.blockedApps.toSet() }
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current

    if (apps.isEmpty()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Hint(APPS_HINT)
            SectionCard {
                EmptyState("Todavía sin aplicaciones","El móvil aún no ha enviado su lista de aplicaciones. Aparecerá cuando se conecte.")
            }
        }
        return
    }

    // Las guardadas, en su orden: así, si se deshace un cambio a mano, deja de haber "cambios sin guardar"
    fun setBlocked(next: Set<String>) = vm.edit { cfg ->
        val saved = vm.detail?.config?.blockedApps.orEmpty()
        cfg.copy(blockedApps = (saved + cfg.blockedApps + apps.map { it.pkg }).distinct().filter { it in next })
    }

    val visibleCount = apps.count { it.pkg !in blocked }
    val shown = remember(apps, query) {
        val q = fold(query)
        if (q.isEmpty()) apps else apps.filter { fold(it.label).contains(q) || it.pkg.contains(q, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize()) {
        // Arriba, fijos: la lista puede tener más de 60 apps
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { setBlocked(emptySet()) }, enabled = blocked.isNotEmpty(), modifier = Modifier.weight(1f)) {
                    Text("Mostrar todas")
                }
                OutlinedButton(onClick = { setBlocked(apps.map { it.pkg }.toSet()) }, enabled = visibleCount > 0, modifier = Modifier.weight(1f)) {
                    Text("Bloquear todas")
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Buscar por nombre") },
                singleLine = true,
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }, modifier = Modifier.semantics { contentDescription = "Borrar la búsqueda" }) {
                            Text("✕")
                        }
                    }
                } else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "$visibleCount de ${apps.size} visibles",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            if (query.isBlank()) item {
                Hint(APPS_HINT)
                Spacer(Modifier.height(12.dp))
            }
            if (shown.isEmpty()) item { Hint("Ninguna aplicación coincide con «${query.trim()}».") }
            itemsIndexed(shown, key = { _, app -> app.pkg }) { i, app ->
                AppRow(
                    app = app,
                    visible = app.pkg !in blocked,
                    shape = groupShape(i, shown.size),
                    divider = i > 0,
                ) { visible -> setBlocked(if (visible) blocked - app.pkg else blocked + app.pkg) }
            }
        }
    }
}

/** Una app con su casilla "visible"; toda la fila se puede tocar. */
@Composable
private fun AppRow(app: AppEntry, visible: Boolean, shape: Shape, divider: Boolean, onChange: (Boolean) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = shape, tonalElevation = 0.dp, shadowElevation = 0.dp) {
        Column {
            if (divider) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant)
            Row(
                Modifier.fillMaxWidth()
                    .toggleable(visible, role = Role.Checkbox, onValueChange = onChange)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = visible, onCheckedChange = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            app.label, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        if (!visible) BlockedTag()
                    }
                    Text(
                        app.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun BlockedTag() {
    Surface(color = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError, shape = RoundedCornerShape(50)) {
        Text("Bloqueada", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

/** Las filas forman una sola tarjeta: esquinas redondeadas solo arriba del todo y abajo del todo. */
private fun groupShape(index: Int, count: Int): Shape {
    val r = 12.dp
    return when {
        count == 1 -> RoundedCornerShape(r)
        index == 0 -> RoundedCornerShape(topStart = r, topEnd = r)
        index == count - 1 -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
        else -> RectangleShape
    }
}

private val MARKS = Regex("\\p{Mn}+")

/** Para buscar sin tildes ni mayúsculas: "cámara" encuentra "Camara" y al revés. */
private fun fold(s: String): String = Normalizer.normalize(s.trim(), Normalizer.Form.NFD).replace(MARKS, "").lowercase(spanish)
