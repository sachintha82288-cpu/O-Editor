package com.oeditor.core.model

import kotlinx.serialization.Serializable

/** Animatable clip properties. Effect parameters are addressed through [EFFECT_PARAM]. */
@Serializable
enum class AnimProperty {
    POS_X, POS_Y,
    SCALE_X, SCALE_Y,
    ROTATION,
    OPACITY,
    ANCHOR_X, ANCHOR_Y,
    CROP_LEFT, CROP_TOP, CROP_RIGHT, CROP_BOTTOM,
    VOLUME, PAN,
    BRIGHTNESS, CONTRAST, SATURATION, EXPOSURE, HUE,
    BLUR,
    MASK_FEATHER,
    EFFECT_PARAM,
}

@Serializable
enum class Easing { LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, BEZIER, HOLD }

/** CSS-style cubic bezier control points, always with P0=(0,0) and P3=(1,1). */
@Serializable
data class BezierHandle(
    val x1: Float = 0.42f,
    val y1: Float = 0f,
    val x2: Float = 0.58f,
    val y2: Float = 1f,
)

@Serializable
data class Keyframe(
    val property: AnimProperty,
    /** Offset from the clip's timeline start, in microseconds. */
    val timeUs: Long,
    val value: Float,
    val easing: Easing = Easing.LINEAR,
    val bezier: BezierHandle = BezierHandle(),
    /** Effect instance id when [property] is [AnimProperty.EFFECT_PARAM]. */
    val targetId: String = "",
    /** Parameter key inside the effect when [property] is [AnimProperty.EFFECT_PARAM]. */
    val paramKey: String = "",
) {
    fun matches(property: AnimProperty, targetId: String, paramKey: String): Boolean =
        this.property == property &&
            this.targetId == targetId &&
            (property != AnimProperty.EFFECT_PARAM || this.paramKey == paramKey)
}

@Serializable
data class Transform(
    /** Normalised offset from the frame centre; 1.0 == one full frame width. */
    val posX: Float = 0f,
    val posY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotationDeg: Float = 0f,
    val anchorX: Float = 0.5f,
    val anchorY: Float = 0.5f,
    val opacity: Float = 1f,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 0f,
    val cropBottom: Float = 0f,
) {
    fun clamped(): Transform = copy(
        scaleX = scaleX.coerceIn(0.01f, 20f),
        scaleY = scaleY.coerceIn(0.01f, 20f),
        opacity = opacity.coerceIn(0f, 1f),
        anchorX = anchorX.coerceIn(0f, 1f),
        anchorY = anchorY.coerceIn(0f, 1f),
        cropLeft = cropLeft.coerceIn(0f, 0.95f),
        cropTop = cropTop.coerceIn(0f, 0.95f),
        cropRight = cropRight.coerceIn(0f, 0.95f),
        cropBottom = cropBottom.coerceIn(0f, 0.95f),
    )
}

@Serializable
data class ColorGrade(
    val exposure: Float = 0f,       // -2..2 stops
    val contrast: Float = 0f,       // -1..1
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val temperature: Float = 0f,    // -1..1 (blue -> orange)
    val tint: Float = 0f,           // -1..1 (green -> magenta)
    val saturation: Float = 0f,     // -1..1
    val vibrance: Float = 0f,
    val hueShiftDeg: Float = 0f,
    val lift: Float = 0f,
    val gamma: Float = 1f,
    val gain: Float = 1f,
    val lutUri: String? = null,
    val lutIntensity: Float = 0f,
) {
    val isIdentity: Boolean
        get() = exposure == 0f && contrast == 0f && highlights == 0f && shadows == 0f &&
            whites == 0f && blacks == 0f && temperature == 0f && tint == 0f &&
            saturation == 0f && vibrance == 0f && hueShiftDeg == 0f && lift == 0f &&
            gamma == 1f && gain == 1f && (lutUri == null || lutIntensity == 0f)
}

@Serializable
enum class BlendMode { NORMAL, ADD, SCREEN, MULTIPLY, OVERLAY, SOFT_LIGHT, DARKEN, LIGHTEN, DIFFERENCE }

@Serializable
enum class MaskShape { NONE, RECTANGLE, ELLIPSE, BEZIER }

@Serializable
data class MaskSpec(
    val shape: MaskShape = MaskShape.NONE,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val width: Float = 0.5f,
    val height: Float = 0.5f,
    val feather: Float = 0.05f,
    val inverted: Boolean = false,
    /** Flat normalised bezier control points for BEZIER masks: x0,y0,cx1,cy1,x1,y1,... */
    val points: List<Float> = emptyList(),
)

