package com.estudiofenix.cerca.familia

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.estudiofenix.cerca.familia.data.Billing
import com.estudiofenix.cerca.familia.data.Push
import com.estudiofenix.cerca.familia.data.Session
import com.estudiofenix.cerca.familia.ui.FamiliaTheme
import com.estudiofenix.cerca.familia.ui.LoginScreen
import com.estudiofenix.cerca.familia.ui.SeniorScreen
import com.estudiofenix.cerca.familia.ui.SeniorsScreen

class MainActivity : ComponentActivity() {

    companion object {
        /** Abrir directamente una persona (p. ej. al tocar un aviso). */
        const val EXTRA_SENIOR_ID = "seniorId"
        const val EXTRA_TAB = "tab"
    }

    // Lo que pide un aviso tocado con la app ya abierta
    private val requested = mutableStateOf<Pair<Long, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            FamiliaTheme {
                val loggedIn by Session.loggedIn.collectAsStateWithLifecycle()
                // La persona abierta y su pestaña sobreviven a girar el móvil
                var seniorId by rememberSaveable { mutableStateOf<Long?>(null) }
                var tab by rememberSaveable { mutableStateOf("mapa") }
                val req by requested
                LaunchedEffect(req) {
                    req?.let { (id, t) -> seniorId = id; tab = t; requested.value = null }
                }
                // Compras que no llegaron al servidor (sin conexión al pagar…): se mandan al entrar
                LaunchedEffect(loggedIn) { if (loggedIn) runCatching { Billing.syncPending() } }
                // Avisos con la app cerrada: permiso de notificaciones (Android 13+) y este móvil en el servidor
                val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                LaunchedEffect(loggedIn) {
                    if (!loggedIn) return@LaunchedEffect
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    runCatching { Push.register() }
                }

                when {
                    !loggedIn -> LoginScreen()
                    seniorId == null -> SeniorsScreen(onOpen = { id, t -> seniorId = id; tab = t })
                    else -> SeniorScreen(
                        seniorId = seniorId!!,
                        tab = tab,
                        onTab = { tab = it },
                        onBack = { seniorId = null; tab = "mapa" },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        // Los avisos de Firebase traen los datos como texto ("seniorId" = "12")
        val extras = intent?.extras ?: return
        val id = extras.getString(EXTRA_SENIOR_ID)?.toLongOrNull() ?: extras.getLong(EXTRA_SENIOR_ID, -1L)
        if (id > 0) requested.value = id to (intent.getStringExtra(EXTRA_TAB) ?: "avisos")
    }
}
