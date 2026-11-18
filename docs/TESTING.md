# Testing

## Automated

`core/src/test/java/com/oeditor/core/timeline/TimelineEngineTest.kt` (JVM, no device):

- split produces two contiguous clips with correct source in-points
- head trim advances the source in-point; minimum duration enforced
- overwrite placement trims head and tail of the clip it lands on
- insert placement pushes later clips right
- ripple delete closes the gap
- snapping picks the nearest clip edge within the threshold
- moving a clip between tracks preserves duration
- undo restores the pre-split state, redo re-applies it
- timeline duration equals the furthest clip end
- linear interpolation exact at midpoint; hold keeps the previous value
- values clamp outside the keyframe range
- cubic-bezier easing is monotonic and hits 0/1 at the ends
- fade envelopes scale volume correctly
- keyframed transform overrides the static value
- frame index, frame quantisation and timecode formatting

Run with `gradle :core:test`.

Not written yet: instrumented tests, and media-fixture tests for the export path.

## Manual matrix (device required)

| # | Test | Expected |
|---|---|---|
| 1 | Create 1080×1920 @30 project | Editor opens with four tracks |
| 2 | Import MP4, tap Add | Clip at playhead, preview shows the frame |
| 3 | Import JPG and transparent PNG | Image clips render, alpha composites |
| 4 | Import MP3 and place it | Audio clip on the audio track, export has sound |
| 5 | Drag clip across tracks | Moves, previous track clean |
| 6 | Drag clip edge | Trims; head trim changes source in-point |
| 7 | Split at playhead, ripple delete | Gap closes on that track only |
| 8 | Ten edits, undo ×10, redo ×10 | Exact restore |
| 9 | Keyframe opacity 0→1 over 2 s | Preview and export both fade |
| 10 | Blur + glow, reorder | Preview matches export |
| 11 | Grade exposure −1, saturation +0.4 | Both shift identically |
| 12 | Chroma key a green screen over a background | Keyed area transparent in export |
| 13 | Text layer with stroke and shadow | Renders at project resolution |
| 14 | Volume 50 %, 1 s fade in | Exported audio starts quiet |
| 15 | Export 1080p H.264 with audio | Appears in Movies/O Editor, plays, correct duration |
| 16 | Choose a resolution the encoder rejects | Absent from the dialog |
| 17 | Kill app mid-edit, reopen | Last autosave restored |
| 18 | Delete source file, reopen | Asset flagged MISSING, other clips render |
| 19 | Relink to a new file | Clips render again |
| 20 | Airplane mode, repeat all | Identical behaviour (no INTERNET permission) |
| 21 | 4K export on a mid-range device | Completes or reports capability failure, no crash |
| 22 | Rotate during playback | Layout adapts, playback continues |

## Checking an exported file

```bash
ffprobe -v error -show_format -show_streams out.mp4
ffmpeg -i out.mp4 -f null -        # decode errors surface here
```

Confirm one video and one audio stream and a duration matching the timeline.

## Reporting rule

Do not mark a row passed unless it was executed on a device and observed. This repository
contains no fabricated test report.