@Serializable
data class ChromaKey(
    val enabled: Boolean = false,
    val keyColorR: Float = 0.1f,
    val keyColorG: Float = 0.8f,
    val keyColorB: Float = 0.2f,
    val threshold: Float = 0.28f,
    val smoothness: Float = 0.12f,
    val spillSuppression: Float = 0.5f,
)

@Serializable
enum class TextAlign { LEFT, CENTER, RIGHT }

@Serializable
enum class TextAnimation { NONE, TYPEWRITER, FADE_IN, SLIDE_UP, POP }

@Serializable
data class TextSpec(
    val text: String = "O Editor",
    val fontFamily: String = "sans-serif",
    val fontSize: Float = 64f,          // project pixels at a 1080p reference height
    val weight: Int = 700,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val gradientEndArgb: Int? = null,
    val strokeColorArgb: Int = 0xFF000000.toInt(),
    val strokeWidth: Float = 0f,
    val shadowRadius: Float = 0f,
    val backgroundArgb: Int? = null,
    val letterSpacing: Float = 0f,
    val lineSpacing: Float = 1.2f,
    val align: TextAlign = TextAlign.CENTER,
    val animation: TextAnimation = TextAnimation.NONE,
)

@Serializable
enum class ShapeKind { RECTANGLE, ELLIPSE, LINE, TRIANGLE, STAR }

@Serializable
data class ShapeSpec(
    val kind: ShapeKind = ShapeKind.RECTANGLE,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val cornerRadius: Float = 0f,
    val strokeWidth: Float = 0f,
    val strokeColorArgb: Int = 0xFF000000.toInt(),
)

@Serializable
enum class TransitionType {
    NONE, CROSS_DISSOLVE, FADE_TO_BLACK, WIPE, SLIDE, PUSH, ZOOM, SPIN, BLUR, FLASH, GLITCH, RGB_SPLIT, MASK
}

@Serializable
data class TransitionSpec(
    val type: TransitionType = TransitionType.CROSS_DISSOLVE,
    val durationUs: Long = 500_000L,
    /** Degrees for directional transitions, 0 = left-to-right. */
    val directionDeg: Float = 0f,
    val softness: Float = 0.15f,
)

@Serializable
data class EffectInstance(
    val id: String,
    val type: String,
    val enabled: Boolean = true,
    val intensity: Float = 1f,
    val params: Map<String, Float> = emptyMap(),
) {
    fun param(key: String, default: Float): Float = params[key] ?: default
}

/**
 * A clip on a track. All times are microseconds on the project timeline unless the
 * name says otherwise; source times refer to the asset's own timeline.
 */
@Serializable
data class Clip(
    val id: String,
    val assetId: String? = null,
    val startUs: Long = 0L,
    val durationUs: Long = 0L,
    val sourceInUs: Long = 0L,
    val speed: Float = 1f,
    val reversed: Boolean = false,
    val frozen: Boolean = false,
    val transform: Transform = Transform(),
    val color: ColorGrade = ColorGrade(),
    val effects: List<EffectInstance> = emptyList(),
    val keyframes: List<Keyframe> = emptyList(),
    val transitionIn: TransitionSpec? = null,
    val volume: Float = 1f,
    val pan: Float = 0f,
    val fadeInUs: Long = 0L,
    val fadeOutUs: Long = 0L,
    val blendMode: BlendMode = BlendMode.NORMAL,
    val chroma: ChromaKey = ChromaKey(),
    val mask: MaskSpec = MaskSpec(),
    val text: TextSpec? = null,
    val shape: ShapeSpec? = null,
    val groupId: String? = null,
    val locked: Boolean = false,
    val label: String = "",
) {
    val endUs: Long get() = startUs + durationUs

    /** Maps a timeline time to the source time for this clip. */
    fun sourceTimeAt(timelineUs: Long): Long {
        val local = (timelineUs - startUs).coerceIn(0L, durationUs)
        val scaled = (local * speed).toLong()
        return if (reversed) {
            (sourceInUs + (durationUs * speed).toLong() - scaled).coerceAtLeast(0L)
        } else {
            sourceInUs + scaled
        }
    }

    fun contains(timelineUs: Long): Boolean = timelineUs >= startUs && timelineUs < endUs

    fun keyframesFor(property: AnimProperty, targetId: String = "", paramKey: String = ""): List<Keyframe> =
        keyframes.filter { it.matches(property, targetId, paramKey) }.sortedBy { it.timeUs }
}
