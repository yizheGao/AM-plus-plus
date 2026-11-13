package dev.amenhancer.module.hook

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.amenhancer.module.ModuleConstants

/**
 * The collapsed mini player sits on a full-width artwork-tinted strip that the host paints
 * inside the sheet. It reads as a broken colour band over the page behind it, so the strip is
 * covered with the window background and the native capsule is lifted back on top of the cover.
 *
 * Purely additive: the host's own views are never modified, only re-ordered inside their
 * parent, and everything is undone by removing our cover.
 */
internal object MiniPlayerBandFix {
    private const val TAG = "mini band"
    private const val MINI_PLAYER = "mini_player"
    private const val MINI_CONTENT = "mini_player_content"
    private const val COVER_TAG = "ampp-mini-band-cover"

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                if (activity.packageName != ModuleConstants.TARGET_PACKAGE) return
                val decor = activity.window?.decorView as? ViewGroup ?: return
                decor.viewTreeObserver.addOnGlobalLayoutListener { runCatching { apply(decor) } }
            }

            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
    }

    private var logCount = 0

    private fun apply(decor: ViewGroup) {
        val mini = decor.findViewById<ViewGroup>(idOf(decor, MINI_PLAYER))
        if (logCount < 6) {
            logCount += 1
            ModernXposedRuntime.log(
                "$TAG: mini=${mini?.javaClass?.simpleName} h=${mini?.height}" +
                    " bg=${mini?.background} fg=${mini?.foreground}" +
                    " kids=" + ((mini as? ViewGroup)?.let { g ->
                        (0 until g.childCount).joinToString(",") { i ->
                            val c = g.getChildAt(i)
                            "${c.javaClass.simpleName}@${c.height}"
                        }
                    } ?: "-"),
            )
        }
        if (mini == null) return
        if (mini !is FrameLayout) return
        val pill = decor.findViewById<View>(idOf(decor, MINI_CONTENT)) ?: return
        val metrics = decor.resources.displayMetrics
        val page = maxOf(metrics.widthPixels, metrics.heightPixels)
        if (mini.height >= page * 0.4f) return
        val existing = mini.findViewWithTag<View>(COVER_TAG)
        val cover = existing ?: View(mini.context).apply {
            tag = COVER_TAG
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val windowColor = (decor.background as? ColorDrawable)?.color ?: Color.WHITE
        cover.setBackgroundColor(windowColor)
        if (cover.parent == null) {
            mini.addView(cover)
            ModernXposedRuntime.log("$TAG: cover added ${mini.width}x${mini.height} color=#$windowColor")
        }
        // Draw order: host material -> our cover -> native capsule.
        cover.bringToFront()
        pill.bringToFront()
    }

    private fun idOf(view: View, name: String): Int = runCatching {
        view.resources.getIdentifier(name, "id", ModuleConstants.TARGET_PACKAGE)
    }.getOrDefault(0)
}
