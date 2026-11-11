package dev.amenhancer.module.hook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * Suppresses module layout work while the window is being recreated for an
 * orientation change.
 *
 * During `isChangingConfigurations` the host lays out intermediate sizes and
 * immediately tears them down again; writing bounds, translations and image
 * sizes into that transient tree is pure main-thread cost and is what makes a
 * rotation stutter. The first pass after the new configuration settles is left
 * alone so the module's own layout returns as early as possible.
 */
internal object RotationGuard {
    fun isChanging(context: Context?): Boolean = activityOf(context)?.isChangingConfigurations == true

    fun activityOf(context: Context?): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val base = current.baseContext
            if (base === current) return null
            current = base
        }
        return null
    }
}
