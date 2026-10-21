package ai.venice.rbxstudio

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Мост к движку Winlator (пакет com.winlator), вшитому в это приложение.
 * Всё через отражение: имена методов движка не влияют на сборку,
 * а в лог пишется понятный отчёт.
 */
object Engine {

    private const val TAG = "EngineBridge"

    private const val CLS_MANAGER = "com.winlator.container.ContainerManager"
    private const val CLS_CONTAINER = "com.winlator.container.Container"
    private const val CLS_XSERVER = "com.winlator.XServerDisplayActivity"
    private const val CLS_MAIN = "com.winlator.MainActivity"

    const val CONTAINER_NAME = "Studio"
    private const val ENV_VARS = "DXVK_FRAME_RATE=30 BOX64_DYNAREC_BIGBLOCK=1"

    private val lines = ArrayList<String>()
    private var apiDumped = false
    private val callbackCalls = AtomicInteger()

    @Synchronized
    fun log(s: String) {
        Log.i(TAG, s)
        lines.add(s)
        if (lines.size > 6000) lines.subList(0, 2000).clear()
    }

    @Synchronized
    fun logText(): String = lines.joinToString("\n")

    @Synchronized
    fun clearLog() { lines.clear() }

    // ---------------------------------------------------------------- отражение

    private fun adapt(type: Class<*>, value: Any?): Any? {
        if (value != null && type.isInstance(value)) return value
        return when (type) {
            Int::class.javaPrimitiveType -> (value as? Number)?.toInt() ?: 0
            Long::class.javaPrimitiveType -> (value as? Number)?.toLong() ?: 0L
            Float::class.javaPrimitiveType -> (value as? Number)?.toFloat() ?: 0f
            Double::class.javaPrimitiveType -> (value as? Number)?.toDouble() ?: 0.0
            Boolean::class.javaPrimitiveType -> value as? Boolean ?: false
            Char::class.javaPrimitiveType -> value as? Char ?: ' '
            String::class.java -> value?.toString()
            else -> if (type.isInterface) noop(type) else value
        }
    }

