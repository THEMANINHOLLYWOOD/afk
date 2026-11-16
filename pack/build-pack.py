#!/usr/bin/env python3
"""Builds AFK-<version>.mrpack (Modrinth modpack format) from pack/sources.json.

Every third-party file (Fabric API, Sodium, Iris, Baritone, shader packs, resource packs) is
linked by URL and hash, never copied, so their authors' licences and download counts are respected.
The AFK mod itself is linked from this repo's GitHub release; the brain ships in overrides/, and so
does anything listed under "builtFromRepo" in sources.json (the AFK shader pack, zipped from
shaderpack/).

usage: python3 pack/build-pack.py <path to afk-<version>.jar> <github owner/repo>
"""
import hashlib, io, json, os, shutil, sys, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

def zip_dir(src):
    """Zips a folder of this repo the way Iris expects a pack (its contents at the zip root),
    with sorted names and fixed timestamps so the same source always gives the same bytes."""
    buf = io.BytesIO()
    root = os.path.join(ROOT, src)
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for base, _, names in sorted(os.walk(root)):
            for n in sorted(names):
                if n == ".DS_Store":
                    continue
                p = os.path.join(base, n)
                info = zipfile.ZipInfo(os.path.relpath(p, root).replace(os.sep, "/"), (2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o644 << 16
                with open(p, "rb") as f:
                    z.writestr(info, f.read())
    return buf.getvalue()

def main():
    jar, repo = sys.argv[1], sys.argv[2]
    version = json.load(open(os.path.join(ROOT, "mod/resources/fabric.mod.json")))["version"]
    src = json.load(open(os.path.join(HERE, "sources.json")))
    data = open(jar, "rb").read()
    files = [{
        "path": f["path"], "hashes": {"sha1": f["sha1"], "sha512": f["sha512"]},
        "env": {"client": "required", "server": "unsupported"}, "downloads": [f["url"]], "fileSize": f["size"],
    } for f in src["files"]]
    files.append({
        "path": f"mods/afk-{version}.jar",
        "hashes": {"sha1": hashlib.sha1(data).hexdigest(), "sha512": hashlib.sha512(data).hexdigest()},
        "env": {"client": "required", "server": "unsupported"},
        "downloads": [f"https://github.com/{repo}/releases/download/v{version}/afk-{version}.jar"],
        "fileSize": len(data),
    })
    index = {
        "formatVersion": 1, "game": "minecraft", "versionId": version, "name": "AFK",
        "summary": "Tap Tab and Herobrine takes over. A cinematic, agentic Minecraft modpack.",
        "files": files,
        "dependencies": {"minecraft": src["minecraft"], "fabric-loader": src["fabric-loader"]},
    }
    out = os.path.join(ROOT, "dist", f"AFK-{version}.mrpack")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("modrinth.index.json", json.dumps(index, indent=2))
        for base, _, names in os.walk(os.path.join(HERE, "overrides")):
            for n in names:
                p = os.path.join(base, n)
                z.write(p, os.path.join("overrides", os.path.relpath(p, os.path.join(HERE, "overrides"))))
        z.write(os.path.join(ROOT, "brain/agent.mjs"), "overrides/afk/brain/agent.mjs")
        for item in src.get("builtFromRepo", []):
            z.writestr("overrides/" + item["path"], zip_dir(item["source"]))
    shutil.copy(jar, os.path.join(ROOT, "dist", f"afk-{version}.jar"))
    print("Built", out)

if __name__ == "__main__":
    main()
