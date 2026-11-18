package com.oeditor.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oeditor.core.model.ProjectDocument
import com.oeditor.core.model.ProjectSettings
import com.oeditor.data.ProjectRepository
import com.oeditor.data.ProjectSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val repository: ProjectRepository) : ViewModel() {

    val projects: StateFlow<List<ProjectSummary>> = repository.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createProject(
        name: String,
        width: Int,
        height: Int,
        fpsNum: Int,
        onCreated: (ProjectDocument) -> Unit,
    ) {
        viewModelScope.launch {
            val doc = repository.createProject(
                name = name,
                settings = ProjectSettings(width = width, height = height, fpsNum = fpsNum, fpsDen = 1),
            )
            onCreated(doc)
        }
    }

    fun duplicate(id: String, onCreated: (String) -> Unit = {}) {
        viewModelScope.launch {
            val copy = repository.duplicate(id)
            if (copy != null) onCreated(copy.id)
        }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch { repository.rename(id, name) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }
}
