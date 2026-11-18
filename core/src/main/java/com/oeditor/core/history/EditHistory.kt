package com.oeditor.core.history

import com.oeditor.core.model.ProjectDocument
import com.oeditor.core.model.Track

/** A reversible edit. Only the changed tracks are retained, never media payload. */
interface EditCommand {
    val label: String
    fun apply(doc: ProjectDocument): ProjectDocument
    fun revert(doc: ProjectDocument): ProjectDocument
}

/** Swaps the track list between two states. Covers every timeline mutation. */
class ReplaceTracksCommand(
    override val label: String,
    private val before: List<Track>,
    private val after: List<Track>,
) : EditCommand {
    override fun apply(doc: ProjectDocument): ProjectDocument =
        doc.copy(timeline = doc.timeline.copy(tracks = after))

    override fun revert(doc: ProjectDocument): ProjectDocument =
        doc.copy(timeline = doc.timeline.copy(tracks = before))
}

class CompositeCommand(
    override val label: String,
    private val commands: List<EditCommand>,
) : EditCommand {
    override fun apply(doc: ProjectDocument): ProjectDocument =
        commands.fold(doc) { acc, c -> c.apply(acc) }

    override fun revert(doc: ProjectDocument): ProjectDocument =
        commands.foldRight(doc) { c, acc -> c.revert(acc) }
}

/**
 * Command-based undo/redo stack.
 *
 * The editor never snapshots whole documents: a command keeps only the track lists it
 * replaced, so a 200-step history of a large project stays small and never duplicates
 * source media.
 */
class EditHistory(private val limit: Int = 200) {

    private val undoStack = ArrayDeque<EditCommand>()
    private val redoStack = ArrayDeque<EditCommand>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    val undoLabel: String? get() = undoStack.lastOrNull()?.label
    val redoLabel: String? get() = redoStack.lastOrNull()?.label

    val depth: Int get() = undoStack.size

    fun push(command: EditCommand) {
        undoStack.addLast(command)
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    /**
     * Records the difference between [before] and [after] if the track list actually
     * changed. Returns [after] so callers can chain: `doc = history.commit(...)`.
     */
    fun commit(label: String, before: ProjectDocument, after: ProjectDocument): ProjectDocument {
        if (before.timeline.tracks == after.timeline.tracks) return after
        push(ReplaceTracksCommand(label, before.timeline.tracks, after.timeline.tracks))
        return after
    }

    fun undo(doc: ProjectDocument): ProjectDocument {
        val command = undoStack.removeLastOrNull() ?: return doc
        redoStack.addLast(command)
        return command.revert(doc)
    }

    fun redo(doc: ProjectDocument): ProjectDocument {
        val command = redoStack.removeLastOrNull() ?: return doc
        undoStack.addLast(command)
        return command.apply(doc)
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
