package com.manst.lantransfer.transfer

/** 一次可推送给 UI 的进度样本。 */
data class TransferProgressSample(
    val transferredBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long
)

/**
 * 传输瞬时速度计算（滑动窗口，纯 JVM 逻辑，可单元测试）。
 *
 * 保留 [windowMillis] 时间窗内的采样点（额外保留窗口外最近的锚点保证精度），
 * 速度 = 窗口内字节增量 / 实际时间差；采样不足或增量非正时返回 0。
 */
class TransferSpeedMeter(private val windowMillis: Long = DEFAULT_WINDOW_MILLIS) {

    private data class Sample(val timeMillis: Long, val bytes: Long)

    private val samples = ArrayDeque<Sample>()

    /** 记录一次“累计已传输 [totalBytes] 字节”的采样；时间单位为毫秒且应单调递增。 */
    fun sample(nowMillis: Long, totalBytes: Long) {
        if (samples.isNotEmpty() && nowMillis < samples.last().timeMillis) {
            samples.clear()
        }
        samples.addLast(Sample(nowMillis, totalBytes))
        while (samples.size > 1 && samples[1].timeMillis <= nowMillis - windowMillis) {
            samples.removeFirst()
        }
    }

    /** 当前瞬时速度（字节/秒），无法计算时返回 0。 */
    fun bytesPerSecond(): Long {
        if (samples.size < 2) return 0L
        val first = samples.first()
        val last = samples.last()
        val elapsed = last.timeMillis - first.timeMillis
        if (elapsed <= 0L) return 0L
        val delta = last.bytes - first.bytes
        if (delta <= 0L) return 0L
        return delta * 1000L / elapsed
    }

    fun reset() {
        samples.clear()
    }

    /** 当前窗口内的采样点数量（测试与诊断用）。 */
    fun sampleCount(): Int = samples.size

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 2_000L
    }
}

/**
 * 进度节流：满足「距上次推送 >= [emitIntervalMillis]」或「字节增量 >= [emitByteStep]」
 * 或「已到最后一个字节」时才推送，避免高频回调拖垮 UI（纯 JVM 逻辑，可单元测试）。
 */
class TransferProgressThrottler(
    private val emitIntervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val emitByteStep: Long = DEFAULT_BYTE_STEP
) {

    private var lastEmitAt = Long.MIN_VALUE
    private var lastEmitBytes = -1L

    /** 是否应在本次回调时推送进度。 */
    fun shouldEmit(nowMillis: Long, transferredBytes: Long, totalBytes: Long): Boolean {
        val firstSample = lastEmitBytes < 0L
        val intervalReached = nowMillis - lastEmitAt >= emitIntervalMillis
        val bytesReached = transferredBytes - lastEmitBytes >= emitByteStep
        val finished = totalBytes > 0L && transferredBytes >= totalBytes
        if (!firstSample && !intervalReached && !bytesReached && !finished) return false
        lastEmitAt = nowMillis
        lastEmitBytes = transferredBytes
        return true
    }

    fun reset() {
        lastEmitAt = Long.MIN_VALUE
        lastEmitBytes = -1L
    }

    companion object {
        const val DEFAULT_INTERVAL_MILLIS = 100L
        const val DEFAULT_BYTE_STEP = 128L * 1024L
    }
}

/**
 * 每个任务的进度聚合器：内部同时维护速度窗口与节流状态。
 * [sample] 在不需要推送时返回 null；需要推送时返回包含瞬时速度的样本。
 */
class TransferProgressTracker(
    emitIntervalMillis: Long = TransferProgressThrottler.DEFAULT_INTERVAL_MILLIS,
    emitByteStep: Long = TransferProgressThrottler.DEFAULT_BYTE_STEP,
    speedWindowMillis: Long = TransferSpeedMeter.DEFAULT_WINDOW_MILLIS
) {

    private val throttler = TransferProgressThrottler(emitIntervalMillis, emitByteStep)
    private val speedMeter = TransferSpeedMeter(speedWindowMillis)

    fun reset() {
        throttler.reset()
        speedMeter.reset()
    }

    fun sample(nowMillis: Long, transferredBytes: Long, totalBytes: Long): TransferProgressSample? {
        speedMeter.sample(nowMillis, transferredBytes)
        if (!throttler.shouldEmit(nowMillis, transferredBytes, totalBytes)) return null
        return TransferProgressSample(
            transferredBytes = transferredBytes,
            totalBytes = totalBytes,
            bytesPerSecond = speedMeter.bytesPerSecond()
        )
    }
}
