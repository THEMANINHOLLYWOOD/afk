#!/usr/bin/env python3
"""Checks a shader pack against Apple's limit of 16 samplers per fragment shader.

With Iris's debug option on (enableDebugOptions=true in config/iris.properties), Iris writes the
final GLSL it compiled for every program to <game dir>/patched_shaders/. This counts the sampler
uniforms each fragment program declares there. Declared samplers are an upper bound on the active
ones the driver counts, so a pack that passes here passes on a Mac. Run it once per quality tier.

usage: python3 dev/count-shader-samplers.py <game dir>/patched_shaders [limit]
"""
import glob
import os
import re
import sys

def main():
    folder = sys.argv[1]
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else 16
    counts = []
    for path in sorted(glob.glob(os.path.join(folder, "*.fsh"))):
        with open(path, errors="replace") as f:
            n = sum(1 for line in f if re.match(r"\s*uniform\s+[iu]?sampler", line))
        counts.append((n, os.path.basename(path)))
    if not counts:
        sys.exit(f"no fragment programs in {folder} (is Iris's debug option on?)")
    counts.sort(reverse=True)
    for n, name in counts[:5]:
        print(f"{n:3d}  {name}")
    over = [c for c in counts if c[0] > limit]
    print(f"{len(counts)} fragment programs, most samplers: {counts[0][0]} (limit {limit})")
    sys.exit(1 if over else 0)

if __name__ == "__main__":
    main()
