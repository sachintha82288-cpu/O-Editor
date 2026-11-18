# Build and release

## Toolchain

| Component | Version |
|---|---|
| JDK | 17 |
| Gradle | 8.9 |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 2.0.21 (Compose compiler plugin 2.0.21) |
| KSP | 2.0.21-1.0.25 |
| compileSdk / targetSdk | 34 |
| minSdk | 31 (Android 12) |

Create `local.properties` with your SDK path:

```properties
sdk.dir=/path/to/Android/Sdk
```

## Commands

```bash
gradle :core:test              # JVM unit tests
gradle :app:assembleDebug      # debug APK
gradle :app:assembleRelease    # release APK
gradle :app:bundleRelease      # AAB
```

Outputs land in `app/build/outputs/apk/…`. If your machine has little RAM, lower
`org.gradle.jvmargs` in `gradle.properties`.

## Release signing

Create `keystore.properties` in the repository root (git-ignored, never shipped):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Generate the keystore once:

```bash
keytool -genkeypair -v -keystore release.jks -alias oeditor \
  -keyalg RSA -keysize 4096 -validity 10000
```

When the file is absent the release build falls back to the debug key: the APK installs and
runs but is **not** distributable.

## Verify a build

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
aapt2 dump badging app/build/outputs/apk/release/app-release.apk | head
```

Expected package: `com.oeditor.app` (debug: `com.oeditor.app.debug`).

## Low-memory devices

The renderer, encoder and database share one process; `android:largeHeap` is set because a
4K export holds several 8 MB framebuffers. On a 2 GB device export at 1080p or below and
keep the timeline to a few hundred clips.


## Building the APK without Gradle

If Gradle cannot run in your environment (very low memory, or a build box that kills long
invocations), the APK can be assembled with the SDK tools alone. `tools/build-apk.sh` does
exactly this; the steps are:

1. `kotlinc` (Kotlin 2.0.21 compiler-embeddable) compiles `core/` with the serialization
   compiler plugin, `data/` against it, then `app/` with the Compose compiler plugin.
   Classpath = AndroidX jars from the Gradle cache (or your own resolution) + `android.jar`.
2. `aapt2 compile` the resources, `aapt2 link` them with the manifest (add
   `package="com.oeditor.app"` and substitute `${applicationId}` when linking outside AGP).
3. `d8` converts the class jars to dex. On a slow machine dex in three balanced groups and
   ship `classes.dex`, `classes2.dex`, `classes3.dex`.
4. `zipalign -p -f 4`, then `apksigner sign` with your keystore.
5. Verify with `apksigner verify` and `aapt2 dump badging`.

Notes learned the hard way: `lifecycle-livedata-core-2.8.6` makes R8 8.2 abort with an
internal NPE, so that jar is excluded (nothing in O Editor uses LiveData); `d8` needs jars
rather than class directories; and duplicate `kotlin-stdlib` versions must be deduplicated
to the newest before dexing.
