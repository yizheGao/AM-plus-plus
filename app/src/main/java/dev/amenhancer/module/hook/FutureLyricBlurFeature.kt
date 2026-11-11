package dev.amenhancer.module.hook

import android.os.Build
import dev.amenhancer.module.ModuleConstants

/** Module setting and health adapter around the upstream AMLyricBlur core. */
internal class FutureLyricBlurFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_FUTURE_BLUR

    override fun install(context: HookContext): FeatureInstallResult {
        // Installed unconditionally: the blur switch, the sung-line fade and the
        // edge feather are read live, so toggling them must not need a restart.
        // The renderer gates the blur radius itself while the switch is off.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return FeatureInstallResult.unsupported("Requires Android 12 or newer")
        }

        return context.target.bidirectionalLyricBlur.install().toFeatureInstallResult()
    }
}

