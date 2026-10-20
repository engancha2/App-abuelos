package com.estudiofenix.cerca.familia.data

import android.content.Context
import android.content.SharedPreferences
import com.estudiofenix.cerca.familia.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Con qué cuenta y en qué servidor ha entrado este móvil. El token se manda como "Authorization: Bearer". */
object Session {
    private lateinit var prefs: SharedPreferences

    private val _loggedIn = MutableStateFlow(false)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("session", Context.MODE_PRIVATE)
        _loggedIn.value = token != null
    }

    var serverUrl: String
        get() = prefs.getString("serverUrl", null) ?: BuildConfig.SERVER_URL
        set(v) = prefs.edit().putString("serverUrl", v.trim().trimEnd('/')).apply()

    val token: String? get() = prefs.getString("token", null)
    val name: String? get() = prefs.getString("name", null)
    val email: String? get() = prefs.getString("email", null)

    fun signIn(token: String, user: User) {
        prefs.edit().putString("token", token).putString("name", user.name).putString("email", user.email).apply()
        _loggedIn.value = true
    }

    fun signOut() {
        prefs.edit().remove("token").remove("name").remove("email").apply()
        _loggedIn.value = false
    }
}
