#!/usr/bin/env bash
# Builds O Editor's APK without Gradle, using only the Android SDK + Kotlin compiler.
# Usage: tools/build-apk.sh [path-to-android-sdk] [path-to-gradle-cache]
#
# It expects the resolved dependency jars to be available in a Gradle cache
# (caches/modules-2 and caches/8.9/transforms); run `gradle :app:dependencies` once on any
# machine to populate it, or point GRADLE_CACHE at an existing cache.
set -euo pipefail

SDK="${1:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
GRADLE_CACHE="${2:-${GRADLE_USER_HOME:-$HOME/.gradle}/caches}"
BUILD_TOOLS="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
WORK="build-manual"
OUT="dist"
KOTLIN_VERSION="2.0.21"

echo "SDK:          $SDK"
echo "Build tools:  $BUILD_TOOLS"
echo "Gradle cache: $GRADLE_CACHE"

mkdir -p "$WORK"/{classes/core,classes/data,classes/app,dex,flat,res} "$OUT"

# ---------------------------------------------------------------- classpath
CP=$( { find "$GRADLE_CACHE/modules-2" -name '*.jar'; \
        find "$GRADLE_CACHE"/8.9/transforms -name 'classes.jar' 2>/dev/null; } \
      | grep -vE 'kotlin-compiler-embeddable|kotlin-gradle-plugin|compose-compiler-gradle-plugin|/bundletool/|/builder/|/aapt2/|/manifest-merger/|/r8/|/d8/' \
      | tr '\n' ':')"$ANDROID_JAR"

KC=$(find "$GRADLE_CACHE/modules-2" -name "kotlin-compiler-embeddable-$KOTLIN_VERSION.jar" | head -1)
SER_PLUGIN=$(find "$GRADLE_CACHE/modules-2" -name "kotlin-serialization-compiler-plugin-embeddable-$KOTLIN_VERSION.jar" | head -1)
COMPOSE_PLUGIN=$(find "$GRADLE_CACHE/modules-2" -name "kotlin-compose-compiler-plugin-embeddable-$KOTLIN_VERSION.jar" | head -1)
[ -f "$KC" ] || { echo "kotlin-compiler-embeddable not found"; exit 1; }

