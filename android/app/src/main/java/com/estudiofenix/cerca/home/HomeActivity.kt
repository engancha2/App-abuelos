package com.estudiofenix.cerca.home

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.estudiofenix.cerca.calls.CallManager
import com.estudiofenix.cerca.calls.DialerActivity
import com.estudiofenix.cerca.calls.InCallActivity
import com.estudiofenix.cerca.data.PhoneBook
import com.estudiofenix.cerca.data.Store
import com.estudiofenix.cerca.data.Sync
import com.estudiofenix.cerca.location.CercaService
import com.estudiofenix.cerca.setup.DeviceChecks
import com.estudiofenix.cerca.setup.SetupActivity
import com.estudiofenix.cerca.ui.CercaTheme
import com.estudiofenix.cerca.ui.cercaSystemBars
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
        data object Contacts : Screen
        data class PhoneBookContact(val entry: PhoneBook.Entry) : Screen
        data object Apps : Screen
        data object Admin : Screen
        data object AllApps : Screen
    }

    private val screen = mutableStateOf<Screen>(Screen.Home)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cercaSystemBars(Store.config.value.ui.highContrast)
        setContent {
            val cfg by Store.config.collectAsStateWithLifecycle()
            val revision by Store.revision.collectAsStateWithLifecycle()
            val calls by CallManager.state.collectAsStateWithLifecycle()
            val paired = remember(revision) { Store.isPaired }
            val current by screen
            // La agenda empieza de cero al entrar desde el inicio y se conserva al abrir una ficha y volver
            var phoneBook by remember { mutableStateOf(PhoneBookState()) }
            LaunchedEffect(cfg.ui.highContrast) { cercaSystemBars(cfg.ui.highContrast) }

            // Atrás nunca saca del launcher; en subpantallas vuelve al inicio
            BackHandler {
                screen.value = when (current) {
                    Screen.AllApps -> Screen.Admin
                    is Screen.PhoneBookContact -> Screen.Contacts
                    else -> Screen.Home
                }
            }

            CercaTheme(highContrast = cfg.ui.highContrast, fontScale = cfg.ui.fontScale) {
                when (val s = current) {
                    is Screen.Contact -> {
                        val contact = cfg.contacts.firstOrNull { it.id == s.id }
                        LaunchedEffect(contact) { if (contact == null) screen.value = Screen.Home }
                        if (contact != null) ContactScreen(contact, showSms = cfg.ui.showSms, onBack = { screen.value = Screen.Home })
                    }
                    Screen.Contacts -> ContactsScreen(
                        cfg = cfg,
                        state = phoneBook,
                        onEntry = { screen.value = Screen.PhoneBookContact(it) },
                        onBack = { screen.value = Screen.Home },
                    )
                    is Screen.PhoneBookContact -> PhoneBookContactScreen(
                        s.entry,
                        cfg = cfg,
                        onCalled = { screen.value = Screen.Home },
                        onBack = { screen.value = Screen.Contacts },
                    )
                    Screen.Apps -> AppsScreen(
                        cfg.blockedApps, showAll = false, onBack = { screen.value = Screen.Home },
                        onMenu = if (cfg.ui.showMenuButton) ({ screen.value = Screen.Admin }) else null,
                        menuNeedsPin = cfg.pinHash != null,
                    )
                    Screen.AllApps -> AppsScreen(emptyList(), showAll = true, onBack = { screen.value = Screen.Admin })
                    Screen.Admin -> AdminScreen(
                        revision = revision,
                        onBack = { screen.value = Screen.Home },
                        onSetup = { startActivity(Intent(this, SetupActivity::class.java)) },
                        // startFirst: si el sistema no tiene esa pantalla, no se cierra la app
                        onAndroidSettings = { DeviceChecks.startFirst(this, listOf(Intent(Settings.ACTION_SETTINGS))) },
                        onChooseLauncher = {
                            DeviceChecks.startFirst(this, listOf(Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_SETTINGS)))
                        },
                        onAllApps = { screen.value = Screen.AllApps },
                        onSync = { lifecycleScope.launch { Sync.syncNow(applicationContext, forceConfig = true) } },
                    )
                    Screen.Home -> HomeScreen(
                        cfg = cfg,
                        paired = paired,
                        inCall = calls.primary != null,
                        onReturnToCall = { startActivity(InCallActivity.intent(this)) },
                        onContact = { screen.value = Screen.Contact(it.id) },
                        onContacts = {
                            phoneBook = PhoneBookState()
                            screen.value = Screen.Contacts
                        },
                        onDialer = { startActivity(Intent(this, DialerActivity::class.java)) },
                        onMessages = { CallManager.openSms(this) },
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
