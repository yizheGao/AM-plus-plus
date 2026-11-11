package dev.amenhancer.module.hook

import android.media.MediaCodec
import java.nio.ByteOrder

/**
 * Probe: does the host's decoded PCM reach a hookable Java path?
 *
 * ExoPlayer pulls audio out of MediaCodec through dequeueOutputBuffer, so the
 * decoded PCM is readable there without any permission. This build only measures
 * and logs; nothing is rendered yet.
 */
internal object PcmProbe {
    private var installed = false
    private var packets = 0L
    private var samples = 0L
    private var sumSquares = 0.0
    private var peak = 0
    private var lastLog = 0L

    fun install() {
        if (installed) return
        installed = true
        runCatching {
            val dequeue = MediaCodec::class.java.getDeclaredMethod(
                "dequeueOutputBuffer",
                MediaCodec.BufferInfo::class.java,
                Long::class.javaPrimitiveType,
            )
            ModernXposedRuntime.hookMethod(dequeue, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val index = (param.result as? Number)?.toInt() ?: return
                    if (index < 0) return
                    val codec = param.thisObject as? MediaCodec ?: return
                    val info = param.args.getOrNull(0) as? MediaCodec.BufferInfo ?: return
                    if (info.size <= 0) return
                    val buffer = runCatching { codec.getOutputBuffer(index) }.getOrNull() ?: return
                    analyze(buffer, info)
                }
            })
            ModernXposedRuntime.log("pcm probe: MediaCodec.dequeueOutputBuffer hook installed")
        }.onFailure { ModernXposedRuntime.log("pcm probe: dequeue hook failed: $it") }

        // Async mode never calls dequeueOutputBuffer: the adapter goes straight
        // to getOutputBuffer()/releaseOutputBuffer().
        runCatching {
            val getOutput = MediaCodec::class.java.getDeclaredMethod("getOutputBuffer", Int::class.javaPrimitiveType)
            ModernXposedRuntime.hookMethod(getOutput, object : ModernMethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.throwable != null) return
                    val buffer = param.result as? java.nio.ByteBuffer ?: return
                    analyzeBuffer(buffer)
                }
            })
            ModernXposedRuntime.log("pcm probe: MediaCodec.getOutputBuffer hook installed")
        }.onFailure { ModernXposedRuntime.log("pcm probe: getOutputBuffer hook failed: $it") }

        runCatching {
            val releaseLong = MediaCodec::class.java.getDeclaredMethod(
                "releaseOutputBuffer", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType,
            )
            ModernXposedRuntime.hookMethod(releaseLong, object : ModernMethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val index = (param.args.getOrNull(0) as? Number)?.toInt() ?: return
                    val codec = param.thisObject as? MediaCodec ?: return
                    val buffer = runCatching { codec.getOutputBuffer(index) }.getOrNull() ?: return
                    analyzeBuffer(buffer)
                }
            })
            ModernXposedRuntime.log("pcm probe: releaseOutputBuffer(long) hook installed")
        }.onFailure { ModernXposedRuntime.log("pcm probe: release hook failed: $it") }
    }

    /** Reads whatever range the framework left on the buffer. */
    private fun analyzeBuffer(buffer: java.nio.ByteBuffer) {
        runCatching {
            val copy = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            if (!copy.hasRemaining()) return
            var localSum = 0.0
            var localCount = 0
            var localPeak = 0
            var guard = 0
            while (copy.remaining() >= 2 && guard++ < 4096) {
                val value = copy.short.toInt()
                localSum += (value * value).toDouble()
                localCount++
                val magnitude = if (value < 0) -value else value
                if (magnitude > localPeak) localPeak = magnitude
                if (copy.remaining() >= 62) copy.position(copy.position() + 62)
            }
            if (localCount == 0) return
            synchronized(this) {
                packets++
                samples += localCount
                sumSquares += localSum
                if (localPeak > peak) peak = localPeak
                val now = System.currentTimeMillis()
                if (now - lastLog >= 2000L) {
                    lastLog = now
                    val rms = kotlin.math.sqrt(sumSquares / samples.coerceAtLeast(1))
                    ModernXposedRuntime.log(
                        "pcm probe: packets=$packets samples=$samples rms=${"%.0f".format(rms)} peak=$peak",
                    )
                    packets = 0
                    samples = 0
                    sumSquares = 0.0
                    peak = 0
                }
            }
        }
    }

    private fun analyze(buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        runCatching {
            val copy = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            copy.position(info.offset)
            copy.limit((info.offset + info.size).coerceAtMost(buffer.capacity()))
            var localSum = 0.0
            var localCount = 0
            var localPeak = 0
            while (copy.remaining() >= 2) {
                val value = copy.short.toInt()
                localSum += (value * value).toDouble()
                localCount++
                val magnitude = if (value < 0) -value else value
                if (magnitude > localPeak) localPeak = magnitude
                if (copy.remaining() >= 62) copy.position(copy.position() + 62)
            }
            if (localCount == 0) return
            synchronized(this) {
                packets++
                samples += localCount
                sumSquares += localSum
                if (localPeak > peak) peak = localPeak
                val now = System.currentTimeMillis()
                if (now - lastLog >= 2000L) {
                    lastLog = now
                    val rms = kotlin.math.sqrt(sumSquares / samples.coerceAtLeast(1))
                    ModernXposedRuntime.log(
                        "pcm probe: packets=$packets samples=$samples rms=${"%.0f".format(rms)} peak=$peak",
                    )
                    packets = 0
                    samples = 0
                    sumSquares = 0.0
                    peak = 0
                }
            }
        }
    }
}
