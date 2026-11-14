#!/bin/bash
# Builds build/afk-<version>.jar against a local Minecraft 26.3 install. No Gradle needed:
# 26.x ships with readable class names, so the mod compiles straight against the game jar.
#   JDK_HOME  JDK 25 (default: Homebrew's openjdk@25)
#   MC_DIR    Minecraft folder (default: ~/Library/Application Support/minecraft)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
MC="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
MC_VERSION="26.3"
JDK="${JDK_HOME:-/opt/homebrew/opt/openjdk@25}"
[ -x "$JDK/bin/javac" ] || { echo "No JDK 25 at $JDK (set JDK_HOME)" >&2; exit 1; }
VERSION="$(python3 -c "import json;print(json.load(open('$HERE/resources/fabric.mod.json'))['version'])")"

BUILD="$HERE/build"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/deps"
first() { for f in "$@"; do [ -f "$f" ] && { echo "$f"; return; }; done; return 1; }
FABRIC_API="$(first "$MC"/mods/fabric-api-*.jar)" || { echo "fabric-api jar not found in $MC/mods" >&2; exit 1; }
BARITONE="$(first "$MC"/mods/baritone-api-fabric-*.jar "$MC"/mods-disabled/baritone-api-fabric-*.jar)" || { echo "Baritone API jar not found" >&2; exit 1; }
IRIS="$(first "$MC"/mods/iris-*.jar "$MC"/mods-disabled/iris-*.jar)" || { echo "Iris jar not found" >&2; exit 1; }
unzip -q -j "$FABRIC_API" 'META-INF/jars/*.jar' -d "$BUILD/deps"

CP="$(MC="$MC" V="$MC_VERSION" python3 - <<'PY'
import json, os
mc, v = os.environ["MC"], os.environ["V"]
d = json.load(open(f"{mc}/versions/{v}/{v}.json"))
jars = [f"{mc}/versions/{v}/{v}.jar"]
for lib in d["libraries"]:
    art = lib.get("downloads", {}).get("artifact")
    if art and os.path.exists(f"{mc}/libraries/{art['path']}"):
        jars.append(f"{mc}/libraries/{art['path']}")
print(":".join(jars))
PY
)"
for jar in "$BUILD"/deps/*.jar "$BARITONE" "$IRIS" "$MC"/libraries/net/fabricmc/fabric-loader/*/fabric-loader-*.jar "$MC"/libraries/net/fabricmc/sponge-mixin/*/sponge-mixin-*.jar; do
  CP="$CP:$jar"
done

find "$HERE/src" -name '*.java' | sed 's/.*/"&"/' > "$BUILD/sources.txt"
"$JDK/bin/javac" --release 25 -proc:none -Xlint:-options -nowarn -cp "$CP" -d "$BUILD/classes" @"$BUILD/sources.txt"
cp -R "$HERE/resources/." "$BUILD/classes/"
"$JDK/bin/jar" --create --file "$BUILD/afk-$VERSION.jar" -C "$BUILD/classes" .
echo "Built $BUILD/afk-$VERSION.jar"
