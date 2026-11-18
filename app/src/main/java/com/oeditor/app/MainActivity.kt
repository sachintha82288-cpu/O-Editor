package com.oeditor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.oeditor.app.ui.editor.EditorScreen
import com.oeditor.app.ui.home.HomeScreen
import com.oeditor.app.ui.theme.OEditorTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OEditorTheme {
                OEditorRoot()
            }
        }
    }
}

private sealed interface Destination {
    data object Home : Destination
    data class Editor(val projectId: String) : Destination
}

private val destinationSaver = Saver<Destination, String>(
    save = { if (it is Destination.Editor) "editor:${it.projectId}" else "home" },
    restore = {
        if (it.startsWith("editor:")) Destination.Editor(it.removePrefix("editor:")) else Destination.Home
    }
)

@Composable
private fun OEditorRoot() {
    var destination by rememberSaveable(stateSaver = destinationSaver) {
        mutableStateOf<Destination>(Destination.Home)
    }

    when (val current = destination) {
        is Destination.Home -> HomeScreen(
            onOpenProject = { id -> destination = Destination.Editor(id) },
        )
        is Destination.Editor -> EditorScreen(
            projectId = current.projectId,
            onBack = { destination = Destination.Home },
        )
    }
}