kc() { java -Xmx1g -cp "$KC:$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -jvm-target 17 -nowarn "$@"; }

# ---------------------------------------------------------------- kotlin
echo "== compiling core"
kc -Xplugin="$SER_PLUGIN" -cp "$CP" -d "$WORK/classes/core" \
   $(find core/src/main/java -name '*.kt')

echo "== compiling data"
kc -Xplugin="$SER_PLUGIN" -cp "$CP:$WORK/classes/core" -d "$WORK/classes/data" \
   $(find data/src/main/java -name '*.kt')

echo "== compiling app"
kc -Xplugin="$COMPOSE_PLUGIN" -Xplugin="$SER_PLUGIN" \
   -cp "$CP:$WORK/classes/core:$WORK/classes/data" -d "$WORK/classes/app" \
   $(find app/src/main/java -name '*.kt')

( cd "$WORK/classes/core" && jar cf ../core.jar . )
( cd "$WORK/classes/data" && jar cf ../data.jar . )
( cd "$WORK/classes/app"  && jar cf ../app.jar  . )

# ---------------------------------------------------------------- resources
echo "== resources"
( cd app/src/main
  "$BUILD_TOOLS/aapt2" compile --dir res -o "../../$WORK/flat/res.zip"
  sed -e 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.oeditor.app">|' \
      -e 's|${applicationId}|com.oeditor.app|g' AndroidManifest.xml > "../../$WORK/AndroidManifest.xml"
  "$BUILD_TOOLS/aapt2" link -o "../../$WORK/base.ap_" \
      -I "$ANDROID_JAR" --manifest "../../$WORK/AndroidManifest.xml" \
      --min-sdk-version 31 --target-sdk-version 34 \
      --version-code 1 --version-name 1.0.0 --auto-add-overlay "../../$WORK/flat/res.zip" )

# ---------------------------------------------------------------- dex (3 groups: d8 is slow on big graphs)
echo "== dexing"
DEXJARS=$( { find "$GRADLE_CACHE"/8.9/transforms -name 'classes.jar'; } \
           | grep -vE 'livedata|material-icons-extended' | tr '\n' ' ')
GROUPS=("$WORK/core.jar $WORK/data.jar $WORK/app.jar")
python3 - "$GRADLE_CACHE" "$WORK" <<'PY'
import glob, os, re, sys
cache, work = sys.argv[1], sys.argv[2]
jars = sorted(glob.glob(cache + '/8.9/transforms/**/classes.jar', recursive=True))
jars = [j for j in jars if 'livedata' not in j and 'material-icons-extended' not in j]
keep = {}
def vkey(v): return [int(x) if x.isdigit() else 0 for x in re.split(r'[.\-]', v)]
for p in glob.glob(cache + '/modules-2/files-2.1/*/*/*/*/*.jar'):
    parts = p[len(cache) + len('/modules-2/files-2.1/'):].split('/')
    group, name, version = parts[0], parts[1], parts[2]
    if group not in ('org.jetbrains.kotlinx', 'org.jetbrains.kotlin'): continue
    if 'sources' in os.path.basename(p): continue
    if name not in ('kotlin-stdlib', 'kotlinx-coroutines-core',
                    'kotlinx-serialization-core', 'kotlinx-serialization-json'): continue
    k = (group, name)
    if k not in keep or vkey(version) > vkey(keep[k][0]): keep[k] = (version, p)
jars += [v[1] for v in keep.values()]
ours = [work + '/app.jar', work + '/data.jar', work + '/core.jar']
allj = ours + jars
allj.sort(key=lambda p: -os.path.getsize(p))
groups = [[], [], []]
sizes = [0, 0, 0]
for j in allj:
    i = sizes.index(min(sizes)); groups[i].append(j); sizes[i] += os.path.getsize(j)
for i, g in enumerate(groups):
    open(f'{work}/group{i+1}.txt', 'w').write('\n'.join(g))
PY
for g in 1 2 3; do
  rm -rf "$WORK/dex$g"; mkdir -p "$WORK/dex$g"
  "$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --min-api 31 --output "$WORK/dex$g" \
     $(cat "$WORK/group$g.txt" | tr '\n' ' ')
done

# ---------------------------------------------------------------- package + sign
echo "== packaging"
cp "$WORK/base.ap_" "$WORK/unsigned.apk"
cp "$WORK/dex1/classes.dex"  "$WORK/classes.dex"
cp "$WORK/dex2/classes.dex"  "$WORK/classes2.dex"
cp "$WORK/dex3/classes.dex"  "$WORK/classes3.dex"
( cd "$WORK" && zip -q unsigned.apk classes.dex classes2.dex classes3.dex )
"$BUILD_TOOLS/zipalign" -p -f 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"

if [ ! -f keystore.properties ]; then
  echo "No keystore.properties found: signing with a throwaway key (not for distribution)."
  keytool -genkeypair -v -keystore "$WORK/dev.jks" -alias oeditor -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass oeditor -keypass oeditor \
    -dname "CN=O Editor, O=O Editor, C=LK" >/dev/null 2>&1
  KS="$WORK/dev.jks"; KSPASS=oeditor; ALIAS=oeditor
else
  KS=$(grep '^storeFile=' keystore.properties | cut -d= -f2-)
  KSPASS=$(grep '^storePassword=' keystore.properties | cut -d= -f2-)
  ALIAS=$(grep '^keyAlias=' keystore.properties | cut -d= -f2-)
  KEYPASS=$(grep '^keyPassword=' keystore.properties | cut -d= -f2-)
fi
"$BUILD_TOOLS/apksigner" sign --ks "$KS" --ks-key-alias "$ALIAS" \
  --ks-pass "pass:$KSPASS" --key-pass "pass:${KEYPASS:-$KSPASS}" \
  --out "$OUT/O-Editor-1.0.0.apk" "$WORK/aligned.apk"

"$BUILD_TOOLS/apksigner" verify --print-certs "$OUT/O-Editor-1.0.0.apk"
"$BUILD_TOOLS/aapt2" dump badging "$OUT/O-Editor-1.0.0.apk" | head -4
echo "APK: $OUT/O-Editor-1.0.0.apk"
