package dev.amenhancer.module.hook

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import dev.amenhancer.module.ModuleConstants
import kotlin.math.sin

/**
 * Breathing player background, driven through the host's own background layer.
 *
 * LyricsBackgroundLayerView owns the blur pipeline (its own `b(...)` does the
 * blurring) and exposes `setArtwork(Bitmap)`. Instead of painting a layer of our
 * own — which the player content always covers — we feed it a small artwork
 * texture a few times per second, so the host blurs and composites it with its
 * own animation. Cost stays low because the source stays at 128 px.
 */
internal object BackgroundPulse {
    private const val ENABLED = true
    private const val BACKGROUND_LAYER = "background_layers"
    private const val ARTWORK_ID = "fullplayerSongImage"
    private const val UPDATE_MS = 200L
    private const val TEXTURE_EDGE = 128

    @Volatile private var pulse = 0f
    /** The host's own cover bitmap, captured from its setArtwork call. */
    @Volatile private var hostTexture: Bitmap? = null
    private val writing = ThreadLocal.withInitial { false }
    @Volatile private var captureHooked = false
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var attached: View? = null

    fun bump() {
        pulse = 1f
    }

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity.packageName != ModuleConstants.TARGET_PACKAGE) return
                if (!ENABLED) return
                val decor = activity.window?.decorView ?: return
                decor.viewTreeObserver.addOnGlobalLayoutListener { attach(activity, decor) }
            }

            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun attach(activity: Activity, decor: View) = runCatching { attachUnsafe(activity, decor) }
        .onFailure { ModernXposedRuntime.log("background pulse: attach failed: $it") }

    private fun attachUnsafe(activity: Activity, decor: View) {
        val id = decor.resources.getIdentifier(BACKGROUND_LAYER, "id", ModuleConstants.TARGET_PACKAGE)
        if (id == 0) return
        val layer = decor.findViewById<View>(id) ?: return
        if (!layer.isShown || layer.width <= 0) return
        if (layer === attached) return

        val setArtwork = runCatching {
            layer.javaClass.getMethod("setArtwork", Bitmap::class.java)
        }.getOrNull() ?: run {
            ModernXposedRuntime.log("background pulse: setArtwork unavailable on ${layer.javaClass.name}")
            return
        }

        attached = layer
        installCaptureHook(setArtwork)
        val animators = reflectAnimators(layer)
        // The host leaves its own breathing animators stopped until it decides
        // to run them; starting them is what makes the background move.
        runCatching { animators.forEach { if (!it.isRunning) it.start() } }
        ModernXposedRuntime.log(
            "background pulse: driving ${layer.javaClass.simpleName} ${layer.width}x${layer.height}" +
                " animators=${animators.size}",
        )

        val startedAt = SystemClock.uptimeMillis()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var base: Bitmap? = null

        val tick = object : Runnable {
            override fun run() {
                if (layer.parent == null || !layer.isShown) {
                    if (attached === layer) attached = null
                    return
                }
                if (pulse > 0.5f) kickAnimators(layer)
                val captured = hostTexture
                if (captured != null) base = captured
                if (base == null) base = findArtwork(activity, decor)
                val source = base
                if (source != null) {
                    val seconds = (SystemClock.uptimeMillis() - startedAt) / 1000f
                    val kick = pulse
                    pulse = (pulse * 0.80f).coerceAtLeast(0f)
                    runCatching {
                        val frame = renderFrame(source, seconds, kick, paint)
                        writing.set(true)
                        try {
                            setArtwork.invoke(layer, frame)
                        } finally {
                            writing.set(false)
                        }
                    }
                }
                handler.postDelayed(this, UPDATE_MS)
            }
        }
        handler.post(tick)
    }

    /** H/L/M are the host's own breathing animators; they are what actually moves. */
    private fun reflectAnimators(layer: View): List<android.animation.ValueAnimator> {
        val out = ArrayList<android.animation.ValueAnimator>(3)
        for (name in listOf("H", "L", "M")) {
            val field = runCatching { layer.javaClass.getField(name) }.getOrNull() ?: continue
            val value = runCatching { field.get(layer) }.getOrNull() as? android.animation.ValueAnimator ?: continue
            out += value
            ModernXposedRuntime.log(
                "background pulse: animator $name running=${value.isRunning} duration=${value.duration}" +
                    " repeat=${value.repeatCount}",
            )
        }
        for (name in listOf("a", "a0", "W", "b0", "c0")) {
            val field = runCatching { layer.javaClass.getField(name) }.getOrNull() ?: continue
            val value = runCatching { field.getFloat(layer) }.getOrNull() ?: continue
            ModernXposedRuntime.log("background pulse: field $name=$value")
        }
        return out
    }

    /** Restarts the host's own pulse; only called when a lyric line lands. */
    private fun kickAnimators(layer: View) {
        runCatching {
            for (name in listOf("H", "L", "M")) {
                val field = layer.javaClass.getField(name)
                val animator = field.get(layer) as? android.animation.ValueAnimator ?: continue
                if (!animator.isRunning) animator.start() else animator.start()
            }
        }
    }

    /**
     * The host hands its own cover to the background layer; that bitmap is the
     * only reliable coloured source (the slot itself renders through a
     * transition surface). Capture it, then feed our variant back in.
     */
    private fun installCaptureHook(method: java.lang.reflect.Method) {
        if (captureHooked) return
        captureHooked = true
        runCatching {
            ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (writing.get() == true) return
                    val bitmap = param.args.firstOrNull() as? Bitmap ?: return
                    if (bitmap.width < 8 || bitmap.height < 8) return
                    if (!hasColour(bitmap)) return
                    hostTexture = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
                }
            })
            ModernXposedRuntime.log("background pulse: setArtwork capture hook installed")
        }.onFailure { ModernXposedRuntime.log("background pulse: capture hook failed: $it") }
    }

    /** Small wobble + optional brightness kick; the host blurs this further. */
    private fun renderFrame(source: Bitmap, seconds: Float, kick: Float, paint: Paint): Bitmap {
        val bitmap = Bitmap.createBitmap(TEXTURE_EDGE, TEXTURE_EDGE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val drift = sin(seconds * 0.55f) * 22f
        val sway = sin(seconds * 0.37f + 1.1f) * 18f
        canvas.drawBitmap(
            source,
            null,
            Rect(
                (drift - 8f).toInt(),
                (sway - 8f).toInt(),
                TEXTURE_EDGE + (8f - drift).toInt(),
                TEXTURE_EDGE + (8f - sway).toInt(),
            ),
            paint,
        )
        // Slow brightness breath so the field visibly inhales even without a kick.
        val breath = 0.5f - 0.5f * kotlin.math.cos(seconds * 2f * Math.PI.toFloat() * 0.35f)
        val dim = (0.10f * (1f - breath)).coerceIn(0f, 0.10f)
        if (dim > 0.005f) {
            paint.colorFilter = PorterDuffColorFilter(
                Color.argb((dim * 255).toInt(), 0, 0, 0),
                PorterDuff.Mode.SRC_ATOP,
            )
            canvas.drawRect(0f, 0f, TEXTURE_EDGE.toFloat(), TEXTURE_EDGE.toFloat(), paint)
            paint.colorFilter = null
        }
        if (kick > 0.01f) {
            paint.colorFilter = PorterDuffColorFilter(
                Color.argb((kick * 90).toInt().coerceIn(0, 255), 255, 255, 255),
                PorterDuff.Mode.SRC_ATOP,
            )
            canvas.drawRect(0f, 0f, TEXTURE_EDGE.toFloat(), TEXTURE_EDGE.toFloat(), paint)
            paint.colorFilter = null
        }
        return bitmap
    }

    /**
     * The artwork slot renders through a transition surface, so its pixels are
     * taken from the window itself: PixelCopy over the slot rect is the only
     * way to get the real cover (and its colour field) rather than a blank card.
     */
    private fun captureFromWindow(activity: Activity, slot: View): Bitmap? {
        val window = activity.window ?: return null
        val location = IntArray(2)
        slot.getLocationInWindow(location)
        val rect = android.graphics.Rect(
            location[0].coerceAtLeast(0),
            location[1].coerceAtLeast(0),
            (location[0] + slot.width).coerceAtMost(window.decorView.width),
            (location[1] + slot.height).coerceAtMost(window.decorView.height),
        )
        if (rect.width() <= 0 || rect.height() <= 0) return null
        val raw = runCatching {
            Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        runCatching {
            android.view.PixelCopy.request(window, rect, raw, { result ->
                ok = result == android.view.PixelCopy.SUCCESS
                latch.countDown()
            }, handler)
        }.onFailure { latch.countDown() }
        runCatching { latch.await(600, java.util.concurrent.TimeUnit.MILLISECONDS) }
        if (!ok) return null
        return runCatching {
            Bitmap.createScaledBitmap(raw, 32, 32, true).also { raw.recycle() }
        }.getOrNull()
    }

    private fun findArtwork(activity: Activity, root: View): Bitmap? {
        val id = root.resources.getIdentifier(ARTWORK_ID, "id", ModuleConstants.TARGET_PACKAGE)
        val slot = if (id != 0) root.findViewById<View>(id) else null
        if (slot != null && slot.isShown) {
            captureFromWindow(activity, slot)?.let { if (hasColour(it)) return it }
        }
        val candidates = ArrayList<View>(4)
        if (slot != null) {
            candidates += slot
            if (slot is ViewGroup) {
                val pending = ArrayDeque<View>()
                pending.addLast(slot)
                while (pending.isNotEmpty()) {
                    val child = pending.removeFirst()
                    if (child is ImageView && child.drawable != null) candidates += child
                    if (child is ViewGroup) for (i in 0 until child.childCount) pending.addLast(child.getChildAt(i))
                }
            }
        }
        for (candidate in candidates) {
            val bitmap = sampleArtwork(candidate) ?: continue
            if (hasColour(bitmap)) return bitmap
        }
        return null
    }

    private fun sampleArtwork(view: View): Bitmap? {
        if (view.width <= 0 || view.height <= 0) return null
        return runCatching {
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.scale(32f / view.width, 32f / view.height)
            view.draw(canvas)
            bitmap
        }.getOrNull()
    }

    /** Rejects grey placeholders: the texture must actually carry chroma. */
    private fun hasColour(bitmap: Bitmap): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var chroma = 0L
        var luma = 0L
        var minLuma = 255
        var maxLuma = 0
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            chroma += maxOf(r, g, b) - minOf(r, g, b)
            val l = (r + g + b) / 3
            luma += l
            if (l < minLuma) minLuma = l
            if (l > maxLuma) maxLuma = l
        }
        val count = pixels.size.coerceAtLeast(1)
        return chroma / count >= 10 && maxLuma - minLuma >= 20
    }
}
