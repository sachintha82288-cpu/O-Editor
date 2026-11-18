package com.oeditor.app.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.oeditor.app.ui.theme.OColors
import com.oeditor.core.model.TimeUs
import com.oeditor.core.model.TrackType
import kotlinx.coroutines.delay

@Composable
fun EditorScreen(projectId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as com.oeditor.app.OEditorApp
    val viewModel: EditorViewModel = viewModel(
        key = projectId,
        factory = viewModelFactory {
            initializer {
                EditorViewModel(
                    application = app,
                    repository = app.container.repository,
                    projectId = projectId,
                    frameProvider = app.container.frameProvider,
                )
            }
        }
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showExport by remember { mutableStateOf(false) }

    BackHandler {
        viewModel.saveNow()
        onBack()
    }

    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(2600)
            viewModel.dismissMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(OColors.Background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            EditorTopBar(
                state = state,
                onBack = {
                    viewModel.saveNow()
                    onBack()
                },
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onExport = { showExport = true },
            )

            if (state.loading) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Opening project…", color = OColors.TextSecondary)
                }
                return@Column
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val aspect = state.doc.settings.width.toFloat() / state.doc.settings.height
                Box(modifier = Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.4f, 2.5f))) {
                    PreviewSurface(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                }
                if (state.doc.timeline.tracks.all { it.clips.isEmpty() }) {
                    Text(
                        "Import media, then tap Add to place it on the timeline",
                        color = OColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            TransportBar(
                state = state,
                onPlay = viewModel::togglePlayback,
                onStep = viewModel::stepFrame,
                onSplit = viewModel::splitAtPlayhead,
                onDelete = { viewModel.deleteSelected(ripple = false) },
                onRippleDelete = { viewModel.deleteSelected(ripple = true) },
                onDuplicate = viewModel::duplicateSelected,
                onMarker = viewModel::addMarker,
            )

            ToolTabs(selected = state.tool, onSelect = viewModel::selectTool)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(188.dp)
                    .background(OColors.Surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 12.dp)
                ) {
                    when (state.tool) {
                        EditorTool.MEDIA -> MediaPanel(viewModel, state)
                        EditorTool.TRANSFORM -> {
                            val clip = state.selectedClip
                            if (clip == null) EmptySelection() else TransformPanel(viewModel, clip)
                        }
                        EditorTool.EFFECTS -> EffectsPanel(viewModel, state.selectedClip)
                        EditorTool.COLOR -> {
                            val clip = state.selectedClip
                            if (clip == null) EmptySelection() else ColorPanel(viewModel, clip)
                        }
                        EditorTool.TEXT -> TextPanel(viewModel, state.selectedClip)
                        EditorTool.AUDIO -> AudioPanel(viewModel, state.selectedClip)
                    }
                }
            }

            TimelineToolbar(
                onZoomIn = { viewModel.setZoom(state.pixelsPerSecond * 1.4f) },
                onZoomOut = { viewModel.setZoom(state.pixelsPerSecond / 1.4f) },
                onAddTrack = { viewModel.addTrack(TrackType.OVERLAY) },
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(196.dp)
                    .padding(bottom = 4.dp)
            ) {
                TimelineView(
                    state = state,
                    onSeek = viewModel::seek,
                    onSelectClip = viewModel::selectClip,
                    onMoveClip = viewModel::moveClip,
                    onTrim = viewModel::trimClip,
                    onSnap = { timeUs, threshold, exclude -> viewModel.snap(timeUs, threshold, exclude) },
                    onToggleMute = viewModel::toggleTrackMuted,
                    onToggleLock = viewModel::toggleTrackLocked,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        state.message?.let { message ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(OColors.SurfaceHighest)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(message, color = OColors.TextPrimary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showExport) {
        ExportDialog(
            state = state,
            onDismiss = { showExport = false },
            onExport = { width, height, fpsNum, fpsDen, mime, bitrate, audio ->
                viewModel.export(width, height, fpsNum, fpsDen, mime, bitrate, audio)
            },
            onCancel = {
                viewModel.cancelExport()
                showExport = false
            },
        )
    }
    if (!state.exporting && showExport && state.exportProgress >= 1f) {
        LaunchedEffect(state.exportProgress) {
            delay(1200)
            showExport = false
        }
    }
}

@Composable
private fun EmptySelection() {
    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            "Select a clip on the timeline",
            color = OColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun EditorTopBar(
    state: EditorUiState,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OColors.Surface)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = OColors.TextPrimary)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                state.doc.name,
                color = OColors.TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
            Text(
                "${state.doc.settings.width}×${state.doc.settings.height} · " +
                    "${state.doc.settings.fpsNum}fps · " +
                    "${state.doc.timeline.tracks.sumOf { it.clips.size }} clips",
                color = OColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        IconButton(onClick = onUndo, enabled = state.canUndo) {
            Text(
                "\u21B6",
                color = if (state.canUndo) OColors.TextPrimary else OColors.Outline,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        IconButton(onClick = onRedo, enabled = state.canRedo) {
            Text(
                "\u21B7",
                color = if (state.canRedo) OColors.TextPrimary else OColors.Outline,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(OColors.Accent)
                .clickable(onClick = onExport)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(
                "Export",
                color = OColors.OnAccent,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
    }
}

@Composable
private fun TransportBar(
    state: EditorUiState,
    onPlay: () -> Unit,
    onStep: (Int) -> Unit,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onRippleDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onMarker: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OColors.Background)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onStep(-1) }) {
            Text("◀|", color = OColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onPlay) {
            if (state.playing) {
                // Drawn as text: Icons.Filled.Pause lives in material-icons-extended, which
                // this build does not ship, and a missing icon class crashes the screen.
                Text("❙❙", color = OColors.Accent, style = MaterialTheme.typography.titleMedium)
            } else {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Play",
                    tint = OColors.Accent,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        IconButton(onClick = { onStep(1) }) {
            Text("|▶", color = OColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            TimeUs.timecode(state.playheadUs, state.doc.settings.fpsNum, state.doc.settings.fpsDen),
            color = OColors.TextPrimary,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Text(
            "/ " + TimeUs.shortLabel(state.durationUs),
            color = OColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(modifier = Modifier.weight(1f))
        listOf(
            "Split" to onSplit,
            "Delete" to onDelete,
            "Ripple" to onRippleDelete,
            "Copy" to onDuplicate,
            "Marker" to onMarker,
        ).forEach { (label, action) ->
            Text(
                label,
                color = OColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = action)
                    .padding(horizontal = 7.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ToolTabs(selected: EditorTool, onSelect: (EditorTool) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OColors.Surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EditorTool.entries.forEach { tool ->
            val isSelected = tool == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isSelected) OColors.Accent else OColors.SurfaceHigh)
                    .clickable { onSelect(tool) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tool.name.lowercase().replaceFirstChar { it.uppercase() },
                    color = if (isSelected) OColors.OnAccent else OColors.TextPrimary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun TimelineToolbar(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onAddTrack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OColors.SurfaceHigh)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("TIMELINE", color = OColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
        Spacer(modifier = Modifier.weight(1f))
        listOf("+ Track" to onAddTrack, "−" to onZoomOut, "+" to onZoomIn).forEach { (label, action) ->
            Text(
                label,
                color = OColors.TextPrimary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .clickable(onClick = action)
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            )
        }
    }
}
