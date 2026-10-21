package ai.venice.rbxstudio

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val crashFile = File(filesDir, "crash.txt")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                crashFile.writeText("ПОТОК: " + t.name + "\n\n" + e.stackTraceToString())
            }
            prev?.uncaughtException(t, e)
        }

        setContent { RbxTheme { App() } }
    }
}

@Composable
private fun RbxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF00E5FF),
            background = Color(0xFF0A0C0F),
            surface = Color(0xFF12161B),
        ),
        content = content,
    )
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Диагностика", "Пресеты", "Сессия", "Лог")

    val crashText = remember {
        runCatching {
            File(ctx.filesDir, "crash.txt").takeIf { it.exists() }?.readText()
        }.getOrNull()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {

            if (!crashText.isNullOrBlank()) {
                Text(
                    "БЫЛО ПАДЕНИЕ - открой вкладку Лог",
                    color = Color(0xFFFF8A80),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF3A1010))
                        .padding(5.dp),
                )
            }

            Row(
                Modifier.fillMaxWidth().height(38.dp).background(Color(0xFF12161B)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { i, title ->
                    val on = i == tab
                    Box(
                        Modifier.weight(1f).fillMaxSize().clickable { tab = i },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            title,
                            color = if (on) Color(0xFF00E5FF) else Color(0xFF6B7683),
                            fontSize = 12.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
            when (tab) {
                0 -> DiagnosticsScreen()
                1 -> PresetsScreen()
                2 -> SessionScreen()
                else -> LogScreen()
            }
        }
    }
}

// ---------- ЛОГ ----------

@Composable
private fun LogScreen() {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick) {
        val f = File(ctx.filesDir, "crash.txt")
        text = withContext(Dispatchers.IO) {
            if (f.exists()) f.readText() else "Падений не было."
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text("ЛОГ ПАДЕНИЙ", color = Color(0xFF6B7683), fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Обновить") { tick++ }
            Chip("Очистить") {
                runCatching { File(ctx.filesDir, "crash.txt").delete() }
                tick++
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(text, color = Color(0xFFE6EDF3), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(24.dp))
    }
}

// ---------- ДИАГНОСТИКА ----------

enum class Verdict { GREEN, YELLOW, RED }

data class DeviceReport(
    val androidRelease: String,
    val sdkInt: Int,
    val soc: String,
    val abis: String,
    val cpuCores: Int,
    val ramTotalMb: Long,
    val storageFreeMb: Long,
    val gpuRenderer: String,
    val vulkanVersion: String?,
    val adreno: Boolean,
    val score: Int,
    val verdict: Verdict,
    val notes: List<String>,
)

object DeviceProbe {

    fun probe(ctx: Context): DeviceReport {
        val notes = ArrayList<String>()
        val vulkan = vulkanInfo(ctx)
        val renderer = glRenderer()
        val adreno = renderer.contains("Adreno", ignoreCase = true)
        val ramMb = totalRamMb(ctx)
        val freeMb = freeStorageMb(ctx)

        var score = 100

        if (vulkan == null) {
            score -= 45
            notes += "Vulkan не найден. Без него DXVK не стартует."
        } else if (vulkan.first < 1 || (vulkan.first == 1 && vulkan.second < 1)) {
            score -= 30
            notes += "Vulkan " + vulkan.first + "." + vulkan.second + " - нужен минимум 1.1."
        }

        if (!adreno) {
            score -= 20
            notes += "GPU не Adreno: драйвер Turnip недоступен, остаётся системный Vulkan."
        }

        when {
            ramMb < 4096 -> {
                score -= 35
                notes += "Меньше 4 ГБ RAM - Studio будет уходить в своп."
            }
            ramMb < 6144 -> {
                score -= 15
                notes += "4-6 ГБ RAM. Только лёгкие места и низкие настройки."
            }
            ramMb < 8192 -> {
                score -= 6
                notes += "6-8 ГБ RAM. Пойдёт, но впритык."
            }
        }

        if (freeMb < 6000) {
            score -= 15
            notes += "Меньше 6 ГБ свободно. Нужно место под префикс со Studio."
        }

        if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
            score -= 30
            notes += "Нет arm64-v8a - Box64 не запустится."
        }

        if (notes.isEmpty()) notes += "Проблем не найдено. Можно ставить префикс."

        val finalScore = score.coerceIn(0, 100)
        return DeviceReport(
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            soc = socName(),
            abis = Build.SUPPORTED_ABIS.joinToString(", "),
            cpuCores = Runtime.getRuntime().availableProcessors(),
            ramTotalMb = ramMb,
            storageFreeMb = freeMb,
            gpuRenderer = renderer,
            vulkanVersion = vulkan?.let { it.first.toString() + "." + it.second },
            adreno = adreno,
            score = finalScore,
            verdict = when {
                finalScore >= 75 -> Verdict.GREEN
                finalScore >= 45 -> Verdict.YELLOW
                else -> Verdict.RED
            },
            notes = notes,
        )
    }

    private fun vulkanInfo(ctx: Context): Pair<Int, Int>? {
        var raw = -1
        for (f in ctx.packageManager.systemAvailableFeatures) {
            if (f.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION) raw = f.version
        }
        if (raw < 0) return null
        return ((raw shr 22) and 0x7F) to ((raw shr 12) and 0x3FF)
    }

    private fun totalRamMb(ctx: Context): Long {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.totalMem / 1024 / 1024
    }

    private fun freeStorageMb(ctx: Context): Long =
        StatFs(ctx.filesDir.absolutePath).availableBytes / 1024 / 1024

    private fun socName(): String {
        val platform = runCatching {
            val c = Class.forName("android.os.SystemProperties")
            val m = c.getMethod("get", String::class.java)
            (m.invoke(null, "ro.board.platform") as? String).orEmpty()
        }.getOrDefault("")
        val model = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.orEmpty() else ""
        return listOf(Build.HARDWARE, platform, model)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" / ")
    }

    private fun glRenderer(): String = runCatching {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(dpy, ver, 0, ver, 1)

        val cfgAttr = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(dpy, cfgAttr, 0, cfgs, 0, 1, n, 0)
        val cfg = cfgs[0] ?: return "unknown"

        val ctx = EGL14.eglCreateContext(
            dpy, cfg, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        val surf = EGL14.eglCreatePbufferSurface(
            dpy, cfg, intArrayOf(EGL14.EGL_WIDTH, 4, EGL14.EGL_HEIGHT, 4, EGL14.EGL_NONE), 0,
        )
        EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
        val r = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty()

        EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
        EGL14.eglDestroySurface(dpy, surf)
        EGL14.eglDestroyContext(dpy, ctx)
        EGL14.eglTerminate(dpy)
        r
    }.getOrDefault("unknown")
}

@Composable
private fun DiagnosticsScreen() {
    val ctx = LocalContext.current
    var report by remember { mutableStateOf<DeviceReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val res = runCatching { withContext(Dispatchers.Default) { DeviceProbe.probe(ctx) } }
        res.onSuccess { report = it }
        res.onFailure { error = it.stackTraceToString() }
    }

    val r = report
    val err = error

    if (r == null) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
        ) {
            if (err == null) {
                Text("Проверяю устройство...", color = Color(0xFF6B7683))
            } else {
                Text("ОШИБКА ПРОВЕРКИ", color = Color(0xFFFF453A), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text(err, color = Color(0xFFE6EDF3), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
        return
    }

    val vc = when (r.verdict) {
        Verdict.GREEN -> Color(0xFF4CD964)
        Verdict.YELLOW -> Color(0xFFFFB300)
        Verdict.RED -> Color(0xFFFF453A)
    }
    val vt = when (r.verdict) {
        Verdict.GREEN -> "ПОТЯНЕТ"
        Verdict.YELLOW -> "ПОТЯНЕТ, НО С ОГОВОРКАМИ"
        Verdict.RED -> "БУДЕТ ТЯЖЕЛО"
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.score.toString(), color = vc, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(12.dp))
            Text(vt, color = vc, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))

        InfoRow("Android", r.androidRelease + " (API " + r.sdkInt + ")")
        InfoRow("SoC", r.soc)
        InfoRow("Ядра CPU", r.cpuCores.toString())
        InfoRow("ABI", r.abis)
        InfoRow("RAM", r.ramTotalMb.toString() + " МБ")
        InfoRow("Свободно", r.storageFreeMb.toString() + " МБ")
        InfoRow("GPU", r.gpuRenderer)
        InfoRow("Vulkan", r.vulkanVersion ?: "не найден")
        InfoRow("Turnip", if (r.adreno) "доступен (Adreno)" else "недоступен")

        Spacer(Modifier.height(16.dp))
        Text("ЗАМЕЧАНИЯ", color = Color(0xFF6B7683), fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        r.notes.forEach {
            Text(
                "- " + it,
                color = Color(0xFFC8D2DC),
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, color = Color(0xFF6B7683), fontSize = 13.sp, modifier = Modifier.weight(0.38f))
        Text(v, color = Color(0xFFE6EDF3), fontSize = 13.sp, modifier = Modifier.weight(0.62f))
    }
}

// ---------- ПРЕСЕТЫ ----------

@Serializable
data class RuntimePreset(
    val id: String,
    val name: String,
    val resScale: Float,
    val fpsLimit: Int,
    val box64: Map<String, String>,
    val dxvk: Map<String, String>,
    val wine: Map<String, String>,
) {
    fun env(): Map<String, String> = box64 + wine
    fun dxvkConf(): String = dxvk.entries.joinToString("\n") { it.key + " = " + it.value }
}

object Presets {

    private val baseBox64 = mapOf(
        "BOX64_DYNAREC_SAFEFLAGS" to "1",
        "BOX64_DYNAREC_FASTNAN" to "1",
        "BOX64_DYNAREC_CALLRET" to "1",
        "BOX64_DYNAREC_WAIT" to "1",
        "BOX64_LOG" to "0",
        "BOX64_NOBANNER" to "1",
    )

    private val baseWine = mapOf(
        "WINEDEBUG" to "-all",
        "WINEDLLOVERRIDES" to "mscoree=d;mshtml=d;winemenubuilder.exe=d",
        "WINEESYNC" to "1",
        "WINEFSYNC" to "1",
        "DXVK_ASYNC" to "1",
    )

    private val baseDxvk = mapOf(
        "d3d11.maxFeatureLevel" to "11_0",
        "dxvk.maxFrameLatency" to "1",
        "dxvk.enableAsync" to "True",
    )

    val battery = RuntimePreset(
        "battery", "Батарея", 0.5f, 30,
        baseBox64 + mapOf(
            "BOX64_DYNAREC_BIGBLOCK" to "1",
            "BOX64_DYNAREC_FORWARD" to "256",
            "BOX64_DYNAREC_STRONGMEM" to "1",
        ),
        baseDxvk + mapOf("dxvk.numCompilerThreads" to "1", "dxvk.maxFrameLatency" to "2"),
        baseWine,
    )

    val balanced = RuntimePreset(
        "balanced", "Баланс", 0.7f, 45,
        baseBox64 + mapOf(
            "BOX64_DYNAREC_BIGBLOCK" to "2",
            "BOX64_DYNAREC_FORWARD" to "512",
            "BOX64_DYNAREC_STRONGMEM" to "2",
        ),
        baseDxvk + mapOf("dxvk.numCompilerThreads" to "2"),
        baseWine,
    )

    val mali = RuntimePreset(
        "mali", "Mali (твой чип)", 0.5f, 30,
        baseBox64 + mapOf(
            "BOX64_DYNAREC_BIGBLOCK" to "1",
            "BOX64_DYNAREC_FORWARD" to "256",
            "BOX64_DYNAREC_STRONGMEM" to "1",
        ),
        baseDxvk + mapOf(
            "dxvk.numCompilerThreads" to "1",
            "dxvk.maxFrameLatency" to "2",
            "dxvk.enableGraphicsPipelineLibrary" to "False",
        ),
        baseWine + mapOf("DXVK_FRAME_RATE" to "30"),
    )

    val performance = RuntimePreset(
        "performance", "Производительность", 1.0f, 60,
        baseBox64 + mapOf(
            "BOX64_DYNAREC_BIGBLOCK" to "3",
            "BOX64_DYNAREC_FORWARD" to "512",
            "BOX64_DYNAREC_STRONGMEM" to "2",
            "BOX64_DYNAREC_X87DOUBLE" to "1",
        ),
        baseDxvk + mapOf("dxvk.numCompilerThreads" to "4"),
        baseWine,
    )

    val all = listOf(mali, battery, balanced, performance)
}

@Composable
private fun PresetsScreen() {
    var selected by remember { mutableStateOf("mali") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Text("ПРЕСЕТ РАНТАЙМА", color = Color(0xFF6B7683), fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))

        Presets.all.forEach { p ->
            val on = p.id == selected
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) Color(0xFF16232B) else Color(0xFF12161B))
                    .border(
                        1.dp,
                        if (on) Color(0xFF00E5FF) else Color(0xFF232A32),
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { selected = p.id }
                    .padding(12.dp),
            ) {
                Text(
                    p.name,
                    color = if (on) Color(0xFF00E5FF) else Color(0xFFE6EDF3),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "разрешение " + (p.resScale * 100).toInt() + "%   лимит " + p.fpsLimit + " FPS",
                    color = Color(0xFF8A97A5),
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("ПЕРЕМЕННЫЕ ОКРУЖЕНИЯ", color = Color(0xFF6B7683), fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))

        val cur = Presets.all.first { it.id == selected }
        (cur.env() + cur.dxvk).entries.sortedBy { it.key }.forEach { e ->
            Text(
                e.key + "=" + e.value,
                color = Color(0xFF9FB0C0),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------- ОВЕРЛЕЙ ----------

enum class MouseButton { LEFT, MIDDLE, RIGHT }

interface InputSink {
    fun move(dx: Float, dy: Float)
    fun button(button: MouseButton, down: Boolean)
    fun wheel(delta: Float)
    fun key(keyCode: Int, down: Boolean)
}

class LogInputSink(private val state: OverlayState) : InputSink {
    override fun move(dx: Float, dy: Float) = Unit
    override fun button(button: MouseButton, down: Boolean) {
        if (down) state.lastEvent = button.name + " нажата"
    }
    override fun wheel(delta: Float) {
        if (delta > 0.3f || delta < -0.3f) state.lastEvent = "колесо " + "%.1f".format(delta)
    }
    override fun key(keyCode: Int, down: Boolean) {
        if (down) state.lastEvent = "клавиша " + keyCode
    }
}

@Serializable
data class OverlayButton(
    val id: String,
    val label: String,
    val x: Float,
    val y: Float,
    val size: Float = 0.10f,
    val isModifier: Boolean = false,
    val keyCode: Int = 0,
)

@Serializable
data class OverlayLayout(
    val name: String = "default",
    val padZoneWidth: Float = 0.34f,
    val cursorSpeed: Float = 1.0f,
    val offsetCursor: Boolean = true,
    val loupeEnabled: Boolean = true,
    val buttons: List<OverlayButton> = defaultButtons(),
)

fun defaultButtons(): List<OverlayButton> = listOf(
    OverlayButton("ctrl", "CTRL", 0.365f, 0.855f, isModifier = true),
    OverlayButton("shift", "SHIFT", 0.445f, 0.855f, isModifier = true),
    OverlayButton("alt", "ALT", 0.525f, 0.855f, isModifier = true),
    OverlayButton("t1", "1", 0.605f, 0.855f, keyCode = 49),
    OverlayButton("t2", "2", 0.672f, 0.855f, keyCode = 50),
    OverlayButton("t3", "3", 0.739f, 0.855f, keyCode = 51),
    OverlayButton("t4", "4", 0.806f, 0.855f, keyCode = 52),
    OverlayButton("esc", "ESC", 0.873f, 0.855f, keyCode = 27),
    OverlayButton("del", "DEL", 0.940f, 0.855f, keyCode = 127),
    OverlayButton("focus", "F", 0.940f, 0.745f, keyCode = 70),
    OverlayButton("undo", "Z", 0.873f, 0.745f, keyCode = 90),
)

enum class Latch { OFF, ONCE, LOCKED }

class OverlayState {
    var cursor by mutableStateOf(Offset.Zero)
    var editMode by mutableStateOf(false)
    var loupeOn by mutableStateOf(true)
    var fineAim by mutableStateOf(false)
    var cameraActive by mutableStateOf(false)
    var lastEvent by mutableStateOf("")

    var ctrl by mutableStateOf(Latch.OFF)
    var shift by mutableStateOf(Latch.OFF)
    var alt by mutableStateOf(Latch.OFF)

    fun toggle(id: String) {
        when (id) {
            "ctrl" -> ctrl = ctrl.next()
            "shift" -> shift = shift.next()
            "alt" -> alt = alt.next()
        }
    }

    fun consumeOnce() {
        if (ctrl == Latch.ONCE) ctrl = Latch.OFF
        if (shift == Latch.ONCE) shift = Latch.OFF
        if (alt == Latch.ONCE) alt = Latch.OFF
    }

    private fun Latch.next() = when (this) {
        Latch.OFF -> Latch.ONCE
        Latch.ONCE -> Latch.LOCKED
        Latch.LOCKED -> Latch.OFF
    }
}

private enum class Role { PAD, CAMERA, MULTI, IDLE }

@Composable
private fun TouchOverlay(
    layout: OverlayLayout,
    sink: InputSink,
    state: OverlayState,
    onLayoutChange: (OverlayLayout) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val padEdge = w * layout.padZoneWidth
        val offsetY = if (layout.offsetCursor) 52f else 0f

        LaunchedEffect(w, h) {
            if (state.cursor == Offset.Zero) {
                state.cursor = Offset(w * 0.5f, h * 0.5f)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(state.editMode) {
                    if (state.editMode) return@pointerInput
                    awaitPointerEventScope {
                        val roles = HashMap<PointerId, Role>()
                        val startPos = HashMap<PointerId, Offset>()
                        val lastPos = HashMap<PointerId, Offset>()
                        val startTime = HashMap<PointerId, Long>()
                        val longFired = HashSet<PointerId>()
                        var lastTapAt = 0L
                        var lastTapPos = Offset.Zero

                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Main)

                            for (ch in ev.changes) {
                                if (ch.pressed && !ch.previousPressed && !ch.isConsumed) {
                                    val pos = ch.position
                                    val role = when {
                                        roles.isEmpty() && pos.x <= padEdge -> Role.PAD
                                        roles.isEmpty() -> Role.CAMERA
                                        roles.size == 1 &&
                                                roles.values.first() == Role.PAD -> Role.MULTI
                                        else -> Role.IDLE
                                    }
                                    roles[ch.id] = role
                                    startPos[ch.id] = pos
                                    lastPos[ch.id] = pos
                                    startTime[ch.id] = System.currentTimeMillis()
                                    if (role == Role.CAMERA) {
                                        sink.button(MouseButton.RIGHT, true)
                                        state.cameraActive = true
                                    }
                                    if (role == Role.PAD) {
                                        state.cursor = pos.copy(y = pos.y - offsetY)
                                    }
                                }
                            }

                            for (ch in ev.changes) {
                                val role = roles[ch.id] ?: continue
                                val pos = ch.position
                                val prev = lastPos[ch.id] ?: pos
                                val d = pos - prev

                                when (role) {
                                    Role.PAD -> {
                                        val speed = layout.cursorSpeed *
                                                (if (state.fineAim) 0.5f else 1f)
                                        state.cursor = Offset(
                                            (state.cursor.x + d.x * speed).coerceIn(0f, w),
                                            (state.cursor.y + d.y * speed).coerceIn(0f, h),
                                        )
                                        lastPos[ch.id] = pos

                                        val held = System.currentTimeMillis() -
                                                (startTime[ch.id] ?: 0L)
                                        val moved = (pos - (startPos[ch.id] ?: pos)).getDistance()
                                        if (held > 450 && moved < 26f && longFired.add(ch.id)) {
                                            sink.button(MouseButton.RIGHT, true)
                                            sink.button(MouseButton.RIGHT, false)
                                            state.lastEvent = "ПКМ (долгий тап)"
                                        }
                                    }
                                    Role.CAMERA -> {
                                        sink.move(d.x, d.y)
                                        lastPos[ch.id] = pos
                                    }
                                    Role.MULTI -> {
                                        sink.wheel(-d.y * 0.06f)
                                        lastPos[ch.id] = pos
                                    }
                                    Role.IDLE -> lastPos[ch.id] = pos
                                }
                                ch.consume()
                            }

                            for (ch in ev.changes) {
                                if (ch.pressed || !ch.previousPressed) continue
                                val role = roles.remove(ch.id) ?: continue
                                val st = startPos[ch.id] ?: Offset.Zero
                                val t0 = startTime[ch.id] ?: 0L
                                val dist = (ch.position - st).getDistance()
                                val dt = System.currentTimeMillis() - t0

                                if (role == Role.PAD && dt < 220 && dist < 26f &&
                                    !longFired.contains(ch.id)
                                ) {
                                    val now = System.currentTimeMillis()
                                    val isDouble = now - lastTapAt < 320 &&
                                            (ch.position - lastTapPos).getDistance() < 70f
                                    sink.button(MouseButton.LEFT, true)
                                    sink.button(MouseButton.LEFT, false)
                                    if (isDouble) {
                                        sink.button(MouseButton.LEFT, true)
                                        sink.button(MouseButton.LEFT, false)
                                    }
                                    state.lastEvent = if (isDouble) "двойной клик" else "клик"
                                    state.consumeOnce()
                                    lastTapAt = now
                                    lastTapPos = ch.position
                                }
                                if (role == Role.CAMERA) {
                                    sink.button(MouseButton.RIGHT, false)
                                    if (roles.values.none { it == Role.CAMERA }) {
                                        state.cameraActive = false
                                    }
                                }

                                startPos.remove(ch.id)
                                lastPos.remove(ch.id)
                                startTime.remove(ch.id)
                                longFired.remove(ch.id)
                                ch.consume()
                            }
                        }
                    }
                }
        )

        layout.buttons.forEach { btn ->
            key(btn.id) {
                OverlayButtonView(
                    btn = btn,
                    state = state,
                    containerW = w,
                    containerH = h,
                    sink = sink,
                    onMoved = { nx, ny ->
                        onLayoutChange(
                            layout.copy(
                                buttons = layout.buttons.map {
                                    if (it.id == btn.id) it.copy(x = nx, y = ny) else it
                                },
                            ),
                        )
                    },
                )
            }
        }

        CursorLayer(state, layout, padEdge)

        Text(
            "ПАД",
            color = Color(0x55FFFFFF),
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 6.dp),
        )
        Text(
            if (state.cameraActive) "КАМЕРА (тяни)" else "КАМЕРА",
            color = if (state.cameraActive) Color(0xCCFFB300) else Color(0x44FFFFFF),
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
        )
        Text(
            "левая зона - курсор, правая - камера",
            color = Color(0x33FFFFFF),
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
        )
    }
}

@Composable
private fun OverlayButtonView(
    btn: OverlayButton,
    state: OverlayState,
    containerW: Float,
    containerH: Float,
    sink: InputSink,
    onMoved: (Float, Float) -> Unit,
) {
    val density = LocalDensity.current
    val base = minOf(containerW, containerH)
    val sizeDp = with(density) { (base * btn.size).toDp() }
    val baseX = with(density) { (containerW * btn.x).toDp() }
    val baseY = with(density) { (containerH * btn.y).toDp() }

    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }

    // живые значения, иначе обработчик замораживает старую раскладку
    val curX by rememberUpdatedState(btn.x)
    val curY by rememberUpdatedState(btn.y)
    val curOnMoved by rememberUpdatedState(onMoved)

    val latch = when (btn.id) {
        "ctrl" -> state.ctrl
        "shift" -> state.shift
        "alt" -> state.alt
        else -> Latch.OFF
    }

    Box(
        Modifier
            .offset(x = baseX + with(density) { dx.toDp() }, y = baseY + with(density) { dy.toDp() })
            .size(sizeDp)
            .clip(CircleShape)
            .background(
                when {
                    latch == Latch.LOCKED -> Color(0xCC00E5FF)
                    latch == Latch.ONCE -> Color(0x99FFB300)
                    else -> Color(0x77000000)
                },
            )
            .border(1.dp, Color(0x66FFFFFF), CircleShape)
            .pointerInput(btn.id, state.editMode) {
                if (state.editMode) {
                    detectDragGestures(
                        onDrag = { _, drag -> dx += drag.x; dy += drag.y },
                        onDragEnd = {
                            curOnMoved(
                                (curX + dx / containerW).coerceIn(0.01f, 0.97f),
                                (curY + dy / containerH).coerceIn(0.02f, 0.96f),
                            )
                            dx = 0f
                            dy = 0f
                        },
                        onDragCancel = {
                            dx = 0f
                            dy = 0f
                        },
                    )
                } else {
                    detectTapGestures(
                        onTap = {
                            if (btn.isModifier) {
                                state.toggle(btn.id)
                                state.lastEvent = "модификатор " + btn.label
                            } else {
                                if (state.ctrl != Latch.OFF) sink.key(17, true)
                                if (state.shift != Latch.OFF) sink.key(16, true)
                                if (state.alt != Latch.OFF) sink.key(18, true)
                                sink.key(btn.keyCode, true)
                                sink.key(btn.keyCode, false)
                                if (state.ctrl != Latch.OFF) sink.key(17, false)
                                if (state.shift != Latch.OFF) sink.key(16, false)
                                if (state.alt != Latch.OFF) sink.key(18, false)
                                state.lastEvent = "клавиша " + btn.label
                                state.consumeOnce()
                            }
                        },
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(btn.label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CursorLayer(state: OverlayState, layout: OverlayLayout, padEdge: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val c = state.cursor

        drawLine(Color(0x22FFFFFF), Offset(padEdge, 0f), Offset(padEdge, size.height), 2f)

        if (state.cameraActive) {
            drawRect(
                color = Color(0x22FFB300),
                topLeft = Offset(padEdge, 0f),
                size = Size(size.width - padEdge, size.height),
            )
        }

        drawCircle(Color(0x6600E5FF), radius = 30f, center = c, style = Stroke(2f))
        drawCircle(Color(0xAA00E5FF), radius = 3f, center = c)
        drawLine(Color(0xCC00E5FF), c - Offset(16f, 0f), c + Offset(16f, 0f), 2f)
        drawLine(Color(0xCC00E5FF), c - Offset(0f, 16f), c + Offset(0f, 16f), 2f)

        if (state.loupeOn && layout.loupeEnabled) {
            val r = 78f
            val center = Offset(c.x, (c.y - r - 24f).coerceAtLeast(r + 4f))
            drawCircle(Color(0xCC000000), r, center)
            drawCircle(Color(0x6600E5FF), r, center, style = Stroke(2f))
            drawLine(Color(0xFF00E5FF), center - Offset(10f, 0f), center + Offset(10f, 0f), 2f)
            drawLine(Color(0xFF00E5FF), center - Offset(0f, 10f), center + Offset(0f, 10f), 2f)
        }
    }
}

// ---------- СЕССИЯ ----------

private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

@Composable
private fun SessionScreen() {
    val ctx = LocalContext.current
    val state = remember { OverlayState() }
    val sink = remember { LogInputSink(state) }
    val file = remember { File(ctx.filesDir, "overlay.json") }
    var layout by remember {
        mutableStateOf(
            runCatching { json.decodeFromString(OverlayLayout.serializer(), file.readText()) }
                .getOrDefault(OverlayLayout()),
        )
    }

    LaunchedEffect(layout) {
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(json.encodeToString(OverlayLayout.serializer(), layout)) }
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0A0C0F))) {
        TouchOverlay(
            layout = layout,
            sink = sink,
            state = state,
            onLayoutChange = { layout = it },
        )

        Row(
            Modifier.align(Alignment.TopEnd).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Chip(if (state.editMode) "Готово" else "Раскладка") { state.editMode = !state.editMode }
            Chip(if (state.loupeOn) "Лупа вкл" else "Лупа выкл") { state.loupeOn = !state.loupeOn }
            Chip(if (state.fineAim) "Точно" else "Быстро") { state.fineAim = !state.fineAim }
            Chip("Сброс") {
                layout = layout.copy(buttons = defaultButtons())
            }
        }
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color(0xFFC8D2DC),
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xAA1B2229))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
