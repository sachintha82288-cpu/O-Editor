package com.oeditor.core.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.opengl.GLES30
import android.opengl.Matrix
import com.oeditor.core.effect.EffectCatalog
import com.oeditor.core.effect.EffectDef
import com.oeditor.core.keyframe.KeyframeEngine
import com.oeditor.core.model.BlendMode
import com.oeditor.core.model.Clip
import com.oeditor.core.model.MaskShape
import com.oeditor.core.model.MediaAsset
import com.oeditor.core.model.MediaKind
import com.oeditor.core.model.ProjectDocument
import com.oeditor.core.model.ShapeKind
import com.oeditor.core.model.TextAlign
import com.oeditor.core.model.Transform

/** Supplies decoded frames to the renderer. Implemented by the media layer. */
interface FrameProvider {
    fun frame(asset: MediaAsset, sourceTimeUs: Long, maxDimension: Int): Bitmap?
}

/**
 * Multi-track compositor.
 *
 * Both the preview and the export engine drive this same class, so what you see while
 * editing is what gets encoded. It assumes a current GLES 3.0 context and renders into
 * whatever framebuffer the caller binds.
 */
class Compositor(private val provider: FrameProvider) {

    private val quad = Quad()
    private val programs = HashMap<String, GlProgram>()
    private val matrix = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private var accum: Framebuffer? = null
    private var scratchA: Framebuffer? = null
    private var scratchB: Framebuffer? = null
    private var scratchC: Framebuffer? = null

