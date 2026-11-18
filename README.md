# O Editor

A free, offline, watermark-free multi-track video editor for Android, built with Kotlin,
Jetpack Compose, OpenGL ES 3.0, MediaCodec and Room.

Developer: **SACHINTHA**

## What this repository is

The complete Android Studio project for O Editor: application code, the editing engine,
the GPU render graph, the export pipeline, the persistence layer, unit tests and
documentation. There are no placeholder panels: every control mutates the document, every
effect maps to a real fragment shader, and export renders frames through the same
compositor the preview uses.

Read `docs/STATUS.md` first — it states, feature by feature, what is implemented, what is
partial and what is not built. Nothing there is marked complete unless it exists here.

## Product rules the code obeys

- No INTERNET permission. No analytics, ads, accounts, activation or remote config.
- No subscription gate, no watermark, no export limits.
- Source media is never copied, moved or modified — projects store SAF URIs only.
- Codec, resolution and frame-rate options are enumerated from the device's own encoder
  list, so the UI cannot offer something the hardware cannot produce.

## Requirements

- Android Studio Ladybug or newer, or local Gradle 8.9 + JDK 17
- Android SDK Platform 34, Build-Tools 34.0.0
- Device or emulator on Android 12 (API 31)+ with GLES 3.0

## Download

A signed, installable APK is attached to the GitHub release:

```
O-Editor-1.0.2.apk   (9.7 MB, com.oeditor.app, Android 12+)
```

Install with `adb install -r O-Editor-1.0.2.apk`, or copy it to the device and open it (allow
"install unknown apps" for your file manager). It is signed with the O Editor release key
included in this repository's release notes; keep that key to ship updates.

## Build

```bash
gradle :app:assembleDebug      # debug APK
gradle :app:assembleRelease    # release APK (see docs/BUILD.md for signing)
gradle :core:test              # JVM unit tests
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

If Gradle cannot run on your machine, `tools/build-apk.sh` builds the same APK with the SDK
tools alone (aapt2 → kotlinc → d8 → zipalign → apksigner); see `docs/BUILD.md`.

Release signing is read from `keystore.properties` in the repository root; no credential
is ever committed. Without that file the release build falls back to the debug key and
produces an installable but non-distributable APK.

## Bug status

1.0.0 crashed on launch for two independent reasons: the coroutines Main dispatcher was not
packaged, and no AndroidX library resources were in the resource table. 1.0.2 fixes both, plus
twelve further crash/ANR paths. Evidence and method: `docs/CRASH-REPORT.md` and
`docs/BUG-HUNT.md`.

## Module map

```
app/      Jetpack Compose UI, view models, application container
core/     Editing engine: models, timeline, keyframes, history, GL compositor,
          shaders, media probing, audio mixing, export, LUT and subtitle IO
data/     Room database, project serialisation, autosave/recovery repository
docs/     Architecture, status, testing, build, roadmap
LICENSES/ Third-party notices
```

## Licence

Application code is original work by SACHINTHA. Third-party components and their licences
are listed in `LICENSES/THIRD-PARTY.md`. No assets, templates, LUTs, fonts or code from any
commercial editor are included.
