package com.grandassistant

import android.content.Context
import android.graphics.drawable.GradientDrawable

object Ui {
    val BG = 0xFF14161F.toInt()
    val CARD = 0xFF1F2333.toInt()
    val ACCENT = 0xFF7C5CFF.toInt()
    val TEXT = 0xFFFFFFFF.toInt()
    val MUTED = 0xFFA6AABF.toInt()
    val DANGER = 0xFFE5484D.toInt()
    val STROKE = 0x33FFFFFF

    fun dp(ctx: Context, v: Float): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun bg(color: Int, radiusDp: Float, ctx: Context, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * ctx.resources.displayMetrics.density
            if (stroke != 0) setStroke(dp(ctx, 1f), stroke)
        }
}
