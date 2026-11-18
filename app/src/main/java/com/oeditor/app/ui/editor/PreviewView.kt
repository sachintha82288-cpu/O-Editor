package com.oeditor.app.ui.editor

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oeditor.app.oeditor
import com.oeditor.core.gl.Compositor
import com.oeditor.core.gl.FrameProvider
import com.oeditor.core.model.ProjectDocument
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GPU preview surface.
 *
 * The preview is the export renderer: the same [Compositor], the same shaders and the
 * same keyframe evaluation. It renders into an offscreen FBO and blits a letterboxed
 * frame to the window.
 */
private class PreviewRenderer(
    private val provider: FrameProvider,
    private val docProvider: () -> ProjectDocument,
    private val timeProvider: () -> Long,
) : GLSurfaceView.Renderer {

    private var compositor: Compositor? = null
    private var width = 1
    private var height = 1

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        compositor = runCatching { Compositor(provider) }.getOrNull()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
        GLES30.glViewport(0, 0, width, height)
        compositor?.setSurfaceSize(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val compositor = compositor ?: return
        try {
            val doc = docProvider()
            if (doc.id.isEmpty()) return
            compositor.setBoundTarget(0)
            compositor.setSurfaceSize(width, height)
            // Render at the project resolution, capped so preview stays responsive.
            val settings = doc.settings
            if (settings.width <= 0 || settings.height <= 0) return
            val renderWidth = settings.width.coerceAtMost(720)
            val renderHeight = (renderWidth.toFloat() / settings.width * settings.height)
                .toInt().coerceAtLeast(2)
            compositor.render(doc, timeProvider(), renderWidth, renderHeight)
        } catch (_: Throwable) {
            // A failed frame (driver quirk, unsupported effect, OOM) shows black for one
            // frame instead of terminating the process on the GL thread.
        }
    }
}

@Composable
fun PreviewSurface(viewModel: EditorViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val provider = remember { context.oeditor.frameProvider }
    val glView = remember {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
        }
    }
    val renderer = remember {
        PreviewRenderer(provider, { viewModel.renderDoc }, { viewModel.renderTimeUs })
    }
    remember {
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        glView
    }

    AndroidView(
        factory = { glView },
        modifier = modifier,
        update = { view -> view.requestRender() },
    )

    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.playheadUs, state.doc, state.selectedClipId) {
        glView.requestRender()
    }
    LaunchedEffect(state.playing) {
        glView.renderMode = if (state.playing) {
            GLSurfaceView.RENDERMODE_CONTINUOUSLY
        } else {
            GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
    }
}

@Composable
fun PreviewPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize())
}