    private fun noop(type: Class<*>): Any = Proxy.newProxyInstance(
        Engine::class.java.classLoader, arrayOf(type)
    ) { _, m, _ ->
        callbackCalls.incrementAndGet()
        log("движок позвал колбэк: ${m.name}")
        when (m.returnType) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            else -> null
        }
    }

    fun call(target: Any?, name: String, vararg args: Any?): Any? {
        if (target == null) return null
        val cls: Class<*> = if (target is Class<*>) target else target.javaClass
        val candidates = cls.methods.filter { it.name == name }
        if (candidates.isEmpty()) { log("! в движке нет метода $name"); return null }
        val m = candidates.firstOrNull { it.parameterTypes.size == args.size } ?: candidates.first()
        val params = m.parameterTypes.mapIndexed { i, t -> adapt(t, if (i < args.size) args[i] else null) }
        return try {
            m.invoke(if (target is Class<*>) null else target, *params.toTypedArray())
        } catch (e: Throwable) {
            log("! $name: ${e.cause ?: e}")
            null
        }
    }

    private fun setAny(obj: Any, value: Any, vararg setters: String, field: String) {
        for (s in setters) {
            val m = obj.javaClass.methods.firstOrNull { it.name == s && it.parameterTypes.size == 1 }
            if (m != null) { call(obj, s, value); log("  $s = $value"); return }
        }
        try {
            val f = obj.javaClass.getDeclaredField(field)
            f.isAccessible = true
            f.set(obj, value.toString())
            log("  поле $field = $value")
        } catch (e: Throwable) {
            log("  (нет ни $field-сеттера, ни поля — пропускаю)")
        }
    }

    fun dumpApi() {
        if (apiDumped) return
        apiDumped = true
        log("=== АПИ ДВИЖКА ===")
        for (n in listOf(CLS_MANAGER, CLS_CONTAINER)) {
            try {
                val c = Class.forName(n)
                log("--- $n ---")
                val ms = c.methods.filter { it.declaringClass != Any::class.java }
                for (m in ms.sortedBy { it.name }) {
                    log("  ${m.name}(${m.parameterTypes.joinToString(", ") { it.simpleName }})")
                }
            } catch (e: Throwable) { log("$n — НЕТ") }
        }
        log("=== /АПИ ===")
    }

    fun dumpAssets(ctx: Context) {
        for (dir in listOf("", "box64", "graphics_driver", "dxwrapper", "wincomponents")) {
            try {
                val l = ctx.assets.list(dir)?.sorted() ?: emptyList<String>()
                log("assets/[$dir] = ${if (l.isEmpty()) "(пусто)" else l.joinToString(", ")}")
            } catch (e: Throwable) { log("assets/[$dir] — ошибка") }
        }
    }

    // ---------------------------------------------------------------- контейнер

    fun manager(ctx: Context): Any? {
        return try {
            val c = Class.forName(CLS_MANAGER)
            val ctor = c.constructors.firstOrNull {
                it.parameterTypes.size == 1 && it.parameterTypes[0].isAssignableFrom(ctx.javaClass)
            } ?: c.constructors.firstOrNull { it.parameterTypes.size == 1 }
            ctor?.newInstance(ctx)
        } catch (e: Throwable) {
            log("! ContainerManager: $e"); null
        }
    }

    fun containerId(container: Any?): Int =
        (call(container, "getId") as? Number)?.toInt() ?: -1

    fun containerDir(ctx: Context, cm: Any?, id: Int): File? {
        val c = call(cm, "getContainerById", id)
        val root = call(c, "getRootDir") as? File
        if (root != null) return root
        val guess = File(ctx.filesDir, "containers/$id")
        return if (guess.exists()) guess else null
    }

    private fun applySettings(c: Any) {
        setAny(c, CONTAINER_NAME, "setName", field = "name")
        setAny(c, "1280x720", "setScreenSize", field = "screenSize")
        setAny(c, "system", "setGraphicsDriver", field = "graphicsDriver")
        setAny(c, "dxvk", "setDXWrapper", field = "dxwrapper")
        setAny(c, "alsa", "setAudioDriver", field = "audioDriver")
        setAny(c, ENV_VARS, "setEnvVars", field = "envVars")
        call(c, "putExtra", "winVersion", "win10")
        call(c, "putExtra", "videoMemorySize", "1024")
        call(c, "putExtra", "box64Preset", "STABILITY")
        call(c, "saveData")
        log("настройки записаны")
    }

    private fun dumpContainer(c: Any) {
        log("--- состояние контейнера ---")
        for (m in listOf(
            "getName", "getScreenSize", "getGraphicsDriver", "getGraphicsDriverConfig",
            "getDXWrapper", "getAudioDriver", "getWineVersion", "getBox64Preset",
            "getEnvVars", "getStartupSelection", "getHUDMode", "getWinComponents", "getDrives"
        )) {
            log("  $m = ${call(c, m)}")
        }
        val cf = call(c, "getConfigFile") as? File
        if (cf != null && cf.exists()) {
            log("--- файл ${cf.name} ---")
            log(cf.readText())
            log("--- /файл ---")
        }
    }

    /** Находит контейнер "Studio", при отсутствии — создаёт его. */
    fun ensureStudioContainer(ctx: Context, cm: Any): Any? {
        val list = call(cm, "getContainers") as? List<*> ?: emptyList<Any?>()
        log("контейнеров в движке: ${list.size}")
        for (c in list) {
            val n = call(c, "getName") as? String
            log("  #${containerId(c)} \"$n\"")
            if (n == CONTAINER_NAME) { log("нашёл готовый контейнер \"$CONTAINER_NAME\""); return c }
        }

        log("создаю контейнер \"$CONTAINER_NAME\"...")
        val data = JSONObject()
        data.put("name", CONTAINER_NAME)
        data.put("screenSize", "1280x720")
        data.put("graphicsDriver", "system")
        data.put("graphicsDriverConfig", "")
        data.put("dxwrapper", "dxvk")
        data.put("dxwrapperConfig", "")
        data.put("audioDriver", "alsa")
        data.put("audioDriverConfig", "")
        data.put("winVersion", "win10")
        data.put("wineVersion", "")
        data.put("box64Version", "")
        data.put("box64Preset", "STABILITY")
        data.put("envVars", ENV_VARS)
        data.put("cpuList", "")
        data.put("cpuListWoW64", "")
        data.put("videoMemorySize", "1024")
        data.put("startupSelection", "normal")
        data.put("hudMode", "1")
        data.put("desktopTheme", "")
        data.put("winComponents", "")
        data.put("drives", "")

        log("вызываю createContainerAsync(JSONObject)...")
        call(cm, "createContainerAsync", data, null)

        log("жду появления контейнера (до 6 минут)...")
        val t0 = System.currentTimeMillis()
        var container: Any? = null
        while (System.currentTimeMillis() - t0 < 360_000) {
            Thread.sleep(3000)
            val now = call(cm, "getContainers") as? List<*> ?: emptyList<Any?>()
            val found = now.firstOrNull { (call(it, "getName") as? String) == CONTAINER_NAME }
            if (found != null) { container = found; break }
        }
        if (container == null) { log("! контейнер не появился за 6 минут"); return null }
        log("контейнер создан: #${containerId(container)}")

        applySettings(container)

        val root = call(container, "getRootDir") as? File
        log("папка контейнера: ${root?.absolutePath}")
        if (root != null) {
            val t1 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t1 < 360_000) {
                if (root.listFiles()?.any { it.isDirectory } == true) break
                Thread.sleep(3000)
            }
            Thread.sleep(5000)
            log("в папке: " + (root.listFiles()?.joinToString { it.name } ?: "пусто"))
        }
        dumpContainer(container)
        return container
    }

    fun launchContainer(ctx: Context, container: Any, execArgs: String?) {
        if (execArgs != null) {
            setAny(container, execArgs, "setExecArgs", field = "execArgs")
            call(container, "saveData")
        }
        val id = containerId(container)
        log("запускаю контейнер #$id, команда: ${execArgs ?: "по умолчанию"}")
        val i = Intent().setClassName(ctx, CLS_XSERVER)
        i.putExtra("container_id", id)
        ctx.startActivity(i)
    }

    fun openEngineUi(ctx: Context) {
        try { ctx.startActivity(Intent().setClassName(ctx, CLS_MAIN)) }
        catch (e: Throwable) { log("! не открыть меню движка: $e") }
    }

    // ---------------------------------------------------------------- студия

    fun findStudioExe(dir: File): File? {
        var found: File? = null
        fun walk(f: File, depth: Int) {
            if (found != null || depth > 16) return
            val kids = f.listFiles() ?: return
            for (k in kids) {
                if (found != null) return
                if (k.isDirectory) walk(k, depth + 1)
                else if (k.name.equals("RobloxStudioBeta.exe", true)) found = k
            }
        }
        walk(dir, 0)
        return found
    }

    fun toWindowsPath(f: File, containerDir: File): String? {
        val rel = f.absolutePath.removePrefix(containerDir.absolutePath).trimStart('/')
        val key = "drive_c/"
        val i = rel.indexOf(key)
        if (i < 0) return null
        return "C:\\" + rel.substring(i + key.length).replace('/', '\\')
    }

    fun fetchVersionGuid(): String? {
        log("спрашиваю версию Студии у Roblox...")
        val text = httpText("https://setup.rbxcdn.com/versionStudio") ?: return null
        val guid = text.trim().removePrefix("version-").trim()
        log("версия: $guid")
        return if (guid.isNotEmpty()) guid else null
    }

    private fun httpText(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 20000
        c.instanceFollowRedirects = true
        c.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Throwable) {
        log("! $url -> $e"); null
    }

    fun downloadInstaller(guid: String, onProgress: (Int) -> Unit): File? {
        val url = "https://setup.rbxcdn.com/version-$guid-RobloxStudioInstaller.exe"
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val out = File(dir, "version-$guid-RobloxStudioInstaller.exe")
        log("качаю установщик: $url")
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20000
            c.readTimeout = 60000
            val total = c.contentLength
            c.inputStream.use { ins ->
                FileOutputStream(out).use { fos ->
                    val buf = ByteArray(1 shl 16)
                    var read = 0L
                    var last = -1
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val p = (read * 100 / total).toInt()
                            if (p != last && p % 5 == 0) { last = p; onProgress(p) }
                        }
                    }
                }
            }
            log("скачано: ${out.length() / 1024 / 1024} МБ -> ${out.absolutePath}")
            out
        } catch (e: Throwable) {
            log("! скачивание: $e"); null
        }
    }
}
