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
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.Window
import dev.amenhancer.module.ModuleConstants
import java.lang.ref.WeakReference
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Self-drawn glow layer for the player page.
 *
 * Sits between the host's own blurred artwork and the player content: inserted into
 * `player_root` directly after `background_layers`, with z kept at 0. The scan that
 * established this position is documented in `AMPP-insertion-scan.md`; the short
 * version is that the host paints siblings in Z order first and child order second,
 * and the dual-pane wrapper carries `translationZ = 1.0`, so elevation on this view
 * would push the glow above the lyrics.
 *
 * The layer owns no bitmap and never touches the host's artwork pipeline: it draws a
 * few soft radial blobs tinted by the current cover, keeps a slow ambient breath, and
 * lifts them for a moment whenever a lyric line changes.
 */
internal object GlowLayer {
    private const val TAG = "player glow"
    private const val PLAYER_ROOT = "player_root"
    private const val BACKGROUND_LAYER = "background_layers"
    private const val FRAME_MS = 50L
    private const val IDLE_FRAME_MS = 160L
    private const val AMBIENT_ALPHA = 0.07f
    private const val PULSE_ALPHA = 0.50f
    private const val PULSE_ATTACK_MS = 70f
    private const val PULSE_DECAY_MS = 260f
    private const val BREATH_PERIOD_MS = 7_400f
    private const val TEST_ACTION = "dev.ampp.glow.TEST"

