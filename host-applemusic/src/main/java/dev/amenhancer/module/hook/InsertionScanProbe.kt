package dev.amenhancer.module.hook

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import dev.amenhancer.module.ModuleConstants
import java.lang.ref.WeakReference

/**
 * Experiment: which z-index inside the player view tree actually paints.
 *
 * Cycles a fully opaque magenta layer through a list of (parent id, child index)
 * positions, plus a baseline step with nothing inserted, and dumps the resolved
 * hierarchy once per window. Screenshots taken while it cycles answer two
 * questions that geometry alone cannot:
 *
 *  - does a layer at this index reach the screen at all, and
 *  - is it above or below the lyric text (opaque magenta hides text below it,
 *    and leaves text drawn on top of it untouched).
 *
 * Targets can be overridden without rebuilding:
 *   adb shell am broadcast -a dev.ampp.probe.CONFIG -p com.apple.android.music \
 *       --es targets "off,player_root:1,player_fragments_host:0" --ei cycleMs 5000
 */
internal object InsertionScanProbe {
    /**
     * Diagnostic only: the scan inserts an opaque magenta layer over the player
     * page, so it stays off in normal builds. Flip to true (or see
     * AMPP-insertion-scan.md) to re-run the insertion-position scan.
     */
    private const val ENABLED = false
    private const val ACTION = "dev.ampp.probe.CONFIG"
    private const val DEFAULT_TARGETS =
        "off,player_root:1,player_root:2,player_root:3,player_fragments_host:0,player_fragments_host:top"
    private const val DEFAULT_CYCLE_MS = 5_000L
    private const val BASELINE = "off"
    private const val PARENT_PREFIX = "^"
    private const val TOP = "top"
    private const val HIERARCHY_MAX_DEPTH = 22
    private const val HIERARCHY_MAX_LINES = 900
    private const val TAG = "insertion scan"

    private val handler = Handler(Looper.getMainLooper())
    private val installed = java.util.WeakHashMap<Application, Boolean>()

    @Volatile private var targets: List<Target> = parse(DEFAULT_TARGETS)
    @Volatile private var cycleMs: Long = DEFAULT_CYCLE_MS
    @Volatile private var overlayZ: Float = 0f
    @Volatile private var fillAlpha: Int = 255
    @Volatile private var glowMode = false
    private var step = 0
    private var overlay: ScanOverlay? = null
    private var decorRef: WeakReference<View> = WeakReference(null)
    private var running = false
    private var dumpedFor = 0

    private data class Target(val parentId: String?, val index: Int, val label: String)

