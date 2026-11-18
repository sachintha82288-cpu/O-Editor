package com.oeditor.app.ui.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oeditor.app.ui.theme.OColors
import com.oeditor.core.effect.EffectCatalog
import com.oeditor.core.model.AnimProperty
import com.oeditor.core.model.Clip
import com.oeditor.core.model.MaskShape
import com.oeditor.core.model.ShapeKind
import com.oeditor.core.model.TextAlign
import com.oeditor.core.model.TextAnimation
import com.oeditor.core.model.TimeUs

// ---------------------------------------------------------------- shared pieces

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = OColors.TextSecondary,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
    )
}

@Composable
internal fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onKeyframe: (() -> Unit)? = null,
    display: (Float) -> String = { String.format("%.2f", it) },
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = OColors.TextPrimary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Text(display(value), color = OColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
            if (onKeyframe != null) {
                Spacer(modifier = Modifier.height(0.dp))
                Text(
                    "◆",
                    color = OColors.Accent,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onKeyframe() }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = OColors.Accent,
                activeTrackColor = OColors.Accent,
                inactiveTrackColor = OColors.Outline,
            ),
            modifier = Modifier.height(28.dp),
        )
    }
}

@Composable
internal fun ChipRow(options: List<Pair<String, Boolean>>, onSelect: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options.size) { index ->
            val (label, selected) = options[index]
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) OColors.Accent else OColors.SurfaceHigh)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    label,
                    color = if (selected) OColors.OnAccent else OColors.TextPrimary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- media

@Composable
internal fun MediaPanel(viewModel: EditorViewModel, state: EditorUiState) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> -> viewModel.importMedia(uris) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Media library",
                style = MaterialTheme.typography.titleMedium,
                color = OColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { launcher.launch(arrayOf("video/*", "audio/*", "image/*")) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = OColors.Accent,
                    contentColor = OColors.OnAccent,
                ),
                shape = RoundedCornerShape(10.dp),
            ) { Text("Import") }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (state.doc.assets.isEmpty()) {
            Text(
                "Import video, audio or images. Files stay where they are on the device — " +
                    "O Editor only stores a reference and never copies or modifies them.",
                color = OColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items = state.doc.assets, key = { it.id }) { asset ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(OColors.SurfaceHigh)
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                asset.displayName,
                                color = OColors.TextPrimary,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )
                            val detail = buildString {
                                append(asset.kind.name.lowercase())
                                if (asset.width > 0) append(" · ${asset.width}×${asset.height}")
                                if (asset.durationUs > 0) append(" · ${TimeUs.shortLabel(asset.durationUs)}")
                                if (asset.missing) append(" · MISSING")
                            }
                            Text(
                                detail,
                                color = if (asset.missing) OColors.Danger else OColors.TextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            "Add",
                            color = OColors.Accent,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { viewModel.addAssetToTimeline(asset.id) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            ChipRow(
                options = listOf(
                    "Add text layer" to false,
                    "Rectangle" to false,
                    "Ellipse" to false,
                ),
                onSelect = { index ->
                    when (index) {
                        0 -> viewModel.addTextClip()
                        1 -> viewModel.addShapeClip(ShapeKind.RECTANGLE)
                        2 -> viewModel.addShapeClip(ShapeKind.ELLIPSE)
                    }
                },
            )
        }
    }
}

// ---------------------------------------------------------------- transform

