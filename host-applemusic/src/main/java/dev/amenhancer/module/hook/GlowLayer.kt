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
import kotlin.math.cos
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
    private const val VALUE_FLOOR = 0.25f
    private const val VALUE_CEILING = 0.46f
    /** Share of the cover's dominant colour mixed into every cell, so the field stays coherent. */
    private const val DOMINANT_MIX = 0.75f
    /** Corner darkening, matched to the reference backdrop's radial falloff. */
    private const val VIGNETTE = 0.18f

    private val handler = Handler(Looper.getMainLooper())
    private val installed = java.util.WeakHashMap<Application, Boolean>()

    @Volatile private var attached: GlowView? = null
    @Volatile private var rootRef: WeakReference<ViewGroup> = WeakReference(null)
    @Volatile private var sheetRef: WeakReference<View> = WeakReference(null)
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
        val aurora: Boolean,
    )

    @Volatile private var config = GlowConfig(false, 1f, 0.18f, 7_000f, false)
    /** Test-only override so a style can be inspected without walking the settings UI. */
    @Volatile private var forceAurora: Boolean? = null
    @Volatile private var configAt = 0L

    private val frame = object : Runnable {
        override fun run() {
            val view = attached ?: return stopLooping()
            val root = rootRef.get()
            if (root == null || !view.isAttachedToWindow || !view.isShown) {
                // Leaving alpha at 1 here painted the whole collapsed mini-player bar.
                view.alpha = 0f
                return stopLooping()
            }
            val config = config()
            if (!config.on || !expanded(root)) {
                // The collapsed sheet is only the mini player bar; painting the wash there
                // leaves a full-width colour band over the page behind it.
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
            view.sheet = sheetRef.get()
            view.aurora = config.aurora
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

    /** True while the player sheet covers the page rather than sitting in the mini bar. */
    private fun expanded(root: ViewGroup): Boolean {
        val metrics = root.resources.displayMetrics
        val page = maxOf(metrics.widthPixels, metrics.heightPixels)
        val sheet = sheetRef.get()
        if (sheet != null) return sheet.height >= page * 0.4f && sheet.isShown
        return root.height >= page * 0.5f
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
            aurora = forceAurora ?: (settings?.lyricGlowAuroraEnabled == true),
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
        sheetRef = WeakReference(decor.findViewById<View>(idOf(decor, "player_sheet_container")))
        watch(root)
        if (!looping && config().on && expanded(root)) {
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
            if (sizeChanged) {
                val group = view as ViewGroup
                if (!expanded(group)) attached?.alpha = 0f
                view.post { runCatching { attachUnsafe(group) } }
            }
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
        view.alpha = 0f
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
                
                
                
                if (intent.hasExtra("forceAurora")) {
                    forceAurora = intent.getBooleanExtra("forceAurora", false)
                    configAt = 0L
                    ModernXposedRuntime.log("$TAG: forceAurora=$forceAurora")
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
        var aurora: Boolean = false
        /** The sheet that clips this layer: when it is only the mini-player bar, draw nothing. */
        var sheet: View? = null
        private var curtain: Bitmap? = null

        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            blendMode = android.graphics.BlendMode.PLUS
        }
        /** One unit-radius soft blob, reused for every ribbon sample (no per-frame allocation). */
        private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            blendMode = android.graphics.BlendMode.PLUS
            shader = android.graphics.RadialGradient(
                0f, 0f, 1f,
                intArrayOf(
                    Color.argb(255, 255, 255, 255),
                    Color.argb(190, 255, 255, 255),
                    Color.argb(120, 255, 255, 255),
                    Color.argb(55, 255, 255, 255),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        private val ditherPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            blendMode = android.graphics.BlendMode.PLUS
        }
        private var mesh: Bitmap? = null
        private var cols = 0
        private var rows = 0
        private var dither: Bitmap? = null
        private var startedAt = 0L
        private var dominant: Int = FALLBACK
        private var dominantKey: Int = 0
        private var dominantSource: Bitmap? = null

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
            // Collapsed sheet: the layer keeps its full layout size but is clipped to the mini
            // player bar, so the clip — not this view's own height — is what has to be checked.
            // What matters is how much of this layer actually reaches the screen: a collapsed
            // sheet clips it to the mini-player bar even though the view keeps full size.
            val visible = android.graphics.Rect()
            if (!getGlobalVisibleRect(visible)) return
            if (height > 0 && visible.height() < height * 0.35f) return
            if (startedAt == 0L) startedAt = SystemClock.uptimeMillis()
            // Drawing before the host's cover arrives would flash the neutral fallback and make
            // the colour jump on the first lyric line; wait for real artwork instead.
            if (cover == null) return
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
            if (aurora) drawAurora(canvas, w, h, now)
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
            val dominant = dominantColor()
            val mixed = mix(sampled, dominant, DOMINANT_MIX)
            val hsv = FloatArray(3)
            Color.colorToHSV(mixed, hsv)
            // Slow drift: each cell breathes on its own phase so the wash never looks frozen.
            val phase = (col * 0.7f + row * 1.3f) * TWO_PI_THIRD
            val drift = sin(nowMs / 9_000f + phase) * (if (breathing) 0.035f else 0.012f)
            hsv[0] = (hsv[0] + drift * 220f + 360f) % 360f
            hsv[1] = (hsv[1] * 1.30f * (0.95f + 0.05f * breath)).coerceIn(0f, 1f)
            val base = VALUE_FLOOR + (VALUE_CEILING - VALUE_FLOOR) * hsv[2]
            // Very small energy lift: the reference backdrop only breathes, it never flashes.
            val radial = ((u - 0.5f) * (u - 0.5f) + (v - 0.5f) * (v - 0.5f)) / 0.5f
            val shade = 1f - VIGNETTE * radial.coerceIn(0f, 1f)
            val lifted = base * (1f + 0.02f * breath) * (1f + 0.03f * level) * shade
            hsv[2] = lifted.coerceIn(0f, 1f)
            return Color.HSVToColor(hsv)
        }

        /**
         * The artwork's overall colour, weighted towards its saturated pixels. Mixing every
         * cell towards it is what turns a literal per-cell sample (blotchy) into the single
         * coherent wash the reference backdrop shows.
         */
        private fun dominantColor(): Int {
            val source = cover ?: return FALLBACK
            // Key on the artwork's own colour, not on the bitmap instance: the host hands a
            // fresh bitmap after every resume, which used to produce a slightly different
            // palette each time the page was reopened.
            val key = quantizedKey(source)
            if (key == dominantKey && dominant != FALLBACK) return dominant
            var r = 0L
            var g = 0L
            var b = 0L
            var weight = 0L
            val hsv = FloatArray(3)
            val stepX = (source.width / 24).coerceAtLeast(1)
            val stepY = (source.height / 24).coerceAtLeast(1)
            var y = 0
            while (y < source.height) {
                var x = 0
                while (x < source.width) {
                    val pixel = source.getPixel(x, y)
                    Color.colorToHSV(pixel, hsv)
                    val w = (40 + (hsv[1] * 200f).toInt()).toLong()
                    r += Color.red(pixel) * w
                    g += Color.green(pixel) * w
                    b += Color.blue(pixel) * w
                    weight += w
                    x += stepX
                }
                y += stepY
            }
            dominant = if (weight == 0L) FALLBACK else Color.rgb(
                (r / weight).toInt().coerceIn(0, 255),
                (g / weight).toInt().coerceIn(0, 255),
                (b / weight).toInt().coerceIn(0, 255),
            )
            dominantKey = key
            return dominant
        }

        private fun quantizedKey(source: Bitmap): Int {
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            val stepX = (source.width / 8).coerceAtLeast(1)
            val stepY = (source.height / 8).coerceAtLeast(1)
            var y = 0
            while (y < source.height) {
                var x = 0
                while (x < source.width) {
                    val p = source.getPixel(x, y)
                    r += Color.red(p); g += Color.green(p); b += Color.blue(p); n += 1
                    x += stepX
                }
                y += stepY
            }
            if (n == 0) return 0
            val rr = (r / n).toInt() shr 2
            val gg = (g / n).toInt() shr 2
            val bb = (b / n).toInt() shr 2
            return (rr shl 12) or (gg shl 6) or bb
        }

        private fun mix(from: Int, to: Int, amount: Float): Int {
            val keep = 1f - amount
            return Color.rgb(
                (Color.red(from) * keep + Color.red(to) * amount).toInt().coerceIn(0, 255),
                (Color.green(from) * keep + Color.green(to) * amount).toInt().coerceIn(0, 255),
                (Color.blue(from) * keep + Color.blue(to) * amount).toInt().coerceIn(0, 255),
            )
        }

        /**
         * Scheme B: vertical aurora curtains. Each ribbon rises from the bottom edge, sways on
         * an incommensurate pair of sine waves and fades towards its top, so the motion never
         * loops visibly and never paints a hard edge.
         */
        /**
         * Scheme B: soft light ribbons that travel on curves across the whole page. Each ribbon
         * is a chain of overlapping soft blobs along a sine centreline; the centreline's base
         * height, amplitude and frequency all drift on incommensurate rates, so the light wanders
         * over the screen instead of only sliding along the bottom edge.
         */
        private fun drawAurora(canvas: Canvas, w: Int, h: Int, nowMs: Long) {
            val t = nowMs / 1000f
            val tint = auroraTint()
            ballPaint.colorFilter = android.graphics.PorterDuffColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_IN)
            val edge = minOf(w, h).toFloat()
            for (ribbon in 0 until AURORA_RIBBONS) {
                val phase = ribbon * 2.1f
                val baseY = h * (0.5f + 0.40f * sin(t * 0.13f + phase))
                val amp = h * (0.10f + 0.07f * sin(t * 0.09f + phase * 1.7f))
                val freq = 1.1f + 0.6f * sin(t * 0.07f + phase * 0.6f)
                val drift = 0.18f * sin(t * 0.11f + phase * 1.3f)
                val radius = edge * (0.10f + 0.02f * sin(t * 0.19f + phase))
                for (step in 0..AURORA_STEPS) {
                    val u = step / AURORA_STEPS.toFloat()
                    val x = w * (u + drift).coerceIn(0.02f, 0.98f)
                    val y = (baseY + amp * sin(u * TWO_PI * freq + t * 0.23f + phase * 1.9f))
                        .coerceIn(h * 0.05f, h * 0.95f)
                    val travel = 0.5f + 0.5f * sin(u * TWO_PI * 1.6f - t * 0.37f + phase)
                    val edgeFade = 1f - 0.55f * kotlin.math.abs(u - 0.5f) * 2f
                    val alpha = ((0.05f + 0.06f * travel + 0.10f * level) * edgeFade).coerceIn(0f, 0.30f)
                    if (alpha <= 0.004f) continue
                    canvas.save()
                    canvas.translate(x, y)
                    canvas.scale(radius, radius)
                    ballPaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
                    canvas.drawCircle(0f, 0f, 1f, ballPaint)
                    canvas.restore()
                }
            }
        }

        /**
         * Bell curve across, soft fade up: the ribbon has no hard edges to catch the eye.
         * Built once; the per-frame cost is one bitmap blit per curtain.
         */
        private fun curtainMask(): Bitmap? {
            curtain?.let { return it }
            val mw = 96
            val mh = 160
            val bitmap = runCatching { Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
            val pixels = IntArray(mw * mh)
            for (y in 0 until mh) {
                val v = y.toFloat() / (mh - 1)
                val vertical = (v * v * (3f - 2f * v)) * 0.9f + 0.1f
                for (x in 0 until mw) {
                    val u = x.toFloat() / (mw - 1)
                    val horizontal = sin(Math.PI * u).toFloat()
                    val bell = horizontal * horizontal
                    val a = (bell * vertical * 255f).toInt().coerceIn(0, 255)
                    pixels[y * mw + x] = Color.argb(a, 255, 255, 255)
                }
            }
            runCatching { bitmap.setPixels(pixels, 0, mw, 0, 0, mw, mh) }
            curtain = bitmap
            return bitmap
        }

        private fun auroraTint(): Int {
            val source = cover ?: return 0xFF8FB6E8.toInt()
            return sampleArea(source, 0.35f, 0.4f)
        }

        private fun withAlpha(color: Int, alpha: Float): Int =
            Color.argb((alpha * 255f).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

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
            val size = 128
            val tile = runCatching { Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888) }
                .getOrNull() ?: return
            val pixels = IntArray(size * size)
            // Random (not ordered) noise at ±6 levels: a 4x4 Bayer pattern still leaves visible
            // contour lines on a slow 8-bit ramp, random noise does not.
            val random = java.util.Random(0x5EED)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val value = random.nextInt(7)
                    pixels[y * size + x] = Color.argb(255, value, value, value)
                }
            }
            runCatching { tile.setPixels(pixels, 0, size, 0, 0, size, size) }
            ditherPaint.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            dither = tile
        }

        private companion object {
            const val AURORA_RIBBONS = 3
            const val AURORA_STEPS = 26
            const val FALLBACK = 0xFF2A2A33.toInt()
            val TWO_PI_THIRD = (2.0 * Math.PI / 3.0).toFloat()
        }
    }
}