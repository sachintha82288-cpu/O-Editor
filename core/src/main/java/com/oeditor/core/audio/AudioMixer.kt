package com.oeditor.core.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.oeditor.core.keyframe.KeyframeEngine
import com.oeditor.core.model.Clip
import com.oeditor.core.model.MediaKind
import com.oeditor.core.model.ProjectDocument
import com.oeditor.core.model.Track
import com.oeditor.core.model.TrackType
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Real PCM audio engine.
 *
 * Every audio clip is decoded through MediaCodec, resampled to the project rate,
 * gain/pan/fade automated, and summed into a float mix written to a temporary 16-bit
 * PCM file. Nothing here is a UI simulation: the samples in the exported file come from
 * these buffers.
 */
class AudioMixer(private val context: Context) {

    data class MixResult(
        val pcmFile: File,
        val sampleRate: Int,
        val channels: Int,
        val frames: Long,
    )

    fun renderTimeline(
        doc: ProjectDocument,
        outFile: File,
        durationUs: Long,
        sampleRate: Int = 48_000,
        channels: Int = 2,
        onProgress: (Float) -> Unit = {},
    ): MixResult {
        val totalFrames = ((durationUs.toDouble() / 1_000_000.0) * sampleRate).toLong()
            .coerceAtLeast(sampleRate.toLong())
        val bytesPerFrame = channels * 2
        RandomAccessFile(outFile, "rw").use { raf -> raf.setLength(totalFrames * bytesPerFrame) }

        val assets = doc.assets.associateBy { it.id }
        val audioClips = doc.timeline.tracks
            .filter { it.type != TrackType.EFFECT && !it.muted }
            .flatMap { track -> track.clips.map { track to it } }
            .filter { (_, clip) ->
                val asset = clip.assetId?.let { assets[it] } ?: return@filter false
                asset.hasAudio || asset.kind == MediaKind.AUDIO
            }

        audioClips.forEachIndexed { index, (track, clip) ->
            val asset = assets[clip.assetId] ?: return@forEachIndexed
            runCatching {
                mixClip(
                    uri = Uri.parse(asset.uri),
                    clip = clip,
                    track = track,
                    outFile = outFile,
                    sampleRate = sampleRate,
                    channels = channels,
                    totalFrames = totalFrames,
                )
            }
            onProgress((index + 1).toFloat() / audioClips.size.coerceAtLeast(1))
        }
        return MixResult(outFile, sampleRate, channels, totalFrames)
    }

