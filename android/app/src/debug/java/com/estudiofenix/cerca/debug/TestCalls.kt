package com.estudiofenix.cerca.debug

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.TelecomManager.PRESENTATION_ALLOWED
import android.util.Log
import androidx.core.os.BundleCompat

/*
 * SOLO EN LA VERSIÓN DE PRUEBAS (src/debug): llamadas entrantes falsas para probar sin otro móvil la pantalla
 * de llamada, el filtro y "contestar sola". Llegan a Cerca como una llamada de verdad (sin voz).
 *
 * Desde el PC:
 *   adb shell am broadcast -n com.estudiofenix.cerca/.debug.TestCallReceiver -a com.estudiofenix.cerca.TEST_CALL \
 *       --es number +34600112233 --ei ring 20 --ei talk 60
 *   ring: segundos sonando antes de que "cuelgue quien llama" (llamada perdida); talk: segundos hablando si se contesta.
 *   Con -a com.estudiofenix.cerca.TEST_HANGUP cuelga la llamada de prueba que haya.
 */
private const val TAG = "TestCalls"

private fun handle(ctx: Context) = PhoneAccountHandle(ComponentName(ctx, TestConnectionService::class.java), "prueba")

class TestCallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == "com.estudiofenix.cerca.TEST_HANGUP") {
            TestConnectionService.current?.remoteHangUp()
            return
        }
        val number = intent.getStringExtra("number") ?: "+34600112233"
        val telecom = ctx.getSystemService(TelecomManager::class.java)
        val account = PhoneAccount.builder(handle(ctx), "Llamadas de prueba")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL))
            .build()
        telecom.registerPhoneAccount(account)
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts("tel", number, null))
            putInt("ring", intent.getIntExtra("ring", 20))
            putInt("talk", intent.getIntExtra("talk", 60))
        }
        Log.i(TAG, "Llamada de prueba entrante de $number")
        telecom.addNewIncomingCall(handle(ctx), extras)
    }
}

class TestConnectionService : ConnectionService() {
    companion object {
        var current: TestConnection? = null
    }

    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val extras = request.extras
        val address = BundleCompat.getParcelable(extras, TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri::class.java)
        return TestConnection(extras.getInt("ring", 20), extras.getInt("talk", 60)).apply {
            setAddress(address, PRESENTATION_ALLOWED)
            connectionProperties = Connection.PROPERTY_SELF_MANAGED
            audioModeIsVoip = false
            setRinging()
            current = this
        }
    }

    override fun onCreateIncomingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) {
        Log.w(TAG, "Android no ha dejado crear la llamada de prueba")
    }
}

class TestConnection(ringSeconds: Int, private val talkSeconds: Int) : Connection() {
    private val handler = Handler(Looper.getMainLooper())

    // Si nadie contesta, "quien llama" cuelga: queda como llamada perdida (como en la vida real)
    private val missed = Runnable {
        if (state == STATE_RINGING) end(DisconnectCause(DisconnectCause.MISSED))
    }

    init {
        handler.postDelayed(missed, ringSeconds * 1000L)
    }

    override fun onAnswer() = onAnswer(0)

    override fun onAnswer(videoState: Int) {
        Log.i(TAG, "Contestada")
        handler.removeCallbacks(missed)
        setActive()
        handler.postDelayed({ remoteHangUp() }, talkSeconds * 1000L)
    }

    override fun onReject() = end(DisconnectCause(DisconnectCause.REJECTED))

    override fun onDisconnect() = end(DisconnectCause(DisconnectCause.LOCAL))

    override fun onHold() = setOnHold()

    override fun onUnhold() = setActive()

    override fun onShowIncomingCallUi() {
        // La pantalla la pone Cerca (su InCallService recibe también estas llamadas en la versión de pruebas)
    }

    fun remoteHangUp() {
        if (state == STATE_RINGING) end(DisconnectCause(DisconnectCause.MISSED)) else end(DisconnectCause(DisconnectCause.REMOTE))
    }

    private fun end(cause: DisconnectCause) {
        if (state == STATE_DISCONNECTED) return
        Log.i(TAG, "Terminada: ${cause.code}")
        handler.removeCallbacksAndMessages(null)
        setDisconnected(cause)
        destroy()
        if (TestConnectionService.current == this) TestConnectionService.current = null
    }
}
