package dev.amenhancer.module.hook

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import dev.amenhancer.module.ModuleConstants
import java.util.WeakHashMap

/**
 * Keeps the player artwork on screen across the activity recreation that Apple
 * Music performs on rotation.
 *
 * The host re-reads and re-decodes the cover after every configuration change,
 * which leaves the artwork slot empty for a moment. This captures the cover
 * that is already on screen, and paints it into the fresh slot only while that
 * slot has no drawable of its own; the host's own image replaces it as soon as
 * it arrives.
 */
internal object ArtworkRotationCache {
    private const val MAX_AGE_MS = 2_500L
    private const val MAX_EDGE_PX = 720
    private const val OVERLAY_TAG = "ampp-artwork-placeholder"

    private class Snapshot(val bitmap: Bitmap, val idName: String, val at: Long)

    @Volatile private var snapshot: Snapshot? = null
    private val handler = Handler(Looper.getMainLooper())
    private val installed = WeakHashMap<Application, Boolean>()
    private val placeholders = WeakHashMap<View, Boolean>()

    fun install(application: Application, enabled: () -> Boolean) {
        if (installed.put(application, true) != null) return
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(activity: Activity) {
                if (activity.packageName != ModuleConstants.TARGET_PACKAGE) return
                if (!enabled() || !activity.isChangingConfigurations) return
                capture(activity)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity.packageName != ModuleConstants.TARGET_PACKAGE) return
                if (!enabled()) return
                val snap = snapshot ?: return
                if (SystemClock.uptimeMillis() - snap.at > MAX_AGE_MS) return
                val decor = activity.window?.decorView ?: return
                // Two frames: the host inflates and binds its own artwork first.
                decor.postOnAnimation {
                    decor.postOnAnimation { apply(activity, decor, snap) }
                }
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun capture(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        val target = artworkViews(decor)
            .filter { it.drawable != null && it.width > 0 && it.height > 0 && it.isShown }
            .maxByOrNull { it.width * it.height } ?: return
        val name = idName(target)
        val bitmap = runCatching { drawableToBitmap(target) }.getOrNull() ?: return
        snapshot = Snapshot(bitmap, name, SystemClock.uptimeMillis())
    }

    private fun apply(activity: Activity, decor: View, snap: Snapshot) {
        val candidates = artworkViews(decor)
            .filter { idName(it) == snap.idName && it.width > 0 && it.height > 0 && it.isShown }
        val empty = candidates.firstOrNull { it.drawable == null } ?: return
        if (placeholders[empty] == true) return
        placeholders[empty] = true
        runCatching {
            empty.tag = OVERLAY_TAG
            empty.setImageBitmap(snap.bitmap)
            // Drop the placeholder if the host has not replaced it within a second.
            handler.postDelayed({
                if (empty.drawable !== null && empty.tag == OVERLAY_TAG) empty.tag = null
            }, 1_200L)
        }
    }

    private fun artworkViews(root: View): List<ImageView> {
        val out = ArrayList<ImageView>(4)
        val pending = ArrayDeque<View>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val view = pending.removeFirst()
            if (view is ImageView && isArtwork(view)) out += view
            if (view is ViewGroup) for (i in 0 until view.childCount) pending.addLast(view.getChildAt(i))
        }
        return out
    }

    private fun isArtwork(view: ImageView): Boolean {
        val name = idName(view)
        return name.isNotEmpty() && (name.contains("artwork", true) ||
            name == "fullplayerSongImage" || name == "album_art" || name == "cover_image")
    }

    private fun idName(view: View): String {
        if (view.id == View.NO_ID) return ""
        return runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
    }

    private fun drawableToBitmap(view: ImageView): Bitmap {
        val drawable = view.drawable ?: error("no drawable")
        val scale = minOf(1f, MAX_EDGE_PX.toFloat() / maxOf(view.width, view.height).coerceAtLeast(1))
        val width = (view.width * scale).toInt().coerceAtLeast(1)
        val height = (view.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bounds = drawable.copyBounds()
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        drawable.bounds = bounds
        return bitmap
    }
}
