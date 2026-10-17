package com.example.mystore

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/** In-memory lock state; reset whenever the store leaves the screen. */
object AppState {
    @Volatile
    var unlocked = false
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("mystore_prefs", Context.MODE_PRIVATE)

    /** 0 = automatic (follow system), 1 = light, 2 = dark */
    var displayMode: Int
        get() = sp.getInt("display", 0)
        set(v) = sp.edit().putInt("display", v).apply()

    var themeIndex: Int
        get() = sp.getInt("theme", 0).coerceIn(0, Themes.accents.size - 1)
        set(v) = sp.edit().putInt("theme", v).apply()

    var askedNotifications: Boolean
        get() = sp.getBoolean("asked_notif", false)
        set(v) = sp.edit().putBoolean("asked_notif", v).apply()

    val lockEnabled: Boolean
        get() = sp.getString("pin_hash", null) != null

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        sp.edit().putString("pin_salt", salt).putString("pin_hash", hash(salt, pin)).apply()
    }

    fun checkPin(pin: String): Boolean {
        val salt = sp.getString("pin_salt", null) ?: return false
        val stored = sp.getString("pin_hash", null) ?: return false
        return hash(salt, pin) == stored
    }

    fun clearPin() {
        sp.edit().remove("pin_salt").remove("pin_hash").apply()
    }

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((salt + pin).toByteArray())
            .joinToString("") { "%02x".format(it) }
}
