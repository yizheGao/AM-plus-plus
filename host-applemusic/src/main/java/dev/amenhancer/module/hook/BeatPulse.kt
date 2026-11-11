package dev.amenhancer.module.hook

import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Beat detection from the host's own audio buffers.
 *
 * A Visualizer would need RECORD_AUDIO, which the embedded module cannot add to
 * the host manifest. The host already plays audio through AudioTrack, so the
 * write() calls carry the PCM we need: energy is computed on the fly and an
 * adaptive onset detector turns it into beat pulses.
 */
internal object BeatPulse {
    @Volatile var onBeat: ((Float) -> Unit)? = null
    @Volatile var lastBpm: Int = 0
        private set

    private const val REFRACTORY_MS = 180L
    private const val HISTORY = 32

    private val energies = FloatArray(HISTORY)
    private var cursor = 0
    private var filled = 0
    private val lastBeatAt = AtomicLong(0L)
    private val intervals = LongArray(8)
    private var intervalCursor = 0
    private var intervalFilled = 0
    private var beatsSinceLog = 0
    private var hooked = false

    fun install() {
        if (hooked) return
        hooked = true
        val installed = ArrayList<String>(3)
        runCatching {
            val shortWrite = AudioTrack::class.java.getDeclaredMethod(
                "write", ShortArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
            ModernXposedRuntime.hookMethod(shortWrite, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val data = param.args.getOrNull(0) as? ShortArray ?: return
                    feed(data, (param.result as? Number)?.toInt() ?: return)
                }
            })
            installed += "short[]"
        }
        runCatching {
            val floatWrite = AudioTrack::class.java.getDeclaredMethod(
                "write", FloatArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
            ModernXposedRuntime.hookMethod(floatWrite, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val data = param.args.getOrNull(0) as? FloatArray ?: return
                    val count = minOf((param.result as? Number)?.toInt() ?: return, data.size)
                    var sum = 0.0
                    var i = 0
                    var n = 0
                    while (i < count) { val v = data[i]; sum += (v * v).toDouble(); n++; i += 16 }
                    if (n > 0) detect(kotlin.math.sqrt(sum / n).toFloat() * 32767f)
                }
            })
            installed += "float[]"
        }
        runCatching {
            val byteWrite = AudioTrack::class.java.getDeclaredMethod(
                "write", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
            ModernXposedRuntime.hookMethod(byteWrite, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val data = param.args.getOrNull(0) as? ByteArray ?: return
                    val count = minOf((param.result as? Number)?.toInt() ?: return, data.size)
                    var sum = 0.0
                    var i = 0
                    var n = 0
                    while (i + 1 < count) {
                        val v = ((data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)).toShort().toInt()
                        sum += (v * v).toDouble(); n++; i += 32
                    }
                    if (n > 0) detect(kotlin.math.sqrt(sum / n).toFloat())
                }
            })
            installed += "byte[]"
        }
        runCatching {
            val bufferWrite = AudioTrack::class.java.getDeclaredMethod(
                "write", java.nio.ByteBuffer::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
            ModernXposedRuntime.hookMethod(bufferWrite, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val buffer = param.args.getOrNull(0) as? java.nio.ByteBuffer ?: return
                    val count = (param.result as? Number)?.toInt() ?: return
                    val duplicate = buffer.duplicate()
                    duplicate.position(buffer.position())
                    var sum = 0.0
                    var n = 0
                    var i = 0
                    while (i + 1 < count && duplicate.remaining() >= 2) {
                        val v = duplicate.short.toInt()
                        sum += (v * v).toDouble(); n++; i += 32
                        if (duplicate.remaining() >= 30) duplicate.position(duplicate.position() + 30)
                    }
                    if (n > 0) detect(kotlin.math.sqrt(sum / n).toFloat())
                }
            })
            installed += "ByteBuffer"
        }
        ModernXposedRuntime.log("beat pulse: AudioTrack write hooks = ${if (installed.isEmpty()) "none" else installed.joinToString(",")}")
    }

    /** Cheap RMS over a stride; runs on the audio thread, so keep it tiny. */
    private fun feed(data: ShortArray, count: Int) {
        if (count <= 0) return
        val end = minOf(count, data.size)
        val stride = 16
        var sum = 0.0
        var n = 0
        var i = 0
        while (i < end) {
            val v = data[i].toInt()
            sum += (v * v).toDouble()
            n++
            i += stride
        }
        if (n == 0) return
        val rms = kotlin.math.sqrt(sum / n).toFloat()
        detect(rms)
    }

    private fun detect(rms: Float) {
        if (rms.isNaN() || rms.isInfinite()) return
        val now = System.currentTimeMillis()
        energies[cursor] = rms
        cursor = (cursor + 1) % HISTORY
        if (filled < HISTORY) filled++

        if (filled < 8) return
        var sum = 0f
        for (k in 0 until filled) sum += energies[k]
        val average = sum / filled
        val threshold = average * 1.35f + 20f
        val since = now - lastBeatAt.get()
        if (rms <= threshold || since < REFRACTORY_MS) return

        lastBeatAt.set(now)
        if (since in 200L..2000L) {
            intervals[intervalCursor] = since
            intervalCursor = (intervalCursor + 1) % intervals.size
            if (intervalFilled < intervals.size) intervalFilled++
            if (intervalFilled >= 4) {
                val sorted = intervals.take(intervalFilled).sorted()
                val median = sorted[sorted.size / 2]
                if (median > 0) lastBpm = (60_000L / median).toInt().coerceIn(40, 220)
            }
        }
        val strength = ((rms - threshold) / (average.coerceAtLeast(1f))).coerceIn(0.2f, 1f)
        beatsSinceLog++
        if (beatsSinceLog >= 8) {
            beatsSinceLog = 0
            ModernXposedRuntime.log("beat pulse: bpm=$lastBpm strength=${"%.2f".format(strength)}")
        }
        onBeat?.invoke(strength)
    }
}