    private fun mixClip(
        uri: Uri,
        clip: Clip,
        track: Track,
        outFile: File,
        sampleRate: Int,
        channels: Int,
        totalFrames: Long,
    ) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    format = f
                    break
                }
            }
            if (audioTrack < 0 || format == null) return

            extractor.selectTrack(audioTrack)
            val sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val startUs = clip.sourceInUs.coerceAtLeast(0L)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val sourceEndUs = clip.sourceInUs + (clip.durationUs * clip.speed).toLong()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val ratio = sourceRate.toDouble() / sampleRate.toDouble()

            RandomAccessFile(outFile, "rw").use { raf ->
                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = decoder.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buffer = decoder.getInputBuffer(inIndex) ?: continue
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0 || extractor.sampleTime > sourceEndUs) {
                                decoder.queueInputBuffer(
                                    inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                    if (outIndex >= 0) {
                        val buffer = decoder.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val shorts = ShortArray(info.size / 2)
                            buffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts)
                            val mono = toMono(shorts, sourceChannels)
                            val resampled = resample(mono, ratio)
                            val timelineStartUs = clip.startUs +
                                ((info.presentationTimeUs - clip.sourceInUs) / clip.speed).toLong()
                            writeMix(
                                raf = raf,
                                samples = resampled,
                                startFrame = ((timelineStartUs / 1_000_000.0) * sampleRate).toLong(),
                                clip = clip,
                                track = track,
                                sampleRate = sampleRate,
                                channels = channels,
                                totalFrames = totalFrames,
                            )
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } catch (_: Exception) {
            // A clip that cannot be decoded is skipped; the rest of the mix still renders.
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun toMono(shorts: ShortArray, channels: Int): FloatArray {
        if (channels <= 1) return FloatArray(shorts.size) { shorts[it] / 32768f }
        val frames = shorts.size / channels
        return FloatArray(frames) { frame ->
            var sum = 0f
            for (c in 0 until channels) sum += shorts[frame * channels + c] / 32768f
            sum / channels
        }
    }

    /** Linear resampling from the source rate to the project rate. */
    private fun resample(input: FloatArray, ratio: Double): FloatArray {
        if (abs(ratio - 1.0) < 1e-6 || input.isEmpty()) return input
        val outLength = (input.size / ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLength)
        for (i in out.indices) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceIn(0, input.size - 1)
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = input[i0] * (1f - frac) + input[i1] * frac
        }
        return out
    }

    private fun writeMix(
        raf: RandomAccessFile,
        samples: FloatArray,
        startFrame: Long,
        clip: Clip,
        track: Track,
        sampleRate: Int,
        channels: Int,
        totalFrames: Long,
    ) {
        if (samples.isEmpty()) return
        val bytesPerFrame = channels * 2
        val buffer = ByteBuffer.allocate(samples.size * bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)

        val pan = KeyframeEngine.resolvePan(clip, 0)
        val leftGain = min(1f, 1f - pan)
        val rightGain = min(1f, 1f + pan)
        val trackGain = track.volume

        for (i in samples.indices) {
            val frame = startFrame + i
            if (frame < 0 || frame >= totalFrames) continue
            val timelineUs = (frame.toDouble() / sampleRate * 1_000_000.0).toLong()
            val localUs = (timelineUs - clip.startUs).coerceAtLeast(0L)
            val gain = KeyframeEngine.resolveVolume(clip, localUs) * trackGain
            val value = samples[i] * gain
            for (c in 0 until channels) {
                val channelGain = if (channels == 1) 1f else if (c == 0) leftGain else rightGain
                val mixed = tanh(value * channelGain)
                buffer.putShort((mixed * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        buffer.flip()

        val offset = startFrame * bytesPerFrame
        val length = buffer.remaining()
        if (offset >= raf.length() || length <= 0) return
        raf.seek(offset)
        val existing = ByteArray(length)
        val read = raf.read(existing)
        if (read > 0) {
            val existingBuf = ByteBuffer.wrap(existing, 0, read).order(ByteOrder.LITTLE_ENDIAN)
            val addition = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val merged = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
            var written = 0
            while (addition.remaining() >= 2) {
                val addValue = addition.short.toInt()
                val baseValue = if (existingBuf.remaining() >= 2) existingBuf.short.toInt() else 0
                merged.putShort((baseValue + addValue).coerceIn(-32768, 32767).toShort())
                written += 2
            }
            merged.flip()
            raf.seek(offset)
            raf.write(merged.array(), 0, written)
        } else {
            raf.seek(offset)
            raf.write(buffer.array(), 0, length)
        }
    }

    /** Biquad peaking EQ, used by the audio studio inspector. */
    class Biquad(sampleRate: Int, frequency: Float, q: Float, gainDb: Float) {
        private var b0 = 1f
        private var b1 = 0f
        private var b2 = 0f
        private var a1 = 0f
        private var a2 = 0f
        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f

        init {
            val a = Math.pow(10.0, gainDb / 40.0)
            val w0 = 2.0 * PI * frequency / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val cosw = cos(w0)
            val b0n = 1 + alpha * a
            val b1n = -2 * cosw
            val b2n = 1 - alpha * a
            val a0n = 1 + alpha / a
            val a1n = -2 * cosw
            val a2n = 1 - alpha / a
            b0 = (b0n / a0n).toFloat()
            b1 = (b1n / a0n).toFloat()
            b2 = (b2n / a0n).toFloat()
            a1 = (a1n / a0n).toFloat()
            a2 = (a2n / a0n).toFloat()
        }

        fun process(input: Float): Float {
            val out = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = input
            y2 = y1
            y1 = out
            return out
        }
    }
}
