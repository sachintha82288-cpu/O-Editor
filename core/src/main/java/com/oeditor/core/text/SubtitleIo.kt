package com.oeditor.core.text

import com.oeditor.core.model.TimeUs

/** Minimal SRT reader/writer used by the subtitle editor. */
object SubtitleIo {

    data class Cue(val index: Int, val startUs: Long, val endUs: Long, val text: String)

    fun parseSrt(content: String): List<Cue> {
        val cues = ArrayList<Cue>()
        val blocks = content.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
        var autoIndex = 1
        for (block in blocks) {
            val lines = block.trim().lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            val timeLineIndex = lines.indexOfFirst { it.contains("-->") }
            if (timeLineIndex < 0) continue
            val index = lines.getOrNull(timeLineIndex - 1)?.trim()?.toIntOrNull() ?: autoIndex
            val times = lines[timeLineIndex].split("-->")
            if (times.size != 2) continue
            val start = parseTimestamp(times[0].trim())
            val end = parseTimestamp(times[1].trim())
            val text = lines.drop(timeLineIndex + 1).joinToString("\n")
            cues.add(Cue(index, start, end, text))
            autoIndex = index + 1
        }
        return cues.sortedBy { it.startUs }
    }

    fun writeSrt(cues: List<Cue>): String = buildString {
        cues.sortedBy { it.startUs }.forEachIndexed { i, cue ->
            append(i + 1).append('\n')
            append(formatTimestamp(cue.startUs)).append(" --> ").append(formatTimestamp(cue.endUs)).append('\n')
            append(cue.text).append("\n\n")
        }
    }

    private fun parseTimestamp(value: String): Long {
        val cleaned = value.replace(',', '.')
        val parts = cleaned.split(":")
        if (parts.size < 3) return 0L
        val hours = parts[0].toLongOrNull() ?: 0L
        val minutes = parts[1].toLongOrNull() ?: 0L
        val seconds = parts[2].toDoubleOrNull() ?: 0.0
        return (hours * 3600 + minutes * 60) * TimeUs.PER_SECOND + Math.round(seconds * TimeUs.PER_SECOND)
    }

    private fun formatTimestamp(us: Long): String {
        val totalMs = (us.coerceAtLeast(0L) + 500) / 1000
        val ms = totalMs % 1000
        val totalSeconds = totalMs / 1000
        val s = totalSeconds % 60
        val m = (totalSeconds / 60) % 60
        val h = totalSeconds / 3600
        return "%02d:%02d:%02d,%03d".format(h, m, s, ms)
    }
}
