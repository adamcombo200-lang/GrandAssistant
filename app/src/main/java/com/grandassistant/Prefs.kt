package com.grandassistant

import android.content.Context
import org.json.JSONArray

/** Все настройки хранятся локально. Паролей и данных авторизации приложение не видит и не хранит. */
class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("grand_assistant", Context.MODE_PRIVATE)

    var gamePackage: String?
        get() = sp.getString("game_pkg", null)
        set(v) = sp.edit().putString("game_pkg", v).apply()

    var bubbleX: Int
        get() = sp.getInt("bubble_x", 40)
        private set(v) = sp.edit().putInt("bubble_x", v).apply()

    var bubbleY: Int
        get() = sp.getInt("bubble_y", 400)
        private set(v) = sp.edit().putInt("bubble_y", v).apply()

    fun setBubblePos(x: Int, y: Int) {
        sp.edit().putInt("bubble_x", x).putInt("bubble_y", y).apply()
    }

    var bubbleHidden: Boolean
        get() = sp.getBoolean("bubble_hidden", false)
        set(v) = sp.edit().putBoolean("bubble_hidden", v).apply()

    /** Прозрачность 30..100 % */
    var opacityPercent: Int
        get() = sp.getInt("opacity", 95).coerceIn(30, 100)
        set(v) = sp.edit().putInt("opacity", v.coerceIn(30, 100)).apply()

    /** Размер 70..150 % */
    var sizePercent: Int
        get() = sp.getInt("size", 100).coerceIn(70, 150)
        set(v) = sp.edit().putInt("size", v.coerceIn(70, 150)).apply()

    var notes: String
        get() = sp.getString("notes", "") ?: ""
        set(v) = sp.edit().putString("notes", v).apply()

    var commands: List<String>
        get() {
            val s = sp.getString("cmds", null) ?: return listOf("/help", "/time", "/stats")
            return try {
                val a = JSONArray(s)
                List(a.length()) { a.getString(it) }
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(v) = sp.edit().putString("cmds", JSONArray(v).toString()).apply()

    var bestHits: Int
        get() = sp.getInt("best_hits", 0)
        set(v) = sp.edit().putInt("best_hits", v).apply()

    var askedNotifications: Boolean
        get() = sp.getBoolean("asked_notif", false)
        set(v) = sp.edit().putBoolean("asked_notif", v).apply()
}
