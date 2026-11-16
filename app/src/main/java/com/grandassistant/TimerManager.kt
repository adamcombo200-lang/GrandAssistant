package com.grandassistant

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Простые таймеры обратного отсчёта. Живут, пока работает сервис помощника. */
class TimerManager(private val onFinish: (String) -> Unit) {

    data class T(val label: String, val endAt: Long)

    val timers = mutableListOf<T>()
    var onChange: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val done = timers.filter { it.endAt <= now }
            if (done.isNotEmpty()) {
                timers.removeAll(done.toSet())
                done.forEach { onFinish(it.label) }
            }
            onChange?.invoke()
            if (timers.isNotEmpty()) handler.postDelayed(this, 500)
        }
    }

    fun add(label: String, seconds: Int) {
        timers.add(T(label, SystemClock.elapsedRealtime() + seconds * 1000L))
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun remove(t: T) {
        timers.remove(t)
        onChange?.invoke()
    }

    fun clear() {
        handler.removeCallbacks(tick)
        timers.clear()
    }
}
