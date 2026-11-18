package com.oeditor.core.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import com.oeditor.core.audio.AudioMixer
import com.oeditor.core.gl.Compositor
import com.oeditor.core.gl.FrameProvider
import com.oeditor.core.gl.GlEnv
import com.oeditor.core.media.MediaFrameProvider
import com.oeditor.core.model.ProjectDocument
import com.oeditor.core.model.TimeUs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

data class ExportRequest(
    val outputFile: File,
    val width: Int,
    val height: Int,
    val fpsNum: Int,
    val fpsDen: Int,
    val videoMime: String,
    val videoBitrate: Int,
    val includeAudio: Boolean = true,
    val audioMime: String = MediaFormat.MIMETYPE_AUDIO_AAC,
    val audioBitrate: Int = 192_000,
    val sampleRate: Int = 48_000,
    val channels: Int = 2,
)

data class ExportResult(
    val success: Boolean,
    val file: File?,
    val message: String = "",
    val framesRendered: Long = 0L,
)

/**
 * Export engine.
 *
 * Renders the timeline frame by frame through the same [Compositor] the preview uses,
 * pushes each frame into a MediaCodec encoder surface, mixes the audio from real PCM,
 * then muxes both into a single playable file. Progress reported here is measured, not
 * simulated: completed frames over total frames.
 */
