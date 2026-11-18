package com.oeditor.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.oeditor.core.gl.FrameProvider
import com.oeditor.core.model.MediaAsset
import com.oeditor.core.model.MediaKind
import java.io.File

/**
 * Frame supply for preview and export.
 *
 * Frames are pulled with MediaMetadataRetriever, which handles every container the
 * platform decodes and returns a correctly scaled bitmap. Retrievers are cached per
 * asset because opening one costs a file descriptor and a parser initialisation.
 */
class MediaFrameProvider(private val context: Context) : FrameProvider {

    // Accessed from the GL render thread, the export thread and IO: MediaMetadataRetriever
    // is not thread-safe, so every access is serialised on one lock.
    private val lock = Any()
    private val retrievers = HashMap<String, MediaMetadataRetriever>()
    private val stillCache = HashMap<String, Bitmap>()

    private fun retriever(uri: String): MediaMetadataRetriever? = synchronized(lock) {
        retrievers[uri]?.let { return it }
        try {
            val r = MediaMetadataRetriever()
            r.setDataSource(context, Uri.parse(uri))
            retrievers[uri] = r
            r
        } catch (_: Exception) {
            null
        }
    }

    override fun frame(asset: MediaAsset, sourceTimeUs: Long, maxDimension: Int): Bitmap? {
        if (asset.kind == MediaKind.IMAGE) return still(asset, maxDimension)
        val r = retriever(asset.uri) ?: return null
        val option = MediaMetadataRetriever.OPTION_CLOSEST
        return synchronized(lock) { try {
            val width = asset.width
            val height = asset.height
            if (width > 0 && height > 0) {
                val scale = maxDimension.toFloat() / maxOf(width, height).toFloat()
                if (scale < 1f) {
                    val w = (width * scale).toInt().coerceAtLeast(2)
                    val h = (height * scale).toInt().coerceAtLeast(2)
                    r.getScaledFrameAtTime(sourceTimeUs.coerceAtLeast(0L), option, w, h)
                } else {
                    r.getFrameAtTime(sourceTimeUs.coerceAtLeast(0L), option)
                }
            } else {
                r.getFrameAtTime(sourceTimeUs.coerceAtLeast(0L), option)
            }
        } catch (_: Exception) {
            null
        } }
    }

    fun still(asset: MediaAsset, maxDimension: Int): Bitmap? = synchronized(lock) {
        stillCache[asset.uri]?.let { return it }
        val bitmap = decodeStill(context, Uri.parse(asset.uri), maxDimension)
        if (bitmap != null) stillCache[asset.uri] = bitmap
        bitmap
    }

    fun release() {
        synchronized(lock) {
            retrievers.values.forEach { runCatching { it.release() } }
            retrievers.clear()
            stillCache.clear()
        }
    }

    companion object {
        fun decodeStill(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
            return try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDimension) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** Reads container/stream metadata into a [MediaAsset] without decoding media. */
object MediaProbe {

    fun probe(context: Context, uri: Uri, displayName: String, kindHint: MediaKind? = null): MediaAsset? {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: ""
        val kind = kindHint ?: when {
            mime.startsWith("image/") -> MediaKind.IMAGE
            mime.startsWith("audio/") -> MediaKind.AUDIO
            else -> MediaKind.VIDEO
        }
        val retriever = MediaMetadataRetriever()
        var durationUs = 0L
        var width = 0
        var height = 0
        var rotation = 0
        var frameRateNum = 0
        var frameRateDen = 1
        try {
            retriever.setDataSource(context, uri)
            durationUs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.times(1000L) ?: 0L
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull()
            if (fps != null && fps > 0f) {
                frameRateNum = Math.round(fps * 1000f)
                frameRateDen = 1000
            }
        } catch (_: Exception) {
            // Some containers refuse the retriever; MediaExtractor below may still work.
        } finally {
            runCatching { retriever.release() }
        }

        var sampleRate = 0
        var channels = 0
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val trackMime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (trackMime.startsWith("audio/") && sampleRate == 0) {
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                if (trackMime.startsWith("video/") && width == 0) {
                    width = format.getInteger(MediaFormat.KEY_WIDTH)
                    height = format.getInteger(MediaFormat.KEY_HEIGHT)
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        durationUs = format.getLong(MediaFormat.KEY_DURATION)
                    }
                }
            }
        } catch (_: Exception) {
            // ignore
        } finally {
            runCatching { extractor.release() }
        }

        if (width == 0 && height == 0 && sampleRate == 0 && kind != MediaKind.IMAGE) return null

        return MediaAsset(
            id = "asset_" + java.lang.Long.toHexString(System.nanoTime()),
            uri = uri.toString(),
            displayName = displayName,
            kind = kind,
            durationUs = if (kind == MediaKind.IMAGE) 5_000_000L else durationUs,
            width = width,
            height = height,
            rotationDeg = rotation,
            sampleRate = sampleRate,
            channelCount = channels,
            mimeType = mime,
            fileSizeBytes = fileSize(context, uri),
            frameRateNum = frameRateNum,
            frameRateDen = frameRateDen,
            importedAt = System.currentTimeMillis(),
        )
    }

    fun fileSize(context: Context, uri: Uri): Long = try {
        if (uri.scheme == "file") {
            File(uri.path ?: "").length()
        } else {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
        }
    } catch (_: Exception) {
        0L
    }

    fun exists(context: Context, uri: String): Boolean = try {
        val parsed = Uri.parse(uri)
        if (parsed.scheme == "file") {
            File(parsed.path ?: "").exists()
        } else {
            context.contentResolver.openAssetFileDescriptor(parsed, "r")?.use { true } ?: false
        }
    } catch (_: Exception) {
        false
    }

    fun thumbnail(context: Context, asset: MediaAsset, maxDimension: Int = 512): Bitmap? {
        if (asset.kind == MediaKind.IMAGE) {
            return MediaFrameProvider.decodeStill(context, Uri.parse(asset.uri), maxDimension)
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(asset.uri))
            val time = if (asset.durationUs > 0) asset.durationUs / 3 else 0L
            retriever.getScaledFrameAtTime(
                time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxDimension, maxDimension
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
