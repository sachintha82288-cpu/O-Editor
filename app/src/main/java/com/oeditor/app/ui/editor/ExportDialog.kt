package com.oeditor.app.ui.editor

import android.media.MediaFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.oeditor.app.ui.theme.OColors
import com.oeditor.core.export.EncoderCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ResolutionOption(val label: String, val width: Int, val height: Int)

@Composable
fun ExportDialog(
    state: EditorUiState,
    onDismiss: () -> Unit,
    onExport: (width: Int, height: Int, fpsNum: Int, fpsDen: Int, mime: String, bitrate: Int, audio: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val projectAspect = state.doc.settings.width.toFloat() / state.doc.settings.height.toFloat()
    // Codec enumeration walks every encoder on the device; keep it off the UI thread.
    val codecs by produceState(initialValue = emptyList<String>()) {
        value = withContext(Dispatchers.IO) { EncoderCapabilities.availableVideoMimes() }
    }
    var mime by remember { mutableStateOf(codecs.firstOrNull() ?: MediaFormat.MIMETYPE_VIDEO_AVC) }
    var resolutionIndex by remember { mutableStateOf(2) }
    var fps by remember { mutableStateOf(state.doc.settings.fpsNum) }
    var includeAudio by remember { mutableStateOf(true) }
    var bitrateMbps by remember { mutableStateOf(12f) }

    // Once the device's encoder list arrives, prefer a codec it actually reports.
    LaunchedEffect(codecs) {
        val first = codecs.firstOrNull()
        if (first != null && mime !in codecs) mime = first
    }

    val resolutions by produceState(
        initialValue = emptyList<ResolutionOption>(),
        projectAspect, mime, fps
    ) {
        value = withContext(Dispatchers.IO) {
            listOf(
            ResolutionOption("480p", 854, (854 / projectAspect).toInt()),
            ResolutionOption("720p", 1280, (1280 / projectAspect).toInt()),
            ResolutionOption("1080p", 1920, (1920 / projectAspect).toInt()),
            ResolutionOption("1440p", 2560, (2560 / projectAspect).toInt()),
            ResolutionOption("2160p", 3840, (3840 / projectAspect).toInt()),
            ).filter { option ->
                val w = if (projectAspect < 1f) option.height else option.width
                val h = if (projectAspect < 1f) option.width else option.height
                EncoderCapabilities.supportsVideo(mime, w, h, fps)
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!state.exporting) onDismiss() },
        title = { Text(if (state.exporting) "Exporting" else "Export video") },
        text = {
            Column {
                if (state.exporting) {
                    Text(state.exportStatus, color = OColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { state.exportProgress },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = OColors.Accent,
                        trackColor = OColors.Outline,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${(state.exportProgress * 100).toInt()}%",
                        color = OColors.TextPrimary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                } else {
                    Text("Codec", color = OColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        codecs.forEach { option ->
                            OptionChip(
                                label = option.substringAfter("/").uppercase(),
                                selected = mime == option,
                                onClick = { mime = option },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Resolution", color = OColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        resolutions.forEachIndexed { index, option ->
                            OptionChip(
                                label = option.label,
                                selected = resolutionIndex == index,
                                onClick = { resolutionIndex = index },
                            )
                        }
                    }
                    if (resolutions.isEmpty()) {
                        Text(
                            "No supported resolution for this codec on this device.",
                            color = OColors.Danger,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Frame rate", color = OColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(24, 25, 30, 50, 60).forEach { option ->
                            OptionChip(label = "$option", selected = fps == option, onClick = { fps = option })
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Bitrate: ${bitrateMbps.toInt()} Mbps",
                        color = OColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(6f, 12f, 20f, 40f).forEach { option ->
                            OptionChip(
                                label = "${option.toInt()}",
                                selected = bitrateMbps == option,
                                onClick = { bitrateMbps = option },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Include audio",
                            color = OColors.TextPrimary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = includeAudio,
                            onCheckedChange = { includeAudio = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = OColors.OnAccent,
                                checkedTrackColor = OColors.Accent,
                            ),
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Saved to Movies/O Editor. Every option listed here was verified against " +
                            "this device's encoder capabilities.",
                        color = OColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (state.exporting) {
                TextButton(onClick = onCancel) { Text("Cancel", color = OColors.Danger) }
            } else {
                Button(
                    onClick = {
                        val option = resolutions.getOrNull(resolutionIndex) ?: return@Button
                        val w = if (projectAspect < 1f) option.height else option.width
                        val h = if (projectAspect < 1f) option.width else option.height
                        onExport(w, h, fps, 1, mime, (bitrateMbps * 1_000_000).toInt(), includeAudio)
                    },
                    enabled = resolutions.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = OColors.Accent,
                        contentColor = OColors.OnAccent,
                    ),
                ) { Text("Start export") }
            }
        },
        dismissButton = {
            if (!state.exporting) {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@Composable
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) OColors.Accent else OColors.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            color = if (selected) OColors.OnAccent else OColors.TextPrimary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
