@file:Suppress("NewApi")
package dev.amenhancer.module.hook

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import java.util.Collections
import java.util.WeakHashMap

/** Records the baseline before lyric Views exist; never uses a hidden RenderEffect getter. */
internal class LyricBlurEffectOwner(private val hostChanged: (View) -> Unit) : AutoCloseable {
    private companion object {
        /** 1% steps keep the colour-matrix cache small while the fade runs. */
        const val ALPHA_STEP = 4
    }
    private class Record(var native: RenderEffect?) {
        var owner: Any? = null
        var lastWriter: Any? = null
        var hostRevision = 0L
        var appliedRevision = -1L
        var radius = -1
        var alpha = 100
        var composed: RenderEffect? = null
    }
    private val records = Collections.synchronizedMap(WeakHashMap<View, Record>())
    private val effects = HashMap<Int, RenderEffect>()
    private val alphaEffects = HashMap<Int, RenderEffect>()
    private val writing = ThreadLocal.withInitial { false }

    fun install(scope: HookRegistrationScope) {
        val constructor = View::class.java.getDeclaredConstructor(Context::class.java, AttributeSet::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        check(ModernXposedRuntime.hookMethod(constructor, object : ModernMethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.throwable != null) return
                val view = param.thisObject as? View ?: return
                synchronized(records) { records.getOrPut(view) { Record(null) } }
            }
        }, scope))
        check(ModernXposedRuntime.hookMethod(View::class.java.getDeclaredMethod("setRenderEffect", RenderEffect::class.java),
            object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val view = param.thisObject as? View ?: return
                    val record = synchronized(records) {
                        records.getOrPut(view) { Record(param.args.firstOrNull() as? RenderEffect) }
                    }
                    if (writing.get() == true) record.lastWriter = record.owner
                    else {
                        record.native = param.args.firstOrNull() as? RenderEffect
                        record.lastWriter = null
                        record.hostRevision += 1
                        if (record.owner != null) hostChanged(view)
                    }
                }
            }, scope))
        scope.onClose(::close)
    }

    fun acquire(view: View, owner: Any): Boolean {
        val record = records[view] ?: return false // Existing unknown baselines are never overwritten.
        if (record.owner != null && record.owner !== owner) return false
        record.owner = owner
        return true
    }

    /**
     * [alphaPercent] is folded into the effect chain as an alpha-only colour
     * matrix rather than written to `View.alpha`, so host word animations that
     * write alpha every frame cannot overwrite it and are not disturbed.
     */
    fun apply(view: View, owner: Any, radius: Int, alphaPercent: Int = 100): Boolean {
        val record = records[view]?.takeIf { it.owner === owner } ?: return false
        val quantized = radius.coerceIn(0, 32)
        val alpha = alphaPercent.coerceIn(0, 100).let { (it / ALPHA_STEP) * ALPHA_STEP }
        if (record.lastWriter === owner && record.radius == quantized && record.alpha == alpha &&
            record.appliedRevision == record.hostRevision) return true
        val blur = if (quantized == 0) null else effects.getOrPut(quantized) {
            RenderEffect.createBlurEffect(quantized.toFloat(), quantized.toFloat(), Shader.TileMode.DECAL)
        }
        val baseline = record.native
        val shaped = when {
            blur == null -> baseline
            baseline == null -> blur
            else -> RenderEffect.createChainEffect(blur, baseline)
        }
        val composed = if (alpha >= 100) shaped else {
            val fade = alphaEffects.getOrPut(alpha) {
                val matrix = ColorMatrix().apply { setScale(1f, 1f, 1f, alpha / 100f) }
                RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix))
            }
            if (shaped == null) fade else RenderEffect.createChainEffect(fade, shaped)
        }
        moduleWrite { view.setRenderEffect(composed) }
        record.lastWriter = owner
        record.radius = quantized
        record.alpha = alpha
        record.appliedRevision = record.hostRevision
        record.composed = composed
        return true
    }

    fun release(view: View, owner: Any) {
        val record = records[view]?.takeIf { it.owner === owner } ?: return
        if (record.lastWriter === owner) moduleWrite { view.setRenderEffect(record.native) }
        record.owner = null
        record.lastWriter = null
        record.radius = -1
        record.alpha = 100
        record.composed = null
    }

    private inline fun moduleWrite(action: () -> Unit) {
        val previous = writing.get() == true
        writing.set(true)
        try { action() } finally { writing.set(previous) }
    }

    override fun close() {
        synchronized(records) { records.entries.map { it.key to it.value.owner } }
            .forEach { (view, owner) -> if (owner != null) release(view, owner) }
        records.clear()
        effects.clear()
        alphaEffects.clear()
        writing.remove()
    }
}