    fun install(application: Application) {
        if (!ENABLED) return
        if (installed.put(application, true) != null) return
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity.packageName != ModuleConstants.TARGET_PACKAGE) return
                val decor = activity.window?.decorView ?: return
                decorRef = WeakReference(decor)
                decor.viewTreeObserver.addOnGlobalLayoutListener { startIfNeeded(decor) }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        registerConfigReceiver(application)
        ModernXposedRuntime.log("$TAG: probe armed, targets=${targets.joinToString { it.label }} cycle=${cycleMs}ms")
    }

    private fun startIfNeeded(decor: View) {
        if (running) return
        running = true
        handler.postDelayed(::tick, 800L)
    }

    private fun tick() {
        runCatching { applyStep() }
            .onFailure { ModernXposedRuntime.log("$TAG: step failed", it) }
        handler.postDelayed(::tick, cycleMs)
    }

    private fun applyStep() {
        val decor = decorRef.get()
        if (decor == null || !decor.isAttachedToWindow || decor.width <= 0) {
            detachOverlay()
            return
        }
        if (dumpedFor != 0 && dumpedFor != System.identityHashCode(decor)) dumpedFor = 0
        val target = targets[step % targets.size]
        step += 1
        if (target.parentId == null) {
            detachOverlay()
            ModernXposedRuntime.log(
                "$TAG: $BASELINE (nothing inserted) decor=${decor.width}x${decor.height}",
            )
            return
        }
        val parent = resolve(decor, target.parentId)
        if (parent == null) {
            detachOverlay()
            ModernXposedRuntime.log("$TAG: ${target.label} - parent ${target.parentId} absent")
            return
        }
        if (parent.width <= 0 || parent.height <= 0) {
            detachOverlay()
            ModernXposedRuntime.log("$TAG: ${target.label} - parent not laid out yet")
            return
        }
        dumpHierarchyOnce(decor)
        val index = if (target.index == Int.MAX_VALUE) parent.childCount else target.index
        val clamped = index.coerceIn(0, parent.childCount)
        detachOverlay()
        val view = overlay ?: ScanOverlay(decor.context).also { overlay = it }
        view.label = target.label
        view.fillAlpha = fillAlpha
        view.glowMode = glowMode
        runCatching { view.z = overlayZ }
        val params = cloneLayoutParams(parent, parent.width, parent.height)
        runCatching {
            view.layoutParams = params
            parent.addView(view, clamped, params)
        }.onFailure {
            ModernXposedRuntime.log("$TAG: ${target.label} - addView failed: $it")
            return
        }
        view.invalidate()
        ModernXposedRuntime.log(
            "$TAG: inserted ${target.label} at ${idName(parent)}#$clamped" +
                " parent=${parent.width}x${parent.height} children=${parent.childCount}" +
                " lp=${params.javaClass.simpleName} z=$overlayZ a=$fillAlpha",
        )
    }

    /** Resolves `id`, or the parent of `id` when the spec is prefixed with `^`. */
    private fun resolve(decor: View, spec: String): ViewGroup? {
        val parentOf = spec.startsWith(PARENT_PREFIX)
        val idName = if (parentOf) spec.substring(PARENT_PREFIX.length) else spec
        val id = runCatching {
            decor.resources.getIdentifier(idName, "id", ModuleConstants.TARGET_PACKAGE)
        }.getOrDefault(0)
        if (id == 0) return null
        val view = runCatching { decor.findViewById<View>(id) }.getOrNull() ?: return null
        if (!parentOf) return view as? ViewGroup
        return view.parent as? ViewGroup
    }

    private fun detachOverlay() {
        val view = overlay ?: return
        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
    }

    /**
     * A sibling's params class belongs to the host's own view library; cloning it
     * through the (ViewGroup.LayoutParams) constructor keeps constraints empty
     * while staying the exact type that parent expects.
     */
    private fun cloneLayoutParams(parent: ViewGroup, width: Int, height: Int): ViewGroup.LayoutParams {
        val template = if (parent.childCount > 0) parent.getChildAt(0).layoutParams else null
        val params = template?.javaClass
            ?.let { type ->
                runCatching {
                    type.getConstructor(ViewGroup.LayoutParams::class.java)
                        .newInstance(ViewGroup.LayoutParams(1, 1)) as ViewGroup.LayoutParams
                }.getOrNull()
            }
            ?: ViewGroup.LayoutParams(width, height)
        params.width = width
        params.height = height
        if (params is ViewGroup.MarginLayoutParams) {
            params.setMargins(0, 0, 0, 0)
            params.marginStart = 0
            params.marginEnd = 0
        }
        return params
    }

    /** Dumps the subtree of every distinct node the probe targets, deepest-first, once per window. */
    private fun dumpHierarchyOnce(decor: View) {
        val identity = System.identityHashCode(decor)
        if (dumpedFor == identity) return
        dumpedFor = identity
        val seen = mutableSetOf<Int>()
        targets.mapNotNull { it.parentId }.distinct().forEach { name ->
            val node = resolve(decor, name) ?: return@forEach
            if (!seen.add(System.identityHashCode(node))) return@forEach
            val lines = mutableListOf<String>()
            walk(node, 0, lines)
            ModernXposedRuntime.log(
                "$TAG: subtree ${idName(node)} ${node.width}x${node.height}" +
                    " children=${(node as? ViewGroup)?.childCount ?: 0} nodes=${lines.size}",
            )
            lines.forEach { line -> ModernXposedRuntime.log("$TAG: $line") }
        }
    }

    private fun walk(view: View, depth: Int, out: MutableList<String>, index: Int = -1) {
        if (depth > HIERARCHY_MAX_DEPTH || out.size >= HIERARCHY_MAX_LINES) return
        out += buildString {
            repeat(depth) { append("  ") }
            if (index >= 0) {
                append('#')
                append(index)
                append(' ')
            }
            append(idName(view))
            append(' ')
            append(view.javaClass.simpleName)
            append(" ")
            append(view.width)
            append('x')
            append(view.height)
            val lp = view.layoutParams
            if (lp != null) {
                append(" lp=")
                append(lp.javaClass.simpleName)
                append('(')
                append(lp.width)
                append(',')
                append(lp.height)
                append(')')
            }
            if (view.visibility != View.VISIBLE) append(" vis=").append(view.visibility)
            if (view.alpha < 1f) append(" alpha=").append(view.alpha)
            if (view.elevation != 0f) append(" elev=").append(view.elevation)
            if (view.translationZ != 0f) append(" tz=").append(view.translationZ)
            if (view.z != 0f && view.z != view.elevation) append(" z=").append(view.z)
            view.background?.let { bg ->
                append(" bg=")
                append(bg.javaClass.simpleName)
                if (bg is android.graphics.drawable.GradientDrawable) {
                    append(" opacity=").append(bg.alpha)
                }
            }
        }
        val group = view as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            if (out.size >= HIERARCHY_MAX_LINES) return
            walk(group.getChildAt(index), depth + 1, out, index)
        }
    }

    private fun idName(view: View): String {
        if (view.id == View.NO_ID) return "-"
        return runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault(view.id.toString())
    }

    private fun registerConfigReceiver(application: Application) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val raw = intent.getStringExtra("targets")
                if (!raw.isNullOrBlank()) {
                    targets = parse(raw)
                    step = 0
                }
                if (intent.hasExtra("z")) overlayZ = intent.getFloatExtra("z", 0f)
                if (intent.hasExtra("glow")) glowMode = intent.getBooleanExtra("glow", false)
                if (intent.hasExtra("fillAlpha")) {
                    fillAlpha = intent.getIntExtra("fillAlpha", 255).coerceIn(0, 255)
                }
                if (intent.getBooleanExtra("dump", false)) dumpedFor = 0
                val requested = intent.getIntExtra("cycleMs", 0)
                if (requested in 500..60_000) cycleMs = requested.toLong()
                ModernXposedRuntime.log(
                    "$TAG: config targets=${targets.joinToString { it.label }}" +
                        " cycle=${cycleMs}ms z=$overlayZ fillAlpha=$fillAlpha glow=$glowMode",
                )
                handler.post { runCatching { applyStep() } }
            }
        }
        runCatching {
            val filter = IntentFilter(ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                application.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                application.registerReceiver(receiver, filter)
            }
        }.onFailure { ModernXposedRuntime.log("$TAG: config receiver unavailable: $it") }
    }

    private fun parse(spec: String): List<Target> = spec.split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapNotNull { entry ->
            if (entry == BASELINE || entry == "none" || entry == "-1") {
                return@mapNotNull Target(null, -1, BASELINE)
            }
            val separator = entry.lastIndexOf(':')
            if (separator <= 0) return@mapNotNull null
            val id = entry.substring(0, separator)
            val index = when (val raw = entry.substring(separator + 1)) {
                TOP -> Int.MAX_VALUE
                else -> raw.toIntOrNull() ?: return@mapNotNull null
            }
            Target(id, index, entry)
        }
        .ifEmpty { listOf(Target(null, -1, BASELINE)) }

    /** Opaque magenta: anything drawn above it stays readable, anything below it disappears. */
    private class ScanOverlay(context: Context) : View(context) {
        private val fill = Paint().apply {
            color = 0xFFFF00FF.toInt()
            style = Paint.Style.FILL
        }
        private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 200f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(16f, 0f, 6f, Color.BLACK)
        }
        private val marker = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }

        var label: String = ""
        var fillAlpha: Int = 255
            set(value) {
                field = value
                fill.color = (value shl 24) or 0x00FF00FF
                invalidate()
            }
        /** Soft radial blobs: what the real glow layer will look like in this slot. */
        var glowMode: Boolean = false
            set(value) {
                field = value
                invalidate()
            }

        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            if (!glowMode) {
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
            } else {
                drawGlow(canvas)
            }
            canvas.drawRect(0f, 0f, 300f, 300f, marker)
            canvas.drawText(label, 60f, 250f, caption)
        }

        private fun drawGlow(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val radius = 0.30f * minOf(w, h)
            val centers = arrayOf(
                floatArrayOf(w * 0.22f, h * 0.32f, 120f),
                floatArrayOf(w * 0.46f, h * 0.58f, 145f),
                floatArrayOf(w * 0.74f, h * 0.42f, 110f),
            )
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            centers.forEach { spot ->
                val alpha = spot[2].toInt()
                paint.shader = android.graphics.RadialGradient(
                    spot[0], spot[1], radius,
                    intArrayOf(
                        Color.argb(alpha, 255, 236, 210),
                        Color.argb((alpha * 0.35f).toInt(), 255, 210, 190),
                        Color.TRANSPARENT,
                    ),
                    floatArrayOf(0f, 0.42f, 1f),
                    android.graphics.Shader.TileMode.CLAMP,
                )
                canvas.drawCircle(spot[0], spot[1], radius, paint)
            }
        }
    }
}
