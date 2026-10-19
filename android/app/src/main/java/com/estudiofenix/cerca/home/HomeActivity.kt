package com.estudiofenix.cerca.home

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.estudiofenix.cerca.calls.DialerActivity
import com.estudiofenix.cerca.data.Store
import com.estudiofenix.cerca.data.Sync
import com.estudiofenix.cerca.location.CercaService
import com.estudiofenix.cerca.setup.SetupActivity
import com.estudiofenix.cerca.ui.CercaTheme
import kotlinx.coroutines.launch

/** La pantalla de inicio. Es lo que la persona mayor ve siempre al pulsar el botón de inicio. */
class HomeActivity : ComponentActivity() {

    companion object {
        private var setupAutoOpened = false
        private var lastSyncAt = 0L
    }

    private sealed interface Screen {
        data object Home : Screen
        data class Contact(val id: String) : Screen
        data object Apps : Screen
        data object Admin : Screen
        data object AllApps : Screen
    }

    private val screen = mutableStateOf<Screen>(Screen.Home)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val cfg by Store.config.collectAsStateWithLifecycle()
            val revision by Store.revision.collectAsStateWithLifecycle()
            val paired = remember(revision) { Store.isPaired }
            val current by screen

            // Atrás nunca saca del launcher; en subpantallas vuelve al inicio
            BackHandler { screen.value = if (current == Screen.AllApps) Screen.Admin else Screen.Home }

            CercaTheme(highContrast = cfg.ui.highContrast, fontScale = cfg.ui.fontScale) {
                when (val s = current) {
                    is Screen.Contact -> {
                        val contact = cfg.contacts.firstOrNull { it.id == s.id }
                        LaunchedEffect(contact) { if (contact == null) screen.value = Screen.Home }
                        if (contact != null) ContactScreen(contact, onBack = { screen.value = Screen.Home })
                    }
                    Screen.Apps -> AppsScreen(cfg.apps, showAll = false, onBack = { screen.value = Screen.Home })
                    Screen.AllApps -> AppsScreen(emptyList(), showAll = true, onBack = { screen.value = Screen.Admin })
                    Screen.Admin -> AdminScreen(
                        revision = revision,
                        onBack = { screen.value = Screen.Home },
                        onSetup = { startActivity(Intent(this, SetupActivity::class.java)) },
                        onAndroidSettings = { startActivity(Intent(Settings.ACTION_SETTINGS)) },
                        onChooseLauncher = { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) },
                        onAllApps = { screen.value = Screen.AllApps },
                        onSync = { lifecycleScope.launch { Sync.syncNow(applicationContext, forceConfig = true) } },
                    )
                    Screen.Home -> HomeScreen(
                        cfg = cfg,
                        paired = paired,
                        onContact = { screen.value = Screen.Contact(it.id) },
                        onDialer = { startActivity(Intent(this, DialerActivity::class.java)) },
                        onApps = { screen.value = Screen.Apps },
                        onSos = { Sos.trigger(this) },
                        onAdmin = { screen.value = Screen.Admin },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pulsar "inicio" estando ya en el launcher: volver a la pantalla principal
        screen.value = Screen.Home
    }

    override fun onStop() {
        super.onStop()
        // Al salir a otra app, el menú de familia no se queda abierto
        if (screen.value == Screen.Admin || screen.value == Screen.AllApps) screen.value = Screen.Home
    }

    override fun onResume() {
        super.onResume()
        Store.bump()
        CercaService.ensure(applicationContext)
        if (!Store.isPaired && !setupAutoOpened) {
            setupAutoOpened = true
            startActivity(Intent(this, SetupActivity::class.java))
            return
        }
        val now = System.currentTimeMillis()
        if (Store.isPaired && now - lastSyncAt > 2 * 60_000) {
            lastSyncAt = now
            lifecycleScope.launch { Sync.syncNow(applicationContext) }
        }
    }
}