    private val textureCache = object : LinkedHashMap<String, Int>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>): Boolean {
            if (size > 8) {
                GlTextures.delete(eldest.value)
                return true
            }
            return false
        }
    }

    private var width = 0
    private var height = 0
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var boundTarget = 0
    private var rotation = 0

    // ------------------------------------------------------------ lifecycle

    private fun ensureFramebuffers(w: Int, h: Int) {
        if (width == w && height == h && accum != null) return
        width = w
        height = h
        accum?.delete(); scratchA?.delete(); scratchB?.delete(); scratchC?.delete()
        accum = Framebuffer(w, h)
        scratchA = Framebuffer(w, h)
        scratchB = Framebuffer(w, h)
        scratchC = Framebuffer(w, h)
    }

    fun release() {
        programs.values.forEach { it.delete() }
        programs.clear()
        accum?.delete(); scratchA?.delete(); scratchB?.delete(); scratchC?.delete()
        accum = null; scratchA = null; scratchB = null; scratchC = null
        textureCache.values.forEach { GlTextures.delete(it) }
        textureCache.clear()
        width = 0
        height = 0
    }

    fun invalidateFrameCache() {
        textureCache.values.forEach { GlTextures.delete(it) }
        textureCache.clear()
    }

    /**
     * Compiling a shader can fail on unusual drivers. A failed effect must degrade to a
     * pass-through, never take the process down from the GL thread.
     */
    private fun program(id: String): GlProgram = programs.getOrPut(id) {
        try {
            GlProgram.create(ShaderLibrary.VERTEX, ShaderLibrary.fragment(id))
        } catch (_: Throwable) {
            if (id == "passthrough") throw IllegalStateException("passthrough shader failed")
            programs["passthrough"] ?: GlProgram.create(ShaderLibrary.VERTEX, ShaderLibrary.fragment("passthrough"))
        }
    }

    /** Size of the window we blit into (the GLSurfaceView or encoder surface). */
    fun setSurfaceSize(w: Int, h: Int) {
        surfaceWidth = w
        surfaceHeight = h
    }

    fun setBoundTarget(framebufferId: Int) {
        boundTarget = framebufferId
    }

    // ------------------------------------------------------------ rendering

    /**
     * Composites the timeline at [timeUs] into the currently bound framebuffer.
     * [targetWidth]/[targetHeight] is the render size in pixels.
     */
    fun render(doc: ProjectDocument, timeUs: Long, targetWidth: Int, targetHeight: Int) {
        if (targetWidth < 2 || targetHeight < 2) return
        ensureFramebuffers(targetWidth, targetHeight)
        val assets = doc.assets.associateBy { it.id }

        val acc = accum ?: return
        acc.bind()
        val bg = doc.settings.backgroundArgb
        GLES30.glClearColor(
            Color.red(bg) / 255f, Color.green(bg) / 255f, Color.blue(bg) / 255f,
            Color.alpha(bg) / 255f
        )
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        val timeSec = timeUs / 1_000_000f
        for (track in doc.timeline.compositingOrder()) {
            if (track.muted) continue
            val clip = track.clips.firstOrNull { it.contains(timeUs) } ?: continue
            val localUs = timeUs - clip.startUs
            drawClip(doc, assets, clip, timeUs, localUs, timeSec, targetWidth, targetHeight)
        }

        blitToBoundTarget(acc)
    }

    /** Copies the accumulated frame to whatever framebuffer the caller bound (0 or an encoder surface). */
    fun blitToBoundTarget(source: Framebuffer?) {
        val source = source ?: return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, boundTarget)
        val sw = if (surfaceWidth > 0) surfaceWidth else width
        val sh = if (surfaceHeight > 0) surfaceHeight else height
        GLES30.glViewport(0, 0, sw, sh)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        // letterbox the project frame inside the window so preview never distorts
        val srcAspect = width.toFloat() / height.toFloat()
        val dstAspect = sw.toFloat() / sh.toFloat()
        val vw: Int
        val vh: Int
        if (dstAspect > srcAspect) {
            vh = sh
            vw = (sh * srcAspect).toInt().coerceAtLeast(1)
        } else {
            vw = sw
            vh = (sw / srcAspect).toInt().coerceAtLeast(1)
        }
        GLES30.glViewport((sw - vw) / 2, (sh - vh) / 2, vw, vh)

        GLES30.glDisable(GLES30.GL_BLEND)
        val program = program("passthrough")
        program.use()
        program.setFloats("u_p", FloatArray(16))
        program.setFloat("u_intensity", 1f)
        program.setVec2("u_resolution", width.toFloat(), height.toFloat())
        program.setMatrix("u_matrix", identity)
        program.setMatrix("u_texMatrix", identity)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, source.textureId)
        program.setInt("u_texture", 0)
        quad.draw(program)
        GLES30.glEnable(GLES30.GL_BLEND)
    }

    private fun drawClip(
        doc: ProjectDocument,
        assets: Map<String, MediaAsset>,
        clip: Clip,
        timeUs: Long,
        localUs: Long,
        timeSec: Float,
        w: Int,
        h: Int,
    ) {
        val transform = KeyframeEngine.resolveTransform(clip, localUs)
        val bitmap = resolveBitmap(doc, assets, clip, timeUs, w, h) ?: return
        val baseTex = textureFor("${clip.id}:${timeUs / 33_000}", bitmap)

        var tex = applyEffects(clip, baseTex, localUs, timeSec, w, h)

        if (clip.chroma.enabled) {
            val target = pickTarget(tex)
            val program = program("chroma_key")
            target.bind()
            program.use()
            val params = FloatArray(16)
            params[0] = clip.chroma.keyColorR
            params[1] = clip.chroma.keyColorG
            params[2] = clip.chroma.keyColorB
            params[3] = clip.chroma.threshold
            params[4] = clip.chroma.smoothness
            params[5] = clip.chroma.spillSuppression
            program.setFloats("u_p", params)
            program.setFloat("u_intensity", 1f)
            program.setFloat("u_time", timeSec)
            program.setVec2("u_resolution", w.toFloat(), h.toFloat())
            program.setMatrix("u_matrix", identity)
            program.setMatrix("u_texMatrix", identity)
            bindTexture(program, tex)
            quad.draw(program)
            tex = target.textureId
        }

        if (clip.mask.shape != MaskShape.NONE) {
            val target = pickTarget(tex)
            val program = program("mask")
            target.bind()
            program.use()
            val params = FloatArray(16)
            params[0] = if (clip.mask.shape == MaskShape.RECTANGLE) 1f else 2f
            params[1] = clip.mask.centerX
            params[2] = clip.mask.centerY
            params[3] = clip.mask.width
            params[4] = clip.mask.height
            params[5] = KeyframeEngine.resolveMaskFeather(clip, localUs)
            params[6] = if (clip.mask.inverted) 1f else 0f
            program.setFloats("u_p", params)
            program.setFloat("u_intensity", 1f)
            program.setFloat("u_time", timeSec)
            program.setVec2("u_resolution", w.toFloat(), h.toFloat())
            program.setMatrix("u_matrix", identity)
            program.setMatrix("u_texMatrix", identity)
            bindTexture(program, tex)
            quad.draw(program)
            tex = target.textureId
        }

        val transition = clip.transitionIn
        if (transition != null && localUs < transition.durationUs) {
            val previous = previousClip(doc, clip)
            if (previous != null) {
                val prevBitmap = resolveBitmap(doc, assets, previous, previous.endUs - 1, w, h)
                if (prevBitmap != null) {
                    val prevTex = textureFor("${previous.id}:prev", prevBitmap)
                    val progress = (localUs.toFloat() / transition.durationUs.toFloat()).coerceIn(0f, 1f)
                    val target = pickTarget(tex, prevTex)
                    val program = program("transition")
                    target.bind()
                    program.use()
                    val params = FloatArray(16)
                    params[0] = transitionTypeIndex(transition.type.name)
                    params[1] = progress
                    params[2] = transition.directionDeg
                    params[3] = transition.softness
                    program.setFloats("u_p", params)
                    program.setFloat("u_intensity", 1f)
                    program.setFloat("u_time", timeSec)
                    program.setVec2("u_resolution", w.toFloat(), h.toFloat())
                    program.setMatrix("u_matrix", identity)
                    program.setMatrix("u_texMatrix", identity)
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
                    program.setInt("u_texture", 0)
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, prevTex)
                    program.setInt("u_second", 1)
                    quad.draw(program)
                    compositeLayer(target.textureId, clip.blendMode, transform.opacity, w, h)
                    return
                }
            }
        }

        compositeLayer(tex, clip.blendMode, transform.opacity, w, h, transform)
    }

    private fun transitionTypeIndex(name: String): Float = when (name) {
        "CROSS_DISSOLVE" -> 1f
        "FADE_TO_BLACK" -> 2f
        "WIPE" -> 3f
        "SLIDE" -> 4f
        "PUSH" -> 5f
        "ZOOM" -> 6f
        "SPIN" -> 7f
        "BLUR" -> 8f
        "FLASH" -> 9f
        "GLITCH" -> 10f
        "RGB_SPLIT" -> 11f
        "MASK" -> 12f
        else -> 1f
    }

    private fun previousClip(doc: ProjectDocument, clip: Clip): Clip? {
        val track = doc.timeline.tracks.firstOrNull { t -> t.clips.any { it.id == clip.id } } ?: return null
        return track.clips.filter { it.endUs <= clip.startUs + 1000 }.maxByOrNull { it.endUs }
    }

    /** Draws a finished layer texture over the accumulation buffer. */
    private fun compositeLayer(
        tex: Int,
        blend: BlendMode,
        opacity: Float,
        w: Int,
        h: Int,
        transform: Transform? = null,
    ) {
        val acc = accum ?: return
        acc.bind()
        GLES30.glEnable(GLES30.GL_BLEND)
        val program = program("passthrough")

        if (blend == BlendMode.NORMAL || blend == BlendMode.ADD || blend == BlendMode.SCREEN ||
            blend == BlendMode.MULTIPLY
        ) {
            when (blend) {
                BlendMode.NORMAL -> GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                BlendMode.ADD -> GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
                BlendMode.SCREEN -> GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_COLOR)
                else -> GLES30.glBlendFunc(GLES30.GL_DST_COLOR, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            }
            program.use()
            program.setFloats("u_p", FloatArray(16))
            program.setFloat("u_intensity", opacity.coerceIn(0f, 1f))
            program.setFloat("u_time", 0f)
            program.setVec2("u_resolution", w.toFloat(), h.toFloat())
            if (transform != null) {
                buildMatrix(transform)
                program.setMatrix("u_matrix", matrix)
            } else {
                program.setMatrix("u_matrix", identity)
            }
            program.setMatrix("u_texMatrix", identity)
            bindTexture(program, tex)
            quad.draw(program)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            return
        }

        // Blend modes that need to read the backdrop: composite into scratch then copy back.
        val scratch = pickTarget(tex, acc.textureId)
        scratch.bind()
        GLES30.glDisable(GLES30.GL_BLEND)
        val comp = program("composite")
        comp.use()
        val params = FloatArray(16)
        params[0] = blendModeIndex(blend)
        comp.setFloats("u_p", params)
        comp.setFloat("u_intensity", opacity.coerceIn(0f, 1f))
        comp.setFloat("u_time", 0f)
        comp.setVec2("u_resolution", w.toFloat(), h.toFloat())
        if (transform != null) {
            buildMatrix(transform)
            comp.setMatrix("u_matrix", matrix)
        } else {
            comp.setMatrix("u_matrix", identity)
        }
        comp.setMatrix("u_texMatrix", identity)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        comp.setInt("u_texture", 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, acc.textureId)
        comp.setInt("u_second", 1)
        quad.draw(comp)

        acc.bind()
        GLES30.glDisable(GLES30.GL_BLEND)
        val copy = program("passthrough")
        copy.use()
        copy.setFloats("u_p", FloatArray(16))
        copy.setFloat("u_intensity", 1f)
        copy.setVec2("u_resolution", w.toFloat(), h.toFloat())
        copy.setMatrix("u_matrix", identity)
        copy.setMatrix("u_texMatrix", identity)
        bindTexture(copy, scratch.textureId)
        quad.draw(copy)
        GLES30.glEnable(GLES30.GL_BLEND)
    }

    private fun blendModeIndex(mode: BlendMode): Float = when (mode) {
        BlendMode.NORMAL -> 0f
        BlendMode.ADD -> 1f
        BlendMode.SCREEN -> 2f
        BlendMode.MULTIPLY -> 3f
        BlendMode.OVERLAY -> 4f
        BlendMode.SOFT_LIGHT -> 5f
        BlendMode.DARKEN -> 6f
        BlendMode.LIGHTEN -> 7f
        BlendMode.DIFFERENCE -> 8f
    }

    // ------------------------------------------------------------ effects

    private fun applyEffects(clip: Clip, inputTex: Int, localUs: Long, timeSec: Float, w: Int, h: Int): Int {
        var current = inputTex

        val color = KeyframeEngine.resolveColor(clip, localUs)
        if (!color.isIdentity) {
            val target = pickTarget(current)
            val program = program("color_grade")
            target.bind()
            program.use()
            val params = FloatArray(16)
            params[0] = color.exposure
            params[1] = color.contrast
            params[2] = color.highlights
            params[3] = color.shadows
            params[4] = color.whites
            params[5] = color.blacks
            params[6] = color.temperature
            params[7] = color.tint
            params[8] = color.saturation
            params[9] = color.vibrance
            params[10] = color.hueShiftDeg
            params[11] = color.lift
            params[12] = color.gamma
            params[13] = color.gain
            program.setFloats("u_p", params)
            program.setFloat("u_intensity", 1f)
            program.setFloat("u_time", timeSec)
            program.setVec2("u_resolution", w.toFloat(), h.toFloat())
            program.setMatrix("u_matrix", identity)
            program.setMatrix("u_texMatrix", identity)
            bindTexture(program, current)
            quad.draw(program)
            current = target.textureId
        }

        for (effect in clip.effects) {
            if (!effect.enabled) continue
            val def = EffectCatalog.find(effect.type) ?: continue
            current = runEffect(clip, effect.id, def, current, localUs, timeSec, w, h)
        }
        return current
    }

    private fun runEffect(
        clip: Clip,
        effectId: String,
        def: EffectDef,
        inputTex: Int,
        localUs: Long,
        timeSec: Float,
        w: Int,
        h: Int,
    ): Int {
        val instance = clip.effects.firstOrNull { it.id == effectId } ?: return inputTex
        val intensity = KeyframeEngine.resolveEffectIntensity(clip, instance, localUs)
        if (intensity <= 0.001f) return inputTex

        val params = FloatArray(16)
        def.params.forEachIndexed { index, paramDef ->
            if (index < 16) {
                params[index] = KeyframeEngine.resolveEffectParam(
                    clip, instance, paramDef.key, paramDef.default, localUs
                )
            }
        }
        if (def.shader == "lut3d") {
            params[0] = 32f
            params[1] = intensity
        }

        val program = program(def.shader)
        return when (def.passes) {
            1 -> {
                val target = pickTarget(inputTex)
                target.bind()
                program.use()
                program.setFloats("u_p", params)
                program.setFloat("u_intensity", intensity)
                program.setFloat("u_time", timeSec)
                program.setVec2("u_resolution", w.toFloat(), h.toFloat())
                program.setMatrix("u_matrix", identity)
                program.setMatrix("u_texMatrix", identity)
                bindTexture(program, inputTex)
                quad.draw(program)
                target.textureId
            }
            2 -> {
                val mid = pickTarget(inputTex)
                val out = pickTarget(inputTex, mid.textureId)
                program.use()
                program.setFloats("u_p", params)
                program.setFloat("u_intensity", intensity)
                program.setFloat("u_time", timeSec)
                program.setVec2("u_resolution", w.toFloat(), h.toFloat())
                program.setMatrix("u_matrix", identity)
                program.setMatrix("u_texMatrix", identity)

                mid.bind()
                program.setVec2("u_dir", 1f, 0f)
                bindTexture(program, inputTex)
                quad.draw(program)

                out.bind()
                program.setVec2("u_dir", 0f, 1f)
                bindTexture(program, mid.textureId)
                quad.draw(program)
                out.textureId
            }
            else -> {
                val blur = program("blur_gaussian")
                val mid = pickTarget(inputTex)
                val blurred = pickTarget(inputTex, mid.textureId)
                blur.use()
                val blurParams = FloatArray(16)
                blurParams[0] = 0.5f
                blur.setFloats("u_p", blurParams)
                blur.setFloat("u_intensity", 1f)
                blur.setFloat("u_time", timeSec)
                blur.setVec2("u_resolution", w.toFloat(), h.toFloat())
                blur.setMatrix("u_matrix", identity)
                blur.setMatrix("u_texMatrix", identity)

                mid.bind()
                blur.setVec2("u_dir", 1f, 0f)
                bindTexture(blur, inputTex)
                quad.draw(blur)

                blurred.bind()
                blur.setVec2("u_dir", 0f, 1f)
                bindTexture(blur, mid.textureId)
                quad.draw(blur)

                val target = pickTarget(inputTex, blurred.textureId)
                target.bind()
                program.use()
                program.setFloats("u_p", params)
                program.setFloat("u_intensity", intensity)
                program.setFloat("u_time", timeSec)
                program.setVec2("u_resolution", w.toFloat(), h.toFloat())
                program.setMatrix("u_matrix", identity)
                program.setMatrix("u_texMatrix", identity)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTex)
                program.setInt("u_texture", 0)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blurred.textureId)
                program.setInt("u_second", 1)
                quad.draw(program)
                target.textureId
            }
        }
    }

    private fun bindTexture(program: GlProgram, tex: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        program.setInt("u_texture", 0)
    }

    private fun pickTarget(vararg inputs: Int): Framebuffer {
        val buffers = listOfNotNull(scratchA, scratchB, scratchC)
        repeat(buffers.size) {
            val candidate = buffers[rotation % buffers.size]
            rotation++
            if (inputs.none { it == candidate.textureId }) return candidate
        }
        return buffers[rotation % buffers.size]
    }

    // ------------------------------------------------------------ helpers

    private fun textureFor(key: String, bitmap: Bitmap): Int {
        textureCache[key]?.let { return it }
        val tex = GlTextures.fromBitmap(bitmap)
        textureCache[key] = tex
        return tex
    }

    private fun resolveBitmap(
        doc: ProjectDocument,
        assets: Map<String, MediaAsset>,
        clip: Clip,
        timeUs: Long,
        w: Int,
        h: Int,
    ): Bitmap? {
        clip.text?.let { spec -> return TextRenderer.render(spec, w, h) }
        clip.shape?.let { shape -> return ShapeRenderer.render(shape, w, h) }
        val assetId = clip.assetId ?: return null
        val asset = assets[assetId] ?: return null
        if (asset.kind == MediaKind.AUDIO) return null
        val sourceTime = if (clip.frozen) clip.sourceInUs else clip.sourceTimeAt(timeUs)
        return provider.frame(asset, sourceTime, maxOf(w, h))
    }

    /** Model matrix for a layer: position, anchor, rotation, scale. */
    private fun buildMatrix(transform: Transform) {
        Matrix.setIdentityM(matrix, 0)
        Matrix.translateM(matrix, 0, transform.posX * 2f, -transform.posY * 2f, 0f)
        Matrix.rotateM(matrix, 0, transform.rotationDeg, 0f, 0f, 1f)
        Matrix.scaleM(
            matrix, 0,
            transform.scaleX * (if (transform.flipH) -1f else 1f),
            transform.scaleY * (if (transform.flipV) -1f else 1f),
            1f
        )
        Matrix.translateM(
            matrix, 0,
            (0.5f - transform.anchorX) * 2f,
            (transform.anchorY - 0.5f) * 2f,
            0f
        )
    }
}

