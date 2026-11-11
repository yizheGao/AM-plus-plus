package dev.amenhancer.module.hook

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import dev.amenhancer.module.ModuleConstants
import kotlin.math.cos

/**
 * Experimental "breathing" player background.
 *
 * v1 tried a RuntimeShader on the host's own background layer; that layer
 * renders through its own path, so the shader input was empty and the area went
 * black. v2 leaves the shader alone and animates the layer itself: a slow
 * scale/alpha breath plus a short brightness bump whenever a lyric line
 * changes, which is the part of Apple's "律动背景" that reads as a pulse.
 */
internal object BackgroundPulse {
    private const val ENABLED = false
    private const val BACKGROUND_LAYER = "background_layers"
    private const val TAG_ID = 0x0A4D5002
    private const val BREATH_HZ = 0.42f
    private const val SCALE_AMPLITUDE = 0.018f
    private const val ALPHA_FLOOR = 0.90f

    @Volatile private var pulse = 0f
    @Volatile private var attachedLayer: View? = null

    fun bump() {
        pulse = 1f
    }

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity.packageName != ModuleConstants.TARGET_PACKAGE) return
                if (!ENABLED) return
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

    private fun attach(decor: View) {
        val id = decor.resources.getIdentifier(BACKGROUND_LAYER, "id", ModuleConstants.TARGET_PACKAGE)
        if (id == 0) return
        val layer = decor.findViewById<View>(id) ?: return
        if (!layer.isShown || layer.width <= 0) return
        if (layer === attachedLayer) return
        attachedLayer = layer

        val baseAlpha = layer.alpha
        val baseScaleX = layer.scaleX
        val baseScaleY = layer.scaleY
        val startedAt = SystemClock.uptimeMillis()

        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!layer.isAttachedToWindow || !layer.isShown) {
                    runCatching {
                        layer.alpha = baseAlpha
                        layer.scaleX = baseScaleX
                        layer.scaleY = baseScaleY
                    }
                    if (attachedLayer === layer) attachedLayer = null
                    return
                }
                val seconds = (SystemClock.uptimeMillis() - startedAt) / 1000f
                val breath = 0.5f - 0.5f * cos(seconds * 2f * Math.PI.toFloat() * BREATH_HZ)
                val kick = pulse
                pulse = (pulse * 0.90f).coerceAtLeast(0f)
                val scale = 1f + SCALE_AMPLITUDE * (breath * 0.6f + kick * 0.4f)
                val alpha = (baseAlpha * (ALPHA_FLOOR + (1f - ALPHA_FLOOR) * breath) + 0.08f * kick)
                    .coerceIn(0f, 1f)
                runCatching {
                    layer.scaleX = baseScaleX * scale
                    layer.scaleY = baseScaleY * scale
                    layer.alpha = alpha
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        Choreographer.getInstance().postFrameCallback(callback)
        ModernXposedRuntime.log("background pulse v2: attached ${layer.width}x${layer.height}")
    }
}
