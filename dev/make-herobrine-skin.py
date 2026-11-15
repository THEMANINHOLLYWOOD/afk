#!/usr/bin/env python3
"""Draws Herobrine's skin: a retired legend dragged back to work.

Steve's palette gone to seed: the cyan shirt is now a faded short-sleeve Hawaiian shirt hanging open
over a tank top, the blue trousers are worn jeans with a big belt buckle, and he has grey hair,
grey mutton chops, aviators pushed up on his forehead, the white Herobrine eyes and a toothpick.
Iron shackles on both wrists and ankles, broken chain hanging off them, and a chain with a padlock
slung across his chest.

    python3 dev/make-herobrine-skin.py [out.png] [preview.png]

Writes the 64x64 skin (classic/wide arms) and, optionally, a scaled front/back preview.
"""
import random
import sys

from PIL import Image

W = 64
img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
px = img.load()
rng = random.Random(7)

# ---------------------------------------------------------------- palette
SKIN, SKIN_SH, SKIN_HI, WRINKLE = (176, 122, 88), (148, 100, 70), (194, 140, 104), (132, 88, 62)
HAIR, HAIR_DK, HAIR_LT, HAIR_WH = (146, 146, 148), (98, 98, 102), (186, 186, 188), (214, 214, 214)
CHOP, CHOP_DK = (150, 150, 152), (108, 108, 112)
EYE = (255, 255, 255)
MOUTH = (92, 56, 44)
TOOTHPICK = (214, 186, 128)
SHIRT, SHIRT_DK, SHIRT_LT = (52, 142, 150), (36, 108, 118), (78, 170, 176)
FLOWER, FLOWER2, LEAF = (238, 222, 184), (232, 132, 62), (30, 92, 82)
TANK, TANK_DK = (226, 222, 208), (196, 190, 172)
DENIM, DENIM_DK, DENIM_LT, RIP = (54, 72, 122), (40, 54, 96), (82, 102, 152), (196, 204, 220)
BELT, BUCKLE, BUCKLE_DK = (68, 44, 30), (214, 176, 76), (160, 124, 44)
BOOT, BOOT_DK, SOLE = (100, 66, 40), (72, 46, 28), (40, 30, 22)
IRON, IRON_HI, IRON_SH, RIVET = (164, 164, 170), (212, 212, 218), (98, 98, 106), (66, 66, 72)
LINK, LINK_DK = (150, 150, 156), (88, 88, 96)
BRASS, BRASS_DK = (192, 152, 62), (120, 90, 30)
GOLD, LENS, LENS_HI = (204, 172, 84), (38, 44, 56), (92, 110, 130)
INK = (58, 82, 92)


def put(x, y, c):
    px[x, y] = c + (255,) if len(c) == 3 else c


def fill(x0, y0, w, h, c):
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            put(x, y, c)


def jitter(c, amount=8):
    d = rng.randint(-amount, amount)
    return tuple(max(0, min(255, v + d)) for v in c)


def hawaiian(x0, y0, w, h, density=0.22):
    """Faded cyan with cream and orange hibiscus specks."""
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            put(x, y, jitter(SHIRT, 6))
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            r = rng.random()
            if r < density * 0.55:
                put(x, y, jitter(FLOWER, 6))
            elif r < density * 0.8:
                put(x, y, jitter(FLOWER2, 6))
            elif r < density:
                put(x, y, LEAF)


def denim(x0, y0, w, h, outer_seam=None):
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            put(x, y, jitter(DENIM, 5))
    if outer_seam is not None:
        for y in range(y0, y0 + h):
            put(outer_seam, y, DENIM_DK)


def skin(x0, y0, w, h):
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            put(x, y, jitter(SKIN, 5))


def hair(x0, y0, w, h):
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            r = rng.random()
            put(x, y, HAIR_DK if r < 0.25 else HAIR_LT if r < 0.45 else HAIR_WH if r < 0.52 else HAIR)


def chain_v(x, y0, n):
    """A short vertical run of chain links."""
    for i in range(n):
        put(x, y0 + i, LINK if i % 2 == 0 else LINK_DK)


