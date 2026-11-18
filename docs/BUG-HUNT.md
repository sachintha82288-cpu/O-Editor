# Deep bug hunt — 1.0.0 → 1.0.1

Scope: every path that can take the process down or hang it — the GL thread, the export
pipeline, the persistence layer, coroutine scopes and the packaged runtime class set.

Method: (1) read the crash-prone code paths, (2) compare the class references in our own
compiled classes against the classes actually present in the APK's dex files, (3) inspect
the previously shipped 1.0.0 APK the same way. Step 2 is how the fatal bug below was found
and proved, not guessed.

---

## BUG-1 (critical) — the app crashed on launch: no Main dispatcher in the APK

`kotlinx-coroutines-android` was missing from the dex. It is the artifact that registers
`Dispatchers.Main`; without it the first coroutine on the main dispatcher throws:

```
IllegalStateException: Module with the Main dispatcher had failed to initialize.
```

`HomeViewModel` calls `stateIn(viewModelScope, …)` while the home screen composes, and
`viewModelScope` runs on `Dispatchers.Main.immediate` — so the crash happened on the very
first screen, before anything could be drawn.

Proof, from the shipped 1.0.0 APK:

```
Main dispatcher (coroutines-android)   MISSING
LifecycleOwner                         PRESENT
PlayArrow / Pause icon                 PRESENT
```

Fixed: `kotlinx-coroutines-android-1.8.1.jar` is now an explicit dex input, and the same
check on 1.0.1 reports it present. This is the bug that made the app crash.

## BUG-2 (high) — export released the preview's frame provider

`ExportEngine.release()` disposed the `MediaFrameProvider` it was handed — the same
instance the preview renderer was using. After one export the preview lost its
`MediaMetadataRetriever`s and either went black or threw `IllegalStateException` from a
released retriever.

Fixed: the engine only releases a provider it created itself (`ownsProvider`), and
`EditorViewModel` now gives export its own provider.

## BUG-3 (high) — one frame provider shared across three threads

`MediaFrameProvider` kept its retrievers in plain `HashMap`s and was called from the GL
render thread (preview), the export thread and `Dispatchers.IO` (thumbnails). Plain maps
mutated from several threads can throw `ConcurrentModificationException`, and
`MediaMetadataRetriever` is not thread-safe — a concurrent decode is a native crash, not a
Java exception.

Fixed: every access is serialised on one lock, and each consumer gets its own provider.

## BUG-4 (high) — a single NaN killed autosave and the app with it

`ProjectRepository.save` called `json.encodeToString` outside its `try`. kotlinx.serialization
refuses NaN/Infinity by default, and a degenerate value (a slider at an extreme, a bezier
solve at a singular point) is enough to raise `SerializationException`. That ran inside the
autosave coroutine, so it was uncaught — process death while the user was editing.

Fixed: `allowSpecialFloatingPointValues = true` and the encode is wrapped; a failed encode
returns `false` instead of throwing.

## BUG-5 (high) — `runBlocking` on the main thread when leaving the editor

`onCleared()` saved the document with `runBlocking` on the main thread. On a slow device or
a locked database that is an ANR at best, a deadlock at worst.

Fixed: the last snapshot is handed to an IO coroutine instead.

## BUG-6 (high) — any GL failure terminated the process

`Compositor.program()` threw `IllegalStateException` when a shader failed to compile, and
`PreviewRenderer.onDrawFrame` had no guard. A driver that rejects one effect shader (or a
framebuffer that comes back incomplete at 4K) killed the app from the GL thread, where
nothing can catch it.

Fixed: a failed shader falls back to pass-through, `Compositor.render` rejects degenerate
sizes, and `onDrawFrame` catches `Throwable` so one bad frame renders black instead of
closing the app.

## BUG-7 (medium) — publishing an export blocked the UI thread

`publishToGallery` copied the finished MP4 into MediaStore on the main thread — for a 4K
export that is hundreds of megabytes of I/O in a UI callback.

Fixed: runs on `Dispatchers.IO`.

## BUG-8 (medium) — SQLite queried on the main thread

`ProjectStore.observeSummaries()` executed its query on whatever thread collected the flow,
which is the main thread via `collectAsStateWithLifecycle`.

Fixed: `flowOn(Dispatchers.IO)`.

## BUG-9 (medium) — encoder enumeration during composition

`ExportDialog` walked every encoder on the device (`MediaCodecList` + capability queries)
inside `remember` during composition, on the main thread. On a device with many codecs this
is a visible stall and can ANR.

Fixed: `produceState` + `Dispatchers.IO`, with the codec selection applied once the list
arrives.

## BUG-10 (medium) — export could hang forever on a stalled AAC encoder

`encodeAudio` looped `while (true)` until the encoder reported end-of-stream. A codec that
stops draining leaves the export spinning indefinitely with the progress bar frozen.

Fixed: a stall counter aborts the loop and the export reports failure instead of hanging.

## BUG-11 (medium) — corrupt project row crashed the editor

`EditorViewModel.init` and `ProjectRepository.load` let SQLite/JSON exceptions escape into
the view-model coroutine. A damaged row or a half-written backup closed the app.

Fixed: `runCatching` around the load, the recovery row and the backup file; the editor shows
"Project not found" instead of crashing.

## BUG-12 (medium) — lapsed SAF permission crashed import

`MediaProbe.probe` called `contentResolver.getType(uri)` outside its `try`. After a reboot
or a cleared grant that throws `SecurityException` and took the import coroutine down.

Fixed: guarded; an unreadable file is reported as "could not read the selected media".

## BUG-13 (low, would have been critical) — icon that only exists in the extended pack

`Icons.Filled.Pause` lives in `material-icons-extended`, which this build no longer ships.
It compiled only because the extended jar was still in the build cache, and the dex step
would have dropped it — `NoClassDefFoundError` the moment the transport bar composed.

Fixed: drawn as a text glyph; every icon the app uses (`Add`, `MoreVert`, `ArrowBack`,
`PlayArrow`) was verified present in `material-icons-core`.

---

## Verification of 1.0.1

```
referenced class-ish names: 331   missing: 0
```

Every `androidx` / `kotlinx` / `kotlin` class referenced by our own compiled classes exists
in the APK's three dex files. Plus: `apksigner verify` passes, `aapt2 dump badging` reports
`com.oeditor.app` versionCode 2 / versionName 1.0.1, minSdk 31, targetSdk 34.

Still not verified, and not claimed: installation and interaction on a physical device, a
full export run, and the JVM unit tests (written, not executed).
