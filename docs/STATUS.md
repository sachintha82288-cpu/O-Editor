# Implementation status

The honest ledger for this project, written against the code in this tree.

Legend: **Implemented** = code exists, compiles and does the described work.
**Partial** = architecture plus a working subset, with the listed limits.
**Not built** = absent; do not assume it works.

## Build verification for this revision

| Check | Result |
|---|---|
| `core/` Kotlin compilation (Kotlin 2.0.21 + serialization plugin) | passed |
| `data/` Kotlin compilation | passed |
| `app/` Kotlin compilation (Kotlin 2.0.21 + Compose compiler plugin 2.0.21, full AndroidX classpath) | passed, zero errors |
| APK packaging | **built** with the SDK toolchain directly (aapt2 → kotlinc → d8 → zipalign → apksigner); see `docs/BUILD.md` |
| Unit tests executed | **no** — written, not run |
| On-device run, export playback, encoder probing | **not executed** |
| Packaged class set vs. referenced class set | **331 referenced, 0 missing** |
| Library resource IDs present in `resources.arsc` | **23 R-references, 280 resources packaged** |

### About the APK

`O-Editor-1.0.2.apk` (9.7 MB) is built and signed: package `com.oeditor.app`, versionCode
3, versionName 1.0.2, minSdk 31, targetSdk 34, launchable activity
`com.oeditor.app.MainActivity`, three dex files, verified with `apksigner verify` and
`aapt2 dump badging`.

It was produced without Gradle: the build machine available for this revision could not
complete a Gradle configuration phase inside its 300-second command ceiling, so the APK was
assembled directly with the SDK tools (aapt2 → kotlinc + Compose/serialization compiler
plugins → d8 in three groups → zipalign → apksigner). `docs/BUILD.md` documents that path
and `tools/build-apk.sh` reproduces it. A normal Gradle build works on any machine with
enough memory; that route is the one to use for day-to-day work.

What has **not** been verified: installation and launch on a physical device, runtime
behaviour of the renderer/encoder, and the unit tests (written, not executed).

## Module A — Project management

Implemented: new project, multiple projects, duplication, rename, delete, debounced
autosave, manual save, recovery points on every save, file backup with atomic rename,
missing-media detection, relink, resolution/fps/aspect selection at creation, structured
serialisation into Room.
Partial: backup exists but there is no user-facing project archive import/export; project
settings cannot be edited after creation.
Not built: —

Persistence note: the schema is the one the Room entities described (a `projects` row per
project holding metadata plus the serialised document, plus `recovery_points`), implemented
directly on `SQLiteOpenHelper` in `data/ProjectStore.kt`. Room's KSP code generation could
not run in the build environment used for this APK, and a database that cannot open would
make the app unusable, so the store was written by hand against the identical schema. The
repository API, the document format and the atomic-save/recovery behaviour are unchanged.

## Module B — Media library

Implemented: SAF multi-file import (video/audio/image), metadata (duration, resolution,
codec mime, sample rate, channels, size, capture fps), thumbnail decoding helper, missing
source detection.
Partial: the library list shows metadata rather than thumbnail tiles.
Not built: search, sort, collections.

## Module C — Timeline

Implemented: multi-track timeline (video, overlay, text, audio, effect tracks), drag to
move across tracks and time, magnetic snapping, trim start/end, split at playhead,
split-all, ripple delete, ripple trim, insert and overwrite placement, duplicate, track
mute/lock/hide, add/remove track, markers, frame stepping, zoom, scroll, playhead.
Partial: single selection (no multi-select or clipboard stack); grouping field exists but
group moves are not wired.
Not built: pinch-to-zoom gesture.

## Module D — Video editing

Implemented: crop, position, scale, rotation, opacity, anchor, flip/mirror, speed change,
reverse time mapping, freeze frame, picture-in-picture via overlay tracks, rectangle and
ellipse masks with feather, chroma key with spill suppression, blend modes.
Partial: bezier masks are stored but rendered as rectangle/ellipse.
Not built: perspective transform, clip replacement, stabilisation, frame export to file.

