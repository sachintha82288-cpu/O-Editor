# Crash investigation report — O Editor 1.0.0 → 1.0.2

Mission: find the real cause of the launch crash, fix it at the root, rebuild, verify.

## Environment limitation, stated up front

This work was done on a headless build container with **no Android device and no emulator**
(`/dev/kvm` is absent, so no accelerated system image can run, and a TCG-emulated image
cannot survive the 300-second command ceiling here). `adb logcat` could therefore not be
collected, and Tests A–F from the brief were **not executed**. Nothing below is presented as
a device test result.

What was done instead: the APK was taken apart and its launch path verified structurally —
the packaged class set, the packaged resource table, the manifest, the signature and the
ServiceLoader entries. That is how both root causes were found, with hard evidence rather
than guesses.

---

## Root cause 1 (1.0.0) — the coroutines Main dispatcher was not in the APK

**Symptom.** The app installed, then died before drawing the home screen.

**Evidence.** `kotlinx/coroutines/android/AndroidDispatcherFactory` was **absent** from the
1.0.0 dex files (checked byte-for-byte across all three dex files). That class is what
installs `Dispatchers.Main`.

**Mechanism.** `HomeViewModel` calls
`repository.observeSummaries().stateIn(viewModelScope, …)` while the home screen composes.
`viewModelScope` runs on `Dispatchers.Main.immediate`, so the first coroutine throws
`IllegalStateException: Module with the Main dispatcher had failed to initialize` on the main
thread during composition — a fatal exception before the first frame.

**Fix.** `kotlinx-coroutines-android-1.8.1.jar` is now an explicit dex input, and its
`META-INF/services` entries are packaged too (see root cause 3). Verified present in 1.0.1
and 1.0.2.

---

## Root cause 2 (1.0.0 and 1.0.1) — every library resource was missing

**Symptom.** Even with the dispatcher fixed, the app would still crash as soon as a library
component resolved one of its own resources.

**Evidence.**

