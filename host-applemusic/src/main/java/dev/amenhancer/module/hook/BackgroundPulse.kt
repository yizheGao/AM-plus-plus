package dev.amenhancer.module.hook

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import dev.amenhancer.module.ModuleConstants
import kotlin.math.cos

/**
 * Self-rendered "blooming" player background.
 *
 * The host's own LyricsBackgroundLayerView does not expose usable content in
 * this build, so this draws its own layer: the album art is reduced to a 32x32
 * texture (a natural low-frequency colour field), painted full screen through a
 * runtime shader that warps it with slow noise, applies a vignette and lifts
 * brightness on lyric-line pulses. It is inserted as the first child of the
 * host's background container and the host's own children are hidden while it
 * runs, then restored on detach.
 */
internal object BackgroundPulse {
    private const val ENABLED = false
    private const val BACKGROUND_LAYER = "background_layers"
    private const val ARTWORK_ID = "fullplayerSongImage"

    @Volatile private var pulse = 0f
    @Volatile private var attachedLayer: View? = null

    fun bump() {
        pulse = 1f
    }

    private val AGSL = """
        uniform shader art;
        uniform float2 size;
        uniform float time;
        uniform float energy;

        float hash21(float2 p) {
            return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
        }
        float vnoise(float2 p) {
            float2 i = floor(p);
            float2 f = fract(p);
            float2 u = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash21(i), hash21(i + float2(1.0, 0.0)), u.x),
                       mix(hash21(i + float2(0.0, 1.0)), hash21(i + float2(1.0, 1.0)), u.x), u.y);
        }

        half4 main(float2 p) {
            float2 uv = p / max(size, float2(1.0));
            float2 q = uv * 2.2;
            float2 warp = float2(vnoise(q + float2(time * 0.12, time * 0.07)),
                                 vnoise(q - float2(time * 0.09, time * 0.11))) - 0.5;
            float2 suv = clamp(uv + warp * 0.06, float2(0.0), float2(1.0));
            half4 c = art.eval(suv * size);
            float vignette = smoothstep(1.20, 0.25, length(uv - 0.5));
            float gain = (0.55 + 0.45 * vignette) * (0.90 + 0.35 * energy);
            return half4(c.rgb * gain, 1.0);
        }
    """.trimIndent()

    fun install(application: Application) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
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

    private fun attach(decor: View) = runCatching { attachUnsafe(decor) }
        .onFailure { ModernXposedRuntime.log("background bloom: attach failed: $it") }

    private fun attachUnsafe(decor: View) {
        val layerId = decor.resources.getIdentifier(BACKGROUND_LAYER, "id", ModuleConstants.TARGET_PACKAGE)
        if (layerId == 0) return
        // LyricsBackgroundLayerView is a plain View, not a container: insert the
        // bloom layer as its next sibling so it paints above the host background
        // but still below the player content.
        val layer = decor.findViewById<View>(layerId) ?: return
        if (!layer.isShown || layer.width <= 0) return
        val parent = layer.parent as? ViewGroup ?: return
        if (layer === attachedLayer) return

        val shader = runCatching { RuntimeShader(AGSL) }.getOrNull() ?: return
        val seed = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(40, 32, 36))
        }
        val view = runCatching { BloomView(layer.context, seed, shader) }.getOrNull() ?: return
        val layerAlpha = layer.alpha
        val insertAt = parent.indexOfChild(layer) + 1
        runCatching {
            parent.addView(view, insertAt, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            layer.alpha = 0f
        }.onFailure {
            runCatching { parent.removeView(view) }
            layer.alpha = layerAlpha
            return
        }
        attachedLayer = layer
        ModernXposedRuntime.log("background bloom: attached ${layer.width}x${layer.height}")
        fillArtwork(decor, view, attemptsLeft = 12)

        val startedAt = SystemClock.uptimeMillis()
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (view.parent == null || !view.isShown) {
                    runCatching { parent.removeView(view) }
                    runCatching { layer.alpha = layerAlpha }
                    if (attachedLayer === layer) attachedLayer = null
                    return
                }
                val seconds = (SystemClock.uptimeMillis() - startedAt) / 1000f
                val breath = 0.5f - 0.5f * cos(seconds * 2f * Math.PI.toFloat() * 0.35f)
                val kick = pulse
                pulse = (pulse * 0.90f).coerceAtLeast(0f)
                view.timeSeconds = seconds
                view.energy = (0.25f * breath + 0.75f * kick).coerceIn(0f, 1f)
                view.invalidate()
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        Choreographer.getInstance().postFrameCallback(callback)
    }

    /** The cover is bound asynchronously; keep sampling until it carries colour. */
    private fun fillArtwork(decor: View, view: BloomView, attemptsLeft: Int) {
        if (view.parent == null) return
        val bitmap = findArtwork(decor)
        if (bitmap != null) {
            view.setArt(bitmap)
            ModernXposedRuntime.log("background bloom: art texture ready")
            return
        }
        if (attemptsLeft <= 0) return
        decor.postDelayed({ fillArtwork(decor, view, attemptsLeft - 1) }, 250L)
    }

    private fun findArtwork(root: View): Bitmap? {
        val id = root.resources.getIdentifier(ARTWORK_ID, "id", ModuleConstants.TARGET_PACKAGE)
        val slot = if (id != 0) root.findViewById<View>(id) else null
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

    private fun hasColour(bitmap: Bitmap): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var min = 255
        var max = 0
        for (pixel in pixels) {
            val luma = (((pixel shr 16) and 0xFF) + ((pixel shr 8) and 0xFF) + (pixel and 0xFF)) / 3
            if (luma < min) min = luma
            if (luma > max) max = luma
        }
        return max - min >= 24
    }

    /**
     * 32x32 keeps only the low-frequency colour field, which is the point.
     * The artwork slot is not always an ImageView (it is a MaterialCardView in
     * this build), so the view itself is rasterised at thumbnail size.
     */
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

    private class BloomView(
        context: Context,
        art: Bitmap,
        private val shader: RuntimeShader,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        var timeSeconds = 0f
        var energy = 0f

        init {
            shader.setInputShader("art", BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            paint.shader = shader
        }

        fun setArt(bitmap: Bitmap) {
            shader.setInputShader("art", BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            shader.setFloatUniform("size", width.toFloat(), height.toFloat())
            shader.setFloatUniform("time", timeSeconds)
            shader.setFloatUniform("energy", energy)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }
}
