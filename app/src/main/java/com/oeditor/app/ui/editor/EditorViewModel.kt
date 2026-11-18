package com.oeditor.app.ui.editor

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.oeditor.core.effect.EffectCatalog
import com.oeditor.core.export.ExportEngine
import com.oeditor.core.export.ExportRequest
import com.oeditor.core.gl.FrameProvider
import com.oeditor.core.history.EditHistory
import com.oeditor.core.keyframe.KeyframeEngine
import com.oeditor.core.media.MediaFrameProvider
import com.oeditor.core.media.MediaProbe
import com.oeditor.core.model.AnimProperty
import com.oeditor.core.model.BlendMode
import com.oeditor.core.model.Clip
import com.oeditor.core.model.ColorGrade
import com.oeditor.core.model.Keyframe
import com.oeditor.core.model.Marker
import com.oeditor.core.model.MaskShape
import com.oeditor.core.model.MediaAsset
import com.oeditor.core.model.MediaKind
import com.oeditor.core.model.ProjectDocument
import com.oeditor.core.model.ShapeKind
import com.oeditor.core.model.ShapeSpec
import com.oeditor.core.model.TextSpec
import com.oeditor.core.model.TimeUs
import com.oeditor.core.model.Track
import com.oeditor.core.model.TrackType
import com.oeditor.core.model.Transform
import com.oeditor.core.model.TransitionSpec
import com.oeditor.core.model.TransitionType
import com.oeditor.core.timeline.EditMode
import com.oeditor.core.timeline.TimelineEngine
import com.oeditor.core.timeline.TrimEdge
import com.oeditor.data.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class EditorTool { MEDIA, TRANSFORM, EFFECTS, COLOR, TEXT, AUDIO }

data class EditorUiState(
    val doc: ProjectDocument = ProjectDocument(),
    val selectedClipId: String? = null,
    val playheadUs: Long = 0L,
    val playing: Boolean = false,
    val tool: EditorTool = EditorTool.MEDIA,
    val pixelsPerSecond: Float = 64f,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoLabel: String? = null,
    val redoLabel: String? = null,
    val loading: Boolean = true,
    val message: String? = null,
    val exporting: Boolean = false,
    val exportProgress: Float = 0f,
    val exportStatus: String = "",
    val lastExportUri: String? = null,
    val missingMedia: List<MediaAsset> = emptyList(),
    val recoveryAvailable: Boolean = false,
) {
    val selectedClip: Clip?
        get() = selectedClipId?.let { id -> doc.timeline.clip(id)?.second }

    val selectedTrack: Track?
        get() = selectedClipId?.let { id -> doc.timeline.clip(id)?.first }

    val durationUs: Long get() = doc.timeline.durationUs().coerceAtLeast(1_000_000L)
}

