package dev.amenhancer.module.hook

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import dev.amenhancer.module.ModuleConstants
import java.lang.ref.WeakReference
import kotlin.math.exp
import kotlin.math.sin

/**
 * Background wash for the player page.
 *
 * Sits between the host's own blurred artwork and the player content: inserted into
 * `player_root` directly after `background_layers`, with z kept at 0. The scan that
 * established this position is documented in `AMPP-insertion-scan.md`.
 *
 * The rendering follows what LyricsBlossom actually does (see
 * `analysis/lyricsblossom/REPORT.md`): a very small control mesh whose colours come
 * straight from the cover, bilinearly upscaled into a full-page gradient, with ordered
 * dither to keep the ramp band-free. That app has no particles, bloom, feedback or beat
 * detection — the whole backdrop is this one gradient, and the audio only nudges its
 * colour and contrast. Here the same nudge comes from the lyric line/word events, so the
 * wash still breathes without an audio path.
 */
internal object GlowLayer {
    private const val TAG = "player glow"
    private const val PLAYER_ROOT = "player_root"
    private const val BACKGROUND_LAYER = "background_layers"
    private const val FRAME_MS = 50L
    private const val IDLE_FRAME_MS = 120L
    private const val PULSE_ATTACK_MS = 120f
    private const val PULSE_DECAY_MS = 480f
    private const val CONFIG_TTL_MS = 200L
    private const val TEST_ACTION = "dev.ampp.glow.TEST"
    /** Cover-derived gradients need a floor of darkness to keep lyric text readable. */
    private const val VALUE_FLOOR = 0.30f
    private const val VALUE_CEILING = 0.62f

    private val handler = Handler(Looper.getMainLooper())
    private val installed = java.util.WeakHashMap<Application, Boolean>()

    @Volatile private var attached: GlowView? = null
    @Volatile private var rootRef: WeakReference<ViewGroup> = WeakReference(null)
    @Volatile private var pulseAt: Long = 0L
    @Volatile private var pulsePeak: Float = 0f
    /** Test-only holds and slow motion, driven by the debug broadcast. */
    @Volatile private var holdUntil: Long = 0L
    @Volatile private var holdLevel: Float = 0f
    @Volatile private var decayMs: Float = PULSE_DECAY_MS
    @Volatile private var looping: Boolean = false
    private var pulseLogs = 0

    private data class GlowConfig(
        val on: Boolean,
        val strength: Float,
        val detail: Float,
        val breathMs: Float,
    )

    @Volatile private var config = GlowConfig(false, 1f, 0.18f, 7_000f)
    @Volatile private var configAt = 0L

    private val frame = object : Runnable {
        override fun run() {
            val view = attached ?: return stopLooping()
            val root = rootRef.get()
            if (root == null || !view.isAttachedToWindow || !view.isShown) return stopLooping()
            val config = config()
            if (!config.on) {
                view.alpha = 0f
                pulsePeak = 0f
                return stopLooping()
            }
            val now = SystemClock.uptimeMillis()
            val energy = energyAt(now)
            val playing = musicActive(view.context)
            val breathing = playing && config.breathMs > 0f
            val breath = if (breathing) 0.5f + 0.5f * sin((now / config.breathMs) * TWO_PI) else 0.5f
            view.level = energy
            view.strength = config.strength
            view.detail = config.detail
            view.breath = breath
            view.breathing = breathing
            view.cover = BackgroundPulse.coverTexture()
            view.alpha = 1f
            view.invalidate()
            handler.postDelayed(this, if (energy > 0f) FRAME_MS else IDLE_FRAME_MS)
        }
    }

