package com.oeditor.core.keyframe

import com.oeditor.core.model.AnimProperty
import com.oeditor.core.model.BezierHandle
import com.oeditor.core.model.Clip
import com.oeditor.core.model.ColorGrade
import com.oeditor.core.model.Easing
import com.oeditor.core.model.EffectInstance
import com.oeditor.core.model.Keyframe
import com.oeditor.core.model.Transform

/**
 * Keyframe evaluation engine.
 *
 * Values are always derived from timeline time: nothing is stored as a "current"
 * value, so seeking backwards, exporting and scrubbing all produce identical frames.
 */
object KeyframeEngine {

    /** Evaluates an ordered keyframe list at [localUs] (clip-relative microseconds). */
    fun evaluate(sorted: List<Keyframe>, localUs: Long, fallback: Float): Float {
        if (sorted.isEmpty()) return fallback
        if (localUs <= sorted.first().timeUs) return sorted.first().value
        if (localUs >= sorted.last().timeUs) return sorted.last().value

        var prev = sorted[0]
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (localUs <= next.timeUs) {
                val span = (next.timeUs - prev.timeUs).toFloat()
                if (span <= 0f) return next.value
                val linear = (localUs - prev.timeUs) / span
                val eased = when (prev.easing) {
                    Easing.HOLD -> 0f
                    Easing.LINEAR -> linear
                    Easing.EASE_IN -> cubicBezier(0.42f, 0f, 1f, 1f, linear)
                    Easing.EASE_OUT -> cubicBezier(0f, 0f, 0.58f, 1f, linear)
                    Easing.EASE_IN_OUT -> cubicBezier(0.42f, 0f, 0.58f, 1f, linear)
                    Easing.BEZIER -> cubicBezier(
                        prev.bezier.x1, prev.bezier.y1, prev.bezier.x2, prev.bezier.y2, linear
                    )
                }
                return prev.value + (next.value - prev.value) * eased
            }
            prev = next
        }
        return sorted.last().value
    }

    /**
     * CSS-compatible cubic bezier timing function. [x] is linear progress in 0..1;
     * returns eased progress. Newton-Raphson with a bisection fallback, so pathological
     * control points cannot make it diverge.
     */
    fun cubicBezier(x1: Float, y1: Float, x2: Float, y2: Float, x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        if (x1 == y1 && x2 == y2) return t
        val cx1 = x1.coerceIn(0f, 1f)
        val cx2 = x2.coerceIn(0f, 1f)

        fun sampleX(s: Float): Float {
            val u = 1f - s
            return 3f * u * u * s * cx1 + 3f * u * s * s * cx2 + s * s * s
        }

        fun sampleY(s: Float): Float {
            val u = 1f - s
            return 3f * u * u * s * y1 + 3f * u * s * s * y2 + s * s * s
        }

        fun sampleDX(s: Float): Float {
            val u = 1f - s
            return 3f * u * u * cx1 + 6f * u * s * (cx2 - cx1) + 3f * s * s * (1f - cx2)
        }

        var s = t
        repeat(8) {
            val err = sampleX(s) - t
            if (Math.abs(err) < 1e-5f) return sampleY(s)
            val d = sampleDX(s)
            if (Math.abs(d) < 1e-6f) return@repeat
            s -= err / d
            s = s.coerceIn(0f, 1f)
        }

        var lo = 0f
        var hi = 1f
        s = t
        repeat(24) {
            val v = sampleX(s)
            if (Math.abs(v - t) < 1e-5f) return sampleY(s)
            if (v < t) lo = s else hi = s
            s = (lo + hi) * 0.5f
        }
        return sampleY(s)
    }

    private fun kf(clip: Clip, property: AnimProperty, target: String = "", param: String = ""): List<Keyframe> =
        clip.keyframesFor(property, target, param)

    private fun value(clip: Clip, property: AnimProperty, localUs: Long, fallback: Float): Float {
        val list = kf(clip, property)
        return if (list.isEmpty()) fallback else evaluate(list, localUs, fallback)
    }

    fun resolveTransform(clip: Clip, localUs: Long): Transform {
        val t = clip.transform
        val resolved = t.copy(
            posX = value(clip, AnimProperty.POS_X, localUs, t.posX),
            posY = value(clip, AnimProperty.POS_Y, localUs, t.posY),
            scaleX = value(clip, AnimProperty.SCALE_X, localUs, t.scaleX),
            scaleY = value(clip, AnimProperty.SCALE_Y, localUs, t.scaleY),
            rotationDeg = value(clip, AnimProperty.ROTATION, localUs, t.rotationDeg),
            opacity = value(clip, AnimProperty.OPACITY, localUs, t.opacity),
            anchorX = value(clip, AnimProperty.ANCHOR_X, localUs, t.anchorX),
            anchorY = value(clip, AnimProperty.ANCHOR_Y, localUs, t.anchorY),
            cropLeft = value(clip, AnimProperty.CROP_LEFT, localUs, t.cropLeft),
            cropTop = value(clip, AnimProperty.CROP_TOP, localUs, t.cropTop),
            cropRight = value(clip, AnimProperty.CROP_RIGHT, localUs, t.cropRight),
            cropBottom = value(clip, AnimProperty.CROP_BOTTOM, localUs, t.cropBottom),
        )
        return resolved.clamped()
    }

    fun resolveColor(clip: Clip, localUs: Long): ColorGrade {
        val c = clip.color
        return c.copy(
            exposure = value(clip, AnimProperty.EXPOSURE, localUs, c.exposure),
            contrast = value(clip, AnimProperty.CONTRAST, localUs, c.contrast),
            saturation = value(clip, AnimProperty.SATURATION, localUs, c.saturation),
            hueShiftDeg = value(clip, AnimProperty.HUE, localUs, c.hueShiftDeg),
        )
    }

    /** Volume including fade in/out envelopes, used by the mixer and the export audio pass. */
    fun resolveVolume(clip: Clip, localUs: Long): Float {
        var gain = value(clip, AnimProperty.VOLUME, localUs, clip.volume)
        if (clip.fadeInUs > 0 && localUs < clip.fadeInUs) {
            gain *= (localUs.toFloat() / clip.fadeInUs.toFloat()).coerceIn(0f, 1f)
        }
        val fromEnd = clip.durationUs - localUs
        if (clip.fadeOutUs > 0 && fromEnd < clip.fadeOutUs) {
            gain *= (fromEnd.toFloat() / clip.fadeOutUs.toFloat()).coerceIn(0f, 1f)
        }
        return gain.coerceIn(0f, 4f)
    }

    fun resolvePan(clip: Clip, localUs: Long): Float =
        value(clip, AnimProperty.PAN, localUs, clip.pan).coerceIn(-1f, 1f)

    fun resolveEffectIntensity(clip: Clip, effect: EffectInstance, localUs: Long): Float {
        val list = kf(clip, AnimProperty.EFFECT_PARAM, effect.id, "__intensity")
        val base = if (list.isEmpty()) effect.intensity else evaluate(list, localUs, effect.intensity)
        return base.coerceIn(0f, 1f)
    }

    fun resolveEffectParam(clip: Clip, effect: EffectInstance, key: String, default: Float, localUs: Long): Float {
        val list = kf(clip, AnimProperty.EFFECT_PARAM, effect.id, key)
        val base = effect.param(key, default)
        return if (list.isEmpty()) base else evaluate(list, localUs, base)
    }

    fun resolveMaskFeather(clip: Clip, localUs: Long): Float =
        value(clip, AnimProperty.MASK_FEATHER, localUs, clip.mask.feather).coerceIn(0f, 0.5f)

    /** Adds or replaces a keyframe at the given clip-relative time. */
    fun upsert(clip: Clip, keyframe: Keyframe): Clip {
        val filtered = clip.keyframes.filterNot {
            it.property == keyframe.property &&
                it.targetId == keyframe.targetId &&
                it.paramKey == keyframe.paramKey &&
                it.timeUs == keyframe.timeUs
        }
        return clip.copy(keyframes = (filtered + keyframe).sortedBy { it.timeUs })
    }

    fun remove(clip: Clip, property: AnimProperty, targetId: String, paramKey: String, timeUs: Long): Clip =
        clip.copy(keyframes = clip.keyframes.filterNot {
            it.property == property && it.targetId == targetId && it.paramKey == paramKey && it.timeUs == timeUs
        })

    /** Shifts keyframes when a clip is trimmed from the head. */
    fun shift(keyframes: List<Keyframe>, deltaUs: Long): List<Keyframe> =
        keyframes.map { it.copy(timeUs = (it.timeUs - deltaUs).coerceAtLeast(0L)) }

    fun handles(handle: BezierHandle): BezierHandle = handle.copy(
        x1 = handle.x1.coerceIn(0f, 1f),
        x2 = handle.x2.coerceIn(0f, 1f),
        y1 = handle.y1.coerceIn(-2f, 2f),
        y2 = handle.y2.coerceIn(-2f, 2f),
    )
}
