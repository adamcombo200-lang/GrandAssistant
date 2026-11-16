package com.grandassistant

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.grandassistant.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private data class AppItem(val label: String, val pkg: String, val info: ApplicationInfo)

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startAssistant() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(s.left, s.top, s.right, s.bottom)
            insets
        }

        b.btnAddGame.setOnClickListener { pickGame() }
        b.btnStart.setOnClickListener { onStartClicked() }
        b.btnPermission.setOnClickListener { requestOverlayPermission() }
        b.btnBubble.setOnClickListener { toggleBubble() }
        b.btnStop.setOnClickListener {
            OverlayService.stop(this)
            handler.postDelayed({ refreshStatus() }, 300)
        }
        b.btnTips.setOnClickListener { showTips() }
    }

    override fun onResume() {
        super.onResume()
        refreshGame()
        refreshStatus()
    }

    // ---------- игра ----------

    @Suppress("DEPRECATION")
    private fun packageInfo(pkg: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            packageManager.getPackageInfo(pkg, 0)
        }

    private fun showNoGame() {
        b.gameIcon.setImageResource(R.drawable.ic_launcher_foreground)
        b.gameName.text = "Игра не добавлена"
        b.gameVersion.text = "Нажмите «Добавить игру»"
        b.gamePackage.text = ""
        b.btnStart.isEnabled = false
        b.btnAddGame.text = "Добавить игру"
    }

    /** Название и версия определяются автоматически при каждом открытии экрана */
    private fun refreshGame() {
        val pkg = prefs.gamePackage
        if (pkg == null) {
            showNoGame()
            return
        }
        try {
            val pi = packageInfo(pkg)
            val ai = pi.applicationInfo
            if (ai == null) {
                showNoGame()
                return
            }
            b.gameIcon.setImageDrawable(ai.loadIcon(packageManager))
            b.gameName.text = ai.loadLabel(packageManager)
            val code = PackageInfoCompat.getLongVersionCode(pi)
            b.gameVersion.text = "Версия: ${pi.versionName ?: "неизвестно"} (сборка $code)"
            b.gamePackage.text = pkg
            b.btnStart.isEnabled = true
            b.btnAddGame.text = "Сменить игру"
        } catch (e: PackageManager.NameNotFoundException) {
            prefs.gamePackage = null
            showNoGame()
            toast("Выбранное приложение удалено")
        }
    }

    @Suppress("DEPRECATION")
    private fun queryApps(): List<AppItem> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val res = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            packageManager.queryIntentActivities(intent, 0)
        }
        return res
            .map {
                AppItem(
                    it.loadLabel(packageManager).toString(),
                    it.activityInfo.packageName,
                    it.activityInfo.applicationInfo
                )
            }
            .filter { it.pkg != packageName }
            .distinctBy { it.pkg }
            .sortedWith(
                compareByDescending<AppItem> {
                    it.label.contains("grand", true) || it.pkg.contains("grand", true)
                }.thenBy { it.label.lowercase() }
            )
    }

    private fun pickGame() {
        Thread {
            val items = queryApps()
            runOnUiThread {
                if (!isFinishing && !isDestroyed) showPicker(items)
            }
        }.start()
    }

    private fun showPicker(items: List<AppItem>) {
        if (items.isEmpty()) {
            toast("Приложения не найдены")
            return
        }
        val adapter = object : ArrayAdapter<AppItem>(this, 0, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(R.layout.item_app, parent, false)
                val app = getItem(position)!!
                v.findViewById<ImageView>(R.id.appIcon).setImageDrawable(app.info.loadIcon(packageManager))
                v.findViewById<TextView>(R.id.appName).text = app.label
                v.findViewById<TextView>(R.id.appPkg).text = app.pkg
                return v
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Выберите Grand Mobile")
            .setAdapter(adapter) { _, which ->
                prefs.gamePackage = items[which].pkg
                refreshGame()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------- запуск ----------

    private fun onStartClicked() {
        if (prefs.gamePackage == null) {
            toast("Сначала добавьте игру")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        val needNotif = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needNotif && !prefs.askedNotifications) {
            prefs.askedNotifications = true
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startAssistant()
        }
    }

    private fun startAssistant() {
        val pkg = prefs.gamePackage ?: return
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            toast("Не удалось открыть игру")
            return
        }
        OverlayService.send(this, OverlayService.ACTION_START)
        startActivity(launch)
        // дальше пользователь сам входит в аккаунт и выбирает сервер
    }

    // ---------- разрешения ----------

    private fun requestOverlayPermission() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Показ поверх других приложений")
            .setMessage(
                "Разрешение нужно только для плавающей кнопки и меню помощника поверх игры. " +
                    "Приложение не читает экран игры, не видит ваш аккаунт и не запрашивает доступ к файлам."
            )
            .setPositiveButton("Открыть настройки") { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun refreshStatus() {
        val ok = Settings.canDrawOverlays(this)
        b.permStatus.text = if (ok) "Поверх других приложений: разрешено ✅"
        else "Поверх других приложений: не разрешено ❌"
        b.btnPermission.visibility = if (ok) View.GONE else View.VISIBLE

        val hidden = !OverlayService.isRunning || prefs.bubbleHidden
        b.btnBubble.text = if (hidden) "Показать плавающую кнопку" else "Скрыть плавающую кнопку"
        b.btnStop.isEnabled = OverlayService.isRunning
    }

    private fun toggleBubble() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        val show = !OverlayService.isRunning || prefs.bubbleHidden
        OverlayService.send(this, if (show) OverlayService.ACTION_SHOW else OverlayService.ACTION_HIDE)
        handler.postDelayed({ refreshStatus() }, 400)
    }

    private fun showTips() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Redmi / Xiaomi")
            .setMessage(
                "Чтобы окно не пропадало во время игры:\n\n" +
                    "1. Настройки приложения → «Другие разрешения» → включите " +
                    "«Отображать всплывающие окна, когда запущено в фоне».\n" +
                    "2. Включите «Автозапуск».\n" +
                    "3. «Контроль активности» / «Экономия заряда» → «Нет ограничений».\n" +
                    "4. Закрепите приложение в списке недавних (замок)."
            )
            .setPositiveButton("Открыть настройки приложения") { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
