package com.estudiofenix.cerca.familia.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.estudiofenix.cerca.familia.data.FamilyApi
import com.estudiofenix.cerca.familia.data.Session
import com.estudiofenix.cerca.familia.data.friendlyError
import kotlinx.coroutines.launch

/** Entrar o crear la cuenta de la familia (la misma que en el panel web). */
@Composable
fun LoginScreen() {
    val scope = rememberCoroutineScope()
    var register by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf(Session.serverUrl) }
    var showServer by rememberSaveable { mutableStateOf(false) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    val valid = email.contains('@') && password.length >= (if (register) 8 else 1) && server.startsWith("http")

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Cerca Familia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 32.dp))
        Text(
            if (register) "Crea tu cuenta para cuidar del móvil de tu familiar." else "Entra con tu cuenta de Cerca.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (register) {
            OutlinedTextField(name, { name = it }, label = { Text("Tu nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(
            email, { email = it.trim() }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            password, { password = it }, label = { Text("Contraseña") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            supportingText = { if (register) Text("Mínimo 8 caracteres") },
        )
        if (showServer) {
            OutlinedTextField(
                server, { server = it.trim() }, label = { Text("Servidor") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }

        Button(
            enabled = valid && !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            onClick = {
                busy = true; error = null
                Session.serverUrl = server
                scope.launch {
                    try {
                        if (register) FamilyApi.register(email, name.ifBlank { email.substringBefore('@') }, password)
                        else FamilyApi.login(email, password)
                    } catch (e: Exception) {
                        error = friendlyError(e)
                    } finally {
                        busy = false
                    }
                }
            },
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(if (register) "Crear cuenta" else "Entrar")
        }
        TextButton(onClick = { register = !register; error = null }) {
            Text(if (register) "Ya tengo cuenta" else "Crear una cuenta")
        }
        TextButton(onClick = { showServer = !showServer }) {
            Text(if (showServer) "Ocultar servidor" else "Cambiar servidor", style = MaterialTheme.typography.bodySmall)
        }
    }
}
