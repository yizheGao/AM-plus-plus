package dev.amenhancer.module.hook

import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import dev.amenhancer.module.model.ModuleSettings
import java.util.IdentityHashMap
import kotlin.math.max

/** Independent lyric-effect switches, re-read whenever the settings revision moves. */
internal data class LyricRenderOptions(
    val blurEnabled: Boolean = true,
    val hideSungLines: Boolean = false,
    val edgeFeather: Boolean = false,
    val offsetPx: Int = 0,
)

/**
 * Has no ticker of its own: the view session owns dirty work and all animation frames.
 *
 * Opacity and feather are folded into the module-owned `RenderEffect` chain
 * instead of `View.alpha`/`View.translationY`. The host animates lyric words
 * itself, so writing those properties from here fights the native per-word
 * animation; a colour-matrix alpha layer composes with it instead.
 */
internal class LyricBlurViewRenderer(
    private val effects: LyricBlurEffectOwner,
    private val settingsProvider: () -> ModuleSettings,
) : AutoCloseable {
    private data class Key(val document: Long, val kind: LyricRowKind, val nativeId: Int?)
    private data class Bound(val key: Key, val generation: Long, val lease: Any = Any(), var acquired: Boolean = false)
    private data class Logical(
        val row: LyricRowSnapshot,
        var animation: LyricBlurRenderState,
        var hidden: Boolean = false,
        var opacity: Fade = Fade.settled(),
    )

    /** Opacity ramp for a sung line: ease-out, and reversible from the current value. */
    private data class Fade(val start: Float, val target: Float, val startedAtMs: Long) {
        fun value(nowMs: Long): Float {
            if (start == target) return target
            val progress = ((nowMs - startedAtMs).toFloat() / FADE_MILLIS).coerceIn(0f, 1f)
            val eased = 1f - (1f - progress) * (1f - progress) * (1f - progress)
            return start + (target - start) * eased
        }

        fun running(nowMs: Long): Boolean = start != target && nowMs - startedAtMs < FADE_MILLIS

        fun to(next: Float, nowMs: Long): Fade =
            if (next == target) this else Fade(value(nowMs), next, nowMs)

        companion object {
            fun settled(): Fade = Fade(1f, 1f, 0L)
        }
    }

    private val logical = LinkedHashMap<Key, Logical>()
    private val bound = IdentityHashMap<View, Bound>()
    private var clipping: LyricBlurClippingLease? = null
    private val rect = RectF()
    private var options = LyricRenderOptions()
    private var optionsRevision = -1
    var unavailableBaselines: Int = 0
        private set

    fun owns(view: View): Boolean = bound.containsKey(view)

    fun clippingChanged(): Boolean = clipping?.needsUpdate() == true

    fun detach(view: View) {
        bound.remove(view)?.let { effects.release(view, it.lease) }
        if (bound.isEmpty()) releaseClipping()
    }

    fun documentChanged() {
        bound.keys.toList().forEach(::detach)
        logical.clear()
    }

    fun seek(nowMs: Long) {
        logical.values.forEach { row ->
            row.animation = LyricBlurRenderState.initial(LyricBlurTarget(row.animation.focus.value(nowMs),
                row.animation.edgeWeight.value(nowMs)), nowMs)
        }
    }

    fun update(state: LyricBlurState, rows: List<LyricBlurBoundRow>, nowMs: Long) {
        refreshOptions()
        val live = rows.mapTo(HashSet()) { it.view }
        bound.keys.filterNot(live::contains).toList().forEach(::detach)
        val all = LinkedHashMap<Key, LyricRowSnapshot>()
        logical.forEach { (key, row) -> all[key] = row.row }
        rows.forEach { row ->
            val identity = row.identity
            if (identity.kind != LyricRowKind.LYRIC && identity.kind != LyricRowKind.CREDITS) {
                detach(row.view)
                return@forEach
            }
            val key = Key(state.epoch.document, identity.kind, identity.nativeLineId)
            all[key] = identity
            val previous = bound[row.view]
            if (previous == null || previous.key != key || previous.generation != identity.bindingGeneration) {
                detach(row.view)
                bound[row.view] = Bound(key, identity.bindingGeneration)
            }
        }
        val targets = LyricBlurPolicy.targets(state, all.values.toList(), options.offsetPx, options.hideSungLines)
        all.entries.forEachIndexed { index, (key, row) ->
            val old = logical[key]
            val hidden = targets[index].hidden
            val opacityTarget = if (hidden) 0f else 1f
            logical[key] = Logical(
                row,
                old?.animation?.to(targets[index], nowMs)
                    ?: LyricBlurRenderState.initial(targets[index], nowMs),
                hidden,
                old?.opacity?.to(opacityTarget, nowMs) ?: Fade(opacityTarget, opacityTarget, nowMs),
            )
        }
    }

    fun render(recycler: ViewGroup, tabletEdges: Boolean, nowMs: Long): Boolean {
        refreshOptions()
        unavailableBaselines = 0
        var running = false
        var blurred = false
        bound.forEach { (view, binding) ->
            val row = logical[binding.key] ?: return@forEach
            if (!binding.acquired) binding.acquired = effects.acquire(view, binding.lease)
            if (!binding.acquired) { unavailableBaselines += 1; return@forEach }
            val edge = if (tabletEdges) edgeRadius(view, recycler) else 0f
            val animation = row.animation
            val radius = if (options.blurEnabled) {
                max(animation.focus.value(nowMs), edge * animation.edgeWeight.value(nowMs))
            } else {
                0f
            }
            val quantized = BidirectionalBlurPolicy.quantize(radius)
            if (quantized > 0 && !blurred) {
                allowRowOverflow(recycler)
                blurred = true
            }
            val sung = if (options.hideSungLines && tabletEdges) row.opacity.value(nowMs) else 1f
            val alphaPercent = (sung * featherFactor(view, recycler) * 100f).toInt().coerceIn(0, 100)
            effects.apply(view, binding.lease, quantized, alphaPercent)
            if (options.hideSungLines && tabletEdges) running = running || row.opacity.running(nowMs)
            running = running || animation.running(nowMs)
        }
        if (!blurred) releaseClipping()
        return running
    }

    private fun refreshOptions() {
        val revision = ModuleSettingsRevision.current()
        if (revision == optionsRevision) return
        optionsRevision = revision
        val settings = runCatching { settingsProvider() }.getOrNull() ?: return
        options = LyricRenderOptions(
            blurEnabled = settings.futureBlurEnabled,
            hideSungLines = settings.dualPaneHidePlayedLyricsEnabled,
            edgeFeather = settings.lyricEdgeFeatherEnabled,
            offsetPx = settings.lyricBlurRadiusOffsetPx,
        )
    }

    private fun allowRowOverflow(recycler: ViewGroup) {
        if (clipping?.recycler !== recycler) {
            releaseClipping()
            clipping = LyricBlurClippingLease(recycler)
        }
        clipping?.update()
    }

    private fun releaseClipping() {
        clipping?.close()
        clipping = null
    }

    fun observations(nowMs: Long): List<String> = bound.entries.take(LyricHighlightProbe.MAX_IDS).mapNotNull { (view, binding) ->
        val row = logical[binding.key] ?: return@mapNotNull null
        "${row.row.nativeLineId}:${row.row.lyricOrdinal}:${row.row.adapterPosition}:${binding.generation}" +
            ":${row.animation.focus.target}:${row.animation.focus.value(nowMs)}:${nowMs - row.animation.focus.startedAtMs}"
    }

    fun geometry(view: View, recycler: ViewGroup): Float {
        rect.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
        view.matrix.mapRect(rect)
        rect.offset((view.left - recycler.scrollX).toFloat(), (view.top - recycler.scrollY).toFloat())
        return rect.centerY()
    }

    /**
     * Vertical feather: rows crossing the top or bottom edge of the lyric
     * viewport lose opacity through the effect chain instead of being cut off.
     */
    private fun featherFactor(view: View, recycler: ViewGroup): Float {
        if (!options.edgeFeather) return 1f
        val height = (recycler.height - recycler.paddingTop - recycler.paddingBottom).toFloat()
        if (height <= 0f) return 1f
        // Proportional to the viewport so a tablet pane and a phone screen get
        // the same visual falloff, with a dp floor for very short panes.
        val edgePx = maxOf(FEATHER_MIN_DP * view.resources.displayMetrics.density, height * FEATHER_FRACTION)
        if (edgePx <= 0f) return 1f
        val centre = geometry(view, recycler) - recycler.paddingTop
        val distance = minOf(centre, height - centre).coerceAtLeast(0f)
        val t = (distance / edgePx).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun edgeRadius(view: View, recycler: ViewGroup): Float = TabletLyricVisualPolicy.edgeBlurRadius(
        geometry(view, recycler) - recycler.paddingTop,
        (recycler.height - recycler.paddingTop - recycler.paddingBottom).toFloat())

    override fun close() = documentChanged()

    private companion object {
        /** AMLL's brightness cross-fade runs 450 ms; this is the same feel, slightly tighter. */
        const val FADE_MILLIS = 320f
        /** Feather height: 8% of the viewport, never under 28 dp. */
        const val FEATHER_FRACTION = 0.08f
        const val FEATHER_MIN_DP = 28f
    }
}
