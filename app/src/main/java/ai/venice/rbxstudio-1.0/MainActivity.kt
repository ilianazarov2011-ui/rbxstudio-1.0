package ai.venice.rbxstudio

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Date

class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var statusView: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()
        setContentView(buildUi())
        Engine.log("=== PortBox запущен, ${Date()} ===")
        Engine.dumpApi()

        val crash = File(filesDir, "crash.txt")
        if (crash.exists()) {
            Engine.log("--- прошлое падение ---")
            Engine.log(crash.readText())
            crash.delete()
        }
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 100) } catch (_: Throwable) {}
        }
        refreshLog()
    }

    // ---------------------------------------------------------------- интерфейс

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(TextView(this).apply {
            text = "PortBox — Roblox Studio на телефоне"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, Typeface.BOLD)
        })
        statusView = TextView(this).apply {
            text = "готов"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.GRAY)
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(statusView)

        root.addView(button("ЗАПУСТИТЬ СТУДИЮ") { startStudio() })
        root.addView(button("Меню движка") { Engine.openEngineUi(this) })
        root.addView(button("Скачать Студию заново") { downloadOnly() })
        root.addView(button("Диагностика") { diagnostics() })
        root.addView(button("Скопировать лог") { copyLog() })
        root.addView(button("Очистить лог") { Engine.clearLog(); refreshLog() })

        logView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            addView(logView)
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun button(text: String, action: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { action() }
        }

    private fun status(s: String) { statusView.text = s }

    private fun refreshLog() { logView.text = Engine.logText() }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun copyLog() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log", Engine.logText()))
            toast("Лог скопирован — вставь его в чат")
        } catch (e: Throwable) { toast("Не скопировалось") }
    }

    private fun runBg(what: String, body: () -> Unit) {
        if (busy) { toast("Занят, подожди"); return }
        busy = true
        status("$what...")
        Thread {
            try { body() } catch (e: Throwable) { Engine.log("!! $what: $e") }
            ui.post { busy = false; status("готово"); refreshLog() }
        }.start()
    }

    // ---------------------------------------------------------------- действия

    private fun startStudio() = runBg("Запуск Студии") {
        val cm = Engine.manager(this) ?: run {
            Engine.log("! движок не найден, открываю меню движка")
            ui.post { Engine.openEngineUi(this) }
            return@runBg
        }
        val container = Engine.ensureStudioContainer(this, cm) ?: return@runBg
        val id = Engine.containerId(container)
        val dir = Engine.containerDir(this, cm, id)

        val studio = if (dir != null) Engine.findStudioExe(dir) else null
        if (studio != null && dir != null) {
            val win = Engine.toWindowsPath(studio, dir)
            Engine.log("Студия найдена: $win")
            if (win != null) { ui.post { Engine.launchContainer(this, container, win) }; return@runBg }
        }

        Engine.log("Студия ещё не установлена — нужен установщик")
        val guid = Engine.fetchVersionGuid() ?: return@runBg
        val f = Engine.downloadInstaller(guid) { p -> ui.post { status("Скачано $p%") } } ?: return@runBg
        ui.post { Engine.launchContainer(this, container, "D:\\" + f.name) }
    }

    private fun downloadOnly() = runBg("Скачивание") {
        val guid = Engine.fetchVersionGuid() ?: return@runBg
        Engine.downloadInstaller(guid) { p -> ui.post { status("Скачано $p%") } }
    }

    private fun diagnostics() {
        val sb = StringBuilder()
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Плата/SoC: ${Build.HARDWARE}")
        if (Build.VERSION.SDK_INT >= 31) sb.appendLine("Чип: ${Build.SOC_MODEL}")
        sb.appendLine("Ядер: ${Runtime.getRuntime().availableProcessors()}")
        sb.appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine("RAM: ${ramGb()} ГБ")
        sb.appendLine("Свободно: ${freeGb()} ГБ")
        sb.appendLine("GPU: ${gpuRenderer()}")
        val score = score()
        sb.appendLine("Оценка под DXVK: $score / 100")
        if (score < 60) sb.appendLine("Вывод: будет тяжело, но работать может")
        Engine.log(sb.toString())
        refreshLog()
    }

    private fun ramGb(): String = try {
        val line = File("/proc/meminfo").readLines().first { it.startsWith("MemTotal") }
        val kb = line.filter { it.isDigit() }.toLong()
        String.format("%.1f", kb / 1024.0 / 1024.0)
    } catch (e: Throwable) { "?" }

    private fun freeGb(): String = try {
        val st = StatFs(filesDir.absolutePath)
        String.format("%.1f", st.availableBytes / 1024.0 / 1024.0 / 1024.0)
    } catch (e: Throwable) { "?" }

    private fun gpuRenderer(): String = try {
        val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(d, ver, 0, ver, 1)
        val cfg = arrayOfNulls<android.opengl.EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(
            d, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE), 0,
            cfg, 0, 1, num, 0
        )
        val ctx = EGL14.eglCreateContext(
            d, cfg[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        val surf = EGL14.eglCreatePbufferSurface(
            d, cfg[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        EGL14.eglMakeCurrent(d, surf, surf, ctx)
        val s = GLES20.glGetString(GLES20.GL_RENDERER) ?: "?"
        EGL14.eglTerminate(d)
        s
    } catch (e: Throwable) { "не определился" }

    private fun score(): Int {
        var s = 100
        val ram = ramGb().toDoubleOrNull() ?: 4.0
        if (ram < 4) s -= 30 else if (ram < 8) s -= 20
        if (freeGb().toDoubleOrNull()?.let { it < 8 } == true) s -= 20
        val gpu = gpuRenderer().lowercase()
        if (!gpu.contains("adreno")) s -= 20
        if (Runtime.getRuntime().availableProcessors() < 8) s -= 10
        return s.coerceAtLeast(0)
    }

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { File(filesDir, "crash.txt").writeText("${Date()}\n${t.name}\n" + Log.getStackTraceString(e)) }
            catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }
}
