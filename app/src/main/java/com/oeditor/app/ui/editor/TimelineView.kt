package com.oeditor.app.ui.editor

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.oeditor.app.ui.theme.OColors
import com.oeditor.core.model.TrackType
import com.oeditor.core.timeline.TrimEdge
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

private const val HEADER_DP = 88f
private const val ROW_DP = 48f
private const val RULER_DP = 22f

private enum class DragKind { MOVE, TRIM_START, TRIM_END, SCRUB, SCROLL }

private data class DragState(
    val kind: DragKind,
    val clipId: String? = null,
    val originalStartUs: Long = 0L,
    val originalDurationUs: Long = 0L,
    val sourceTrackIndex: Int = 0,
    var deltaX: Float = 0f,
    var deltaY: Float = 0f,
    var targetTrackIndex: Int = 0,
)

/**
 * Multi-track timeline.
 *
 * One Canvas draws the ruler, track headers, clips, markers and playhead so everything
 * stays pixel-aligned. Dragging a clip only commits on release, which keeps one undo
 * entry per gesture instead of one per frame.
 */
@Composable
fun TimelineView(
    state: EditorUiState,
    onSeek: (Long) -> Unit,
    onSelectClip: (String?) -> Unit,
    onMoveClip: (clipId: String, trackId: String, startUs: Long) -> Unit,
    onTrim: (clipId: String, edge: TrimEdge, timeUs: Long) -> Unit,
    onSnap: (timeUs: Long, thresholdUs: Long, excludeClipId: String?) -> Long,
    onToggleMute: (trackId: String) -> Unit,
    onToggleLock: (trackId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val headerPx = with(density) { HEADER_DP.dp.toPx() }
    val rowPx = with(density) { ROW_DP.dp.toPx() }
    val rulerPx = with(density) { RULER_DP.dp.toPx() }

    var scrollPx by remember { mutableStateOf(0f) }
    var drag by remember { mutableStateOf<DragState?>(null) }

    val tracks = remember(state.doc.timeline.tracks) {
        state.doc.timeline.tracks.sortedByDescending { it.order }
    }
    val pps = state.pixelsPerSecond * density.density

    fun timeToX(us: Long): Float = headerPx + (us / 1_000_000f) * pps - scrollPx
    fun xToTime(x: Float): Long = (((x - headerPx + scrollPx) / pps) * 1_000_000f).toLong().coerceAtLeast(0L)
    fun rowAt(y: Float): Int = floor((y - rulerPx) / rowPx).toInt().coerceIn(0, max(tracks.size - 1, 0))

    Box(modifier = modifier.background(OColors.Surface)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(tracks, pps, scrollPx) {
                    detectTapGestures { offset ->
                        if (offset.x < headerPx) {
                            val index = rowAt(offset.y)
                            tracks.getOrNull(index)?.let { track ->
                                if (offset.x > headerPx * 0.62f) onToggleLock(track.id) else onToggleMute(track.id)
                            }
                        } else {
                            val time = xToTime(offset.x)
                            onSeek(time)
                            val track = tracks.getOrNull(rowAt(offset.y))
                            val clip = track?.clips?.firstOrNull {
                                time >= it.startUs && time < it.startUs + it.durationUs
                            }
                            onSelectClip(clip?.id)
                        }
                    }
                }
                .pointerInput(tracks, pps, scrollPx) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            if (offset.y < rulerPx) {
                                drag = DragState(DragKind.SCRUB)
                                return@detectDragGestures
                            }
                            val index = rowAt(offset.y)
                            val track = tracks.getOrNull(index)
                            val time = xToTime(offset.x)
                            val clip = track?.clips?.firstOrNull {
                                time >= it.startUs && time < it.startUs + it.durationUs
                            }
                            drag = if (clip != null && offset.x > headerPx) {
                                val startX = timeToX(clip.startUs)
                                val endX = timeToX(clip.startUs + clip.durationUs)
                                val kind = when {
                                    abs(offset.x - startX) < 22f -> DragKind.TRIM_START
                                    abs(offset.x - endX) < 22f -> DragKind.TRIM_END
                                    else -> DragKind.MOVE
                                }
                                onSelectClip(clip.id)
                                DragState(
                                    kind = kind,
                                    clipId = clip.id,
                                    originalStartUs = clip.startUs,
                                    originalDurationUs = clip.durationUs,
                                    sourceTrackIndex = index,
                                    targetTrackIndex = index,
                                )
                            } else {
                                DragState(DragKind.SCROLL)
                            }
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            val current = drag ?: return@detectDragGestures
                            when (current.kind) {
                                DragKind.SCRUB -> onSeek(xToTime(change.position.x))
                                DragKind.SCROLL -> scrollPx = (scrollPx - amount.x).coerceAtLeast(0f)
                                else -> {
                                    current.deltaX += amount.x
                                    current.deltaY += amount.y
                                    current.targetTrackIndex = rowAt(change.position.y)
                                    drag = current.copy()
                                }
                            }
                        },
                        onDragEnd = {
                            val current = drag
                            drag = null
                            if (current == null) return@detectDragGestures
                            val clipId = current.clipId ?: return@detectDragGestures
                            val deltaUs = (current.deltaX / pps * 1_000_000f).toLong()
                            val threshold = (8 / pps * 1_000_000f).toLong() + 10_000L
                            when (current.kind) {
                                DragKind.MOVE -> {
                                    val rawStart = (current.originalStartUs + deltaUs).coerceAtLeast(0L)
                                    val snapped = onSnap(rawStart, threshold, clipId)
                                    val targetTrack = tracks.getOrNull(current.targetTrackIndex)
                                    if (targetTrack != null && !targetTrack.locked) {
                                        onMoveClip(clipId, targetTrack.id, snapped)
                                    }
                                }
                                DragKind.TRIM_START -> {
                                    val raw = (current.originalStartUs + deltaUs).coerceAtLeast(0L)
                                    onTrim(clipId, TrimEdge.START, onSnap(raw, threshold, clipId))
                                }
                                DragKind.TRIM_END -> {
                                    val raw = current.originalStartUs + current.originalDurationUs + deltaUs
                                    onTrim(clipId, TrimEdge.END, onSnap(raw.coerceAtLeast(0L), threshold, clipId))
                                }
                                else -> Unit
                            }
                        },
                        onDragCancel = { drag = null },
                    )
                }
        ) {
            val labelPaint = Paint().apply {
                color = OColors.TextPrimary.toArgb()
                textSize = 11f * density.density
                isAntiAlias = true
            }
            val smallPaint = Paint().apply {
                color = OColors.TextSecondary.toArgb()
                textSize = 9f * density.density
                isAntiAlias = true
            }
            val clipTextPaint = Paint().apply {
                color = Color.White.toArgb()
                textSize = 10f * density.density
                isAntiAlias = true
            }

            drawRect(
                color = OColors.SurfaceHigh,
                topLeft = Offset(0f, 0f),
                size = Size(size.width, rulerPx)
            )
            val stepUs = chooseStep(pps)
            var tickUs = (scrollPx / pps * 1_000_000f).toLong() / stepUs * stepUs
            while (true) {
                val x = timeToX(tickUs)
                if (x > size.width) break
                if (x >= headerPx) {
                    drawLine(OColors.Outline, Offset(x, rulerPx - 6f), Offset(x, rulerPx), 1f)
                    drawContext.canvas.nativeCanvas.drawText(formatTick(tickUs), x + 3f, rulerPx - 7f, smallPaint)
                }
                tickUs += stepUs
            }

            tracks.forEachIndexed { index, track ->
                val top = rulerPx + index * rowPx
                drawRect(
                    color = if (index % 2 == 0) OColors.Surface else OColors.SurfaceHigh,
                    topLeft = Offset(headerPx, top),
                    size = Size(size.width - headerPx, rowPx - 2f)
                )
                drawRect(OColors.SurfaceHigh, Offset(0f, top), Size(headerPx, rowPx - 2f))
                drawContext.canvas.nativeCanvas.drawText(
                    track.name.take(11), 8f, top + rowPx / 2f + 4f, labelPaint
                )
                if (track.muted) {
                    drawContext.canvas.nativeCanvas.drawText("M", headerPx * 0.66f, top + rowPx / 2f + 4f, smallPaint)
                }
                if (track.locked) {
                    drawContext.canvas.nativeCanvas.drawText("L", headerPx * 0.86f, top + rowPx / 2f + 4f, smallPaint)
                }

                track.clips.forEach { clip ->
                    val isDragging = drag?.clipId == clip.id
                    var startUs = clip.startUs
                    var durationUs = clip.durationUs
                    var rowIndex = index
                    if (isDragging) {
                        val d = drag!!
                        when (d.kind) {
                            DragKind.MOVE -> {
                                startUs = (d.originalStartUs + (d.deltaX / pps * 1_000_000f).toLong())
                                    .coerceAtLeast(0L)
                                rowIndex = d.targetTrackIndex
                            }
                            DragKind.TRIM_START -> {
                                val delta = (d.deltaX / pps * 1_000_000f).toLong()
                                val newStart = (d.originalStartUs + delta).coerceIn(
                                    d.originalStartUs - d.originalDurationUs + 10_000L,
                                    d.originalStartUs + d.originalDurationUs - 10_000L
                                )
                                startUs = newStart.coerceAtLeast(0L)
                                durationUs = (d.originalStartUs + d.originalDurationUs - startUs)
                                    .coerceAtLeast(10_000L)
                            }
                            DragKind.TRIM_END -> {
                                val delta = (d.deltaX / pps * 1_000_000f).toLong()
                                durationUs = (d.originalDurationUs + delta).coerceAtLeast(10_000L)
                            }
                            else -> Unit
                        }
                    }
                    val left = timeToX(startUs)
                    val width = (durationUs / 1_000_000f) * pps
                    val rowTop = rulerPx + rowIndex * rowPx + 3f
                    val height = rowPx - 8f
                    if (left + width < headerPx || left > size.width) return@forEach
                    val rect = Rect(
                        left.coerceAtLeast(headerPx),
                        rowTop,
                        (left + width).coerceAtMost(size.width),
                        rowTop + height
                    )
                    drawRoundRect(
                        color = colorFor(track.type),
                        topLeft = Offset(rect.left, rect.top),
                        size = Size(max(rect.width, 2f), rect.height),
                        cornerRadius = CornerRadius(6f, 6f)
                    )
                    if (clip.id == state.selectedClipId) {
                        drawRoundRect(
                            color = OColors.Accent,
                            topLeft = Offset(rect.left, rect.top),
                            size = Size(max(rect.width, 2f), rect.height),
                            cornerRadius = CornerRadius(6f, 6f),
                            style = Stroke(width = 2f)
                        )
                    }
                    if (clip.keyframes.isNotEmpty()) {
                        drawCircle(OColors.Accent, 3f, Offset(rect.left + 6f, rect.top + rect.height - 5f))
                    }
                    val label = clip.text?.text?.take(18)
                        ?: clip.label.take(18).ifBlank { "clip" }
                    drawContext.canvas.nativeCanvas.drawText(
                        label, rect.left + 6f, rect.top + rect.height / 2f + 4f, clipTextPaint
                    )
                }
            }

            state.doc.timeline.markers.forEach { marker ->
                val x = timeToX(marker.timeUs)
                if (x >= headerPx && x <= size.width) {
                    val path = Path().apply {
                        moveTo(x, rulerPx)
                        lineTo(x + 5f, rulerPx + 7f)
                        lineTo(x - 5f, rulerPx + 7f)
                        close()
                    }
                    drawPath(path, Color(marker.colorArgb))
                }
            }

            val playheadX = timeToX(state.playheadUs)
            if (playheadX >= headerPx) {
                drawLine(OColors.Accent, Offset(playheadX, 0f), Offset(playheadX, size.height), 2f)
                drawCircle(OColors.Accent, 6f, Offset(playheadX, 6f))
            }
        }
    }
}

private fun chooseStep(pixelsPerSecond: Float): Long {
    val candidates = longArrayOf(
        100_000L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 5_000_000L,
        10_000_000L, 15_000_000L, 30_000_000L, 60_000_000L, 120_000_000L
    )
    for (candidate in candidates) {
        if ((candidate / 1_000_000f) * pixelsPerSecond >= 70f) return candidate
    }
    return candidates.last()
}

private fun formatTick(us: Long): String {
    val totalSeconds = us / 1_000_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "%d:%02d".format(minutes, seconds) else "%ds".format(seconds)
}

private fun colorFor(type: TrackType): Color = when (type) {
    TrackType.VIDEO -> OColors.VideoClip
    TrackType.OVERLAY -> OColors.OverlayClip
    TrackType.TEXT -> OColors.TextClip
    TrackType.AUDIO -> OColors.AudioClip
    TrackType.EFFECT -> OColors.EffectClip
}