/** Renders text layers into a bitmap that the compositor uploads as a texture. */
object TextRenderer {

    fun render(spec: com.oeditor.core.model.TextSpec, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = spec.colorArgb
            textSize = spec.fontSize * (height / 1080f).coerceAtLeast(0.2f)
            typeface = android.graphics.Typeface.create(
                spec.fontFamily,
                if (spec.weight >= 600) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
            textAlign = when (spec.align) {
                TextAlign.LEFT -> Paint.Align.LEFT
                TextAlign.CENTER -> Paint.Align.CENTER
                TextAlign.RIGHT -> Paint.Align.RIGHT
            }
            letterSpacing = spec.letterSpacing
            if (spec.shadowRadius > 0f) {
                setShadowLayer(spec.shadowRadius, 0f, spec.shadowRadius * 0.4f, Color.BLACK)
            }
        }

        val strokePaint = Paint(paint).apply {
            if (spec.strokeWidth > 0f) {
                style = Paint.Style.STROKE
                strokeWidth = spec.strokeWidth
                color = spec.strokeColorArgb
            }
        }

        val lines = spec.text.split("\n")
        val lineHeight = paint.textSize * spec.lineSpacing
        val totalHeight = lineHeight * lines.size
        var y = (height / 2f) - totalHeight / 2f + paint.textSize
        val x = when (spec.align) {
            TextAlign.LEFT -> width * 0.08f
            TextAlign.CENTER -> width / 2f
            TextAlign.RIGHT -> width * 0.92f
        }

        spec.backgroundArgb?.let { bg ->
            val rect = RectF(width * 0.05f, y - paint.textSize, width * 0.95f, y + totalHeight)
            canvas.drawRect(rect, Paint().apply { color = bg })
        }

        for (line in lines) {
            if (spec.strokeWidth > 0f) canvas.drawText(line, x, y, strokePaint)
            canvas.drawText(line, x, y, paint)
            y += lineHeight
        }
        return bitmap
    }
}

