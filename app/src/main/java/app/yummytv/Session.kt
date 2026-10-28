package app.yummytv

import android.content.Context
import android.content.SharedPreferences

object Session {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("yummy", Context.MODE_PRIVATE)
    }

    var token: String?
        get() = sp.getString("token", null)
        set(v) { sp.edit().putString("token", v).apply() }

    var userId: Long
        get() = sp.getLong("userId", 0L)
        set(v) { sp.edit().putLong("userId", v).apply() }

    var nickname: String?
        get() = sp.getString("nick", null)
        set(v) { sp.edit().putString("nick", v).apply() }

    var tokenTime: Long
        get() = sp.getLong("tokenTime", 0L)
        set(v) { sp.edit().putLong("tokenTime", v).apply() }

    var guest: Boolean
        get() = sp.getBoolean("guest", false)
        set(v) { sp.edit().putBoolean("guest", v).apply() }

    fun clear() {
        sp.edit().clear().apply()
    }
}
