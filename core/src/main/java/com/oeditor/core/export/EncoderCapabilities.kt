package com.oeditor.core.export

import android.media.MediaCodecList
import android.media.MediaFormat

data class CodecCapability(
    val name: String,
    val mime: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxBitrate: Int,
)

/**
 * Hardware capability probe.
 *
 * The UI only offers codecs, resolutions and frame rates that the device's own encoder
 * list reports as supported — nothing is advertised speculatively.
 */
object EncoderCapabilities {

    private val cache = HashMap<String, List<CodecCapability>>()

    fun videoEncoders(): List<CodecCapability> {
        cache["video"]?.let { return it }
        val result = ArrayList<CodecCapability>()
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            for (mime in info.supportedTypes) {
                if (!mime.startsWith("video/")) continue
                val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
                val video = caps.videoCapabilities ?: continue
                result.add(
                    CodecCapability(
                        name = info.name,
                        mime = mime,
                        maxWidth = video.supportedWidths.upper,
                        maxHeight = video.supportedHeights.upper,
                        maxBitrate = video.bitrateRange.upper,
                    )
                )
            }
        }
        cache["video"] = result
        return result
    }

    fun supportsVideo(mime: String, width: Int, height: Int, fps: Int): Boolean {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.contains(mime)) continue
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
            val video = caps.videoCapabilities ?: continue
            val sizeOk = runCatching { video.isSizeSupported(width, height) }.getOrDefault(false)
            if (!sizeOk) continue
            val fpsRange = runCatching { video.getSupportedFrameRatesFor(width, height) }.getOrNull()
            val fpsOk = fpsRange == null || (fps.toDouble() >= fpsRange.lower && fps.toDouble() <= fpsRange.upper)
            if (fpsOk) return true
        }
        return false
    }

    fun supportsAudio(mime: String): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .any { it.isEncoder && it.supportedTypes.contains(mime) }

    fun availableVideoMimes(): List<String> = videoEncoders().map { it.mime }.distinct().sorted()

    /** Picks the best available encoder mime from a preference list. */
    fun bestVideoMime(
        preferred: List<String> = listOf(
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            MediaFormat.MIMETYPE_VIDEO_AVC,
            MediaFormat.MIMETYPE_VIDEO_VP9,
        )
    ): String? = preferred.firstOrNull { mime -> supportsVideo(mime, 1920, 1080, 30) }
        ?: availableVideoMimes().firstOrNull()
}