/** Renders vector shape layers. */
object ShapeRenderer {

    fun render(spec: com.oeditor.core.model.ShapeSpec, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = spec.colorArgb
            style = if (spec.strokeWidth > 0f) Paint.Style.STROKE else Paint.Style.FILL
            strokeWidth = spec.strokeWidth
            if (spec.strokeWidth > 0f) color = spec.strokeColorArgb
        }
        val rect = RectF(width * 0.25f, height * 0.25f, width * 0.75f, height * 0.75f)
        when (spec.kind) {
            ShapeKind.RECTANGLE -> canvas.drawRoundRect(rect, spec.cornerRadius, spec.cornerRadius, paint)
            ShapeKind.ELLIPSE -> canvas.drawOval(rect, paint)
            ShapeKind.LINE -> canvas.drawLine(rect.left, rect.centerY(), rect.right, rect.centerY(), paint)
            ShapeKind.TRIANGLE -> {
                val path = android.graphics.Path().apply {
                    moveTo(rect.centerX(), rect.top)
                    lineTo(rect.right, rect.bottom)
                    lineTo(rect.left, rect.bottom)
                    close()
                }
                canvas.drawPath(path, paint)
            }
            ShapeKind.STAR -> {
                val path = android.graphics.Path()
                val cx = rect.centerX()
                val cy = rect.centerY()
                val outer = minOf(rect.width(), rect.height()) / 2f
                val inner = outer * 0.45f
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) outer else inner
                    val angle = Math.toRadians((i * 36 - 90).toDouble())
                    val px = cx + (r * Math.cos(angle)).toFloat()
                    val py = cy + (r * Math.sin(angle)).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                canvas.drawPath(path, paint)
            }
        }
        return bitmap
    }
}