    private val handler = Handler(Looper.getMainLooper())
    private val installed = java.util.WeakHashMap<Application, Boolean>()
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)

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

    private val frame = object : Runnable {
        override fun run() {
            val view = attached ?: return stopLooping()
            val root = rootRef.get()
            if (root == null || !view.isAttachedToWindow || !view.isShown) return stopLooping()
            if (!enabled()) {
                view.alpha = 0f
                pulsePeak = 0f
                return stopLooping()
            }
            val now = SystemClock.uptimeMillis()
            val energy = energyAt(now)
            val playing = musicActive(view.context)
            val breath = if (playing) 0.5f + 0.5f * sin(now / BREATH_PERIOD_MS) else 0f
            view.level = (if (playing) AMBIENT_ALPHA * breath else 0f) + PULSE_ALPHA * energy
            view.warm = coverColor()
            if (view.level <= 0.004f) {
                view.alpha = 0f
            } else {
                view.alpha = 1f
                view.invalidate()
            }
            // A pulse needs 20 fps to read as a flash; the slow breath does not, and idling
            // at 6 fps keeps the layer cheap while the player page is open but quiet.
            handler.postDelayed(this, if (energy > 0f) FRAME_MS else IDLE_FRAME_MS)
        }
    }

    fun install(application: Application) {
        if (installed.put(application, true) != null) return
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity.packageName != ModuleConstants.TARGET_PACKAGE) return
                val window = activity.window ?: return
                val decor = window.decorView ?: return
                // The player page can be inflated later than resume; one layout pass is
                // enough for player_root to exist, and the router re-attaches on the next.
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

    /** Line change: a soft lift, independent of the caller's notion of strength scale. */
    fun bump() = pulse(0.72f)

    fun pulse(strength: Float) {
        if (strength <= 0.01f) return
        if (!enabled()) {
            ModernXposedRuntime.log("$TAG: pulse ignored, setting off")
            return
        }
        val view = attached ?: return
        if (!view.isAttachedToWindow) return
        pulseAt = SystemClock.uptimeMillis()
        pulsePeak = strength.coerceIn(0f, 1f)
        if (pulseLogs < 8) {
            pulseLogs += 1
            ModernXposedRuntime.log("$TAG: pulse #$pulseLogs strength=$strength playing=${musicActive(view.context)}")
        }
        if (!looping) {
            looping = true
            handler.post(frame)
        }
    }

    private fun status(): String {
        val view = attached
        val root = rootRef.get()
        val background = root?.let { r -> r.findViewById<View>(idOf(r, BACKGROUND_LAYER)) }
        return "attached=${view != null}" +
            " background=${background?.width}x${background?.height}" +

            " window=${view?.isAttachedToWindow}" +
            " shown=${view?.isShown}" +
            " size=${view?.width}x${view?.height}" +
            " enabled=${enabled()}" +
            " looping=$looping" +
            " pulseAt=$pulseAt holdUntil=$holdUntil"
    }

    private fun enabled(): Boolean = runCatching {
        dev.amenhancer.module.config.TargetConfigClient.currentSettings().lyricLineFlashEnabled
    }.getOrDefault(false)

    private fun energyAt(now: Long): Float {
        val at = pulseAt
        if (at == 0L) return 0f
        val elapsed = (now - at).toFloat()
        if (elapsed < 0f) return 0f
        if (elapsed < PULSE_ATTACK_MS) return pulsePeak * (elapsed / PULSE_ATTACK_MS)
        val decayed = pulsePeak * exp(-(elapsed - PULSE_ATTACK_MS) / decayMs)
        val held = if (now < holdUntil) holdLevel else 0f
        val value = maxOf(if (decayed < 0.004f) 0f else decayed, held)
        return value
    }

    private fun musicActive(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.isMusicActive == true
    }.getOrDefault(false)

    /** Average of the cover's most saturated pixels, pushed towards a warm tint. */
    private fun coverColor(): Int {
        val bitmap = BackgroundPulse.coverTexture() ?: return DEFAULT_WARM
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return DEFAULT_WARM
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var weight = 0L
        val stepX = (width / 8).coerceAtLeast(1)
        val stepY = (height / 8).coerceAtLeast(1)
        val hsv = FloatArray(3)
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val pixel = bitmap.getPixel(x, y)
                Color.colorToHSV(pixel, hsv)
                // Dark and grey cover areas say little about the palette; skip them.
                val w = (hsv[1] * 255f).toInt() + (hsv[2] * 90f).toInt()
                if (w > 40) {
                    sumR += Color.red(pixel) * w
                    sumG += Color.green(pixel) * w
                    sumB += Color.blue(pixel) * w
                    weight += w
                }
                x += stepX
            }
            y += stepY
        }
        if (weight == 0L) return DEFAULT_WARM
        val average = Color.rgb(
            (sumR / weight).toInt(),
            (sumG / weight).toInt(),
            (sumB / weight).toInt(),
        )
        Color.colorToHSV(average, hsv)
        // Keep the hue, but lift value and tame saturation: a washed-out glow reads as light
        // rather than as a coloured stain on top of the host's own blurred backdrop.
        hsv[1] = (hsv[1] * 0.55f).coerceIn(0.10f, 0.55f)
        hsv[2] = 1f
        return Color.HSVToColor(hsv)
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
        // elevation here would lift the glow over the lyric text.
        view.elevation = 0f
        view.translationZ = 0f
        view.alpha = 0f
        rootRef = WeakReference(root)
        watch(root)
        if (!looping && enabled()) {
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

    private fun idOf(view: View, name: String): Int = runCatching {
        view.resources.getIdentifier(name, "id", ModuleConstants.TARGET_PACKAGE)
    }.getOrDefault(0)

    private fun describeChildren(root: ViewGroup): String = buildString {
        for (i in 0 until root.childCount) {
            if (i > 0) append(", ")
            append('#').append(i).append(' ').append(idName(root.getChildAt(i)))
        }
    }

    private fun idName(view: View): String = runCatching {
        view.resources.getResourceEntryName(view.id)
    }.getOrDefault(view.id.toString())

    /** Test hook: `adb shell am broadcast -a dev.ampp.glow.TEST --ef strength 1.0`. */
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
                ModernXposedRuntime.log(
                    "$TAG: test pulse strength=$strength holdMs=$holdMs decayMs=$decayMs" +
                        " ${status()}",
                )
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

    private const val DEFAULT_WARM = 0xFFFFE7C8.toInt()

    /**
     * Plain View: no background, no touch handling. A sibling that does not consume the
     * event lets the host try the next child, so taps still reach the player controls.
     */
    private class GlowView(context: Context) : View(context) {
        var level: Float = 0f
        var warm: Int = 0xFFFFE7C8.toInt()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var shaders: Array<RadialGradient>? = null
        private var builtWidth = -1f
        private var builtHeight = -1f
        private var builtTint = -1

        /** Normalised to the shorter edge, so portrait and the tablet dual pane share framing. */
        private val SPOTS = arrayOf(
            Spot(0.20f, 0.27f, 0.21f, 1.00f),
            Spot(0.47f, 0.50f, 0.25f, 1.15f),
            Spot(0.78f, 0.30f, 0.19f, 0.95f),
            Spot(0.63f, 0.83f, 0.22f, 0.80f),
            Spot(0.30f, 0.79f, 0.17f, 0.70f),
        )

        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            setWillNotDraw(false)
        }

        override fun onDraw(canvas: Canvas) {
            if (level <= 0.004f) return
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            ensureGradients(w, h)
            val gradients = shaders ?: return
            // A wide, very faint halo ties the spots together without reading as a flat
            // brightening of the whole page.
            drawWith(canvas, gradients[0], w * 0.46f, h * 0.54f, minOf(w, h) * 0.95f, level * 0.22f)
            for (index in SPOTS.indices) {
                val spot = SPOTS[index]
                val strength = level * spot.strength
                if (strength <= 0.004f) continue
                val radius = minOf(w, h) * (spot.radius + 0.03f * level)
                drawWith(canvas, gradients[index + 1], w * spot.x, h * spot.y, radius, strength)
            }
        }

        /**
         * Shaders are rebuilt only when the layer size or the cover palette changes: a
         * RadialGradient per frame would allocate ~120 objects per second for no visual gain.
         */
        private fun ensureGradients(w: Float, h: Float) {
            val bucket = tintBucket(warm)
            if (shaders != null && builtWidth == w && builtHeight == h && builtTint == bucket) return
            val edge = minOf(w, h)
            val core = blend(warm, 0.55f)
            val list = ArrayList<RadialGradient>(SPOTS.size + 1)
            list += gradient(w * 0.46f, h * 0.54f, edge * 0.95f, core, warm)
            SPOTS.forEach { spot ->
                list += gradient(w * spot.x, h * spot.y, edge * (spot.radius + 0.03f), core, warm)
            }
            shaders = list.toTypedArray()
            builtWidth = w
            builtHeight = h
            builtTint = bucket
        }

        private fun gradient(cx: Float, cy: Float, radius: Float, core: Int, tint: Int): RadialGradient =
            RadialGradient(
                cx, cy, radius,
                intArrayOf(
                    Color.argb(255, Color.red(core), Color.green(core), Color.blue(core)),
                    Color.argb(140, Color.red(core), Color.green(core), Color.blue(core)),
                    Color.argb(56, Color.red(tint), Color.green(tint), Color.blue(tint)),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.30f, 0.60f, 1f),
                Shader.TileMode.CLAMP,
            )

        private fun drawWith(canvas: Canvas, shader: RadialGradient, cx: Float, cy: Float, radius: Float, strength: Float) {
            val alpha = (strength * 255f).toInt().coerceIn(0, 255)
            if (alpha <= 0) return
            blobPaint.shader = shader
            blobPaint.alpha = alpha
            canvas.drawCircle(cx, cy, radius, blobPaint)
        }

        /** Mixes the cover tint towards warm white. */
        private fun blend(tint: Int, whiteShare: Float): Int {
            val keep = 1f - whiteShare
            return Color.rgb(
                (Color.red(tint) * keep + 255f * whiteShare).toInt().coerceIn(0, 255),
                (Color.green(tint) * keep + 245f * whiteShare).toInt().coerceIn(0, 255),
                (Color.blue(tint) * keep + 232f * whiteShare).toInt().coerceIn(0, 255),
            )
        }

        private fun tintBucket(color: Int): Int =
            (Color.red(color) shr 2 shl 16) or (Color.green(color) shr 2 shl 8) or (Color.blue(color) shr 2)

        private data class Spot(val x: Float, val y: Float, val radius: Float, val strength: Float)

        private fun drawBlob(canvas: Canvas, cx: Float, cy: Float, radius: Float, strength: Float, tint: Int) {
            if (radius <= 0f || strength <= 0.004f) return
            val alpha = (strength * 255f).toInt().coerceIn(0, 255)
            if (alpha <= 0) return
            val coreR = (Color.red(tint) * 0.55f + 255f * 0.45f).toInt().coerceIn(0, 255)
            val coreG = (Color.green(tint) * 0.55f + 245f * 0.45f).toInt().coerceIn(0, 255)
            val coreB = (Color.blue(tint) * 0.55f + 232f * 0.45f).toInt().coerceIn(0, 255)
            blobPaint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(
                    Color.argb(alpha, coreR, coreG, coreB),
                    Color.argb((alpha * 0.55f).toInt(), coreR, coreG, coreB),
                    Color.argb((alpha * 0.22f).toInt(), Color.red(tint), Color.green(tint), Color.blue(tint)),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.30f, 0.60f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, radius, blobPaint)
        }
    }
}