package app.netlify.create_app_4a7921.twa

import android.content.Context

/** Small wrapper over SharedPreferences for wake-word + PC connection settings. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("aquadesk", Context.MODE_PRIVATE)

    var wakePhrase: String
        get() = sp.getString("wake", "hey aqua")!!
        set(v) = sp.edit().putString("wake", v.trim().lowercase().ifEmpty { "hey aqua" }).apply()
    var wakeEnabled: Boolean
        get() = sp.getBoolean("wakeOn", false)
        set(v) = sp.edit().putBoolean("wakeOn", v).apply()
    var pcHost: String
        get() = sp.getString("host", "")!!
        set(v) = sp.edit().putString("host", v.trim()).apply()
    var pcPort: Int
        get() = sp.getInt("port", 8765)
        set(v) = sp.edit().putInt("port", v).apply()
    var pcPin: String
        get() = sp.getString("pin", "")!!
        set(v) = sp.edit().putString("pin", v.trim()).apply()
}