@Composable
internal fun TransformPanel(viewModel: EditorViewModel, clip: Clip) {
    val transform = clip.transform
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        SectionTitle("Transform")
        SliderRow("Position X", transform.posX, -1f..1f,
            { value -> viewModel.updateTransform { it.copy(posX = value) } },
            { viewModel.addKeyframe(AnimProperty.POS_X, transform.posX) })
        SliderRow("Position Y", transform.posY, -1f..1f,
            { value -> viewModel.updateTransform { it.copy(posY = value) } },
            { viewModel.addKeyframe(AnimProperty.POS_Y, transform.posY) })
        SliderRow("Scale X", transform.scaleX, 0.05f..4f,
            { value -> viewModel.updateTransform { it.copy(scaleX = value) } },
            { viewModel.addKeyframe(AnimProperty.SCALE_X, transform.scaleX) })
        SliderRow("Scale Y", transform.scaleY, 0.05f..4f,
            { value -> viewModel.updateTransform { it.copy(scaleY = value) } },
            { viewModel.addKeyframe(AnimProperty.SCALE_Y, transform.scaleY) })
        SliderRow("Rotation", transform.rotationDeg, -180f..180f,
            { value -> viewModel.updateTransform { it.copy(rotationDeg = value) } },
            { viewModel.addKeyframe(AnimProperty.ROTATION, transform.rotationDeg) },
            display = { "%.0f°".format(it) })
        SliderRow("Opacity", transform.opacity, 0f..1f,
            { value -> viewModel.updateTransform { it.copy(opacity = value) } },
            { viewModel.addKeyframe(AnimProperty.OPACITY, transform.opacity) })

        SectionTitle("Crop")
        SliderRow("Left", transform.cropLeft, 0f..0.9f,
            { value -> viewModel.updateTransform { it.copy(cropLeft = value) } })
        SliderRow("Top", transform.cropTop, 0f..0.9f,
            { value -> viewModel.updateTransform { it.copy(cropTop = value) } })
        SliderRow("Right", transform.cropRight, 0f..0.9f,
            { value -> viewModel.updateTransform { it.copy(cropRight = value) } })
        SliderRow("Bottom", transform.cropBottom, 0f..0.9f,
            { value -> viewModel.updateTransform { it.copy(cropBottom = value) } })

        SectionTitle("Orientation")
        ChipRow(
            options = listOf(
                "Flip H" to transform.flipH,
                "Flip V" to transform.flipV,
                "Rotate 90°" to false,
                "Reset" to false,
            ),
            onSelect = { index ->
                when (index) {
                    0 -> viewModel.updateTransform { it.copy(flipH = !it.flipH) }
                    1 -> viewModel.updateTransform { it.copy(flipV = !it.flipV) }
                    2 -> viewModel.updateTransform { it.copy(rotationDeg = it.rotationDeg + 90f) }
                    3 -> viewModel.updateTransform { com.oeditor.core.model.Transform() }
                }
            },
        )

        SectionTitle("Speed & time")
        SliderRow("Speed", clip.speed, 0.1f..4f,
            { value -> viewModel.setClipSpeed(value) },
            display = { "%.2f×".format(it) })
        ChipRow(
            options = listOf("Freeze frame" to false, "Reverse" to clip.reversed),
            onSelect = { index ->
                when (index) {
                    0 -> viewModel.freezeSelected()
                    1 -> viewModel.updateSelected("Reverse") { it.copy(reversed = !it.reversed) }
                }
            },
        )

        SectionTitle("Mask & keying")
        ChipRow(
            options = listOf(
                "None" to (clip.mask.shape == MaskShape.NONE),
                "Rectangle" to (clip.mask.shape == MaskShape.RECTANGLE),
                "Ellipse" to (clip.mask.shape == MaskShape.ELLIPSE),
            ),
            onSelect = { index ->
                viewModel.setMask(
                    when (index) {
                        0 -> MaskShape.NONE
                        1 -> MaskShape.RECTANGLE
                        else -> MaskShape.ELLIPSE
                    },
                    clip.mask.width, clip.mask.height, clip.mask.feather
                )
            },
        )
        if (clip.mask.shape != MaskShape.NONE) {
            SliderRow("Mask width", clip.mask.width, 0.05f..1f,
                { v -> viewModel.setMask(clip.mask.shape, v, clip.mask.height, clip.mask.feather) })
            SliderRow("Mask height", clip.mask.height, 0.05f..1f,
                { v -> viewModel.setMask(clip.mask.shape, clip.mask.width, v, clip.mask.feather) })
            SliderRow("Feather", clip.mask.feather, 0f..0.4f,
                { v -> viewModel.setMask(clip.mask.shape, clip.mask.width, clip.mask.height, v) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Chroma key",
                color = OColors.TextPrimary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = clip.chroma.enabled,
                onCheckedChange = { viewModel.setChroma(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = OColors.OnAccent,
                    checkedTrackColor = OColors.Accent,
                ),
            )
        }
        if (clip.chroma.enabled) {
            SliderRow("Threshold", clip.chroma.threshold, 0f..1f, { v ->
                viewModel.setChromaKey(
                    clip.chroma.keyColorR, clip.chroma.keyColorG, clip.chroma.keyColorB,
                    v, clip.chroma.smoothness
                )
            })
            SliderRow("Smoothness", clip.chroma.smoothness, 0f..0.5f, { v ->
                viewModel.setChromaKey(
                    clip.chroma.keyColorR, clip.chroma.keyColorG, clip.chroma.keyColorB,
                    clip.chroma.threshold, v
                )
            })
        }
    }
}

// ---------------------------------------------------------------- effects

@Composable
internal fun EffectsPanel(viewModel: EditorViewModel, clip: Clip?) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        if (clip == null) {
            Text(
                "Select a clip to add effects",
                color = OColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }
        SectionTitle("Effect stack")
        if (clip.effects.isEmpty()) {
            Text(
                "No effects on this clip yet.",
                color = OColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        clip.effects.forEach { effect ->
            val def = EffectCatalog.find(effect.type)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(OColors.SurfaceHigh)
                    .padding(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        def?.displayName ?: effect.type,
                        color = OColors.TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text("↑", color = OColors.TextSecondary,
                        modifier = Modifier.clickable { viewModel.moveEffect(effect.id, -1) }.padding(6.dp))
                    Text("↓", color = OColors.TextSecondary,
                        modifier = Modifier.clickable { viewModel.moveEffect(effect.id, 1) }.padding(6.dp))
                    Text("✕", color = OColors.Danger,
                        modifier = Modifier.clickable { viewModel.removeEffect(effect.id) }.padding(6.dp))
                    Switch(
                        checked = effect.enabled,
                        onCheckedChange = { viewModel.toggleEffect(effect.id) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = OColors.OnAccent,
                            checkedTrackColor = OColors.Accent,
                        ),
                    )
                }
                SliderRow("Strength", effect.intensity, 0f..1f,
                    { v -> viewModel.setEffectIntensity(effect.id, v) })
                def?.params?.forEach { param ->
                    SliderRow(
                        param.label,
                        effect.param(param.key, param.default),
                        param.min..param.max,
                        { v -> viewModel.setEffectParam(effect.id, param.key, v) },
                        {
                            viewModel.addKeyframe(
                                AnimProperty.EFFECT_PARAM,
                                effect.param(param.key, param.default),
                                effect.id,
                                param.key
                            )
                        },
                    )
                }
            }
        }

        SectionTitle("Add effect")
        EffectCatalog.categories().forEach { category ->
            Text(
                category.name.lowercase().replaceFirstChar { it.uppercase() },
                color = OColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(EffectCatalog.byCategory(category)) { def ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(OColors.SurfaceHigh)
                            .clickable { viewModel.addEffect(def.type) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) {
                        Text(
                            def.displayName,
                            color = OColors.TextPrimary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- colour

@Composable
internal fun ColorPanel(viewModel: EditorViewModel, clip: Clip) {
    val color = clip.color
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        SectionTitle("Light")
        SliderRow("Exposure", color.exposure, -2f..2f,
            { v -> viewModel.updateColor { it.copy(exposure = v) } },
            { viewModel.addKeyframe(AnimProperty.EXPOSURE, color.exposure) })
        SliderRow("Contrast", color.contrast, -1f..1f,
            { v -> viewModel.updateColor { it.copy(contrast = v) } },
            { viewModel.addKeyframe(AnimProperty.CONTRAST, color.contrast) })
        SliderRow("Highlights", color.highlights, -1f..1f, { v -> viewModel.updateColor { it.copy(highlights = v) } })
        SliderRow("Shadows", color.shadows, -1f..1f, { v -> viewModel.updateColor { it.copy(shadows = v) } })
        SliderRow("Whites", color.whites, -1f..1f, { v -> viewModel.updateColor { it.copy(whites = v) } })
        SliderRow("Blacks", color.blacks, -1f..1f, { v -> viewModel.updateColor { it.copy(blacks = v) } })

        SectionTitle("Colour")
        SliderRow("Temperature", color.temperature, -1f..1f, { v -> viewModel.updateColor { it.copy(temperature = v) } })
        SliderRow("Tint", color.tint, -1f..1f, { v -> viewModel.updateColor { it.copy(tint = v) } })
        SliderRow("Saturation", color.saturation, -1f..1f,
            { v -> viewModel.updateColor { it.copy(saturation = v) } },
            { viewModel.addKeyframe(AnimProperty.SATURATION, color.saturation) })
        SliderRow("Vibrance", color.vibrance, -1f..1f, { v -> viewModel.updateColor { it.copy(vibrance = v) } })
        SliderRow("Hue", color.hueShiftDeg, -180f..180f,
            { v -> viewModel.updateColor { it.copy(hueShiftDeg = v) } },
            { viewModel.addKeyframe(AnimProperty.HUE, color.hueShiftDeg) },
            display = { "%.0f°".format(it) })

        SectionTitle("Levels")
        SliderRow("Lift", color.lift, -1f..1f, { v -> viewModel.updateColor { it.copy(lift = v) } })
        SliderRow("Gamma", color.gamma, 0.2f..3f, { v -> viewModel.updateColor { it.copy(gamma = v) } })
        SliderRow("Gain", color.gain, 0f..3f, { v -> viewModel.updateColor { it.copy(gain = v) } })

        Spacer(modifier = Modifier.height(10.dp))
        ChipRow(
            options = listOf("Reset grade" to false),
            onSelect = { viewModel.updateColor { com.oeditor.core.model.ColorGrade() } },
        )
    }
}

// ---------------------------------------------------------------- text

@Composable
internal fun TextPanel(viewModel: EditorViewModel, clip: Clip?) {
    val spec = clip?.text
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        if (spec == null) {
            Text(
                "Add a text layer from the Media tab to edit text here.",
                color = OColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = { viewModel.addTextClip() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = OColors.Accent,
                    contentColor = OColors.OnAccent,
                ),
            ) { Text("Add text layer") }
            return@Column
        }
        SectionTitle("Content")
        OutlinedTextField(
            value = spec.text,
            onValueChange = { value -> viewModel.updateText { it.copy(text = value) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Text") },
        )
        SectionTitle("Typography")
        SliderRow("Size", spec.fontSize, 12f..200f,
            { v -> viewModel.updateText { it.copy(fontSize = v) } },
            display = { "%.0f".format(it) })
        SliderRow("Letter spacing", spec.letterSpacing, -0.1f..0.5f,
            { v -> viewModel.updateText { it.copy(letterSpacing = v) } })
        SliderRow("Line spacing", spec.lineSpacing, 0.8f..2.5f,
            { v -> viewModel.updateText { it.copy(lineSpacing = v) } })
        SliderRow("Stroke", spec.strokeWidth, 0f..12f,
            { v -> viewModel.updateText { it.copy(strokeWidth = v) } })
        SliderRow("Shadow", spec.shadowRadius, 0f..24f,
            { v -> viewModel.updateText { it.copy(shadowRadius = v) } })

        SectionTitle("Alignment")
        ChipRow(
            options = listOf(
                "Left" to (spec.align == TextAlign.LEFT),
                "Center" to (spec.align == TextAlign.CENTER),
                "Right" to (spec.align == TextAlign.RIGHT),
            ),
            onSelect = { index ->
                viewModel.updateText {
                    it.copy(
                        align = when (index) {
                            0 -> TextAlign.LEFT
                            2 -> TextAlign.RIGHT
                            else -> TextAlign.CENTER
                        }
                    )
                }
            },
        )

        SectionTitle("Animation")
        ChipRow(
            options = TextAnimation.entries.map { it.name.lowercase() to (spec.animation == it) },
            onSelect = { index -> viewModel.updateText { it.copy(animation = TextAnimation.entries[index]) } },
        )

        SectionTitle("Colour")
        ChipRow(
            options = listOf(
                "White" to (spec.colorArgb == 0xFFFFFFFF.toInt()),
                "Black" to (spec.colorArgb == 0xFF000000.toInt()),
                "Amber" to (spec.colorArgb == 0xFFFFB020.toInt()),
                "Cyan" to (spec.colorArgb == 0xFF35D0E0.toInt()),
            ),
            onSelect = { index ->
                val color = when (index) {
                    0 -> 0xFFFFFFFF.toInt()
                    1 -> 0xFF000000.toInt()
                    2 -> 0xFFFFB020.toInt()
                    else -> 0xFF35D0E0.toInt()
                }
                viewModel.updateText { it.copy(colorArgb = color) }
            },
        )
    }
}

// ---------------------------------------------------------------- audio

@Composable
internal fun AudioPanel(viewModel: EditorViewModel, clip: Clip?) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        if (clip == null) {
            Text(
                "Select an audio or video clip to adjust its sound.",
                color = OColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }
        SectionTitle("Mix")
        SliderRow("Volume", clip.volume, 0f..2f,
            { v -> viewModel.setVolume(v) },
            { viewModel.addKeyframe(AnimProperty.VOLUME, clip.volume) },
            display = { "%.0f%%".format(it * 100) })
        SliderRow("Pan", clip.pan, -1f..1f,
            { v -> viewModel.setPan(v) },
            display = {
                if (it < -0.02f) "L %.0f".format(-it * 100)
                else if (it > 0.02f) "R %.0f".format(it * 100) else "C"
            })

        SectionTitle("Fades")
        SliderRow("Fade in", clip.fadeInUs / 1_000_000f, 0f..5f,
            { v -> viewModel.setFades((v * 1_000_000).toLong(), clip.fadeOutUs) },
            display = { "%.1fs".format(it) })
        SliderRow("Fade out", clip.fadeOutUs / 1_000_000f, 0f..5f,
            { v -> viewModel.setFades(clip.fadeInUs, (v * 1_000_000).toLong()) },
            display = { "%.1fs".format(it) })

        SectionTitle("Time")
        SliderRow("Speed", clip.speed, 0.25f..3f,
            { v -> viewModel.setClipSpeed(v) },
            display = { "%.2f×".format(it) })

        SectionTitle("Notes")
        Text(
            "Mixing runs on decoded PCM: volume automation, pan and fades are applied " +
                "sample by sample during export, then encoded to AAC.",
            color = OColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
