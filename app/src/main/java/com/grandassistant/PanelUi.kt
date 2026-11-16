package com.grandassistant

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged

/** Большое меню помощника, которое открывается из плавающей кнопки. */
class PanelUi(
    private val ctx: Context,
    private val prefs: Prefs,
    private val timers: TimerManager,
    private val host: Host
) {
    interface Host {
        fun minimize()
        fun hideBubble()
        fun quit()
        fun opacityChanged()
        fun sizeChanged()
        fun resetPosition()
        /** true — окно временно становится «фокусируемым», чтобы работала клавиатура */
        fun setTyping(enabled: Boolean)
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    val root = LinearLayout(ctx)
    private val content = FrameLayout(ctx)
    private val pages = ArrayList<View>()
    private val tabButtons = ArrayList<TextView>()
    private val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private var typing = false

    private var timerList: LinearLayout? = null
    private var shownTimers: List<TimerManager.T> = emptyList()
    private val timerTexts = ArrayList<TextView>()

    init {
        build()
    }

    // ---------- helpers ----------

    private fun dp(v: Int) = Ui.dp(ctx, v.toFloat())

    private fun lp(
        w: Int, h: Int, weight: Float = 0f,
        l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0
    ) = LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    private fun vbox() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private fun hbox() = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun chip(
        text: String,
        bg: Int = Ui.CARD,
        color: Int = Ui.TEXT,
        onClick: () -> Unit
    ): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(color)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = Ui.bg(bg, 10f, ctx)
        setOnClickListener { onClick() }
    }

    private fun label(text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(Ui.MUTED)
        textSize = 13f
    }

    private fun input(hint: String, numeric: Boolean = false, multiline: Boolean = false) =
        EditText(ctx).apply {
            this.hint = hint
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.MUTED)
            textSize = 14f
            background = Ui.bg(Ui.CARD, 12f, ctx)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            inputType = when {
                numeric -> InputType.TYPE_CLASS_NUMBER
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (multiline) gravity = Gravity.TOP or Gravity.START else isSingleLine = true
            enableTyping(this)
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableTyping(e: EditText) {
        e.setOnTouchListener { v, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && !typing) {
                typing = true
                host.setTyping(true)
                v.postDelayed({
                    v.requestFocus()
                    imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                }, 150)
            }
            false
        }
    }

    fun hideKeyboard() {
        root.windowToken?.let { imm.hideSoftInputFromWindow(it, 0) }
    }

    fun finishTyping() {
        hideKeyboard()
        if (typing) {
            typing = false
            host.setTyping(false)
        }
    }

    fun dispose() {
        timers.onChange = null
    }

    // ---------- build ----------

    private fun build() {
        root.orientation = LinearLayout.VERTICAL
        root.background = Ui.bg(Ui.BG, 20f, ctx, Ui.STROKE)
        root.setPadding(dp(12), dp(10), dp(12), dp(12))

        val header = hbox()
        header.addView(TextView(ctx).apply {
            text = "🐱 Grand Assistant"
            setTextColor(Ui.TEXT)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }, lp(0, WRAP, 1f))
        header.addView(chip("Свернуть") { host.minimize() }, lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
        header.addView(chip("Скрыть кнопку", color = Ui.MUTED) { host.hideBubble() })
        root.addView(header, lp(MATCH, WRAP))

        val tabs = hbox()
        val names = listOf("Заметки", "Команды", "Таймеры", "Тренировка", "Настройки")
        names.forEachIndexed { i, n ->
            val t = chip(n) { select(i) }
            tabButtons.add(t)
            tabs.addView(t, lp(WRAP, WRAP, 0f, 0, 0, 6, 0))
        }
        val tabScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs)
        }
        root.addView(tabScroll, lp(MATCH, WRAP, 0f, 0, 10, 0, 8))
        root.addView(content, lp(MATCH, 0, 1f))

        pages.add(notesPage())
        pages.add(commandsPage())
        pages.add(timersPage())
        pages.add(trainingPage())
        pages.add(settingsPage())
        pages.forEach { content.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }

        timers.onChange = { refreshTimers() }
        select(0)
    }

    private fun select(i: Int) {
        finishTyping()
        pages.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
        tabButtons.forEachIndexed { idx, t ->
            t.background = Ui.bg(if (idx == i) Ui.ACCENT else Ui.CARD, 10f, ctx)
        }
    }

    // ---------- pages ----------

    private fun notesPage(): View {
        val box = vbox()
        val edit = input("Ваши заметки: координаты, цены, планы…", multiline = true).apply {
            setText(prefs.notes)
            doAfterTextChanged { prefs.notes = it?.toString().orEmpty() }
        }
        box.addView(edit, lp(MATCH, 0, 1f))
        box.addView(chip("Готово (скрыть клавиатуру)") { finishTyping() }, lp(MATCH, WRAP, 0f, 0, 8, 0, 0))
        return box
    }

    private fun commandsPage(): View {
        val box = vbox()
        val row = hbox()
        val edit = input("/новая команда")
        row.addView(edit, lp(0, WRAP, 1f, 0, 0, 6, 0))
        val list = vbox()

        fun refresh() {
            list.removeAllViews()
            prefs.commands.forEachIndexed { i, c ->
                val r = hbox().apply {
                    background = Ui.bg(Ui.CARD, 12f, ctx)
                    setPadding(dp(12), dp(10), dp(8), dp(10))
                }
                r.addView(TextView(ctx).apply {
                    text = c
                    setTextColor(Ui.TEXT)
                    textSize = 15f
                    setOnClickListener {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("command", c))
                        Toast.makeText(ctx, "Скопировано: $c", Toast.LENGTH_SHORT).show()
                    }
                }, lp(0, WRAP, 1f))
                r.addView(chip("✕", color = Ui.MUTED) {
                    prefs.commands = prefs.commands.toMutableList().also { it.removeAt(i) }
                    refresh()
                })
                list.addView(r, lp(MATCH, WRAP, 0f, 0, 0, 0, 6))
            }
        }

        row.addView(chip("+", bg = Ui.ACCENT) {
            val t = edit.text.toString().trim()
            if (t.isNotEmpty()) {
                prefs.commands = prefs.commands + t
                edit.setText("")
                finishTyping()
                refresh()
            }
        })
        box.addView(row, lp(MATCH, WRAP, 0f, 0, 0, 0, 8))
        box.addView(label("Нажмите на команду — она скопируется. Вставьте её в чат игры."), lp(MATCH, WRAP, 0f, 0, 0, 0, 8))
        box.addView(ScrollView(ctx).apply { addView(list) }, lp(MATCH, 0, 1f))
        refresh()
        return box
    }

    private fun timersPage(): View {
        val box = vbox()
        box.addView(label("Быстрый запуск:"), lp(MATCH, WRAP, 0f, 0, 0, 0, 6))
        val presets = hbox()
        listOf(1, 5, 10, 30).forEach { m ->
            presets.addView(
                chip("$m мин") { timers.add("Таймер $m мин", m * 60) },
                lp(WRAP, WRAP, 0f, 0, 0, 6, 0)
            )
        }
        box.addView(presets, lp(MATCH, WRAP, 0f, 0, 0, 0, 10))

        val custom = hbox()
        val minutes = input("Минуты", numeric = true)
        custom.addView(minutes, lp(0, WRAP, 1f, 0, 0, 6, 0))
        custom.addView(chip("Старт", bg = Ui.ACCENT) {
            val m = minutes.text.toString().toIntOrNull()
            if (m != null && m in 1..600) {
                timers.add("Таймер $m мин", m * 60)
                minutes.setText("")
                finishTyping()
            } else {
                Toast.makeText(ctx, "Введите от 1 до 600 минут", Toast.LENGTH_SHORT).show()
            }
        })
        box.addView(custom, lp(MATCH, WRAP, 0f, 0, 0, 0, 10))

        val list = vbox()
        timerList = list
        box.addView(ScrollView(ctx).apply { addView(list) }, lp(MATCH, 0, 1f))
        refreshTimers()
        return box
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d".format(s / 60, s % 60)
    }

    private fun refreshTimers() {
        val list = timerList ?: return
        val now = SystemClock.elapsedRealtime()
        val cur = timers.timers.toList()
        if (cur != shownTimers) {
            shownTimers = cur
            list.removeAllViews()
            timerTexts.clear()
            if (cur.isEmpty()) {
                list.addView(label("Нет активных таймеров. Когда время выйдет — придёт сигнал."))
            }
            cur.forEach { t ->
                val r = hbox().apply {
                    background = Ui.bg(Ui.CARD, 12f, ctx)
                    setPadding(dp(12), dp(10), dp(8), dp(10))
                }
                val tv = TextView(ctx).apply {
                    setTextColor(Ui.TEXT)
                    textSize = 15f
                }
                timerTexts.add(tv)
                r.addView(tv, lp(0, WRAP, 1f))
                r.addView(chip("✕", color = Ui.MUTED) { timers.remove(t) })
                list.addView(r, lp(MATCH, WRAP, 0f, 0, 0, 0, 6))
            }
        }
        cur.forEachIndexed { i, t ->
            timerTexts.getOrNull(i)?.text = "${t.label}  •  ${fmt(t.endAt - now)}"
        }
    }

    private fun trainingPage(): View {
        val box = vbox()
        box.addView(
            label("Тренировка реакции и точности. Это отдельный мини-режим внутри помощника, игру он не затрагивает."),
            lp(MATCH, WRAP, 0f, 0, 0, 0, 8)
        )
        box.addView(TrainingView(ctx, prefs), lp(MATCH, 0, 1f))
        return box
    }

    private fun settingsPage(): View {
        val box = vbox()

        val opLabel = label("Прозрачность: ${prefs.opacityPercent}%")
        val op = SeekBar(ctx).apply {
            max = 70
            progress = prefs.opacityPercent - 30
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    prefs.opacityPercent = p + 30
                    opLabel.text = "Прозрачность: ${p + 30}%"
                    host.opacityChanged()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        val szLabel = label("Размер окна и кнопки: ${prefs.sizePercent}%")
        val sz = SeekBar(ctx).apply {
            max = 80
            progress = prefs.sizePercent - 70
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    prefs.sizePercent = p + 70
                    szLabel.text = "Размер окна и кнопки: ${p + 70}%"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { host.sizeChanged() }
            })
        }

        box.addView(opLabel)
        box.addView(op, lp(MATCH, WRAP, 0f, 0, 0, 0, 12))
        box.addView(szLabel)
        box.addView(sz, lp(MATCH, WRAP, 0f, 0, 0, 0, 12))
        box.addView(chip("Скрыть плавающую кнопку") { host.hideBubble() }, lp(MATCH, WRAP, 0f, 0, 0, 0, 8))
        box.addView(chip("Сбросить положение кнопки") { host.resetPosition() }, lp(MATCH, WRAP, 0f, 0, 0, 0, 8))
        box.addView(chip("Выключить помощник", color = Ui.DANGER) { host.quit() }, lp(MATCH, WRAP, 0f, 0, 0, 0, 12))
        box.addView(label(
            "Вернуть скрытую кнопку можно из уведомления «Grand Assistant» или из главного экрана приложения.\n\n" +
            "Помощник не читает игру, не перехватывает сеть и не видит ваш аккаунт."
        ))
        return ScrollView(ctx).apply { addView(box) }
    }
}
