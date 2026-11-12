package dev.amenhancer.module.hook

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import java.lang.reflect.Method

/**
 * Captures the player cover and dispatches line-change pulses.
 *
 * The visible glow is drawn by [GlowLayer], which owns its own canvas inside
 * `player_root`. This object only keeps a small copy of the host's cover so the
 * layer can tint itself, and forwards every line-change signal to it.
 *
 * Feeding a doctored bitmap back into `LyricsBackgroundLayerView#setArtwork` was
 * the previous approach; it measured at most 1.3/255 of luminance difference
 * because the host's own cover overwrites the frame. That path is gone: the glow
 * no longer depends on the host repainting anything.
 */
internal object BackgroundPulse {
    private const val BACKGROUND_LAYER = "background_layers"
    private const val TEXTURE_EDGE = 96

    @Volatile private var attached: View? = null
    @Volatile private var hostTexture: Bitmap? = null
    @Volatile private var captureHooked = false
    private var bumpCount = 0

    /** Latest 32×32 copy of the cover, or null before the host has published one. */
    fun coverTexture(): Bitmap? = hostTexture

    /**
     * Single entry point for every pulse source: the lyric-line trigger today,
     * a beat detector later. [strength] is 0..1 and only scales brightness.
     */
    fun pulse(strength: Float) {
        GlowLayer.pulse(strength)
    }

    /** Called on every word onset: forwards a small local impulse. */
    fun word() {
        if (!runCatching { dev.amenhancer.module.config.TargetConfigClient.currentSettings().lyricLineFlashEnabled }
                .getOrDefault(false)) return
        GlowLayer.word()
    }

    /** Called when the highlighted lyric line changes. */
    fun bump() {
        if (!runCatching { dev.amenhancer.module.config.TargetConfigClient.currentSettings().lyricLineFlashEnabled }
                .getOrDefault(false)) return
        if (bumpCount < 12) {
            bumpCount += 1
            ModernXposedRuntime.log("background pulse: line pulse #$bumpCount")
        }
        GlowLayer.pulse(0.72f)
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
        val id = decor.resources.getIdentifier(
            BACKGROUND_LAYER, "id", dev.amenhancer.module.ModuleConstants.TARGET_PACKAGE,
        )
        if (id == 0) return
        val layer = decor.findViewById<View>(id) ?: return
        if (!layer.isShown || layer.width <= 0) return
        if (layer === attached) return
        val method = runCatching {
            layer.javaClass.getMethod("setArtwork", Bitmap::class.java)
        }.getOrNull() ?: return
        attached = layer
        installCaptureHook(method)
        ModernXposedRuntime.log("background pulse: cover capture ready on ${layer.javaClass.simpleName} ${layer.width}x${layer.height}")
    }

    private fun installCaptureHook(method: Method) {
        if (captureHooked) return
        captureHooked = true
        runCatching {
            ModernXposedRuntime.hookMethod(method, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val bitmap = param.args.firstOrNull() as? Bitmap ?: return
                    if (bitmap.width < 8 || bitmap.height < 8) return
                    hostTexture = runCatching {
                        Bitmap.createScaledBitmap(bitmap, TEXTURE_EDGE, TEXTURE_EDGE, true)
                    }.getOrNull()
                }
            })
            ModernXposedRuntime.log("background pulse: artwork capture hook installed")
        }.onFailure { ModernXposedRuntime.log("background pulse: capture hook failed: $it") }
    }

    /** Kept for callers that only need to know whether audio is running. */
    fun musicActive(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)?.isMusicActive == true
    }.getOrDefault(false)
}