    fun install(application: Application) {
        if (installed.put(application, true) != null) return
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity.packageName != ModuleConstants.TARGET_PACKAGE) return
                val decor = activity.window?.decorView ?: return
                decor.viewTreeObserver.addOnGlobalLayoutListener { attach(decor) }
            }

            override fun onActivityPaused(activity: Activity) {
                if (activity.packageName != ModuleConstants.TARGET_PACKAGE) return
                detach()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        registerTestReceiver(application)
    }

    /** Line change: the wash lifts and warms a little, then settles. */
    fun bump() = pulse(0.72f)

    /** Word onset: the same impulse at a third of the strength, so it reads as shimmer. */
    fun word() = pulse(0.22f)

    fun pulse(strength: Float) {
        if (strength <= 0.01f) return
        val config = config()
        if (!config.on) {
            ModernXposedRuntime.log("$TAG: pulse ignored, glow off")
            return
        }
        val view = attached ?: return
        if (!view.isAttachedToWindow) return
        pulseAt = SystemClock.uptimeMillis()
        pulsePeak = strength.coerceIn(0f, 1f)
        if (pulseLogs < 8) {
            pulseLogs += 1
            ModernXposedRuntime.log("$TAG: pulse #$pulseLogs strength=$strength")
        }
        if (!looping) {
            looping = true
            handler.post(frame)
        }
    }

    private fun energyAt(now: Long): Float {
        val at = pulseAt
        val held = if (now < holdUntil) holdLevel else 0f
        if (at == 0L) return held
        val elapsed = (now - at).toFloat()
        if (elapsed < 0f) return held
        val decayed = if (elapsed < PULSE_ATTACK_MS) {
            pulsePeak * (elapsed / PULSE_ATTACK_MS)
        } else {
            pulsePeak * exp(-(elapsed - PULSE_ATTACK_MS) / decayMs)
        }
        val value = maxOf(if (decayed < 0.004f) 0f else decayed, held)
        return value
    }

    private fun musicActive(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.isMusicActive == true
    }.getOrDefault(false)

    /**
     * Snapshot of the glow settings, refreshed at most every [CONFIG_TTL_MS]. Any of the
     * three at zero switches the layer off, as does the master toggle.
     */
    private fun config(): GlowConfig {
        val now = SystemClock.uptimeMillis()
        val cached = config
        if (now - configAt < CONFIG_TTL_MS) return cached
        configAt = now
        val settings = runCatching {
            dev.amenhancer.module.config.TargetConfigClient.currentSettings()
        }.getOrNull()
        val strength = (settings?.lyricGlowStrengthPercent ?: 0).coerceAtLeast(0) / 100f
        val detail = (settings?.lyricGlowBandPercent ?: 0).coerceAtLeast(0) / 100f
        val breathSeconds = settings?.lyricGlowBreathSeconds ?: 0
        val fresh = GlowConfig(
            on = settings != null && settings.lyricLineFlashEnabled && strength > 0f && detail > 0f,
            strength = strength,
            detail = detail,
            breathMs = if (breathSeconds > 0) breathSeconds * 1000f else 0f,
        )
        config = fresh
        return fresh
    }

    private fun attach(decor: View) {
        if (decor !is ViewGroup) return
        runCatching { attachUnsafe(decor) }
            .onFailure { ModernXposedRuntime.log("$TAG: attach failed: $it") }
    }

    private fun attachUnsafe(decor: ViewGroup) {
        val root = decor.findViewById<ViewGroup>(idOf(decor, PLAYER_ROOT)) ?: return
        val background = root.findViewById<View>(idOf(decor, BACKGROUND_LAYER)) ?: return
        if (root !== background.parent) return
        if (root.width <= 0 || root.height <= 0) return
        val current = attached
        if (current != null &&
            current.parent === root &&
            root.indexOfChild(current) == root.indexOfChild(background) + 1 &&
            current.width == root.width &&
            current.height == root.height
        ) {
            return
        }
        val index = root.indexOfChild(background) + 1
        val view = current ?: GlowView(root.context).also { attached = it }
        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        val params = cloneLayoutParams(root)
        view.layoutParams = params
        root.addView(view, index, params)
        // Z must stay 0: the dual-pane wrapper above carries translationZ = 1.0, and any
        // elevation here would lift the wash over the lyric text.
        view.elevation = 0f
        view.translationZ = 0f
        view.alpha = 0f
        rootRef = WeakReference(root)
        watch(root)
        if (!looping && config().on) {
            looping = true
            handler.post(frame)
        }
        ModernXposedRuntime.log(
            "$TAG: layer inserted at ${idName(root)}#$index ${root.width}x${root.height}" +
                " order=[${describeChildren(root)}]",
        )
    }

    private fun watch(root: ViewGroup) {
        if (root.getTag(ModuleConstants.MODULE_PACKAGE.hashCode()) == true) return
        root.setTag(ModuleConstants.MODULE_PACKAGE.hashCode(), true)
        root.addOnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
            val sizeChanged = view.width != right - left || view.height != bottom - top
            if (sizeChanged) view.post { runCatching { attachUnsafe(view as ViewGroup) } }
        }
    }

    /** Sibling params keep the host's own (obfuscated) layout-params type. */
    private fun cloneLayoutParams(root: ViewGroup): ViewGroup.LayoutParams {
        val template = if (root.childCount > 0) root.getChildAt(0).layoutParams else null
        val params = template?.javaClass
            ?.let { type ->
                runCatching {
                    type.getConstructor(ViewGroup.LayoutParams::class.java)
                        .newInstance(ViewGroup.LayoutParams(1, 1)) as ViewGroup.LayoutParams
                }.getOrNull()
            }
            ?: ViewGroup.LayoutParams(root.width, root.height)
        params.width = root.width
        params.height = root.height
        if (params is ViewGroup.MarginLayoutParams) {
            params.setMargins(0, 0, 0, 0)
            params.marginStart = 0
            params.marginEnd = 0
        }
        return params
    }

    private fun detach() {
        stopLooping()
        val view = attached ?: return
        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        attached = null
        rootRef = WeakReference(null)
    }

    private fun stopLooping() {
        looping = false
        handler.removeCallbacks(frame)
    }

    private fun describeChildren(root: ViewGroup): String = buildString {
        for (i in 0 until root.childCount) {
            if (i > 0) append(", ")
            append('#').append(i).append(' ').append(idName(root.getChildAt(i)))
        }
    }

    private fun idOf(view: View, name: String): Int = runCatching {
        view.resources.getIdentifier(name, "id", ModuleConstants.TARGET_PACKAGE)
    }.getOrDefault(0)

    private fun idName(view: View): String = runCatching {
        view.resources.getResourceEntryName(view.id)
    }.getOrDefault(view.id.toString())

    private fun status(): String {
        val view = attached
        return "attached=${view != null} window=${view?.isAttachedToWindow} shown=${view?.isShown}" +
            " cfg=${config()} looping=$looping"
    }

    /** Test hook: `adb shell am broadcast -a dev.ampp.glow.TEST --ef strength 1.0 --ei holdMs 8000`. */
    private fun registerTestReceiver(application: Application) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val strength = intent.getFloatExtra("strength", 1f)
                val holdMs = intent.getIntExtra("holdMs", 0)
                val decay = intent.getFloatExtra("decayMs", PULSE_DECAY_MS)
                if (decay in 50f..20_000f) decayMs = decay
                if (holdMs > 0) {
                    holdLevel = strength.coerceIn(0f, 1f)
                    holdUntil = SystemClock.uptimeMillis() + holdMs
                } else {
                    holdUntil = 0L
                }
                ModernXposedRuntime.log("$TAG: test pulse strength=$strength holdMs=$holdMs ${status()}")
                pulse(strength)
            }
        }
        runCatching {
            val filter = IntentFilter(TEST_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                application.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                application.registerReceiver(receiver, filter)
            }
        }.onFailure { ModernXposedRuntime.log("$TAG: test receiver unavailable: $it") }
    }

    private const val TWO_PI = (2.0 * Math.PI).toFloat()

    /**
     * The mesh: a handful of colour cells sampled from the cover, upscaled by the
     * bilinear filter into a full-page gradient — the same trick as the reference app's
     * `BackdropMeshLayer`, at a fraction of the cost of a shader.
     */
    private class GlowView(context: Context) : View(context) {
        /** Global lyric energy, 0..1. */
        var level: Float = 0f
        /** 0..2: how much of the wash covers the host background. */
        var strength: Float = 1f
        /** 0..0.4: cell density. */
        var detail: Float = 0.18f
        var breath: Float = 0.5f
        var breathing: Boolean = false
        var cover: Bitmap? = null

        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val ditherPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            blendMode = android.graphics.BlendMode.PLUS
        }
        private var mesh: Bitmap? = null
        private var cols = 0
        private var rows = 0
        private var dither: Bitmap? = null
        private var startedAt = 0L

        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            setWillNotDraw(false)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return
            if (startedAt == 0L) startedAt = SystemClock.uptimeMillis()
            val now = SystemClock.uptimeMillis() - startedAt
            ensureMesh(w, h)
            val bitmap = mesh ?: return
            val pixels = IntArray(cols * rows)
            for (row in 0 until rows) {
                for (col in 0 until cols) {
                    pixels[row * cols + col] = cellColor(col, row, now)
                }
            }
            runCatching { bitmap.setPixels(pixels, 0, cols, 0, 0, cols, rows) }
            paint.alpha = (strength.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
            canvas.drawBitmap(bitmap, null, Rect(0, 0, w, h), paint)
            ensureDither()
            if (ditherPaint.shader != null) {
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), ditherPaint)
            }
        }

        /**
         * Colours come straight from the cover, the way the reference app feeds its
         * `GradientCS` pass: sample the artwork at the cell centre, then push value into a
         * band that keeps lyric text legible on top.
         */
        private fun cellColor(col: Int, row: Int, nowMs: Long): Int {
            val u = (col + 0.5f) / cols
            val v = (row + 0.5f) / rows
            val source = cover
            val sampled = if (source != null && source.width > 1 && source.height > 1) {
                // Area average, not a point sample: one red glyph in a cell must not tint the
                // whole cell, which is what made the first pass look blotchy.
                sampleArea(source, u, v)
            } else {
                FALLBACK
            }
            val hsv = FloatArray(3)
            Color.colorToHSV(sampled, hsv)
            // Slow drift: each cell breathes on its own phase so the wash never looks frozen.
            val phase = (col * 0.7f + row * 1.3f) * TWO_PI_THIRD
            val drift = sin(nowMs / 9_000f + phase) * (if (breathing) 0.035f else 0.012f)
            hsv[0] = (hsv[0] + drift * 40f + 360f) % 360f
            hsv[1] = (hsv[1] * (0.95f + 0.05f * breath)).coerceIn(0f, 1f)
            val base = VALUE_FLOOR + (VALUE_CEILING - VALUE_FLOOR) * hsv[2]
            val lifted = base * (1f + 0.10f * breath) * (1f + 0.22f * level)
            hsv[2] = lifted.coerceIn(0f, 1f)
            return Color.HSVToColor(hsv)
        }

        private fun sampleArea(source: Bitmap, u: Float, v: Float): Int {
            val left = ((u - 0.5f / cols) * source.width).toInt().coerceIn(0, source.width - 1)
            val right = ((u + 0.5f / cols) * source.width).toInt().coerceIn(left, source.width - 1)
            val top = ((v - 0.5f / rows) * source.height).toInt().coerceIn(0, source.height - 1)
            val bottom = ((v + 0.5f / rows) * source.height).toInt().coerceIn(top, source.height - 1)
            var r = 0L
            var g = 0L
            var b = 0L
            var n = 0
            for (y in top..bottom) {
                for (x in left..right) {
                    val pixel = source.getPixel(x, y)
                    r += Color.red(pixel)
                    g += Color.green(pixel)
                    b += Color.blue(pixel)
                    n += 1
                }
            }
            if (n == 0) return FALLBACK
            return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        }

        private fun ensureMesh(w: Int, h: Int) {
            // Cell sizes follow the reference app's grid rule: roughly screen/645 by
            // screen/1365, clamped to a small maximum. `detail` widens the grid.
            val density = detail.coerceIn(0.02f, 0.40f)
            val targetCols = (4 + (density / 0.40f) * 16f).toInt()
            val targetRows = (3 + (density / 0.40f) * 12f).toInt()
            if (mesh != null && cols == targetCols && rows == targetRows) return
            cols = targetCols.coerceIn(2, 24)
            rows = targetRows.coerceIn(2, 24)
            mesh = runCatching {
                Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
            }.getOrNull()
        }

        private fun ensureDither() {
            if (dither != null) return
            val size = 64
            val tile = runCatching { Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888) }
                .getOrNull() ?: return
            val pixels = IntArray(size * size)
            val bayer = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    // A few luminance steps of ordered noise: enough to break the 8-bit
                    // stair-steps on a large soft ramp, invisible as texture.
                    val value = bayer[(y % 4) * 4 + (x % 4)] * 4 / 15
                    pixels[y * size + x] = Color.argb(255, value, value, value)
                }
            }
            runCatching { tile.setPixels(pixels, 0, size, 0, 0, size, size) }
            ditherPaint.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            dither = tile
        }

        private companion object {
            const val FALLBACK = 0xFF2A2A33.toInt()
            val TWO_PI_THIRD = (2.0 * Math.PI / 3.0).toFloat()
        }
    }
}