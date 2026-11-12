"""Tangram icon (design-package appIcon, icon-colors.md: #8E44AD 七巧紫, unique in the line): a purple disc, a light
maple tangram square cut by white seams, and the top-right parallelogram lifted half off, showing its cherry back.
No text.

  python -I tools/build_icon.py [--icon512 out.png] [--preview out.png]

Writes res/drawable/icon_3d_layer_{0,1,2}.png + icon_3d_sdf_{0,1,2}.png (PICO layered icon), mipmap-xxxhdpi/
ic_spatial_launcher.png and the adaptive foreground vector res/drawable/ic_launcher_fg.xml (same polygons).
"""
import math
import os
import sys
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S, SS = 1024, 4
PURPLE = (0x8E, 0x44, 0xAD)
MAPLE = (0xE8, 0xC9, 0x9A)
CHERRY = (0xD9, 0x8A, 0x5F)
SHADE = (0x5E, 0x2C, 0x73)

# the classic square on a 4 x 4 grid (y down), 13 px per unit, square from (28, 30)
UNIT, OX, OY = 13.0, 28.0, 30.0
GRID = {
    "L1": [(0, 0), (4, 0), (2, 2)], "L2": [(0, 0), (2, 2), (0, 4)], "M": [(4, 2), (4, 4), (2, 4)],
    "S1": [(2, 2), (3, 1), (3, 3)], "S2": [(0, 4), (1, 3), (2, 4)], "SQ": [(2, 2), (3, 3), (2, 4), (1, 3)],
    "P": [(3, 1), (4, 0), (4, 2), (3, 3)],
}


def shrink(pts, d=0.9):
    cx = sum(p[0] for p in pts) / len(pts); cy = sum(p[1] for p in pts) / len(pts)
    out = []
    for x, y in pts:
        vx, vy = x - cx, y - cy
        l = math.hypot(vx, vy)
        out.append((x - vx / l * d, y - vy / l * d))
    return out


def to_px(pts):
    return [(OX + x * UNIT, OY + y * UNIT) for x, y in pts]


PIECES = [shrink(to_px(GRID[k])) for k in ("L1", "L2", "M", "S1", "S2", "SQ")]
# the parallelogram lifted: turned 24° about its left edge and moved up-right, cherry back up, a soft shadow left behind
_p = to_px(GRID["P"])
_pivot = ((_p[0][0] + _p[3][0]) / 2, (_p[0][1] + _p[3][1]) / 2)
_a = math.radians(-24)
LIFTED = shrink([(_pivot[0] + (x - _pivot[0]) * math.cos(_a) - (y - _pivot[1]) * math.sin(_a) + 5, _pivot[1] + (x - _pivot[0]) * math.sin(_a) + (y - _pivot[1]) * math.cos(_a) - 6) for x, y in _p])
SHADOW = shrink(_p)
# white seams: a white square under the pieces (the parallelogram's spot stays shaded)
BACK = [(OX + 0.3, OY + 0.3), (OX + 4 * UNIT - 0.3, OY + 0.3), (OX + 4 * UNIT - 0.3, OY + 4 * UNIT - 0.3), (OX + 0.3, OY + 4 * UNIT - 0.3)]


def big(draw_fn):
    im = Image.new("RGBA", (S * SS, S * SS), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(im), S * SS / 108.0)
    return im.resize((S, S), Image.LANCZOS)


def sc(pts, k):
    return [(x * k, y * k) for x, y in pts]


def layer_bg():
    return big(lambda dr, k: dr.ellipse((0, 0, 108 * k - 1, 108 * k - 1), fill=PURPLE + (255,)))


def layer_box():
    def d(dr, k):
        dr.polygon(sc(BACK, k), fill=(255, 255, 255, 255))
        dr.polygon(sc(SHADOW, k), fill=SHADE + (255,))
        for w in PIECES:
            dr.polygon(sc(w, k), fill=MAPLE + (255,))
    return big(d)


def layer_tile():
    def d(dr, k):
        dr.polygon(sc(LIFTED, k), fill=CHERRY + (255,))
    return big(d)


def sdf(layer):
    inside = layer.getchannel("A").point(lambda v: 255 if v >= 128 else 0)
    dist = Image.new("L", inside.size, 0)
    cur = inside
    for i in range(1, 33):
        dist.paste(min(255, i * 8), mask=cur)
        cur = cur.filter(ImageFilter.MinFilter(3) if i % 2 else ImageFilter.Kernel((3, 3), (0, 1, 0, 1, 1, 1, 0, 1, 0), 5))
        cur = cur.point(lambda v: 255 if v >= 255 else 0)
    dist = Image.composite(dist, Image.new("L", inside.size, 0), inside)
    return Image.merge("RGBA", (dist, dist, dist, dist))


def path(pts):
    return "M" + "L".join("%.2f,%.2f" % p for p in pts) + "Z"


def vector():
    hexc = lambda c: "#FF%02X%02X%02X" % c
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!-- Tangram launcher foreground (tools/build_icon.py draws the same artwork for the PICO layered icon). -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">',
        '    <path android:fillColor="#FFFFFFFF" android:pathData="%s" />' % path(BACK),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(SHADE), path(SHADOW)),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(MAPLE), "".join(path(w) for w in PIECES)),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(CHERRY), path(LIFTED)),
        '</vector>',
    ]
    open(os.path.join(RES, "drawable", "ic_launcher_fg.xml"), "w").write("\n".join(lines) + "\n")


def main():
    layers = [layer_bg(), layer_box(), layer_tile()]
    for i, layer in enumerate(layers):
        layer.save(os.path.join(RES, "drawable", f"icon_3d_layer_{i}.png"))
        sdf(layer).save(os.path.join(RES, "drawable", f"icon_3d_sdf_{i}.png"))
    flat = Image.new("RGBA", layers[0].size, (0, 0, 0, 0))
    for l in layers:
        flat.alpha_composite(l)
    flat.save(os.path.join(RES, "mipmap-xxxhdpi", "ic_spatial_launcher.png"))
    vector()
    if "--icon512" in sys.argv:
        flat.resize((512, 512), Image.LANCZOS).save(sys.argv[sys.argv.index("--icon512") + 1])
    if "--preview" in sys.argv:
        w = 256
        sheet = Image.new("RGB", (w * 4, w), (60, 64, 70))
        for i, t in enumerate(layers + [flat]):
            r = t.resize((w, w), Image.LANCZOS)
            sheet.paste(r, (i * w, 0), r)
        sheet.save(sys.argv[sys.argv.index("--preview") + 1])
    print("ICON ok")


main()
