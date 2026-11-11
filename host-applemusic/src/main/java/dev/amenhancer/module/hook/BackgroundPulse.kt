package dev.amenhancer.module.hook

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import java.lang.reflect.Method

/**
 * Lyric-line flash for the player background.
 *
 * The host owns the blur pipeline: `LyricsBackgroundLayerView#setArtwork(Bitmap)`
 * is fed a copy of its own cover with a small radial glow added on top, so the
 * blur turns that glow into a soft local pulse. The base background keeps
 * whatever the host renders — this only adds a short flash when a lyric line
 * changes, and never runs while playback is paused.
 */
internal object BackgroundPulse {
    private const val ENABLED = true
    private const val BACKGROUND_LAYER = "background_layers"
    private const val TEXTURE_EDGE = 128
    private const val RESTORE_MS = 120L

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var attached: View? = null
    @Volatile private var artworkMethod: Method? = null
    @Volatile private var hostTexture: Bitmap? = null
    private val writing = ThreadLocal.withInitial { false }
    @Volatile private var captureHooked = false

    /**
     * Single entry point for every pulse source: the lyric-line trigger today,
     * a beat detector later. [strength] is 0..1 and only scales brightness.
     */
    fun pulse(strength: Float) {
        if (!ENABLED) return
        val layer = attached ?: return
        val method = artworkMethod ?: return
        val base = hostTexture ?: return
        if (!musicActive(layer.context)) return
        flash(layer, method, base, strength.coerceIn(0f, 1f))
    }

    /** Called when the highlighted lyric line changes. */
    fun bump() {
        if (!ENABLED) return
        if (!runCatching { dev.amenhancer.module.config.TargetConfigClient.currentSettings().lyricLineFlashEnabled }
                .getOrDefault(true)) return
        val layer = attached ?: return
        val method = artworkMethod ?: return
        val base = hostTexture ?: return
        pulse(0.7f)
    }

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity.packageName != dev.amenhancer.module.ModuleConstants.TARGET_PACKAGE) return
                val decor = activity.window?.decorView ?: return
                decor.viewTreeObserver.addOnGlobalLayoutListener { attach(decor) }
            }

            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun attach(decor: View) = runCatching { attachUnsafe(decor) }
        .onFailure { ModernXposedRuntime.log("background pulse: attach failed: $it") }

    private fun attachUnsafe(decor: View) {
        val id = decor.resources.getIdentifier(BACKGROUND_LAYER, "id", dev.amenhancer.module.ModuleConstants.TARGET_PACKAGE)
        if (id == 0) return
        val layer = decor.findViewById<View>(id) ?: return
        if (!layer.isShown || layer.width <= 0) return
        if (layer === attached) return
        val method = runCatching {
            layer.javaClass.getMethod("setArtwork", Bitmap::class.java)
        }.getOrNull() ?: return
        attached = layer
        artworkMethod = method
        installCaptureHook(method)
        ModernXposedRuntime.log("background pulse: ready on ${layer.javaClass.simpleName} ${layer.width}x${layer.height}")
    }

    private fun musicActive(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.isMusicActive == true
    }.getOrDefault(false)

    private fun installCaptureHook(method: Method) {
        if (captureHooked) return
        captureHooked = true
        runCatching {
            ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (writing.get() == true) return
                    val bitmap = param.args.firstOrNull() as? Bitmap ?: return
                    if (bitmap.width < 8 || bitmap.height < 8) return
                    hostTexture = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
                }
            })
            ModernXposedRuntime.log("background pulse: artwork capture hook installed")
        }.onFailure { ModernXposedRuntime.log("background pulse: capture hook failed: $it") }
    }

    private fun flash(layer: View, method: Method, base: Bitmap, strength: Float) {
        val bright = runCatching { renderGlow(base, strength) }.getOrNull() ?: return
        runCatching {
            writing.set(true)
            try {
                method.invoke(layer, bright)
            } finally {
                writing.set(false)
            }
            handler.postDelayed({
                val normal = runCatching { renderGlow(base, 0f) }.getOrNull() ?: return@postDelayed
                writing.set(true)
                try {
                    method.invoke(layer, normal)
                } finally {
                    writing.set(false)
                }
            }, RESTORE_MS)
        }
    }

    /** Base texture + a soft radial glow; the host's blur blooms it. */
    private fun renderGlow(source: Bitmap, strength: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(TEXTURE_EDGE, TEXTURE_EDGE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawBitmap(source, null, Rect(0, 0, TEXTURE_EDGE, TEXTURE_EDGE), Paint(Paint.FILTER_BITMAP_FLAG))
        if (strength <= 0.01f) return bitmap
        val radius = TEXTURE_EDGE * 0.10f
        val glow = RadialGradient(
            TEXTURE_EDGE / 2f,
            TEXTURE_EDGE / 2f,
            radius,
            intArrayOf(
                Color.argb((255 * strength).toInt().coerceIn(0, 255), 255, 255, 255),
                Color.argb((30 * strength).toInt().coerceIn(0, 255), 255, 255, 255),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.25f, 1f),
            Shader.TileMode.CLAMP,
        )
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = glow }
        canvas.drawCircle(TEXTURE_EDGE / 2f, TEXTURE_EDGE / 2f, radius, glowPaint)
        return bitmap
    }
}
