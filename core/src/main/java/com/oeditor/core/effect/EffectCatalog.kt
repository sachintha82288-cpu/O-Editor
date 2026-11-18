package com.oeditor.core.effect

import com.oeditor.core.model.EffectInstance
import com.oeditor.core.model.TransitionType

enum class EffectCategory { BLUR, LIGHT, STYLIZE, DISTORT, COLOR, KEYING }

data class ParamDef(
    val key: String,
    val label: String,
    val default: Float,
    val min: Float,
    val max: Float,
    val step: Float = 0.01f,
)

/**
 * Built-in, offline effect library.
 *
 * Each entry maps to a real fragment shader in [com.oeditor.core.gl.ShaderLibrary];
 * [passes] tells the renderer how many offscreen passes the effect needs (separable
 * blurs run twice with a direction uniform).
 */
data class EffectDef(
    val type: String,
    val displayName: String,
    val category: EffectCategory,
    val shader: String,
    val params: List<ParamDef> = emptyList(),
    val passes: Int = 1,
    val animated: Boolean = true,
)

object EffectCatalog {

    private fun p(key: String, label: String, default: Float, min: Float, max: Float, step: Float = 0.01f) =
        ParamDef(key, label, default, min, max, step)

    val defs: List<EffectDef> = listOf(
        EffectDef("gaussian_blur", "Gaussian Blur", EffectCategory.BLUR, "blur_gaussian",
            listOf(p("radius", "Radius", 0.35f, 0f, 1f)), passes = 2),
        EffectDef("directional_blur", "Directional Blur", EffectCategory.BLUR, "blur_directional",
            listOf(p("angle", "Angle", 0f, -180f, 180f, 1f), p("length", "Length", 0.25f, 0f, 1f))),
        EffectDef("radial_blur", "Radial Blur", EffectCategory.BLUR, "blur_radial",
            listOf(p("center_x", "Center X", 0.5f, 0f, 1f), p("center_y", "Center Y", 0.5f, 0f, 1f),
                p("amount", "Amount", 0.2f, 0f, 1f))),
        EffectDef("motion_blur", "Motion Blur", EffectCategory.BLUR, "blur_directional",
            listOf(p("angle", "Angle", 0f, -180f, 180f, 1f), p("length", "Length", 0.12f, 0f, 1f))),
        EffectDef("glow", "Glow", EffectCategory.LIGHT, "glow",
            listOf(p("radius", "Radius", 0.4f, 0f, 1f), p("threshold", "Threshold", 0.55f, 0f, 1f)),
            passes = 3),
        EffectDef("bloom", "Bloom", EffectCategory.LIGHT, "bloom",
            listOf(p("radius", "Radius", 0.6f, 0f, 1f), p("threshold", "Threshold", 0.7f, 0f, 1f)),
            passes = 3),
        EffectDef("film_grain", "Film Grain", EffectCategory.STYLIZE, "film_grain",
            listOf(p("size", "Grain Size", 0.4f, 0.05f, 1f))),
        EffectDef("vignette", "Vignette", EffectCategory.LIGHT, "vignette",
            listOf(p("amount", "Amount", 0.6f, 0f, 1f), p("softness", "Softness", 0.45f, 0f, 1f))),
        EffectDef("sharpen", "Sharpen", EffectCategory.STYLIZE, "sharpen",
            listOf(p("amount", "Amount", 0.5f, 0f, 1f))),
        EffectDef("pixelate", "Pixelate", EffectCategory.STYLIZE, "pixelate",
            listOf(p("size", "Block Size", 0.25f, 0.01f, 1f))),
        EffectDef("mosaic", "Mosaic", EffectCategory.STYLIZE, "pixelate",
            listOf(p("size", "Block Size", 0.5f, 0.01f, 1f))),
        EffectDef("glitch", "Glitch", EffectCategory.DISTORT, "glitch",
            listOf(p("amount", "Amount", 0.4f, 0f, 1f), p("speed", "Speed", 0.5f, 0f, 1f))),
        EffectDef("rgb_split", "RGB Split", EffectCategory.DISTORT, "rgb_split",
            listOf(p("offset", "Offset", 0.2f, 0f, 1f), p("angle", "Angle", 0f, -180f, 180f, 1f))),
        EffectDef("chromatic_aberration", "Chromatic Aberration", EffectCategory.DISTORT, "chromatic",
            listOf(p("amount", "Amount", 0.3f, 0f, 1f))),
        EffectDef("vhs", "VHS", EffectCategory.STYLIZE, "vhs",
            listOf(p("amount", "Amount", 0.5f, 0f, 1f), p("scanlines", "Scanlines", 0.5f, 0f, 1f))),
        EffectDef("noise", "Noise", EffectCategory.STYLIZE, "noise",
            listOf(p("amount", "Amount", 0.3f, 0f, 1f))),
        EffectDef("light_leak", "Light Leak", EffectCategory.LIGHT, "light_leak",
            listOf(p("position", "Position", 0.25f, 0f, 1f), p("warmth", "Warmth", 0.6f, 0f, 1f))),
        EffectDef("lens_distortion", "Lens Distortion", EffectCategory.DISTORT, "lens_distortion",
            listOf(p("amount", "Amount", 0.25f, -1f, 1f))),
        EffectDef("wave_distortion", "Wave Distortion", EffectCategory.DISTORT, "wave_distortion",
            listOf(p("amplitude", "Amplitude", 0.15f, 0f, 1f), p("frequency", "Frequency", 6f, 1f, 40f, 0.5f),
                p("speed", "Speed", 0.5f, 0f, 1f))),
        EffectDef("color_correction", "Color Correction", EffectCategory.COLOR, "color_grade",
            listOf(p("exposure", "Exposure", 0f, -2f, 2f), p("contrast", "Contrast", 0f, -1f, 1f),
                p("saturation", "Saturation", 0f, -1f, 1f), p("temperature", "Temperature", 0f, -1f, 1f))),
        EffectDef("hsl", "HSL", EffectCategory.COLOR, "hsl",
            listOf(p("hue", "Hue", 0f, -180f, 180f, 1f), p("saturation", "Saturation", 0f, -1f, 1f),
                p("lightness", "Lightness", 0f, -1f, 1f))),
        EffectDef("lut", "LUT", EffectCategory.COLOR, "lut3d",
            listOf(p("intensity", "Intensity", 1f, 0f, 1f))),
        EffectDef("exposure", "Exposure", EffectCategory.COLOR, "color_grade",
            listOf(p("exposure", "Exposure", 0.4f, -2f, 2f))),
        EffectDef("contrast", "Contrast", EffectCategory.COLOR, "color_grade",
            listOf(p("contrast", "Contrast", 0.35f, -1f, 1f))),
        EffectDef("saturation", "Saturation", EffectCategory.COLOR, "color_grade",
            listOf(p("saturation", "Saturation", 0.4f, -1f, 1f))),
        EffectDef("chroma_key", "Chroma Key", EffectCategory.KEYING, "chroma_key",
            listOf(p("threshold", "Threshold", 0.28f, 0f, 1f), p("smoothness", "Smoothness", 0.12f, 0f, 1f),
                p("spill", "Spill Suppression", 0.5f, 0f, 1f))),
    )

