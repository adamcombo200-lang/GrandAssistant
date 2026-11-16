package com.grandassistant

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Плавающее окно поверх игры (TYPE_APPLICATION_OVERLAY, разрешение SYSTEM_ALERT_WINDOW).
 * Два реальных окна WindowManager: маленькая кнопка и большая панель.
 */
class OverlayService : Service(), PanelUi.Host {

    companion object {
        const val ACTION_START = "com.grandassistant.action.START"
        const val ACTION_SHOW = "com.grandassistant.action.SHOW"
        const val ACTION_HIDE = "com.grandassistant.action.HIDE"
        const val ACTION_STOP = "com.grandassistant.action.STOP"

        private const val CH_SERVICE = "assistant_overlay"
        private const val CH_TIMER = "assistant_timers"
        private const val NOTIF_ID = 1001

        @Volatile
        var isRunning: Boolean = false
            private set

        fun send(ctx: Context, action: String) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, OverlayService::class.java).setAction(action)
            )
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var themed: Context
    private lateinit var timers: TimerManager
    private val handler = Handler(Looper.getMainLooper())

    private var bubble: FrameLayout? = null
    private var cat: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleAttached = false
    private var catAnim: ObjectAnimator? = null

    private var panel: PanelUi? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelAttached = false
    private var panelAnim: AnimatorSet? = null
    private var closing = false

    private val wiggleRunnable = object : Runnable {
        override fun run() {
            wiggle()
            handler.postDelayed(this, 6000)
        }
    }

    // ---------- lifecycle ----------

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        prefs = Prefs(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        themed = ContextThemeWrapper(this, R.style.Theme_GrandAssistant)
        timers = TimerManager { label -> notifyTimerDone(label) }
        createChannels()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground() // обязательно сразу после startForegroundService

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_HIDE -> hideBubble()
            else -> showBubble() // START, SHOW
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.postDelayed({ sizeChanged() }, 250)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        panelAnim?.cancel()
        removePanel()
        removeBubble()
        timers.clear()
        isRunning = false
        super.onDestroy()
    }

    // ---------- notifications ----------

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Помощник", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_TIMER, "Таймеры", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun action(title: String, act: String, code: Int): NotificationCompat.Action {
        val pi = PendingIntent.getService(
            this, code,
            Intent(this, OverlayService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_notification, title, pi).build()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val toggle = if (prefs.bubbleHidden) action("Показать кнопку", ACTION_SHOW, 1)
        else action("Скрыть кнопку", ACTION_HIDE, 1)
        return NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Grand Assistant")
            .setContentText(if (prefs.bubbleHidden) "Кнопка скрыта" else "Кнопка на экране")
            .setContentIntent(open)
            .addAction(toggle)
            .addAction(action("Выключить", ACTION_STOP, 2))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    @SuppressLint("MissingPermission")
    private fun notifyTimerDone(label: String) {
        vibrate()
        wiggle()
        Toast.makeText(this, "⏰ $label завершён", Toast.LENGTH_LONG).show()
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) {
            val n = NotificationCompat.Builder(this, CH_TIMER)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Таймер")
                .setContentText("$label завершён")
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build()
            NotificationManagerCompat.from(this)
                .notify((System.currentTimeMillis() % 100000).toInt() + 10, n)
        }
    }

    private fun vibrate() {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1))
        } catch (_: Exception) {
        }
    }

    // ---------- geometry ----------

    private fun dp(v: Float) = Ui.dp(this, v)
    private fun scale() = prefs.sizePercent / 100f
    private fun opacity() = prefs.opacityPercent / 100f

    @Suppress("DEPRECATION")
    private fun screen(): Point {
        val p = Point()
        (getSystemService(DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY).getRealSize(p)
        return p
    }

    private fun clamp(p: WindowManager.LayoutParams) {
        val sc = screen()
        p.x = p.x.coerceIn(0, max(0, sc.x - p.width))
        p.y = p.y.coerceIn(0, max(0, sc.y - p.height))
    }

    private fun panelSize(): Pair<Int, Int> {
        val sc = screen()
        val s = scale()
        return Pair(
            min(dp(400f * s), (sc.x * 0.94f).toInt()),
            min(dp(520f * s), (sc.y * 0.92f).toInt())
        )
    }

    private fun overlayFlags() = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    // ---------- bubble ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun buildBubble() {
        val size = dp(56f * scale())
        val frame = FrameLayout(themed)
        frame.background = Ui.bg(0xE61B1E2B.toInt(), 14f, themed, Ui.ACCENT)
        frame.alpha = opacity()

        val tv = TextView(themed).apply {
            text = "🐱"
            textSize = 26f * scale()
            gravity = Gravity.CENTER
        }
        frame.addView(tv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val p = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            overlayFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.bubbleX
            y = prefs.bubbleY
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        clamp(p)

        frame.setOnTouchListener(DragListener())
        bubble = frame
        cat = tv
        bubbleParams = p
    }

    private inner class DragListener : View.OnTouchListener {
        private var sx = 0
        private var sy = 0
        private var tx = 0f
        private var ty = 0f
        private var moved = false
        private val slop = ViewConfiguration.get(this@OverlayService).scaledTouchSlop

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val p = bubbleParams ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = p.x; sy = p.y
                    tx = e.rawX; ty = e.rawY
                    moved = false
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx
                    val dy = e.rawY - ty
                    if (!moved && hypot(dx, dy) > slop) moved = true
                    if (moved) {
                        p.x = sx + dx.toInt()
                        p.y = sy + dy.toInt()
                        clamp(p)
                        updateBubble()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    if (moved) prefs.setBubblePos(p.x, p.y) else togglePanel()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    if (moved) prefs.setBubblePos(p.x, p.y)
                }
            }
            return true
        }
    }

    private fun updateBubble() {
        val b = bubble ?: return
        if (!bubbleAttached) return
        try {
            wm.updateViewLayout(b, bubbleParams)
        } catch (_: Exception) {
        }
    }

    private fun showBubble() {
        prefs.bubbleHidden = false
        if (bubble == null) buildBubble()
        if (!bubbleAttached) {
            try {
                wm.addView(bubble, bubbleParams)
                bubbleAttached = true
            } catch (e: Exception) {
                Toast.makeText(this, "Не удалось показать окно: нет разрешения", Toast.LENGTH_LONG).show()
                stopSelf()
                return
            }
            startCatAnimation()
            bubble?.let {
                it.scaleX = 0f
                it.scaleY = 0f
                it.animate().scaleX(1f).scaleY(1f).setDuration(300)
                    .setInterpolator(OvershootInterpolator(1.6f)).start()
            }
        }
        updateNotification()
    }

    override fun hideBubble() {
        prefs.bubbleHidden = true
        closePanel(false)
        removeBubble()
        updateNotification()
    }

    private fun removeBubble() {
        handler.removeCallbacks(wiggleRunnable)
        catAnim?.cancel()
        catAnim = null
        val b = bubble
        if (b != null && bubbleAttached) {
            try {
                wm.removeViewImmediate(b)
            } catch (_: Exception) {
            }
        }
        bubbleAttached = false
        bubble = null
        cat = null
        bubbleParams = null
    }

    /** Чтобы кнопка была ВЫШЕ панели (и сворачивала её повторным нажатием) */
    private fun reattachBubble() {
        val b = bubble ?: return
        if (!bubbleAttached || !panelAttached) return
        try {
            wm.removeViewImmediate(b)
            wm.addView(b, bubbleParams)
        } catch (_: Exception) {
        }
    }

    // ---------- cat animation ----------

    private fun startCatAnimation() {
        val tv = cat ?: return
        catAnim?.cancel()
        catAnim = ObjectAnimator.ofPropertyValuesHolder(
            tv,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f)
        ).apply {
            duration = 900
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        handler.removeCallbacks(wiggleRunnable)
        handler.postDelayed(wiggleRunnable, 3000)
    }

    private fun wiggle() {
        val tv = cat ?: return
        ObjectAnimator.ofFloat(tv, View.ROTATION, 0f, -14f, 14f, -9f, 9f, 0f)
            .setDuration(800).start()
    }

    // ---------- panel ----------

    private fun togglePanel() {
        if (panelAttached) {
            if (!closing) closePanel(true)
        } else {
            openPanel()
        }
    }

    private fun openPanel() {
        val ui = PanelUi(themed, prefs, timers, this)
        val (w, h) = panelSize()
        val p = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            overlayFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val root = ui.root
        val sc = screen()
        val bp = bubbleParams
        if (bp != null) {
            val left = (sc.x - w) / 2f
            val top = (sc.y - h) / 2f
            root.pivotX = (bp.x + bp.width / 2f - left).coerceIn(0f, w.toFloat())
            root.pivotY = (bp.y + bp.height / 2f - top).coerceIn(0f, h.toFloat())
        }
        root.alpha = 0f
        root.scaleX = 0.3f
        root.scaleY = 0.3f

        try {
            wm.addView(root, p)
        } catch (e: Exception) {
            ui.dispose()
            Toast.makeText(this, "Не удалось открыть меню", Toast.LENGTH_SHORT).show()
            return
        }
        panel = ui
        panelParams = p
        panelAttached = true
        closing = false

        panelAnim?.cancel()
        panelAnim = AnimatorSet().apply {
            val sx = ObjectAnimator.ofFloat(root, View.SCALE_X, 0.3f, 1f)
            val sy = ObjectAnimator.ofFloat(root, View.SCALE_Y, 0.3f, 1f)
            sx.interpolator = OvershootInterpolator(0.9f)
            sy.interpolator = OvershootInterpolator(0.9f)
            val a = ObjectAnimator.ofFloat(root, View.ALPHA, 0f, opacity())
            a.interpolator = LinearInterpolator()
            playTogether(sx, sy, a)
            duration = 280
            start()
        }
        handler.post { reattachBubble() }
    }

    private fun closePanel(animated: Boolean) {
        val ui = panel ?: return
        if (!panelAttached) return
        ui.hideKeyboard()
        if (!animated) {
            panelAnim?.cancel()
            removePanel()
            return
        }
        if (closing) return
        closing = true
        panelAnim?.cancel()
        val root = ui.root
        panelAnim = AnimatorSet().apply {
            val sx = ObjectAnimator.ofFloat(root, View.SCALE_X, root.scaleX, 0.3f)
            val sy = ObjectAnimator.ofFloat(root, View.SCALE_Y, root.scaleY, 0.3f)
            val a = ObjectAnimator.ofFloat(root, View.ALPHA, root.alpha, 0f)
            interpolator = AccelerateInterpolator()
            playTogether(sx, sy, a)
            duration = 200
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    removePanel()
                }
            })
            start()
        }
    }

    private fun removePanel() {
        val ui = panel
        if (panelAttached && ui != null) {
            try {
                wm.removeViewImmediate(ui.root)
            } catch (_: Exception) {
            }
        }
        ui?.dispose()
        panelAttached = false
        closing = false
        panel = null
        panelParams = null
    }

    // ---------- PanelUi.Host ----------

    override fun minimize() = closePanel(true)

    override fun quit() {
        stopSelf()
    }

    override fun opacityChanged() {
        panel?.root?.alpha = opacity()
        bubble?.alpha = opacity()
    }

    override fun sizeChanged() {
        val s = scale()
        bubbleParams?.let {
            it.width = dp(56f * s)
            it.height = it.width
            clamp(it)
        }
        cat?.textSize = 26f * s
        updateBubble()
        val pp = panelParams
        val ui = panel
        if (pp != null && ui != null && panelAttached) {
            val (w, h) = panelSize()
            pp.width = w
            pp.height = h
            try {
                wm.updateViewLayout(ui.root, pp)
            } catch (_: Exception) {
            }
        }
    }

    override fun resetPosition() {
        bubbleParams?.let {
            it.x = dp(16f)
            it.y = dp(160f)
            clamp(it)
            prefs.setBubblePos(it.x, it.y)
        }
        updateBubble()
    }

    override fun setTyping(enabled: Boolean) {
        val p = panelParams ?: return
        val ui = panel ?: return
        p.flags = if (enabled) {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (panelAttached) {
            try {
                wm.updateViewLayout(ui.root, p)
            } catch (_: Exception) {
            }
        }
    }
}
