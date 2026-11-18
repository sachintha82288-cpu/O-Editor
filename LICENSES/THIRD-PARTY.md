# Third-party components

O Editor's own source (app/, core/, data/) is original work by SACHINTHA. It contains no
code, assets, templates, transitions, LUTs, fonts or audio copied from any commercial
editor.

All libraries below are Apache-2.0 or MIT, which permits redistribution provided the notice
is kept. No GPL, statically-linked LGPL or non-free component is included.

| Component | Version | Licence |
|---|---|---|
| Kotlin standard library | 2.0.21 | Apache-2.0 |
| kotlinx-coroutines | 1.8.1 | Apache-2.0 |
| kotlinx-serialization (runtime + json) | 1.7.3 | Apache-2.0 |
| AndroidX Core KTX | 1.13.1 | Apache-2.0 |
| AndroidX Activity Compose | 1.9.2 | Apache-2.0 |
| AndroidX Lifecycle (viewmodel/runtime compose) | 2.8.6 | Apache-2.0 |
| Jetpack Compose (UI, Foundation, Material 3, BOM 2024.09.02) | via BOM | Apache-2.0 |
| AndroidX Room (runtime, ktx, compiler) | 2.6.1 | Apache-2.0 |
| AndroidX DocumentFile | 1.0.1 | Apache-2.0 |
| AndroidX Media3 (transformer, effect, common) | 1.4.1 | Apache-2.0 |
| JUnit 4 | 4.13.2 | Eclipse Public License 1.0 (test scope) |

Android platform APIs (MediaCodec, MediaMuxer, MediaExtractor, OpenGL ES, EGL, Canvas) are
part of the Android SDK, used under the Android SDK terms and not redistributed here.

## Not used, deliberately

- **FFmpeg** — avoided entirely, so no GPL/LGPL configuration obligation arises.
- Advertising, analytics or crash-reporting SDKs.
- Any font, icon pack, LUT, music bed or template not created for this project.

## Notices

Include the Apache-2.0 text with any binary distribution (an open-source notices screen or
a file alongside the APK). If you add a dependency, record it here first, with its licence
and redistribution terms.
