package ai.venice.rbxstudio

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.Handler
import android.os.Looper
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
 * Всё через отражение: имена методов движка не влияют на сборку.
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

    /** Движок требует Handler -> вызывать его надо с главного потока. */
    private val mainHandler = Handler(Looper.getMainLooper())

    @Synchronized
    fun log(s: String) {
        Log.i(TAG, s)
        lines.add(s)
        if (lines.size > 8000) lines.subList(0, 3000).clear()
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
            Short::class.javaPrimitiveType -> (value as? Number)?.toShort() ?: 0
            Byte::class.javaPrimitiveType -> (value as? Number)?.toByte() ?: 0
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
                for (m in c.methods.filter { it.declaringClass != Any::class.java }.sortedBy { it.name }) {
                    log("  ${m.name}(${m.parameterTypes.joinToString(", ") { it.simpleName }})")
                }
            } catch (e: Throwable) { log("$n — НЕТ") }
        }
        log("=== /АПИ ===")
    }

    fun dumpAssets(ctx: Context) {
        for (dir in listOf("", "box64", "graphics_driver", "dxwrapper", "wincomponents")) {
            try {
                val l = ctx.assets.list(dir)?.sorted() ?: emptyList()
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

    /** У Container нет getId — берём id из публичного поля или из имени папки. */
    fun containerId(container: Any?): Int {
        if (container == null) return -1
        val cls = container.javaClass
        if (cls.methods.any { it.name == "getId" })
            return (call(container, "getId") as? Number)?.toInt() ?: -1
        try {
            val f = cls.getDeclaredField("id")
            f.isAccessible = true
            (f.get(container) as? Number)?.let { return it.toInt() }
        } catch (e: Throwable) { }
        val root = call(container, "getRootDir") as? File
        return root?.name?.toIntOrNull() ?: -1
    }

    fun containerDir(ctx: Context, cm: Any?, id: Int): File? {
        val c = call(cm, "getContainerById", id)
        (call(c, "getRootDir") as? File)?.let { return it }
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
        call(c, "setStartupSelection", 0)
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
            "getDXWrapper", "getDXWrapperConfig", "getAudioDriver", "getWineVersion",
            "getBox64Preset", "getEnvVars", "getStartupSelection", "getHUDMode",
            "getWinComponents", "getDrives", "getRootDir"
        )) {
            log("  $m = ${call(c, m)}")
        }
        for (k in listOf("winVersion", "videoMemorySize", "box64Version", "desktopTheme")) {
            log("  extra[$k] = ${call(c, "getExtra", k, "нет")}")
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
        dumpAssets(ctx)

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
        data.put("startupSelection", 0)
        data.put("hudMode", 1)
        data.put("desktopTheme", "")
        data.put("winComponents", "")
        data.put("drives", "")

        log("вызываю createContainerAsync с главного потока...")
        mainHandler.post { call(cm, "createContainerAsync", data, null) }

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

    private fun httpText(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 20000
        c.instanceFollowRedirects = true
        c.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Throwable) {
        log("! $url -> $e"); null
    }

    /** Пробует адрес и возвращает длину файла (или -1). */
    private fun probe(url: String): Long = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.setRequestProperty("Range", "bytes=0-0")
        val code = c.responseCode
        val len = c.contentLengthLong
        c.disconnect()
        log("  проба [$code] $url")
        if (code == 200 || code == 206) len else -1L
    } catch (e: Throwable) {
        log("  проба [--] $url ($e)"); -1L
    }

    fun fetchVersionGuid(): String? {
        var guid: String? = null
        httpText("https://clientsettingscdn.roblox.com/v1/client-version/WindowsStudio64")?.let { t ->
            log("clientsettings -> ${t.take(300)}")
            Regex("\"clientVersionUpload\"\\s*:\\s*\"([^\"]+)\"").find(t)
                ?.groupValues?.get(1)?.removePrefix("version-")?.let { guid = it }
        }
        if (guid == null) {
            httpText("https://setup.rbxcdn.com/versionStudio")?.let { t ->
                log("versionStudio -> ${t.trim()}")
                t.trim().removePrefix("version-").trim().takeIf { it.isNotEmpty() }?.let { guid = it }
            }
        }
        log("версия: ${guid ?: "не определилась"}")
        return guid
    }

    /** Ищет уже скачанный установщик в «Загрузках». */
    private fun findExistingInstaller(): File? {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val f = dir.listFiles()?.firstOrNull {
            it.isFile && it.name.contains("RobloxStudio", true) && it.name.endsWith(".exe", true)
        }
        if (f != null) log("нашёл готовый установщик: ${f.absolutePath} (${f.length() / 1024 / 1024} МБ)")
        return f
    }

    fun downloadInstaller(guid: String, onProgress: (Int) -> Unit): File? {
        findExistingInstaller()?.let { return it }

        val candidates = mutableListOf<String>()
        for (n in listOf("RobloxStudioInstaller.exe", "RobloxStudioLauncherBeta.exe")) {
            candidates.add("https://setup.rbxcdn.com/version-$guid-$n")
            candidates.add("https://setup.rbxcdn.com/version-$guid/$n")
        }
        candidates.add("https://setup.rbxcdn.com/$guid-RobloxStudioInstaller.exe")

        log("перебираю адреса установщика...")
        for (u in candidates) {
            if (probe(u) > 0) {
                val name = u.substringAfterLast('/')
                return downloadTo(u, name, onProgress)
            }
        }
        log("! ни один адрес не подошёл")
        return null
    }

    private fun downloadTo(url: String, name: String, onProgress: (Int) -> Unit): File? {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val out = File(dir, name)
        log("качаю: $url")
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20000
            c.readTimeout = 60000
            val total = c.contentLengthLong
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
