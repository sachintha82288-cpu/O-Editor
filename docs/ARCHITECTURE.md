# Architecture

## Shape of the app

```
app (Jetpack Compose)
  HomeScreen ── HomeViewModel
  EditorScreen ── EditorViewModel ── EditHistory
      ├── PreviewSurface  (GLSurfaceView + PreviewRenderer)
      ├── TimelineView    (Canvas + gestures)
      ├── Panels          (media / transform / effects / colour / text / audio)
      └── ExportDialog
             │ ProjectDocument (immutable)
data                              core
  OEditorDatabase (Room)            model/     timeline model
  ProjectRepository                 timeline/  TimelineEngine
   · atomic save + recovery         keyframe/  KeyframeEngine
   · backup / relink / dedupe       history/   EditHistory
  kotlinx.serialization JSON        gl/        Compositor, GL helpers
                                    effect/    catalogue + shaders
                                    media/     probe, frame supply
                                    audio/     PCM mixer
                                    export/    MediaCodec pipeline
```

## The document is the single source of truth

`ProjectDocument` is an immutable, serialisable data class: settings, assets, tracks,
clips, keyframes, markers. Every edit produces a new document. Consequences that matter:

- Undo/redo stores track-list snapshots, so history never holds media bytes.
- Autosave is one row write of the serialised document.
- Export renders from a snapshot, so editing during an export cannot corrupt output.

Time is `Long` microseconds everywhere; frame indices come from integer arithmetic over a
rational rate (`fpsNum`/`fpsDen`), so long timelines cannot drift.

## Editing operations

`TimelineEngine` is a set of pure functions `(document) -> document`: place with overwrite
or insert semantics, split, split-all, trim, ripple trim, ripple delete, move across
tracks, duplicate, speed change, freeze frame, track add/remove/reorder, markers, snapping.
Overwrite placement subtracts the incoming time range from the target track, trimming,
splitting or dropping whatever it lands on.

`EditHistory.commit(label, before, after)` records only the tracks that changed.

## Render graph

`Compositor` owns the frame:

1. clear the accumulation framebuffer to the project background;
2. for each visible track in compositing order, find the clip at the current time;
3. resolve its bitmap (decoded frame, still image, text raster or shape raster);
4. upload as a texture (small LRU keyed by clip and time bucket);
5. run colour grade and the effect stack through ping-pong framebuffers (separable blurs
   use two passes with a direction uniform; glow/bloom a third);
6. apply chroma key and mask passes;
7. blend onto the accumulation buffer — fixed-function blending for normal/add/screen/
   multiply, a shader pass for modes that must read the backdrop;
8. blit the result with a letterboxed viewport.

Preview and export instantiate the same class with the same shaders, so what you see is
what is encoded; only the render resolution differs (preview caps width at 720 px).

All shaders share one uniform contract (`u_p[16]`, `u_dir`, `u_intensity`, `u_time`,
`u_second`), so the renderer needs no per-shader parameter mapping and a new effect is a
catalogue entry plus a shader.

## Export pipeline

1. Capability check against `MediaCodecList` before anything is created.
2. Video: `MediaCodec` with `COLOR_FormatSurface`; an EGL context on its input surface;
   render, present with `eglPresentationTimeANDROID`, swap; encoded samples written to a
   temporary video-only MP4.
3. Audio: `AudioMixer` decodes each audible clip with `MediaCodec`, converts to float,
   resamples to the project rate, applies volume automation, fades, pan and a soft limiter,
   and sums into a 16-bit PCM file; that file is encoded to AAC into a temporary M4A.
4. Mux: both intermediates are copied into the final MP4 with a fresh `MediaMuxer`, then
   published to MediaStore.

Progress is `renderedFrames / totalFrames`; there is no timer-driven fake progress.

## Persistence

Room table `projects` holds metadata plus the serialised document; `recovery_points` holds
the last committed payload. `ProjectRepository.save` writes both in one transaction, then
writes a backup file with temp-file-plus-rename, so an interrupted save cannot destroy the
previous state. Loading falls back: primary row → recovery row → backup file.

## Threading

- UI: Compose, one `EditorViewModel` per project, state exposed as `StateFlow`.
- Renderer: GL thread owned by `GLSurfaceView` (preview) or by the export coroutine.
- Media probing and thumbnails run on `Dispatchers.IO`.
- Playback advances the playhead from wall-clock deltas; the renderer reads a `@Volatile`
  snapshot, so no document copy crosses the GL boundary per frame.

## Deliberate omissions

FFmpeg (licence surface and size), a C++ DSP layer, cloud anything, and any SDK that would
add an INTERNET permission.
