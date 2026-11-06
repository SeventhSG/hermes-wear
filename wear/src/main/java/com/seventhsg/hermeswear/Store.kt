package com.seventhsg.hermeswear

import android.content.Context

/** Server address and key, kept in app-private storage on the watch. */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("hermes_wear", Context.MODE_PRIVATE)

    var url: String
        get() = prefs.getString("url", "") ?: ""
        set(v) = prefs.edit().putString("url", v.trim().trimEnd('/')).apply()

    var key: String
        get() = prefs.getString("key", "") ?: ""
        set(v) = prefs.edit().putString("key", v.trim()).apply()

    val configured: Boolean get() = url.startsWith("http://") || url.startsWith("https://")
}
