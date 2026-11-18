package com.oeditor.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.oeditor.app.oeditor
import com.oeditor.app.ui.theme.OColors
import com.oeditor.data.ProjectSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class AspectPreset(val label: String, val width: Int, val height: Int)

private val presets = listOf(
    AspectPreset("9:16 Vertical", 1080, 1920),
    AspectPreset("16:9 Landscape", 1920, 1080),
    AspectPreset("1:1 Square", 1080, 1080),
    AspectPreset("4:5 Portrait", 1080, 1350),
)

@Composable
fun HomeScreen(onOpenProject: (String) -> Unit) {
    val context = LocalContext.current
    val viewModel: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer { HomeViewModel(context.oeditor.repository) }
        }
    )
    val projects by viewModel.projects.collectAsStateWithLifecycle()

    var showNewProject by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ProjectSummary?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(OColors.Background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 24.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OLogo()
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("O Editor", style = MaterialTheme.typography.displaySmall, color = OColors.TextPrimary)
                    Text(
                        "by SACHINTHA  ·  offline video editor",
                        style = MaterialTheme.typography.bodySmall,
                        color = OColors.TextSecondary,
                    )
                }
                TextButton(onClick = { showAbout = true }) {
                    Text("About", color = OColors.TextSecondary)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (projects.isEmpty()) "No projects yet" else "${projects.size} projects",
                    style = MaterialTheme.typography.titleMedium,
                    color = OColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { showNewProject = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = OColors.Accent,
                        contentColor = OColors.OnAccent,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("New project", fontWeight = FontWeight.SemiBold)
                }
            }

            if (projects.isEmpty()) {
                EmptyState(onCreate = { showNewProject = true })
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 260.dp),
                    contentPadding = PaddingValues(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items = projects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            onOpen = { onOpenProject(project.id) },
                            onRename = { renameTarget = project },
                            onDuplicate = { viewModel.duplicate(project.id) },
                            onDelete = { deleteTarget = project },
                        )
                    }
                }
            }
        }
    }

    if (showNewProject) {
        NewProjectDialog(
            onDismiss = { showNewProject = false },
            onCreate = { name, width, height, fps ->
                showNewProject = false
                viewModel.createProject(name, width, height, fps) { doc -> onOpenProject(doc.id) }
            },
        )
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }

    renameTarget?.let { target ->
        RenameDialog(
            initial = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                viewModel.rename(target.id, name)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete project?") },
            text = {
                Text(
                    "\"${target.name}\" and its edit history will be removed. " +
                        "Source media files are never touched."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target.id)
                    deleteTarget = null
                }) { Text("Delete", color = OColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun OLogo() {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(OColors.Accent, Color(0xFFFF7A18)))),
        contentAlignment = Alignment.Center,
    ) {
        Text("O", fontSize = 24.sp, fontWeight = FontWeight.Black, color = OColors.OnAccent)
    }
}

@Composable
private fun EmptyState(onCreate: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Start your first edit", style = MaterialTheme.typography.titleLarge, color = OColors.TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Multi-track timeline, GPU effects, keyframes, colour grading and a real\n" +
                "MediaCodec export — all offline, no account, no watermark.",
            style = MaterialTheme.typography.bodyMedium,
            color = OColors.TextSecondary,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = onCreate,
            colors = ButtonDefaults.buttonColors(
                containerColor = OColors.Accent,
                contentColor = OColors.OnAccent,
            ),
        ) { Text("Create project") }
    }
}

@Composable
private fun ProjectCard(
    project: ProjectSummary,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = OColors.Surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(OColors.SurfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${project.width}×${project.height}",
                        style = MaterialTheme.typography.titleMedium,
                        color = OColors.TextSecondary,
                    )
                    Text(
                        "${project.fpsNum}/${project.fpsDen} fps  ·  ${project.clipCount} clips",
                        style = MaterialTheme.typography.bodySmall,
                        color = OColors.TextSecondary,
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        project.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = OColors.TextPrimary,
                        maxLines = 1,
                    )
                    Text(
                        "edited " + formatDate(project.updatedAt) + "  ·  " + formatDuration(project.durationUs),
                        style = MaterialTheme.typography.bodySmall,
                        color = OColors.TextSecondary,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = OColors.TextSecondary)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Open") }, onClick = { menuOpen = false; onOpen() })
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
                        DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menuOpen = false; onDuplicate() })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
                    }
                }
            }
        }
    }
}

@Composable
private fun NewProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (String, Int, Int, Int) -> Unit,
) {
    var name by remember { mutableStateOf("New project") }
    var presetIndex by remember { mutableStateOf(0) }
    var fps by remember { mutableStateOf(30) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text("Format", color = OColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(6.dp))
                presets.forEachIndexed { index, preset ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (presetIndex == index) OColors.SurfaceHighest else OColors.Surface)
                            .clickable { presetIndex = index }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            preset.label,
                            color = if (presetIndex == index) OColors.Accent else OColors.TextPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${preset.width}×${preset.height}",
                            color = OColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Frame rate", color = OColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(6.dp))
                Row {
                    listOf(24, 25, 30, 50, 60).forEach { option ->
                        Box(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (fps == option) OColors.Accent else OColors.SurfaceHigh)
                                .clickable { fps = option }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                "$option",
                                color = if (fps == option) OColors.OnAccent else OColors.TextPrimary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val preset = presets[presetIndex]
                onCreate(name, preset.width, preset.height, fps)
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename project") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.ifBlank { initial }) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About O Editor") },
        text = {
            Column {
                Text("O Editor 1.0.0", color = OColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Developer: SACHINTHA", color = OColors.TextSecondary)
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "A free, offline, watermark-free multi-track video editor. No account, " +
                        "no advertisements, no subscriptions, no cloud processing and no " +
                        "network permission. All rendering and encoding happens on this device.",
                    color = OColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "Third-party components and their licences are listed in LICENSES/ in the source distribution.",
                    color = OColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(millis))

internal fun formatDuration(us: Long): String {
    val totalSeconds = us / 1_000_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}
