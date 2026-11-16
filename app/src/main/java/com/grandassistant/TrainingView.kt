package com.grandassistant

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.util.Random
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Отдельный тренировочный мини-режим: тапайте по сужающимся мишеням 30 секунд.
 * Работает полностью внутри панели помощника, игру не затрагивает.
 */
class TrainingView(context: Context, private val prefs: Prefs) : View(context) {

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.TEXT
        textSize = 14f * d
        textAlign = Paint.Align.CENTER
    }
    private val rnd = Random()

    private val life = 1100L
    private val total = 30_000L

    private var running = false
    private var endAt = 0L
    private var spawnAt = 0L
    private var cx = 0f
    private var cy = 0f
    private var maxR = 0f
    private var hits = 0
    private var shots = 0
    private var message = "Нажмите, чтобы начать\n30 секунд, мишени сужаются"

    init {
        background = Ui.bg(Ui.CARD, 12f, context)
    }

    private fun now() = SystemClock.uptimeMillis()

    private fun start() {
        running = true
        hits = 0
        shots = 0
        endAt = now() + total
        spawn()
        invalidate()
    }

    private fun spawn() {
        maxR = max(12f * d, min(width, height) * 0.11f)
        val top = 40f * d
        cx = maxR + rnd.nextFloat() * max(1f, width - 2 * maxR)
        cy = top + maxR + rnd.nextFloat() * max(1f, height - top - 2 * maxR)
        spawnAt = now()
    }

    private fun radius(): Float {
        val age = ((now() - spawnAt).toFloat() / life).coerceIn(0f, 1f)
        return maxR * (1f - 0.6f * age)
    }

    private fun finish() {
        running = false
        val acc = if (shots > 0) hits * 100 / shots else 0
        if (hits > prefs.bestHits) prefs.bestHits = hits
        message = "Готово! Попаданий: $hits, точность $acc%\n" +
            "Рекорд: ${prefs.bestHits}\nНажмите, чтобы повторить"
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (running) {
            val t = now()
            if (t >= endAt) {
                finish()
            } else {
                if (t - spawnAt > life) spawn()
                val r = radius()
                fill.color = Ui.DANGER; c.drawCircle(cx, cy, r, fill)
                fill.color = Color.WHITE; c.drawCircle(cx, cy, r * 0.66f, fill)
                fill.color = Ui.DANGER; c.drawCircle(cx, cy, r * 0.33f, fill)
                val left = (endAt - t) / 1000
                c.drawText("Попадания $hits  •  Мимо ${shots - hits}  •  ${left} с", width / 2f, 24f * d, txt)
                postInvalidateOnAnimation()
                return
            }
        }
        val lines = message.split("\n")
        val lh = 22f * d
        var y = height / 2f - (lines.size - 1) * lh / 2f
        for (l in lines) {
            c.drawText(l, width / 2f, y, txt)
            y += lh
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            if (!running) {
                start()
            } else {
                shots++
                if (hypot(e.x - cx, e.y - cy) <= radius() * 1.15f) {
                    hits++
                    spawn()
                }
            }
            invalidate()
        }
        return true
    }
}