class EditorViewModel(
    application: Application,
    private val repository: ProjectRepository,
    private val projectId: String,
    private val frameProvider: FrameProvider,
) : AndroidViewModel(application) {

    private val history = EditHistory()
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    /** Read by the GL renderer on its own thread. */
    @Volatile
    var renderDoc: ProjectDocument = ProjectDocument()
        private set

    @Volatile
    var renderTimeUs: Long = 0L
        private set

    private var autosaveJob: Job? = null
    private var playbackJob: Job? = null
    private var exportJob: Job? = null

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { repository.load(projectId) }.getOrNull()
            }
            if (loaded == null) {
                _state.update { it.copy(loading = false, message = "Project not found") }
                return@launch
            }
            val checked = withContext(Dispatchers.IO) { repository.detectMissingMedia(loaded) }
            renderDoc = checked
            _state.update {
                it.copy(
                    doc = checked,
                    loading = false,
                    missingMedia = checked.assets.filter { asset -> asset.missing },
                    recoveryAvailable = repository.hasRecovery(projectId),
                )
            }
        }
    }

    // ------------------------------------------------------------ state plumbing

    private fun mutate(label: String, block: (ProjectDocument) -> ProjectDocument) {
        val current = _state.value.doc
        val updated = block(current)
        if (updated == current) return
        history.commit(label, current, updated)
        renderDoc = updated
        _state.update {
            it.copy(
                doc = updated,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                undoLabel = history.undoLabel,
                redoLabel = history.redoLabel,
            )
        }
        scheduleAutosave()
    }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(1200)
            repository.save(_state.value.doc)
        }
    }

    fun saveNow() {
        viewModelScope.launch { repository.save(_state.value.doc) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun selectTool(tool: EditorTool) = _state.update { it.copy(tool = tool) }

    fun selectClip(clipId: String?) = _state.update { it.copy(selectedClipId = clipId) }

    fun setZoom(pixelsPerSecond: Float) =
        _state.update { it.copy(pixelsPerSecond = pixelsPerSecond.coerceIn(16f, 400f)) }

    fun seek(timeUs: Long) {
        val clamped = timeUs.coerceIn(0L, _state.value.durationUs)
        renderTimeUs = clamped
        _state.update { it.copy(playheadUs = clamped) }
    }

    fun stepFrame(delta: Int) {
        val settings = _state.value.doc.settings
        val frame = TimeUs.frameIndex(_state.value.playheadUs, settings.fpsNum, settings.fpsDen)
        seek(TimeUs.frameStartUs((frame + delta).coerceAtLeast(0L), settings.fpsNum, settings.fpsDen))
    }

    // ------------------------------------------------------------ history

    fun undo() {
        val restored = history.undo(_state.value.doc)
        renderDoc = restored
        _state.update {
            it.copy(
                doc = restored,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                undoLabel = history.undoLabel,
                redoLabel = history.redoLabel,
            )
        }
        scheduleAutosave()
    }

    fun redo() {
        val restored = history.redo(_state.value.doc)
        renderDoc = restored
        _state.update {
            it.copy(
                doc = restored,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                undoLabel = history.undoLabel,
                redoLabel = history.redoLabel,
            )
        }
        scheduleAutosave()
    }

    // ------------------------------------------------------------ playback

    fun togglePlayback() {
        if (_state.value.playing) pause() else play()
    }

    private fun play() {
        playbackJob?.cancel()
        _state.update { it.copy(playing = true) }
        playbackJob = viewModelScope.launch {
            var last = System.nanoTime()
            while (true) {
                delay(33)
                val now = System.nanoTime()
                val deltaUs = (now - last) / 1000
                last = now
                val next = _state.value.playheadUs + deltaUs
                if (next >= _state.value.durationUs || deltaUs <= 0) {
                    seek(_state.value.durationUs)
                    _state.update { it.copy(playing = false) }
                    break
                }
                renderTimeUs = next
                _state.update { it.copy(playheadUs = next) }
            }
        }
    }

    fun pause() {
        playbackJob?.cancel()
        playbackJob = null
        _state.update { it.copy(playing = false) }
    }

    // ------------------------------------------------------------ media

    fun importMedia(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val context = getApplication<Application>()
            val assets = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "media"
                    MediaProbe.probe(context, uri, name)
                }
            }
            if (assets.isEmpty()) {
                _state.update { it.copy(message = "Could not read the selected media") }
                return@launch
            }
            val current = _state.value.doc
            val updated = current.copy(assets = (current.assets + assets).distinctBy { it.uri })
            renderDoc = updated
            _state.update { it.copy(doc = updated, message = "Imported ${assets.size} file(s)") }
            scheduleAutosave()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val context = getApplication<Application>()
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun removeAsset(assetId: String) = mutate("Remove media") { repository.dropAsset(it, assetId) }

    fun relinkAsset(assetId: String, uri: Uri) {
        mutate("Relink media") { repository.relink(it, assetId, uri.toString()) }
        _state.update { it.copy(missingMedia = it.doc.assets.filter { a -> a.missing }) }
    }

    fun addAssetToTimeline(assetId: String) {
        val doc = _state.value.doc
        val asset = doc.assets.firstOrNull { it.id == assetId } ?: return
        val trackType = if (asset.kind == MediaKind.AUDIO) TrackType.AUDIO else TrackType.VIDEO
        val track = doc.timeline.tracks
            .filter { it.type == trackType && !it.locked }
            .minByOrNull { it.order }
            ?: return
        val duration = if (asset.kind == MediaKind.IMAGE) 4_000_000L
        else asset.durationUs.coerceAtLeast(500_000L)
        val clip = Clip(
            id = TimelineEngine.newId("clip"),
            assetId = asset.id,
            startUs = _state.value.playheadUs,
            durationUs = duration,
            label = asset.displayName,
        )
        mutate("Add clip") { TimelineEngine.placeClip(it, track.id, clip, EditMode.OVERWRITE) }
        _state.update { it.copy(selectedClipId = clip.id) }
    }

    fun addTextClip(text: String = "Your text") {
        val doc = _state.value.doc
        val track = doc.timeline.tracks.firstOrNull { it.type == TrackType.TEXT && !it.locked } ?: return
        val clip = Clip(
            id = TimelineEngine.newId("clip"),
            startUs = _state.value.playheadUs,
            durationUs = 3_000_000L,
            label = "Text",
            text = TextSpec(text = text),
        )
        mutate("Add text") { TimelineEngine.placeClip(it, track.id, clip, EditMode.OVERWRITE) }
        _state.update { it.copy(selectedClipId = clip.id, tool = EditorTool.TEXT) }
    }

    fun addShapeClip(kind: ShapeKind) {
        val doc = _state.value.doc
        val track = doc.timeline.tracks.firstOrNull { it.type == TrackType.OVERLAY && !it.locked } ?: return
        val clip = Clip(
            id = TimelineEngine.newId("clip"),
            startUs = _state.value.playheadUs,
            durationUs = 3_000_000L,
            label = kind.name.lowercase(),
            shape = ShapeSpec(kind = kind),
        )
        mutate("Add shape") { TimelineEngine.placeClip(it, track.id, clip, EditMode.OVERWRITE) }
        _state.update { it.copy(selectedClipId = clip.id) }
    }

    // ------------------------------------------------------------ timeline editing

    fun splitAtPlayhead() = mutate("Split") { TimelineEngine.splitAllAt(it, _state.value.playheadUs) }

    fun splitSelected() {
        val id = _state.value.selectedClipId ?: return
        mutate("Split") { TimelineEngine.split(it, id, _state.value.playheadUs) }
    }

    fun deleteSelected(ripple: Boolean = false) {
        val id = _state.value.selectedClipId ?: return
        mutate(if (ripple) "Ripple delete" else "Delete") {
            if (ripple) TimelineEngine.rippleDelete(it, id) else TimelineEngine.delete(it, id)
        }
        _state.update { it.copy(selectedClipId = null) }
    }

    fun duplicateSelected() {
        val id = _state.value.selectedClipId ?: return
        mutate("Duplicate") { TimelineEngine.duplicate(it, id) }
    }

    fun moveClip(clipId: String, trackId: String, newStartUs: Long) =
        mutate("Move clip") { TimelineEngine.moveClip(it, clipId, trackId, newStartUs) }

    fun trimClip(clipId: String, edge: TrimEdge, timeUs: Long) =
        mutate("Trim clip") { TimelineEngine.trim(it, clipId, edge, timeUs) }

    fun snap(timeUs: Long, thresholdUs: Long, excludeClipId: String? = null): Long =
        TimelineEngine.snapTime(_state.value.doc, timeUs, thresholdUs, excludeClipId, _state.value.playheadUs)

    fun addTrack(type: TrackType) = mutate("Add track") { TimelineEngine.addTrack(it, type) }

    fun removeTrack(trackId: String) = mutate("Remove track") { TimelineEngine.removeTrack(it, trackId) }

    fun toggleTrackMuted(trackId: String) =
        mutate("Mute track") { TimelineEngine.updateTrack(it, trackId) { t -> t.copy(muted = !t.muted) } }

    fun toggleTrackLocked(trackId: String) =
        mutate("Lock track") { TimelineEngine.updateTrack(it, trackId) { t -> t.copy(locked = !t.locked) } }

    fun toggleTrackHidden(trackId: String) =
        mutate("Hide track") { TimelineEngine.updateTrack(it, trackId) { t -> t.copy(hidden = !t.hidden) } }

    fun addMarker() = mutate("Add marker") {
        TimelineEngine.addMarker(it, _state.value.playheadUs, "Marker")
    }

    fun removeMarker(markerId: String) = mutate("Remove marker") { TimelineEngine.removeMarker(it, markerId) }

    fun setClipSpeed(speed: Float) {
        val id = _state.value.selectedClipId ?: return
        mutate("Speed") { TimelineEngine.setSpeed(it, id, speed) }
    }

    fun freezeSelected() {
        val id = _state.value.selectedClipId ?: return
        mutate("Freeze frame") { TimelineEngine.freezeFrame(it, id, _state.value.playheadUs, 2_000_000L) }
    }

    // ------------------------------------------------------------ clip properties

    fun updateSelected(label: String, block: (Clip) -> Clip) {
        val id = _state.value.selectedClipId ?: return
        mutate(label) { doc -> TimelineEngine.updateClip(doc, id, block) }
    }

    fun updateTransform(block: (Transform) -> Transform) =
        updateSelected("Transform") { it.copy(transform = block(it.transform).clamped()) }

    fun updateColor(block: (ColorGrade) -> ColorGrade) =
        updateSelected("Colour") { it.copy(color = block(it.color)) }

    fun updateText(block: (TextSpec) -> TextSpec) = updateSelected("Text") { clip ->
        clip.copy(text = block(clip.text ?: TextSpec()))
    }

    fun updateShape(block: (ShapeSpec) -> ShapeSpec) = updateSelected("Shape") { clip ->
        clip.copy(shape = block(clip.shape ?: ShapeSpec()))
    }

    fun setVolume(volume: Float) = updateSelected("Volume") { it.copy(volume = volume.coerceIn(0f, 2f)) }

    fun setPan(pan: Float) = updateSelected("Pan") { it.copy(pan = pan.coerceIn(-1f, 1f)) }

    fun setFades(fadeInUs: Long, fadeOutUs: Long) = updateSelected("Fades") {
        it.copy(fadeInUs = fadeInUs.coerceAtLeast(0L), fadeOutUs = fadeOutUs.coerceAtLeast(0L))
    }

    fun setBlendMode(mode: BlendMode) = updateSelected("Blend mode") { it.copy(blendMode = mode) }

    fun setChroma(enabled: Boolean) = updateSelected("Chroma key") {
        it.copy(chroma = it.chroma.copy(enabled = enabled))
    }

    fun setChromaKey(r: Float, g: Float, b: Float, threshold: Float, smoothness: Float) =
        updateSelected("Chroma key") {
            it.copy(
                chroma = it.chroma.copy(
                    keyColorR = r, keyColorG = g, keyColorB = b,
                    threshold = threshold, smoothness = smoothness
                )
            )
        }

    fun setMask(shape: MaskShape, width: Float, height: Float, feather: Float) =
        updateSelected("Mask") {
            it.copy(mask = it.mask.copy(shape = shape, width = width, height = height, feather = feather))
        }

    fun setTransition(type: TransitionType, durationUs: Long) = updateSelected("Transition") {
        it.copy(
            transitionIn = if (type == TransitionType.NONE) null
            else TransitionSpec(type = type, durationUs = durationUs)
        )
    }

    // ------------------------------------------------------------ effects

    fun addEffect(type: String) {
        val instance = EffectCatalog.create(type) ?: return
        updateSelected("Add effect") { it.copy(effects = it.effects + instance) }
    }

    fun removeEffect(effectId: String) = updateSelected("Remove effect") {
        it.copy(effects = it.effects.filterNot { e -> e.id == effectId })
    }

    fun toggleEffect(effectId: String) = updateSelected("Toggle effect") {
        it.copy(effects = it.effects.map { e -> if (e.id == effectId) e.copy(enabled = !e.enabled) else e })
    }

    fun setEffectParam(effectId: String, key: String, value: Float) = updateSelected("Effect") {
        it.copy(effects = it.effects.map { e ->
            if (e.id == effectId) e.copy(params = e.params + (key to value)) else e
        })
    }

    fun setEffectIntensity(effectId: String, value: Float) = updateSelected("Effect strength") {
        it.copy(effects = it.effects.map { e ->
            if (e.id == effectId) e.copy(intensity = value.coerceIn(0f, 1f)) else e
        })
    }

    fun moveEffect(effectId: String, delta: Int) = updateSelected("Reorder effect") { clip ->
        val list = clip.effects.toMutableList()
        val index = list.indexOfFirst { it.id == effectId }
        if (index < 0) return@updateSelected clip
        val target = (index + delta).coerceIn(0, list.size - 1)
        if (target == index) return@updateSelected clip
        val item = list.removeAt(index)
        list.add(target, item)
        clip.copy(effects = list)
    }

    // ------------------------------------------------------------ keyframes

    fun addKeyframe(property: AnimProperty, value: Float, targetId: String = "", paramKey: String = "") {
        val clip = _state.value.selectedClip ?: return
        val localUs = (_state.value.playheadUs - clip.startUs).coerceAtLeast(0L)
        val keyframe = Keyframe(
            property = property,
            timeUs = localUs,
            value = value,
            targetId = targetId,
            paramKey = paramKey,
        )
        updateSelected("Add keyframe") { KeyframeEngine.upsert(it, keyframe) }
    }

    fun clearKeyframes(property: AnimProperty, targetId: String = "", paramKey: String = "") =
        updateSelected("Clear keyframes") { clip ->
            clip.copy(
                keyframes = clip.keyframes.filterNot {
                    it.property == property && it.targetId == targetId && it.paramKey == paramKey
                }
            )
        }

    // ------------------------------------------------------------ export

    fun export(
        width: Int,
        height: Int,
        fpsNum: Int,
        fpsDen: Int,
        videoMime: String,
        bitrate: Int,
        includeAudio: Boolean,
    ) {
        if (_state.value.exporting) return
        pause()
        val context = getApplication<Application>()
        val doc = _state.value.doc
        val name = doc.name.replace(Regex("[^A-Za-z0-9 _-]"), "_").ifBlank { "OEditor" }
        val outputDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir,
            "OEditor"
        ).apply { mkdirs() }
        val outputFile = File(outputDir, "$name-${System.currentTimeMillis()}.mp4")

        _state.update { it.copy(exporting = true, exportProgress = 0f, exportStatus = "Starting") }

        exportJob = viewModelScope.launch {
            // A dedicated provider: MediaMetadataRetriever is not thread-safe and the
            // preview is still holding its own instance on the GL thread.
            val engine = ExportEngine(context, MediaFrameProvider(context), Dispatchers.Default, ownsProvider = true)
            val request = ExportRequest(
                outputFile = outputFile,
                width = width,
                height = height,
                fpsNum = fpsNum,
                fpsDen = fpsDen,
                videoMime = videoMime,
                videoBitrate = bitrate,
                includeAudio = includeAudio,
            )
            val result = engine.export(doc, request) { progress, status ->
                _state.update { it.copy(exportProgress = progress, exportStatus = status) }
            }
            engine.release()
            val exportedFile = result.file
            if (result.success && exportedFile != null) {
                val uri = withContext(Dispatchers.IO) { publishToGallery(exportedFile) }
                _state.update {
                    it.copy(
                        exporting = false,
                        exportProgress = 1f,
                        exportStatus = "Saved",
                        lastExportUri = uri?.toString(),
                        message = "Export finished: ${exportedFile.name}",
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        exporting = false,
                        exportProgress = 0f,
                        exportStatus = result.message,
                        message = "Export failed: ${result.message}",
                    )
                }
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        _state.update { it.copy(exporting = false, exportStatus = "Cancelled", exportProgress = 0f) }
    }

    private fun publishToGallery(file: File): Uri? {
        val context = getApplication<Application>()
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/O Editor")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            uri
        } catch (_: Exception) {
            null
        }
    }

    fun markerAt(timeUs: Long): Marker? =
        _state.value.doc.timeline.markers.minByOrNull { kotlin.math.abs(it.timeUs - timeUs) }

    override fun onCleared() {
        super.onCleared()
        playbackJob?.cancel()
        exportJob?.cancel()
        val doc = _state.value.doc
        if (doc.id.isNotEmpty()) {
            // runBlocking here would block the main thread while SQLite flushes; hand the
            // last snapshot to an IO scope instead.
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                runCatching { repository.save(doc) }
            }
        }
    }
}
