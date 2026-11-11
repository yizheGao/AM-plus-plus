package dev.amenhancer.module.hook

import dev.amenhancer.module.ModuleConstants

/** Independent setting/health adapter for the all-player-lyrics typeface capability. */
internal class LyricsTypefaceFeature : FeatureHook {
    override val key: String = ModuleConstants.FEATURE_LYRICS_TYPEFACE

    override fun install(context: HookContext): FeatureInstallResult {
        // Installed unconditionally: bold is read live on every apply, and an
        // import that arrives later only needs the host to relayout a lyric row.
        return context.target.lyricsTypeface.install().toFeatureInstallResult()
    }
}
