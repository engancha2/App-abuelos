package com.estudiofenix.cerca.familia.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val spanish = Locale("es", "ES")

/** "hace 5 min", "hace 2 h"… igual que el panel web. */
fun ago(ts: Long?): String {
    if (ts == null || ts <= 0) return "nunca"
    val s = (System.currentTimeMillis() - ts) / 1000
    return when {
        s < 60 -> "hace un momento"
        s < 3600 -> "hace ${s / 60} min"
        s < 86400 -> "hace ${s / 3600} h"
        else -> SimpleDateFormat("d MMM, HH:mm", spanish).format(Date(ts))
    }
}

/** "lun 20 oct, 21:30" */
fun timeFmt(ts: Long): String = SimpleDateFormat("EEE d MMM, HH:mm", spanish).format(Date(ts))

/** Punto verde (conectado ahora) o gris. */
@Composable
fun StatusDot(on: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(CircleShape).background(if (on) FamiliaColors.ok else MaterialTheme.colorScheme.outline))
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground, modifier = modifier.padding(top = 20.dp, bottom = 8.dp))
}

/** Texto de ayuda, más pequeño y suave. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** Tarjeta blanca de contenido. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** Mensaje centrado para listas vacías o errores, con un botón opcional. */
@Composable
fun EmptyState(title: String, body: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground)
        if (body != null) Text(body, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null && onAction != null) OutlinedButton(onClick = onAction) { Text(action) }
    }
}

/** Fila de texto con valor a la derecha, p. ej. "Batería · 80 %". */
@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}
