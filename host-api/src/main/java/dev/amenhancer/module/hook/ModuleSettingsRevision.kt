package dev.amenhancer.module.hook

import java.util.concurrent.atomic.AtomicInteger

/**
 * In-process revision of the ordinary settings store.
 *
 * The injected settings UI and the lyric pipeline live in the same host
 * process, so a counter is enough to let frame-driven renderers notice that
 * the user changed an effect switch and re-read the values without waiting
 * for an Apple Music restart.
 */
object ModuleSettingsRevision {
    private val counter = AtomicInteger(0)

    /** Called after a successful ordinary-settings write. */
    fun bump() {
        counter.incrementAndGet()
    }

    fun current(): Int = counter.get()
}
