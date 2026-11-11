package dev.amenhancer.module.hook

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import dev.amenhancer.module.ModuleConstants
import java.util.WeakHashMap

/**
 * Landscape only: parks the translations button in the player's bottom action
 * row, directly left of the lyrics button, with the same gap the lyrics and
 * queue buttons already use. Portrait is left exactly as the host renders it.
 *
 * The row's buttons carry no resource ids in this build, so they are located
 * by their localized content descriptions ("lyrics" / "player_queue" string
 * resources) instead of hard-coded text.
 */
internal object PlayerLyricsButtonsOrder {
    private const val TRANSLATIONS_BUTTON = "translations_button"
    private const val LYRICS_STRING = "lyrics"
    private const val QUEUE_STRING = "player_queue"
    private const val FALLBACK_GAP_DP = 8f
    /** The translations drawable sits a hair below its own bounds centre on this build. */
    private const val GLYPH_FINE_TUNE_DP = 2.5f
    /**
     * The host draws the translations glyph smaller than the lyrics glyph
     * (54 px against 77 px on the 450 dpi tablet). Scale the drawable — never
     * the layout params, which squashes the drawable into a sliver.
     */
    private const val ICON_SCALE = 1.0f
    /**
     * Final visual size of the translations control, as a multiple of its
     * native box. The host paints it at 219x129 with a 101 px selected pill,
     * while the lyrics button's badge is ~79 px: 0.78 lands the pill and the
     * glyph on the neighbours without touching the layout box (the layout box
     * stays the popup anchor).
     */
    /**
     * Fallback visual scale when the host drawables expose no intrinsic size.
     * The row is matched against the lyrics button, which draws its badge at
     * roughly 0.64 of its box while the translations pill draws at ~0.81, so
     * 0.8 is the ratio that lands both badges on the same size.
     */
    private const val VISUAL_SCALE_FALLBACK = 0.82f
    /**
     * Optical bias: the intrinsic ratio matches the badges exactly, and the
     * user reads the translations badge as a touch small next to the lyrics
     * button, so lift it a hair above parity.
     */
    private const val OPTICAL_BIAS = 1.02f
    /** The list icon carries no badge, so it reads weaker at parity: lift it. */
    private const val LIST_OPTICAL_BIAS = 1.18f
    /** After the visual scale the pill sits slightly above the row centre; fraction of the row height. */
    private const val VISUAL_OFFSET_FRACTION = 0.080f
    private const val SETTLE_MS = 400L
    private const val MIN_INTERVAL_MS = 120L

    private class State {
        var orientation = Configuration.ORIENTATION_UNDEFINED
        var changedAt = 0L
        var lastPassAt = 0L
    }

