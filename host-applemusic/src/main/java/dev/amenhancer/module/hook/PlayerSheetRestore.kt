package dev.amenhancer.module.hook

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import dev.amenhancer.module.ModuleConstants
import java.lang.ref.WeakReference

/**
 * Restores the player sheet after a host dialog closed it.
 *
 * Apple Music presents the "add to playlist" picker (and some other modals) as a translucent
 * dialog window over the same activity. Opening it collapses the player sheet, and nothing
 * brings that sheet back once the dialog is dismissed — the user is dropped on whatever page
 * was underneath. Verified on the untouched upstream module as well, so it is host behaviour,
 * not something this module changed.
 *
 * Detection stays outside the host: the decor view loses and regains window focus around any
 * dialog, and the sheet itself is visible only while its layout rect covers the page. When the
 * focus comes back and the sheet did not, the mini player is clicked the same way a user would.
 */
internal object PlayerSheetRestore {
    private const val TAG = "sheet restore"
    private const val POLL_MS = 350L
    private const val RESTORE_DELAY_MS = 250L
    /** A dialog session older than this is not treated as "the sheet was just dismissed". */
    private const val RESTORE_WINDOW_MS = 10_000L
    private const val PLAYER_ROOT = "player_root"
    private const val MINI_PLAYER = "mini_player"
    private const val MINI_PLAYER_CONTENT = "mini_player_content"
    private const val MINI_PLAYER_TOUCH = "mini_player_touch_panel"

    private val handler = Handler(Looper.getMainLooper())
    private val installed = java.util.WeakHashMap<Application, Boolean>()
    private var decor: WeakReference<View> = WeakReference(null)
    private var lastFocus = true
    private var sheetHiddenAt = 0L
    private var wasVisible = false

    private val tick = object : Runnable {
        override fun run() {
            val view = decor.get() ?: return
            if (!view.isAttachedToWindow) return
            val focused = view.hasWindowFocus()
            if (lastFocus && !focused) {
                // A window above this activity (dialog) took focus; remember whether the player
                // sheet was the thing on screen, because that is the state worth restoring.
                wasVisible = playerSheetVisible(view)
                sheetHiddenAt = if (wasVisible) SystemClock.uptimeMillis() else 0L
            } else if (!lastFocus && focused && sheetHiddenAt != 0L) {
                val withinWindow = SystemClock.uptimeMillis() - sheetHiddenAt < RESTORE_WINDOW_MS
                val shouldRestore = withinWindow && wasVisible
                sheetHiddenAt = 0L
                wasVisible = false
                if (shouldRestore) {
                    view.postDelayed({
                        if (!playerSheetVisible(view) && reopen(view)) {
                            ModernXposedRuntime.log("$TAG: player sheet restored after dialog")
                        }
                    }, RESTORE_DELAY_MS)
                }
            }
            lastFocus = focused
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun install(application: Application) {
        if (installed.put(application, true) != null) return
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity.packageName != ModuleConstants.TARGET_PACKAGE) return
                val root = activity.window?.decorView ?: return
                decor = WeakReference(root)
                lastFocus = root.hasWindowFocus()
                handler.removeCallbacks(tick)
                handler.postDelayed(tick, POLL_MS)
            }

            override fun onActivityPaused(activity: Activity) {
                if (activity.packageName != ModuleConstants.TARGET_PACKAGE) return
                handler.removeCallbacks(tick)
                decor = WeakReference(null)
                sheetHiddenAt = 0L
                wasVisible = false
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** True while the player sheet covers most of the page; a collapsed sheet is clipped away. */
    private fun playerSheetVisible(root: View): Boolean {
        val player = root.findViewById<View>(id(root, PLAYER_ROOT)) ?: return false
        if (!player.isShown) return false
        val rect = Rect()
        if (!player.getGlobalVisibleRect(rect)) return false
        val screen = root.resources.displayMetrics
        val page = maxOf(screen.widthPixels, screen.heightPixels)
        return rect.height() >= page * 0.45f
    }

    /** Clicks the mini player, which is exactly what a user taps to open the sheet again. */
    private fun reopen(root: View): Boolean {
        for (name in listOf(MINI_PLAYER_TOUCH, MINI_PLAYER_CONTENT, MINI_PLAYER)) {
            val view = root.findViewById<View>(id(root, name)) ?: continue
            if (!view.isShown || view.width <= 0 || view.height <= 0) continue
            if (view.hasOnClickListeners() && view.performClick()) return true
            if (tap(view)) return true
        }
        return false
    }

    /** Synthetic tap for the cases where the host keeps the click listener on a child. */
    private fun tap(view: View): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val x = location[0] + view.width / 2f
        val y = location[1] + view.height / 2f
        val downAt = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downAt, downAt + 40, MotionEvent.ACTION_UP, x, y, 0)
        return try {
            val handled = view.dispatchTouchEvent(down)
            view.dispatchTouchEvent(up)
            handled
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun id(view: View, name: String): Int = runCatching {
        view.resources.getIdentifier(name, "id", ModuleConstants.TARGET_PACKAGE)
    }.getOrDefault(0)
}