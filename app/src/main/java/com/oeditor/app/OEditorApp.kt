package com.oeditor.app

import android.app.Application
import android.content.Context
import com.oeditor.core.gl.FrameProvider
import com.oeditor.core.media.MediaFrameProvider
import com.oeditor.data.ProjectRepository

/**
 * Application container. Deliberately tiny: one repository, one frame provider and no
 * background services, no network, no analytics.
 */
class OEditorApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(context: Context) {
    val repository: ProjectRepository = ProjectRepository(context)
    val frameProvider: FrameProvider = MediaFrameProvider(context)
    val mediaProvider: MediaFrameProvider
        get() = frameProvider as MediaFrameProvider
}

val Context.oeditor: AppContainer
    get() = (applicationContext as OEditorApp).container