    private val attached = WeakHashMap<Activity, Boolean>()
    private val states = WeakHashMap<Activity, State>()
    private val location = IntArray(2)
    private var loggedGeometry = false

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity.packageName != ModuleConstants.TARGET_PACKAGE) return
                if (attached.put(activity, true) != null) return
                val decor = activity.window?.decorView ?: return
                decor.viewTreeObserver.addOnGlobalLayoutListener { place(activity) }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun place(activity: Activity) {
        if (RotationGuard.isChanging(activity)) return
        val now = SystemClock.uptimeMillis()
        val state = states.getOrPut(activity) { State() }
        val orientation = activity.resources.configuration.orientation
        if (orientation != state.orientation) {
            state.orientation = orientation
            state.changedAt = now
            return
        }
        if (now - state.changedAt < SETTLE_MS) return
        if (now - state.lastPassAt < MIN_INTERVAL_MS) return
        state.lastPassAt = now

        val decor = activity.window?.decorView ?: return
        val translations = firstVisibleById(decor, activity, TRANSLATIONS_BUTTON) ?: return

        if (orientation != Configuration.ORIENTATION_LANDSCAPE) {
            // Portrait keeps the host position; clear anything a previous pass wrote.
            if (translations.translationX != 0f) translations.translationX = 0f
            if (translations.translationY != 0f) translations.translationY = 0f
            return
        }

        val lyrics = findByDescription(decor, activity, LYRICS_STRING) ?: return
        val queue = findByDescription(decor, activity, QUEUE_STRING)
        val density = translations.resources.displayMetrics.density
        val lyricsAt = screenLocation(lyrics)
        val queueAt = queue?.let(::screenLocation)
        val gap = if (queueAt != null) {
            (queueAt[0] - (lyricsAt[0] + lyrics.width)).coerceAtLeast(0)
        } else {
            (FALLBACK_GAP_DP * density).toInt()
        }

        scaleGlyph(translations)
        insetSelectionBackground(translations, lyrics)
        queue?.let(::liftListGlyph)
        val scaleValue = computeVisualScale(lyrics, translations)
        translations.scaleX = scaleValue
        translations.scaleY = scaleValue

        translations.translationX = 0f
        translations.translationY = 0f
        val translationsAt = screenLocation(translations)

        // Equal centre pitch, not equal edge gaps: the three controls have
        // different widths and badge paddings, so even edges read uneven.
        val lyricsCentreX = lyricsAt[0] + lyrics.width / 2f
        val pitch = if (queue != null) {
            val queueAt = screenLocation(queue)
            queueAt[0] + queue.width / 2f - lyricsCentreX
        } else {
            (lyrics.width + gap).toFloat()
        }
        val targetCentreX = lyricsCentreX - pitch
        translations.translationX =
            targetCentreX - (translationsAt[0] + translations.width / 2f)

        // Optical vertical alignment: match the centres of the drawn content,
        // then apply the measured residue as a fraction of the row height.
        val lyricsGlyphCentre = lyricsAt[1] + glyphCenterWithin(lyrics)
        val translationsGlyphCentre = translationsAt[1] + glyphCenterWithin(translations)
        translations.translationY =
            lyricsGlyphCentre - translationsGlyphCentre +
                VISUAL_OFFSET_FRACTION * lyrics.height

        if (!loggedGeometry) {
            loggedGeometry = true
            ModernXposedRuntime.log(
                "lyrics buttons: lyricsBox=${lyrics.width}x${lyrics.height}" +
                    " translationsBox=${translations.width}x${translations.height}" +
                    " lyricsIntrinsic=${(lyrics as? android.widget.ImageView)?.drawable?.intrinsicWidth}" +
                    " translationsIntrinsic=${(translations as? android.widget.ImageView)?.drawable?.intrinsicWidth}" +
                    " scale=$scaleValue pitch=$pitch density=$density",
            )
        }
    }

    /** Lifts the list icon so the badge-less control carries the same weight. */
    private fun liftListGlyph(view: View) {
        val image = view as? android.widget.ImageView ?: return
        val current = image.drawable ?: return
        if (current is ScaledGlyphDrawable) return
        image.setImageDrawable(ScaledGlyphDrawable(current, LIST_OPTICAL_BIAS))
    }

    /**
     * Visual scale for the translations control.
     *
     * Both controls are ImageViews, so their intrinsic widths describe how big
     * each badge is drawn inside its box; matching them makes the row line up on
     * any density or host layout instead of relying on a tablet-tuned constant.
     */
    private fun computeVisualScale(lyrics: View, translations: View): Float {
        val target = (lyrics as? android.widget.ImageView)?.drawable?.intrinsicWidth?.takeIf { it > 0 }
        val source = (translations as? android.widget.ImageView)?.drawable?.intrinsicWidth?.takeIf { it > 0 }
        if (target != null && source != null) {
            val ratio = target.toFloat() / source.toFloat()
            if (ratio in 0.5f..1.5f) return (ratio * OPTICAL_BIAS).coerceIn(0.5f, 1.5f)
        }
        if (translations.width > 0) {
            return (lyrics.width.toFloat() / translations.width.toFloat() * 1.30f * OPTICAL_BIAS)
                .coerceIn(0.6f, 1.0f)
        }
        return VISUAL_SCALE_FALLBACK
    }

    /**
     * The host paints the selected-state pill at the full 219x129 box, which
     * reads as a bigger button next to the 124x124 neighbours. Inset the
     * background instead of resizing the view, so the glyph is untouched.
     */
    private fun insetSelectionBackground(view: View, neighbours: View) {
        val background = view.background ?: return
        if (background is InsetToggleBackground) return
        if (view.width <= 0 || view.height <= 0) return
        val targetWidth = neighbours.width.takeIf { it > 0 } ?: return
        val targetHeight = neighbours.height.takeIf { it > 0 } ?: targetWidth
        val insetX = ((view.width - targetWidth) / 2).coerceAtLeast(0)
        val insetY = ((view.height - targetHeight) / 2).coerceAtLeast(0)
        view.background = InsetToggleBackground(background, insetX, insetY)
    }

    private class InsetToggleBackground(
        private val inner: android.graphics.drawable.Drawable,
        private val insetX: Int,
        private val insetY: Int,
    ) : android.graphics.drawable.Drawable() {
        override fun draw(canvas: android.graphics.Canvas) {
            val box = bounds
            inner.setBounds(box.left + insetX, box.top + insetY, box.right - insetX, box.bottom - insetY)
            inner.draw(canvas)
        }

        override fun setAlpha(alpha: Int) { inner.alpha = alpha }
        override fun setColorFilter(filter: android.graphics.ColorFilter?) { inner.colorFilter = filter }
        @Deprecated("Deprecated in Drawable")
        override fun getOpacity(): Int = inner.opacity
        override fun isStateful(): Boolean = inner.isStateful
        override fun setState(state: IntArray): Boolean = inner.setState(state)
        override fun jumpToCurrentState() = inner.jumpToCurrentState()
    }

    /** Wraps the host drawable once so the glyph matches the neighbouring icons. */
    private fun scaleGlyph(view: View) {
        val image = view as? android.widget.ImageView ?: return
        val current = image.drawable ?: return
        if (current is ScaledGlyphDrawable) return
        image.setImageDrawable(ScaledGlyphDrawable(current, ICON_SCALE))
    }

    /**
     * Draws [inner] through a canvas scale around the bounds centre: the glyph
     * grows, the view box and its layout stay untouched.
     */
    private class ScaledGlyphDrawable(
        private val inner: android.graphics.drawable.Drawable,
        private val scale: Float,
    ) : android.graphics.drawable.Drawable() {
        override fun draw(canvas: android.graphics.Canvas) {
            val box = bounds
            canvas.save()
            canvas.scale(scale, scale, box.exactCenterX(), box.exactCenterY())
            inner.setBounds(box)
            inner.draw(canvas)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {
            inner.alpha = alpha
        }

        override fun setColorFilter(filter: android.graphics.ColorFilter?) {
            inner.colorFilter = filter
        }

        @Deprecated("Deprecated in Drawable")
        override fun getOpacity(): Int = inner.opacity

        override fun isStateful(): Boolean = inner.isStateful

        override fun setState(state: IntArray): Boolean = inner.setState(state)

        override fun getIntrinsicWidth(): Int = inner.intrinsicWidth
        override fun getIntrinsicHeight(): Int = inner.intrinsicHeight
    }

    /** Centre of the content the view actually draws, in view coordinates. */
    private fun glyphCenterWithin(view: View): Float {
        val drawable = (view as? android.widget.ImageView)?.drawable
        val bounds = drawable?.bounds
        if (bounds != null && !bounds.isEmpty) return bounds.centerY().toFloat()
        return view.height / 2f
    }

    private fun screenLocation(view: View): IntArray {
        view.getLocationOnScreen(location)
        return intArrayOf(location[0], location[1])
    }

    private fun firstVisibleById(root: View, activity: Activity, name: String): View? {
        val id = activity.resources.getIdentifier(name, "id", ModuleConstants.TARGET_PACKAGE)
        if (id == 0) return null
        return walk(root).firstOrNull { it.id == id && it.isShown }
    }

    private fun findByDescription(root: View, activity: Activity, stringName: String): View? {
        val id = activity.resources.getIdentifier(stringName, "string", ModuleConstants.TARGET_PACKAGE)
        if (id == 0) return null
        val expected = runCatching { activity.getString(id) }.getOrNull() ?: return null
        return walk(root).firstOrNull { it.isShown && it.contentDescription?.toString() == expected }
    }

    private fun walk(root: View): Sequence<View> = sequence {
        val pending = ArrayDeque<View>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val view = pending.removeFirst()
            yield(view)
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) pending.addLast(view.getChildAt(index))
            }
        }
    }
}
