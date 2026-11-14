#!/bin/bash
# Starts an offline test copy of the game with AFK loaded, in its own folder, so the mod can be
# tested by script without the launcher or an account. It copies one of your singleplayer worlds
# (the original is never touched), turns cheats on in the copy, and opens it directly.
#
#   dev/test-client.sh <test dir> [world name] [port]
#
# The bridge listens on <port> (default 25600) with test hooks enabled (POST /dev).
set -euo pipefail
TEST="$1"; WORLD="${2:-New World}"; PORT="${3:-25600}"; USERNAME="AfkTester"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
MC="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
MCV="26.3"; FABRIC="fabric-loader-0.20.1-26.3"
JAVA="$(find "$MC/runtime" -type f -name java -path '*bin*' | head -1)"
[ -x "$JAVA" ] || { echo "Minecraft's Java runtime not found under $MC/runtime" >&2; exit 1; }

mkdir -p "$TEST/mods" "$TEST/saves" "$TEST/config" "$TEST/afk/brain" "$TEST/natives"
rm -f "$TEST"/mods/*.jar
cp "$MC"/mods/*.jar "$TEST/mods/"
rm -f "$TEST"/mods/afk-*.jar
cp "$(ls -t "$HERE"/mod/build/afk-*.jar | head -1)" "$TEST/mods/"
cp "$HERE/brain/agent.mjs" "$TEST/afk/brain/agent.mjs"
# Its own copy of the shader packs: the mod changes per-pack settings files, and tests must never touch yours.
[ -L "$TEST/shaderpacks" ] && rm "$TEST/shaderpacks"
[ -d "$TEST/shaderpacks" ] || { mkdir -p "$TEST/shaderpacks"; cp "$MC"/shaderpacks/*.zip "$TEST/shaderpacks/" 2>/dev/null || true; }
[ -e "$TEST/resourcepacks" ] || ln -s "$MC/resourcepacks" "$TEST/resourcepacks"
[ -f "$TEST/config/iris.properties" ] || printf 'enableShaders=false\nshaderPack=ComplementaryUnbound_r5.9.3.zip\n' > "$TEST/config/iris.properties"
[ -f "$TEST/config/afk.json" ] || printf '{"autoStartBrain": false, "provider": "claude", "model": "", "node": "node", "brainScript": "%s"}\n' "$TEST/afk/brain/agent.mjs" > "$TEST/config/afk.json"
if [ ! -f "$TEST/options.txt" ]; then
  grep -vE '^(fullscreen|pauseOnLostFocus|soundCategory_master|overrideWidth|overrideHeight|resourcePacks|incompatibleResourcePacks):' "$MC/options.txt" > "$TEST/options.txt" || true
  printf 'fullscreen:false\npauseOnLostFocus:false\nsoundCategory_master:0.0\n' >> "$TEST/options.txt"
fi
if [ ! -d "$TEST/saves/$WORLD" ]; then
  cp -R "$MC/saves/$WORLD" "$TEST/saves/$WORLD"
  rm -f "$TEST/saves/$WORLD/session.lock"
  python3 - "$TEST/saves/$WORLD/level.dat" <<'PY'
import gzip, sys
p = sys.argv[1]
raw = gzip.open(p).read()
key = b"\x01\x00\x0dallowCommands"
i = raw.find(key)
assert i >= 0, "allowCommands not found"
raw = raw[:i + len(key)] + b"\x01" + raw[i + len(key) + 1:]
gzip.open(p, "wb").write(raw)
print("cheats enabled in the test copy")
PY
fi
# 26.3 stores the world owner's UUID; an offline player gets a UUID made from its name. Make the
# test player the owner of the copy (so cheats work) and give it the real owner's inventory and position.
python3 - "$TEST/saves/$WORLD" "$USERNAME" <<'PY'
import gzip, hashlib, os, shutil, struct, sys, uuid
world, name = sys.argv[1], sys.argv[2]
offline = uuid.UUID(bytes=hashlib.md5(("OfflinePlayer:" + name).encode()).digest(), version=3)
p = os.path.join(world, "level.dat")
raw = gzip.open(p).read()
key = b"\x0b\x00\x11singleplayer_uuid"
i = raw.find(key)
if i >= 0:
    at = i + len(key) + 4
    owner = uuid.UUID(bytes=raw[at:at + 16])
    if owner != offline:
        raw = raw[:at] + offline.bytes + raw[at + 16:]
        gzip.open(p, "wb").write(raw)
        src = os.path.join(world, "players", "data", f"{owner}.dat")
        dst = os.path.join(world, "players", "data", f"{offline}.dat")
        if os.path.exists(src) and not os.path.exists(dst):
            shutil.copy(src, dst)
        print("test player now owns the world copy")
PY
CP="$(MC="$MC" V="$MCV" F="$FABRIC" python3 - <<'PY'
import json, os
mc, v, f = os.environ["MC"], os.environ["V"], os.environ["F"]
jars = []
fj = json.load(open(f"{mc}/versions/{f}/{f}.json"))
for lib in fj["libraries"]:
    g, a, ver = lib["name"].split(":")
    jars.append(f"{mc}/libraries/{g.replace('.', '/')}/{a}/{ver}/{a}-{ver}.jar")
d = json.load(open(f"{mc}/versions/{v}/{v}.json"))
for lib in d["libraries"]:
    ok = True
    if lib.get("rules"):
        ok = False
        for r in lib["rules"]:
            o = r.get("os", {})
            if o.get("name") in (None, "osx") and o.get("arch") in (None, "arm64"):
                ok = r["action"] == "allow"
    art = lib.get("downloads", {}).get("artifact")
    if ok and art and os.path.exists(f"{mc}/libraries/{art['path']}"):
        jars.append(f"{mc}/libraries/{art['path']}")
jars.append(f"{mc}/versions/{v}/{v}.jar")
print(":".join(jars))
PY
)"
ASSET_INDEX="$(python3 -c "import json;print(json.load(open('$MC/versions/$MCV/$MCV.json'))['assetIndex']['id'])")"
NAT="$TEST/natives"
cd "$TEST"
exec "$JAVA" -XstartOnFirstThread -XX:StackShadowPages=32 --enable-native-access=ALL-UNNAMED \
  --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
  "-Djava.library.path=$NAT/java" "-Djna.tmpdir=$NAT/jna" "-Dorg.lwjgl.system.SharedLibraryExtractPath=$NAT/lwjgl" \
  "-Dio.netty.native.workdir=$NAT/netty" -Dminecraft.launcher.brand=afk-dev -Dminecraft.launcher.version=1 \
  "-DFabricMcEmu= net.minecraft.client.main.Main " -Xmx4G -XX:+UseCompactObjectHeaders \
  -Dafk.dev=true "-Dafk.port=$PORT" \
  -cp "$CP" net.fabricmc.loader.impl.launch.knot.KnotClient \
  --username "$USERNAME" --version "$FABRIC" --gameDir "$TEST" --assetsDir "$MC/assets" --assetIndex "$ASSET_INDEX" \
  --uuid 00000000-0000-0000-0000-000000000000 --accessToken 0 --clientId 0 --xuid 0 --versionType release \
  --width 1280 --height 720 --quickPlaySingleplayer "$WORLD"