class ExportEngine(
    private val context: Context,
    private val provider: FrameProvider,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** True when this engine built [provider] itself and may dispose of it. */
    private val ownsProvider: Boolean = false,
) {
    constructor(context: Context, dispatcher: CoroutineDispatcher = Dispatchers.Default) :
        this(context, MediaFrameProvider(context), dispatcher, ownsProvider = true)

    suspend fun export(
        doc: ProjectDocument,
        request: ExportRequest,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): ExportResult = withContext(dispatcher) {
        val durationUs = doc.timeline.durationUs()
        if (durationUs <= 0L) {
            return@withContext ExportResult(false, null, "Timeline is empty")
        }
        if (!EncoderCapabilities.supportsVideo(
                request.videoMime, request.width, request.height,
                (request.fpsNum / request.fpsDen).coerceAtLeast(1)
            )
        ) {
            return@withContext ExportResult(
                false, null,
                "This device cannot encode ${request.width}x${request.height} with ${request.videoMime}"
            )
        }

        val frameCount = (durationUs * request.fpsNum) / (1_000_000L * request.fpsDen) + 1
        val tempDir = File(context.cacheDir, "oeditor_export").apply { mkdirs() }
        val videoTemp = File(tempDir, "video_${System.nanoTime()}.mp4")
        val audioTemp = File(tempDir, "audio_${System.nanoTime()}.m4a")
        val pcmTemp = File(tempDir, "mix_${System.nanoTime()}.pcm")

        var encoder: MediaCodec? = null
        var gl: GlEnv? = null
        var compositor: Compositor? = null
        var muxer: MediaMuxer? = null

        try {
            onProgress(0f, "Preparing encoder")
            val format = MediaFormat.createVideoFormat(request.videoMime, request.width, request.height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, request.videoBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, (request.fpsNum / request.fpsDen).coerceAtLeast(1))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder = MediaCodec.createEncoderByType(request.videoMime)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface: Surface = encoder.createInputSurface()
            encoder.start()

            muxer = MediaMuxer(videoTemp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            gl = GlEnv.createWindow(inputSurface)
            gl.makeCurrent()

            compositor = Compositor(provider)
            compositor.setBoundTarget(0)

            val bufferInfo = MediaCodec.BufferInfo()
            var videoTrack = -1
            var muxerStarted = false
            var framesRendered = 0L

            for (frame in 0 until frameCount) {
                coroutineContext.ensureActive()
                val timeUs = TimeUs.frameStartUs(frame, request.fpsNum, request.fpsDen)
                compositor.render(doc, timeUs, request.width, request.height)
                gl.setPresentationTime(frame * 1_000_000_000L * request.fpsDen / request.fpsNum)
                gl.swap()

                while (true) {
                    val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            videoTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        continue
                    }
                    if (outIndex >= 0) {
                        val encoded = encoder.getOutputBuffer(outIndex)
                        if (encoded != null && bufferInfo.size > 0 &&
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && muxerStarted
                        ) {
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoTrack, encoded, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                    }
                }

                framesRendered = frame + 1
                if (frame % 3 == 0L || frame == frameCount - 1) {
                    onProgress(
                        (framesRendered.toFloat() / frameCount) * 0.85f,
                        "Rendering frame $framesRendered of $frameCount"
                    )
                }
            }

            encoder.signalEndOfInputStream()
            var eos = false
            while (!eos) {
                val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) continue
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        videoTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    continue
                }
                if (outIndex >= 0) {
                    val encoded = encoder.getOutputBuffer(outIndex)
                    if (encoded != null && bufferInfo.size > 0 &&
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && muxerStarted
                    ) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(videoTrack, encoded, bufferInfo)
                    }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                    encoder.releaseOutputBuffer(outIndex, false)
                }
            }
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
            muxer = null

            var audioTempFile: File? = null
            if (request.includeAudio) {
                onProgress(0.87f, "Mixing audio")
                val mixer = AudioMixer(context)
                val mix = mixer.renderTimeline(
                    doc = doc,
                    outFile = pcmTemp,
                    durationUs = durationUs,
                    sampleRate = request.sampleRate,
                    channels = request.channels,
                )
                if (mix.frames > 0) {
                    if (encodeAudio(mix, request, audioTemp)) audioTempFile = audioTemp
                }
                onProgress(0.93f, "Audio ready")
            }

            onProgress(0.95f, "Writing output")
            val output = request.outputFile
            output.parentFile?.mkdirs()
            if (output.exists()) output.delete()
            muxSegments(videoTemp, audioTempFile, output)
            onProgress(1f, "Export complete")

            ExportResult(
                success = output.exists() && output.length() > 0,
                file = output,
                message = "Exported $framesRendered frames",
                framesRendered = framesRendered,
            )
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) {
                return@withContext ExportResult(false, null, "Export cancelled")
            }
            return@withContext ExportResult(false, null, t.message ?: "Export failed")
        } finally {
            runCatching { compositor?.release() }
            runCatching { gl?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.release() }
            runCatching { videoTemp.delete() }
            runCatching { audioTemp.delete() }
            runCatching { pcmTemp.delete() }
        }
    }

    private fun encodeAudio(
        mix: AudioMixer.MixResult,
        request: ExportRequest,
        outFile: File,
    ): Boolean {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var file: RandomAccessFile? = null
        return try {
            val format = MediaFormat.createAudioFormat(
                request.audioMime, mix.sampleRate, mix.channels
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, request.audioBitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            codec = MediaCodec.createEncoderByType(request.audioMime)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val info = MediaCodec.BufferInfo()
            val bytesPerFrame = mix.channels * 2
            val chunkBytes = 4096 * bytesPerFrame
            val buffer = ByteArray(chunkBytes)
            var track = -1
            var started = false
            var ptsUs = 0L
            var eosInput = false
            var stalled = 0
            file = RandomAccessFile(mix.pcmFile, "r")

            while (true) {
                if (stalled > 5000) break // codec is not draining; stop instead of hanging the export
                if (!eosInput) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val input = codec.getInputBuffer(inIndex) ?: continue
                        input.clear()
                        val read = file.read(buffer)
                        if (read <= 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosInput = true
                        } else {
                            input.put(buffer, 0, read)
                            codec.queueInputBuffer(inIndex, 0, read, ptsUs, 0)
                            ptsUs += (read / bytesPerFrame).toLong() * 1_000_000L / mix.sampleRate
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) stalled++ else stalled = 0
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                } else if (outIndex >= 0) {
                    val encoded = codec.getOutputBuffer(outIndex)
                    if (encoded != null && info.size > 0 && started &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        encoded.position(info.offset)
                        encoded.limit(info.offset + info.size)
                        muxer.writeSampleData(track, encoded, info)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            runCatching { file.close() }
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            outFile.exists() && outFile.length() > 0
        } catch (_: Throwable) {
            runCatching { file?.close() }
            runCatching { muxer?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            false
        }
    }

    /** Remuxes the video-only and audio-only intermediates into the final container. */
    private fun muxSegments(video: File, audio: File?, output: File) {
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val extractors = ArrayList<MediaExtractor>()
        try {
            val videoExtractor = MediaExtractor()
            videoExtractor.setDataSource(video.absolutePath)
            extractors.add(videoExtractor)
            val videoTrackIndex = findTrack(videoExtractor, "video/")
            val outVideo = muxer.addTrack(videoExtractor.getTrackFormat(videoTrackIndex))

            var outAudio = -1
            var audioExtractor: MediaExtractor? = null
            if (audio != null && audio.exists()) {
                audioExtractor = MediaExtractor()
                audioExtractor.setDataSource(audio.absolutePath)
                extractors.add(audioExtractor)
                val audioTrackIndex = findTrack(audioExtractor, "audio/")
                if (audioTrackIndex >= 0) {
                    outAudio = muxer.addTrack(audioExtractor.getTrackFormat(audioTrackIndex))
                    audioExtractor.selectTrack(audioTrackIndex)
                }
            }
            videoExtractor.selectTrack(videoTrackIndex)
            muxer.start()

            val info = MediaCodec.BufferInfo()
            val buffer = ByteBuffer.allocateDirect(1 shl 20)

            fun copy(extractor: MediaExtractor, outTrack: Int) {
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                        MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(outTrack, buffer, info)
                    extractor.advance()
                }
            }

            copy(videoExtractor, outVideo)
            if (audioExtractor != null && outAudio >= 0) copy(audioExtractor, outAudio)

            runCatching { muxer.stop() }
        } finally {
            runCatching { muxer.release() }
            extractors.forEach { runCatching { it.release() } }
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return 0
    }

    /**
     * The provider belongs to the caller (the preview shares it), so releasing it here
     * would blank the preview. Only an engine that created its own provider may release it.
     */
    fun release() {
        if (ownsProvider) (provider as? MediaFrameProvider)?.release()
    }
}