def cuff(x0, y0, w):
    """Two-pixel iron band: bright top edge, darker lower edge, a rivet."""
    for x in range(x0, x0 + w):
        put(x, y0, IRON_HI)
        put(x, y0 + 1, IRON_SH)
    put(x0 + w // 2, y0 + 1, RIVET)


# ---------------------------------------------------------------- head (base)
hair(8, 0, 8, 8)                      # top
skin(16, 0, 8, 8)                     # bottom (chin and neck)
for x in range(16, 24):
    put(x, 0, CHOP)                    # chops along the jaw line seen from below

# front (8,8)
F = (8, 8)
def f(x, y, c): put(F[0] + x, F[1] + y, c)
skin(8, 8, 8, 8)
for x in range(8):
    f(x, 0, rng.choice([HAIR, HAIR_LT, HAIR_DK, HAIR]))
f(0, 1, HAIR); f(7, 1, HAIR)
f(2, 1, WRINKLE); f(5, 1, WRINKLE)    # forehead lines
for x in (1, 2, 5, 6):
    f(x, 2, HAIR_DK)                   # heavy brows
f(0, 2, CHOP_DK); f(7, 2, CHOP_DK)
for x in (1, 2, 5, 6):
    f(x, 3, EYE)                       # the white eyes
f(0, 3, CHOP); f(7, 3, CHOP)
f(3, 4, SKIN_SH); f(4, 4, SKIN_SH)     # nose
f(1, 4, SKIN_HI); f(6, 4, WRINKLE)
f(0, 4, CHOP); f(7, 4, CHOP)
for y in (5, 6):
    f(0, y, CHOP); f(1, y, CHOP_DK); f(6, y, CHOP_DK); f(7, y, CHOP)
f(2, 5, CHOP_DK); f(5, 5, CHOP_DK)     # moustache corners
f(3, 5, CHOP); f(4, 5, CHOP)
f(3, 6, MOUTH); f(4, 6, MOUTH); f(2, 6, WRINKLE)
f(5, 6, TOOTHPICK)                     # toothpick in the corner of the mouth
f(0, 7, CHOP_DK); f(7, 7, CHOP_DK)
for x in range(1, 7):
    f(x, 7, CHOP if rng.random() < 0.45 else SKIN_SH)  # stubble

# right side (0,8): x0 = back, x7 = front
skin(0, 8, 8, 8)
hair(0, 8, 8, 2)
hair(0, 10, 3, 4)
for y in range(10, 15):
    put(6, y, CHOP); put(7, y, CHOP_DK if y > 12 else CHOP)
put(4, 11, SKIN_SH); put(4, 12, SKIN_SH); put(3, 12, WRINKLE)   # ear
# left side (16,8): x0 = front, x7 = back
skin(16, 8, 8, 8)
hair(16, 8, 8, 2)
hair(21, 10, 3, 4)
for y in range(10, 15):
    put(17, y, CHOP); put(16, y, CHOP_DK if y > 12 else CHOP)
put(19, 11, SKIN_SH); put(19, 12, SKIN_SH); put(20, 12, WRINKLE)
# back (24,8)
hair(24, 8, 8, 6)
skin(24, 14, 8, 2)
for x in range(24, 32):
    if rng.random() < 0.5:
        put(x, 14, HAIR_DK)

# ---------------------------------------------------------------- head overlay: aviators on the forehead
for x, c in ((41, LENS_HI), (42, LENS), (43, GOLD), (44, GOLD), (45, LENS_HI), (46, LENS)):
    put(x, 9, c)
put(40, 9, GOLD); put(47, 9, GOLD)
for x in range(36, 40):
    put(x, 9, GOLD)                    # temple arm, right side
for x in range(48, 52):
    put(x, 9, GOLD)                    # temple arm, left side

# ---------------------------------------------------------------- body (base)
hawaiian(20, 16, 8, 4, 0.18)          # top (shoulders)
denim(28, 16, 8, 4)                    # bottom
# front (20,20): shirt hangs open over the tank top
hawaiian(20, 20, 8, 9, 0.14)
for y in range(20, 29):
    put(23, y, jitter(TANK, 4)); put(24, y, jitter(TANK, 4))
    put(22, y, SHIRT_DK); put(25, y, SHIRT_DK)   # inner edge of the open shirt
put(21, 20, SHIRT_LT); put(22, 20, SHIRT_LT); put(25, 20, SHIRT_LT); put(26, 20, SHIRT_LT)  # collar
put(23, 21, HAIR); put(24, 22, HAIR_DK)          # grey chest hair at the neckline
put(23, 27, TANK_DK); put(24, 28, TANK_DK)
fill(20, 29, 8, 1, BELT)
put(23, 29, BUCKLE); put(24, 29, BUCKLE)
denim(20, 30, 8, 2)
put(23, 30, BUCKLE_DK); put(24, 30, BUCKLE_DK)   # the buckle is big
# sides
hawaiian(16, 20, 4, 9); fill(16, 29, 4, 1, BELT); denim(16, 30, 4, 2)
hawaiian(28, 20, 4, 9); fill(28, 29, 4, 1, BELT); denim(28, 30, 4, 2)
# back (32,20)
hawaiian(32, 20, 8, 9, 0.14); fill(32, 29, 8, 1, BELT); denim(32, 30, 8, 2)

# ---------------------------------------------------------------- body overlay: chain and padlock
def chain_diag(x0, y0, step, n):
    """A heavy two-pixel chain running diagonally: bright link faces with a dark edge."""
    for i in range(n):
        x, y = x0 + step * i, y0 + i
        put(x, y, IRON_HI if i % 2 == 0 else LINK)
        nx = x + step
        if min(x0, x0 + step * (n - 1)) <= nx <= max(x0, x0 + step * (n - 1)):
            put(nx, y, LINK_DK)  # the dark edge stays within the chain's own run


chain_diag(20, 36, 1, 8)               # front: from his right shoulder down to his left hip
put(24, 39, IRON); put(25, 39, IRON)   # padlock shackle hanging off the chain
fill(24, 40, 2, 2, BRASS)
put(25, 41, BRASS_DK)                  # keyhole
chain_diag(39, 36, -1, 8)              # back: the same chain coming over the shoulder
put(20, 32, IRON_HI); put(21, 33, LINK); put(20, 33, LINK_DK)  # over the right shoulder (top face)

# ---------------------------------------------------------------- arms (wide)
def arm(top, bottom, faces, cuff_faces, ink=False):
    """faces: (x, y) of the four 4x12 faces; short sleeve, bare forearm, hand."""
    hawaiian(top[0], top[1], 4, 4, 0.2)
    skin(bottom[0], bottom[1], 4, 4)
    for (x0, y0) in faces:
        hawaiian(x0, y0, 4, 3, 0.25)
        put(x0, y0 + 3, SHIRT_DK); put(x0 + 1, y0 + 3, SHIRT_DK); put(x0 + 2, y0 + 3, SHIRT_DK); put(x0 + 3, y0 + 3, SHIRT_DK)
        skin(x0, y0 + 4, 4, 8)
        put(x0 + rng.randint(0, 3), y0 + 6, SKIN_SH)
    if ink:
        x0, y0 = faces[1]
        put(x0 + 1, y0 + 5, INK); put(x0 + 2, y0 + 5, INK); put(x0 + 1, y0 + 6, INK)   # old faded tattoo


def arm_overlay(faces, dangle_face):
    for (x0, y0) in faces:
        cuff(x0, y0 + 8, 4)
    x0, y0 = dangle_face
    chain_v(x0 + 1, y0 + 10, 2)        # broken chain hanging off the cuff


# right arm: outer (40,20) front (44,20) inner (48,20) back (52,20); top (44,16) bottom (48,16)
arm((44, 16), (48, 16), [(40, 20), (44, 20), (48, 20), (52, 20)], None, ink=True)
arm_overlay([(40, 36), (44, 36), (48, 36), (52, 36)], (40, 36))
# left arm: inner (32,52) front (36,52) outer (40,52) back (44,52); top (36,48) bottom (40,48)
arm((36, 48), (40, 48), [(32, 52), (36, 52), (40, 52), (44, 52)], None)
arm_overlay([(48, 52), (52, 52), (56, 52), (60, 52)], (56, 52))

# ---------------------------------------------------------------- legs
def leg(top, bottom, faces, outer_index):
    denim(top[0], top[1], 4, 4)
    fill(bottom[0], bottom[1], 4, 4, SOLE)
    for i, (x0, y0) in enumerate(faces):
        denim(x0, y0, 4, 7, outer_seam=(x0 if i == outer_index and outer_index == 0 else x0 + 3 if i == outer_index else None))
        fill(x0, y0 + 7, 4, 3, BOOT)
        fill(x0, y0 + 10, 4, 1, BOOT_DK)
        fill(x0, y0 + 11, 4, 1, SOLE)
        put(x0 + rng.randint(0, 3), y0 + 8, BOOT_DK)


def leg_overlay(faces, dangle_face):
    for (x0, y0) in faces:
        cuff(x0, y0 + 6, 4)
    x0, y0 = dangle_face
    chain_v(x0 + 2, y0 + 8, 2)


# right leg: outer (0,20) front (4,20) inner (8,20) back (12,20); top (4,16) bottom (8,16)
leg((4, 16), (8, 16), [(0, 20), (4, 20), (8, 20), (12, 20)], 0)
put(5, 23, RIP); put(6, 23, DENIM_LT); put(5, 24, DENIM_LT)        # worn-through knee
leg_overlay([(0, 36), (4, 36), (8, 36), (12, 36)], (4, 36))
# left leg: inner (16,52) front (20,52) outer (24,52) back (28,52); top (20,48) bottom (24,48)
leg((20, 48), (24, 48), [(16, 52), (20, 52), (24, 52), (28, 52)], 2)
put(22, 55, DENIM_LT); put(21, 56, DENIM_LT)
leg_overlay([(0, 52), (4, 52), (8, 52), (12, 52)], (4, 52))


# ---------------------------------------------------------------- output
out = sys.argv[1] if len(sys.argv) > 1 else "herobrine.png"
img.save(out)
print("wrote", out)

if len(sys.argv) > 2:
    S = 12

    def part(base, over, x, y, w, h):
        p = img.crop((base[0], base[1], base[0] + w, base[1] + h))
        if over:
            o = img.crop((over[0], over[1], over[0] + w, over[1] + h))
            p.alpha_composite(o)
        return p

    def view(front):
        v = Image.new("RGBA", (16, 32), (0, 0, 0, 0))
        if front:
            v.alpha_composite(part((8, 8), (40, 8), 0, 0, 8, 8), (4, 0))
            v.alpha_composite(part((20, 20), (20, 36), 0, 0, 8, 12), (4, 8))
            v.alpha_composite(part((44, 20), (44, 36), 0, 0, 4, 12), (0, 8))    # his right arm, on our left
            v.alpha_composite(part((36, 52), (52, 52), 0, 0, 4, 12), (12, 8))
            v.alpha_composite(part((4, 20), (4, 36), 0, 0, 4, 12), (4, 20))
            v.alpha_composite(part((20, 52), (4, 52), 0, 0, 4, 12), (8, 20))
        else:
            v.alpha_composite(part((24, 8), (56, 8), 0, 0, 8, 8), (4, 0))
            v.alpha_composite(part((32, 20), (32, 36), 0, 0, 8, 12), (4, 8))
            v.alpha_composite(part((44, 52), (60, 52), 0, 0, 4, 12), (0, 8))    # his left arm, on our left
            v.alpha_composite(part((52, 20), (52, 36), 0, 0, 4, 12), (12, 8))
            v.alpha_composite(part((28, 52), (12, 52), 0, 0, 4, 12), (4, 20))
            v.alpha_composite(part((12, 20), (12, 36), 0, 0, 4, 12), (8, 20))
        return v.resize((16 * S, 32 * S), Image.NEAREST)

    sheet = Image.new("RGBA", (16 * S * 2 + 3 * S + 64 * 6, 32 * S), (40, 40, 46, 255))
    sheet.alpha_composite(view(True), (0, 0))
    sheet.alpha_composite(view(False), (16 * S + 2 * S, 0))
    sheet.alpha_composite(img.resize((64 * 6, 64 * 6), Image.NEAREST), (16 * S * 2 + 3 * S, 0))
    sheet.save(sys.argv[2])
    print("wrote", sys.argv[2])