    private val byType = defs.associateBy { it.type }

    fun find(type: String): EffectDef? = byType[type]

    fun create(type: String): EffectInstance? {
        val def = byType[type] ?: return null
        return EffectInstance(
            id = "fx_" + java.lang.Long.toHexString(System.nanoTime()),
            type = type,
            params = def.params.associate { it.key to it.default },
        )
    }

    fun categories(): List<EffectCategory> = EffectCategory.entries.toList()

    fun byCategory(category: EffectCategory): List<EffectDef> = defs.filter { it.category == category }
}

data class TransitionDef(
    val type: TransitionType,
    val displayName: String,
    val supportsDirection: Boolean = false,
)

object TransitionCatalog {
    val defs: List<TransitionDef> = listOf(
        TransitionDef(TransitionType.CROSS_DISSOLVE, "Cross Dissolve"),
        TransitionDef(TransitionType.FADE_TO_BLACK, "Fade to Black"),
        TransitionDef(TransitionType.WIPE, "Wipe", supportsDirection = true),
        TransitionDef(TransitionType.SLIDE, "Slide", supportsDirection = true),
        TransitionDef(TransitionType.PUSH, "Push", supportsDirection = true),
        TransitionDef(TransitionType.ZOOM, "Zoom"),
        TransitionDef(TransitionType.SPIN, "Spin"),
        TransitionDef(TransitionType.BLUR, "Blur"),
        TransitionDef(TransitionType.FLASH, "Flash"),
        TransitionDef(TransitionType.GLITCH, "Glitch"),
        TransitionDef(TransitionType.RGB_SPLIT, "RGB Split"),
        TransitionDef(TransitionType.MASK, "Mask"),
    )

    fun find(type: TransitionType): TransitionDef? = defs.firstOrNull { it.type == type }
}