## Module E — Keyframe engine

Implemented: keyframes on position, scale, rotation, opacity, anchor, crop, volume, pan,
exposure/contrast/saturation/hue and any effect parameter; linear, ease-in, ease-out,
ease-in-out, custom cubic bezier (CSS semantics, Newton + bisection) and hold; upsert,
remove, shift on trim; evaluation is a pure function of timeline time.
Not built: graphical curve editor (keyframes are set at the playhead).

## Module F — Effects

Implemented as real GLSL ES 3.0 shaders: Gaussian blur (separable), directional, radial and
motion blur, glow, bloom, film grain, vignette, sharpen, pixelate, mosaic, glitch, RGB
split, chromatic aberration, VHS, noise, light leak, lens distortion, wave distortion,
colour correction, HSL, LUT (.cube, atlas encoded), exposure, contrast, saturation, chroma
key. Stacking, ordering, enable/disable, strength and per-parameter keyframes.
Partial: glow/bloom use a blurred copy of the layer as their source rather than a true
bright pass; the LUT parser and shader exist but there is no file picker in the UI.
Not built: user-saved effect presets.

## Module G — Transitions

Implemented: cross dissolve, fade to black, wipe, slide, push, zoom, spin, blur, flash,
glitch, RGB split, mask, with duration, direction and softness.
Limit: applies between the incoming clip and the clip ending immediately before it on the
same track.

## Module H — Colour grading

Implemented in one shader: exposure, contrast, highlights, shadows, whites, blacks,
temperature, tint, saturation, vibrance, hue, lift, gamma, gain, LUT intensity.
Not built: RGB curves, colour wheels, HSL qualifier, colour presets.

## Module I — Text studio

Implemented: text layers, font family/size/weight, colour, stroke, shadow, background,
letter/line spacing, alignment, multiple layers, rendered at project resolution.
Partial: text is rasterised to a bitmap (correct output, not a shaping engine); the
animation field is stored but not yet animated; SRT parse/write exists but is not wired to
a picker.
Not built: custom font import, ASS subtitles, lower thirds, credits generator.

## Module J — Audio studio

Implemented: multi-track PCM mixing, keyframed volume, pan, fade in/out, track volume,
sample-rate conversion, soft-clip limiter, AAC encoding.
Partial: a peaking biquad EQ class exists and is not yet wired to a per-clip chain.
Not built: compressor, reverb, echo, delay, noise reduction, ducking, pitch shift,
waveform display, beat markers, audio extraction.

## Module K — Compositing

Implemented: layer compositing with correct Z-order, alpha blending, blend modes, masks,
shape layers, opacity, letterboxed preview, shared offscreen render graph for preview and
export.
Not built: layer parenting, nested sequences, compound clips, motion paths.

## Module L — Export

Implemented: MediaCodec surface-input encoding driven by the GL compositor, capability
probing, H.264/H.265/VP9 selection from the device list, 480p–2160p filtered by
capability, 24–60 fps, bitrate choice, audio on/off, measured progress, cancellation, audio
muxed from mixed PCM, output published to `Movies/O Editor`.
Partial: export runs in the editor coroutine scope (no WorkManager queue, no resume after
process death); MP4 container only.
Not built: export queue UI, presets.

## Cross-cutting

- Deterministic microsecond timeline with rational frame rate: done.
- Command-based undo that never stores media: done.
- Shared render graph for preview and export: done.
- Hardware decode for preview: **not** used — frames come from MediaMetadataRetriever,
  correct but slower than a MediaCodec/SurfaceTexture pipeline. This is the biggest
  performance gap and the next milestone.
- Proxy generation, thermal-aware processing, waveform/thumbnail caches: not built.
- Native C++/JNI: not included; all DSP is Kotlin.
- FFmpeg: deliberately not used, so no GPL/LGPL obligation arises.
