package com.oeditor.core.timeline

import com.oeditor.core.history.EditHistory
import com.oeditor.core.keyframe.KeyframeEngine
import com.oeditor.core.model.AnimProperty
import com.oeditor.core.model.Clip
import com.oeditor.core.model.Easing
import com.oeditor.core.model.Keyframe
import com.oeditor.core.model.ProjectDocument
import com.oeditor.core.model.TimeUs
import com.oeditor.core.model.Timeline
import com.oeditor.core.model.Track
import com.oeditor.core.model.TrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineEngineTest {

    private fun docWithClip(startUs: Long = 0, durationUs: Long = 5_000_000): ProjectDocument {
        val clip = Clip(id = "c1", assetId = "a1", startUs = startUs, durationUs = durationUs)
        val track = Track(id = "t1", name = "Video 1", type = TrackType.VIDEO, order = 1, clips = listOf(clip))
        return ProjectDocument(id = "p1", timeline = Timeline(tracks = listOf(track)))
    }

    @Test
    fun splitProducesTwoContiguousClips() {
        val doc = docWithClip()
        val result = TimelineEngine.split(doc, "c1", 2_000_000)
        val clips = result.timeline.track("t1")!!.clips.sortedBy { it.startUs }
        assertEquals(2, clips.size)
        assertEquals(0L, clips[0].startUs)
        assertEquals(2_000_000L, clips[0].durationUs)
        assertEquals(2_000_000L, clips[1].startUs)
        assertEquals(3_000_000L, clips[1].durationUs)
        assertEquals(2_000_000L, clips[1].sourceInUs)
    }

    @Test
    fun trimStartAdvancesSourceIn() {
        val doc = docWithClip(startUs = 1_000_000, durationUs = 4_000_000)
        val result = TimelineEngine.trim(doc, "c1", TrimEdge.START, 2_000_000)
        val clip = result.timeline.track("t1")!!.clips.first()
        assertEquals(2_000_000L, clip.startUs)
        assertEquals(3_000_000L, clip.durationUs)
        assertEquals(1_000_000L, clip.sourceInUs)
    }

    @Test
    fun trimCannotGoBelowMinimumDuration() {
        val doc = docWithClip()
        val result = TimelineEngine.trim(doc, "c1", TrimEdge.END, -100)
        val clip = result.timeline.track("t1")!!.clips.first()
        assertTrue(clip.durationUs >= TimelineEngine.MIN_CLIP_DURATION_US)
    }

    @Test
    fun overwriteRemovesCoveredMaterial() {
        val doc = docWithClip()
        val incoming = Clip(id = "c2", assetId = "a2", startUs = 1_000_000, durationUs = 1_000_000)
        val result = TimelineEngine.placeClip(doc, "t1", incoming, EditMode.OVERWRITE)
        val clips = result.timeline.track("t1")!!.clips.sortedBy { it.startUs }
        assertEquals(3, clips.size)
        assertEquals(1_000_000L, clips[0].durationUs)
        assertEquals("c2", clips[1].id)
        assertEquals(2_000_000L, clips[2].startUs)
        assertEquals(3_000_000L, clips[2].durationUs)
    }

    @Test
    fun insertModePushesLaterClips() {
        val doc = docWithClip(startUs = 0, durationUs = 2_000_000)
        val incoming = Clip(id = "c2", assetId = "a2", startUs = 0, durationUs = 1_000_000)
        val result = TimelineEngine.placeClip(doc, "t1", incoming, EditMode.INSERT)
        val clips = result.timeline.track("t1")!!.clips.sortedBy { it.startUs }
        assertEquals(2, clips.size)
        assertEquals(1_000_000L, clips[1].startUs)
    }

    @Test
    fun rippleDeleteClosesGap() {
        val a = Clip(id = "a", assetId = "x", startUs = 0, durationUs = 1_000_000)
        val b = Clip(id = "b", assetId = "x", startUs = 1_000_000, durationUs = 1_000_000)
        val c = Clip(id = "c", assetId = "x", startUs = 2_000_000, durationUs = 1_000_000)
        val track = Track("t1", "V1", TrackType.VIDEO, 1, clips = listOf(a, b, c))
        val doc = ProjectDocument(timeline = Timeline(tracks = listOf(track)))
        val result = TimelineEngine.rippleDelete(doc, "b")
        val clips = result.timeline.track("t1")!!.clips
        assertEquals(2, clips.size)
        assertEquals(1_000_000L, clips.first { it.id == "c" }.startUs)
    }

    @Test
    fun snappingFindsClipEdge() {
        val doc = docWithClip(startUs = 3_000_000, durationUs = 2_000_000)
        assertEquals(3_000_000L, TimelineEngine.snapTime(doc, 3_120_000, 200_000))
    }

    @Test
    fun moveClipBetweenTracksKeepsDuration() {
        val doc = docWithClip()
        val withSecond = TimelineEngine.addTrack(doc, TrackType.OVERLAY)
        val target = withSecond.timeline.tracks.first { it.type == TrackType.OVERLAY }
        val moved = TimelineEngine.moveClip(withSecond, "c1", target.id, 1_000_000)
        assertTrue(moved.timeline.track("t1")!!.clips.isEmpty())
        assertEquals(5_000_000L, moved.timeline.track(target.id)!!.clips.first().durationUs)
    }

    @Test
    fun historyUndoRestoresPreviousState() {
        val doc = docWithClip()
        val history = EditHistory()
        val afterSplit = history.commit("Split", doc, TimelineEngine.split(doc, "c1", 1_000_000))
        assertEquals(2, afterSplit.timeline.track("t1")!!.clips.size)
        assertTrue(history.canUndo)
        val undone = history.undo(afterSplit)
        assertEquals(1, undone.timeline.track("t1")!!.clips.size)
        assertEquals(2, history.redo(undone).timeline.track("t1")!!.clips.size)
    }

    @Test
    fun durationIsMaxClipEnd() {
        val doc = docWithClip(startUs = 1_000_000, durationUs = 4_000_000)
        assertEquals(5_000_000L, TimelineEngine.durationUs(doc))
    }
}

