package com.oeditor.core.model

/**
 * Deterministic time helpers.
 *
 * The editor uses signed 64-bit microseconds for timeline positions and a rational
 * frame rate (num/den) for frame-quantised operations. Frame indices are derived with
 * integer maths only, so a two-hour timeline cannot accumulate float drift.
 */
object TimeUs {
    const val PER_SECOND: Long = 1_000_000L

    fun seconds(us: Long): Double = us.toDouble() / PER_SECOND

    fun fromSeconds(seconds: Double): Long = Math.round(seconds * PER_SECOND)

    fun fromMillis(ms: Long): Long = ms * 1_000L

    fun toMillis(us: Long): Long = us / 1_000L

    fun frameIndex(us: Long, fpsNum: Int, fpsDen: Int): Long =
        floorDiv(us * fpsNum, PER_SECOND * fpsDen)

    fun frameStartUs(frameIndex: Long, fpsNum: Int, fpsDen: Int): Long =
        frameIndex * PER_SECOND * fpsDen / fpsNum

    fun quantiseToFrame(us: Long, fpsNum: Int, fpsDen: Int): Long =
        frameStartUs(frameIndex(us, fpsNum, fpsDen), fpsNum, fpsDen)

    fun floorDiv(a: Long, b: Long): Long {
        var q = a / b
        if ((a xor b) < 0 && q * b != a) q--
        return q
    }

    fun clamp(value: Long, min: Long, max: Long): Long = when {
        value < min -> min
        value > max -> max
        else -> value
    }

    /** SMPTE-style display, e.g. 00:01:23:12 */
    fun timecode(us: Long, fpsNum: Int, fpsDen: Int): String {
        val safe = us.coerceAtLeast(0L)
        val totalFrames = frameIndex(safe, fpsNum, fpsDen)
        val fps = if (fpsDen == 0) 30 else (fpsNum / fpsDen).coerceAtLeast(1)
        val frames = (totalFrames % fps).toInt()
        val totalSeconds = totalFrames / fps
        val seconds = (totalSeconds % 60).toInt()
        val minutes = ((totalSeconds / 60) % 60).toInt()
        val hours = (totalSeconds / 3600).toInt()
        return "%02d:%02d:%02d:%02d".format(hours, minutes, seconds, frames)
    }

    /** Short display for rulers and lists, e.g. 1:05.2 */
    fun shortLabel(us: Long): String {
        val totalMs = (us.coerceAtLeast(0L) + 500) / 1000
        val seconds = totalMs / 1000
        val tenths = (totalMs % 1000) / 100
        val minutes = seconds / 60
        val secs = seconds % 60
        return if (minutes > 0) "%d:%02d.%d".format(minutes, secs, tenths) else "%d.%ds".format(secs, tenths)
    }
}