| Build | Resources in `resources.arsc` | `resources.arsc` size |
|---|---|---|
| 1.0.1 | 8 (only O Editor's own) | 1,624 bytes |
| 1.0.2 | 280 (O Editor + 43 libraries) | 462,136 bytes |

And the dex genuinely references library resources:

```
library R-class references found in dex: 23
  Landroidx/compose/material3/R$string;
  Landroidx/compose/material3/R$style;
  Landroidx/compose/ui/R$string;
  Landroidx/compose/ui/R$style;
  Landroidx/compose/foundation/R$string;
  Landroidx/core/R$drawable;
  Landroidx/core/R$dimen;
  Landroidx/activity/R$id;
  … 15 more
```

**Mechanism.** The APK was assembled with `aapt2 link` over the app's `res/` directory only.
An Android app's resource table must also contain the resources contributed by its AAR
dependencies — Gradle merges them automatically; a hand-built APK does not. With those IDs
absent, any lookup through `androidx.compose.material3.R$string` (Material3 does this for
accessibility strings and default styles) raises `Resources$NotFoundException`. The crash
happens while composing the first Material3 surface — i.e. on the home screen.

**Fix.** Resources from all 43 AAR dependencies are extracted, compiled with `aapt2 compile`
and linked together with the app's own resources (app last, so it wins any clash).
`tools/build-apk.sh` now performs this merge.

---

## Root cause 3 (hardening) — ServiceLoader entries were not packaged

The dex contains classes but an APK also needs the `META-INF/services/*` files that
libraries use for discovery. `kotlinx-coroutines-android` ships two:

```
META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory
META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler
```

Android's `FastServiceLoader` hardcodes the Android factory class, which is why the dispatcher
worked once the class was present, but relying on that fallback is fragile. Both files are
now packaged, as Gradle would do.

---

## Secondary crashes and hangs fixed in 1.0.1

Full detail in `docs/BUG-HUNT.md`; these were all found by reading the code paths that can
kill the process or hang it:

1. Export disposed the preview's shared frame provider → blank or crashing preview after one export.
2. One frame provider used from the GL thread, the export thread and IO with plain `HashMap`s and
   a non-thread-safe `MediaMetadataRetriever` → `ConcurrentModificationException` or native crash.
3. A single NaN made `json.encodeToString` throw inside the autosave coroutine → process death
   while editing.
4. `runBlocking` on the main thread in `onCleared()` → ANR when leaving the editor.
5. Unguarded GL thread: a shader that fails to compile threw out of `onDrawFrame`.
6. Main-thread file copy after export, SQLite queries on the main thread, encoder enumeration
   during composition.
7. Unbounded AAC encode loop could hang an export forever.
8. Corrupt project row / lapsed SAF grant escaped into a view-model coroutine.
9. `Icons.Filled.Pause` exists only in `material-icons-extended`, which is not shipped.

---

## Modified files

- `core/src/main/java/com/oeditor/core/media/MediaProbe.kt` — serialised retriever access, guarded `getType`
- `core/src/main/java/com/oeditor/core/gl/Compositor.kt` — shader fallback, degenerate-size guard
- `core/src/main/java/com/oeditor/core/export/ExportEngine.kt` — provider ownership, stall guard
- `data/src/main/java/com/oeditor/data/ProjectRepository.kt` — NaN-safe encoding, load guards
- `data/src/main/java/com/oeditor/data/ProjectStore.kt` — `flowOn(IO)`
- `app/src/main/java/com/oeditor/app/ui/editor/EditorViewModel.kt` — dedicated export provider, IO copy, no `runBlocking`, load guard
- `app/src/main/java/com/oeditor/app/ui/editor/PreviewView.kt` — GL thread guard
- `app/src/main/java/com/oeditor/app/ui/editor/ExportDialog.kt` — codec probing off the UI thread
- `app/src/main/java/com/oeditor/app/ui/editor/EditorScreen.kt` — pause glyph instead of extended icon
- `tools/build-apk.sh` — library resource merge and `META-INF/services` packaging
- `docs/BUG-HUNT.md`, `docs/CRASH-REPORT.md`, `dist/*` — documentation and release artefacts

No feature was removed and no exception is swallowed silently: the guards report through the
UI (`state.message`) or return a typed failure from the export engine.

## Test status

| Test | Result |
|---|---|
| A. Clean install + launch | **not executed** — no device/emulator available |
| B. Startup stability / navigation | **not executed** |
| C. Project create → save → reopen | **not executed** |
| D. Media import + trim | **not executed** |
| E. Effect + export + playback | **not executed** |
| F. Release build install | **not executed** (release APK is built, signed and verified) |
| Static: referenced classes present in dex | **passed** — 331 referenced, 0 missing |
| Static: library resource IDs present in arsc | **passed** — 23 library R references, 280 resources packaged |
| Static: ServiceLoader entries packaged | **passed** |
| Signature verification | **passed** — `apksigner verify` on both APKs |
| Manifest / badging | **passed** — `com.oeditor.app`, versionCode 3, versionName 1.0.2 |

## Install

```bash
adb install -r O-Editor-1.0.2.apk          # release-signed
# or, if you prefer the debug key:
adb install -r O-Editor-1.0.2-debug.apk
```

Requires Android 12 (API 31)+ with OpenGL ES 3.0.

## Known remaining issues

- Preview decodes frames with `MediaMetadataRetriever`: accurate scrubbing, not real-time playback.
- No waveform display; audio chain limited to volume/pan/fades; no nested sequences.
- Export runs inside the editor process; it does not survive the process being killed.
- The release APK is **not** R8-minified. Minification cannot be validated without a device, and
  shipping unverified shrinking on a build that already had two packaging bugs would be
  reckless. `proguard-rules.pro` is in place for when it can be tested.
- If the app still crashes on your device, `adb logcat -d -v threadtime | grep -A40 "FATAL EXCEPTION"`
  will show the exact class and line.