class KeyframeEngineTest {

    @Test
    fun linearInterpolationIsExactAtMidpoint() {
        val keys = listOf(
            Keyframe(AnimProperty.OPACITY, 0, 0f),
            Keyframe(AnimProperty.OPACITY, 1_000_000, 1f),
        )
        assertEquals(0.5f, KeyframeEngine.evaluate(keys, 500_000, 0f), 0.001f)
    }

    @Test
    fun holdKeepsPreviousValue() {
        val keys = listOf(
            Keyframe(AnimProperty.OPACITY, 0, 0.2f, Easing.HOLD),
            Keyframe(AnimProperty.OPACITY, 1_000_000, 1f),
        )
        assertEquals(0.2f, KeyframeEngine.evaluate(keys, 900_000, 0f), 0.001f)
    }

    @Test
    fun valuesClampOutsideRange() {
        val keys = listOf(
            Keyframe(AnimProperty.SCALE_X, 1_000_000, 2f),
            Keyframe(AnimProperty.SCALE_X, 2_000_000, 3f),
        )
        assertEquals(2f, KeyframeEngine.evaluate(keys, 0, 1f), 0.001f)
        assertEquals(3f, KeyframeEngine.evaluate(keys, 5_000_000, 1f), 0.001f)
    }

    @Test
    fun bezierEasingIsMonotonic() {
        var previous = -1f
        var t = 0f
        while (t <= 1f) {
            val v = KeyframeEngine.cubicBezier(0.42f, 0f, 0.58f, 1f, t)
            assertTrue("value decreased at $t", v >= previous - 0.0001f)
            previous = v
            t += 0.05f
        }
        assertEquals(0f, KeyframeEngine.cubicBezier(0.42f, 0f, 0.58f, 1f, 0f), 0.001f)
        assertEquals(1f, KeyframeEngine.cubicBezier(0.42f, 0f, 0.58f, 1f, 1f), 0.001f)
    }

    @Test
    fun fadeEnvelopeApplies() {
        val clip = Clip(
            id = "c", startUs = 0, durationUs = 1_000_000,
            volume = 1f, fadeInUs = 500_000, fadeOutUs = 0
        )
        assertEquals(0.5f, KeyframeEngine.resolveVolume(clip, 250_000), 0.01f)
        assertEquals(1f, KeyframeEngine.resolveVolume(clip, 750_000), 0.01f)
    }

    @Test
    fun keyframedTransformOverridesBase() {
        val clip = Clip(
            id = "c", startUs = 0, durationUs = 1_000_000,
            keyframes = listOf(
                Keyframe(AnimProperty.POS_X, 0, 0f),
                Keyframe(AnimProperty.POS_X, 1_000_000, 1f),
            )
        )
        val mid = KeyframeEngine.resolveTransform(clip, 500_000)
        assertEquals(0.5f, mid.posX, 0.01f)
        assertNotNull(mid)
    }
}

class TimeUsTest {

    @Test
    fun frameIndexRoundsDown() {
        assertEquals(0L, TimeUs.frameIndex(33_000, 30, 1))
        assertEquals(1L, TimeUs.frameIndex(33_334, 30, 1))
        assertEquals(30L, TimeUs.frameIndex(1_000_000, 30, 1))
    }

    @Test
    fun frameStartIsStable() {
        assertEquals(1_000_000L, TimeUs.frameStartUs(30, 30, 1))
        assertEquals(1_000_000L, TimeUs.quantiseToFrame(1_010_000, 30, 1))
    }

    @Test
    fun timecodeFormats() {
        assertEquals("00:00:01:00", TimeUs.timecode(1_000_000, 30, 1))
    }
}
