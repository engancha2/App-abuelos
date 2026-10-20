package com.estudiofenix.cerca.calls

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estudiofenix.cerca.data.PhoneBook
import com.estudiofenix.cerca.data.Store
import com.estudiofenix.cerca.ui.BigButton
import com.estudiofenix.cerca.ui.cardBorder
import com.estudiofenix.cerca.ui.CercaIcons
import com.estudiofenix.cerca.ui.cercaSystemBars
import com.estudiofenix.cerca.ui.CercaTheme
import com.estudiofenix.cerca.ui.FitText
import com.estudiofenix.cerca.ui.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Teclado para marcar un número. También abre los enlaces "tel:" de otras apps. */
class DialerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = intent?.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart.orEmpty()
        setContent {
            val cfg by Store.config.collectAsStateWithLifecycle()
            LaunchedEffect(cfg.ui.highContrast) { cercaSystemBars(cfg.ui.highContrast) }
            CercaTheme(highContrast = cfg.ui.highContrast, fontScale = cfg.ui.fontScale) {
                // key(initial): tras recreate() por un nuevo "tel:", no restaurar el número anterior
                key(initial) {
                    Dialer(initial, onClose = ::finish) { number ->
                        if (CallManager.placeCall(this, number)) finish()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }
}

@Composable
private fun Dialer(initial: String, onClose: () -> Unit, onCall: (String) -> Unit) {
    val ctx = LocalContext.current
    var number by rememberSaveable { mutableStateOf(initial) }
    val contact = Store.config.value.contactForNumber(number)
    // Si no es de la familia pero está en la agenda del móvil, también se ve a quién se va a llamar
    val bookName by produceState<String?>(null, number, contact) {
        value = null
        if (contact == null && number.count { it.isDigit() } >= 6) {
            delay(300) // mientras se teclea, no buscar a cada cifra
            value = withContext(Dispatchers.IO) { PhoneBook.lookup(ctx, number)?.name }
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Con espacios (600 11 22 33) se comprueba mejor; con letra grande, se encoge para caber en una línea
        FitText(
            if (number.isEmpty()) "Marca el número" else CallManager.pretty(number),
            fontSize = if (number.isEmpty()) 28.sp else 40.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = if (number.isEmpty()) 2 else 1,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        )
        Text(contact?.name ?: bookName ?: " ", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center, maxLines = 2)
        Spacer(Modifier.weight(1f))

        val feedback = rememberKeyFeedback()
        val rows = listOf("123", "456", "789", "*0#")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { c ->
                        DialKey(Modifier.weight(1f), onClick = { feedback.press(c); if (number.length < 20) number += c }) {
                            Text(c.toString(), fontSize = 36.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            // "Volver" y "Borrar" son teclas como las cifras (antes eran texto pequeño junto al botón enorme de llamar),
            // cada una de su color para no confundirlas con las cifras ni con "Llamar" (verde)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DialKey(Modifier.weight(1f), color = Palette.back, onClick = { feedback.press(); onClose() }) {
                    KeyLabel(CercaIcons.Back, "Volver")
                }
                DialKey(
                    Modifier.weight(1f),
                    color = DELETE_COLOR,
                    dimmed = number.isEmpty(), // sin número se ve apagada, pero responde (vibra y suena) igual
                    onLongClick = { feedback.press(); number = "" }, // mantener pulsado borra todo
                    onClick = { feedback.press(); number = number.dropLast(1) },
                ) {
                    KeyLabel(CercaIcons.Backspace, "Borrar")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        BigButton("Llamar", Palette.green, height = 96.dp, icon = CercaIcons.Call) { if (number.isNotBlank()) onCall(number) }
    }
}

// "Volver" usa el gris de Volver de toda la app (Palette.back); "Borrar", naranja. Con letra blanca, 5:1
private val DELETE_COLOR = Color(0xFFB45309)  // naranja oscuro

/** Icono y texto de las teclas "Volver" y "Borrar" (antes "←" y "⌫", que no se entendían bien). */
@Composable
private fun KeyLabel(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(10.dp))
        FitText(text, 26.sp, Modifier.weight(1f, fill = false), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/**
 * Tecla del teclado de marcar. Todas del mismo tamaño (cifras, "Volver", "Borrar"): grandes y fáciles de acertar.
 * Sin [color], la de las cifras; con color, la letra va en blanco.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialKey(
    modifier: Modifier,
    color: Color? = null,
    dimmed: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val text = if (color != null) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = (color ?: MaterialTheme.colorScheme.surfaceVariant).copy(alpha = if (dimmed) 0.45f else 1f),
        contentColor = text.copy(alpha = if (dimmed) 0.6f else 1f),
        border = cardBorder(),
    ) {
        Box(
            // Altura mínima, no fija: con letra grande la cifra se cortaba
            Modifier.fillMaxWidth().heightIn(min = 80.dp)
                .combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}
