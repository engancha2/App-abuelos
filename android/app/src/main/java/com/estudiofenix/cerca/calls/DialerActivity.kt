package com.estudiofenix.cerca.calls

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estudiofenix.cerca.data.Store
import com.estudiofenix.cerca.ui.BigButton
import com.estudiofenix.cerca.ui.CercaTheme
import com.estudiofenix.cerca.ui.Palette

/** Teclado para marcar un número. También abre los enlaces "tel:" de otras apps. */
class DialerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = intent?.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart.orEmpty()
        setContent {
            val cfg by Store.config.collectAsStateWithLifecycle()
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
    var number by rememberSaveable { mutableStateOf(initial) }
    val contact = Store.config.value.contactForNumber(number)

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            number.ifEmpty { "Marca el número" },
            fontSize = if (number.isEmpty()) 28.sp else 40.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        )
        Text(contact?.name ?: " ", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.weight(1f))

        val rows = listOf("123", "456", "789", "*0#")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { c ->
                        Button(
                            onClick = { if (number.length < 20) number += c },
                            modifier = Modifier.weight(1f).height(80.dp),
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) { Text(c.toString(), fontSize = 36.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onClose) { Text("Volver", fontSize = 24.sp) }
            TextButton(onClick = { number = number.dropLast(1) }, enabled = number.isNotEmpty()) { Text("⌫ Borrar", fontSize = 24.sp) }
        }
        Spacer(Modifier.height(8.dp))
        BigButton("📞  Llamar", Palette.green, height = 96.dp) { if (number.isNotBlank()) onCall(number) }
    }
}
