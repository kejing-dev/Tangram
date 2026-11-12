"""Material Symbols (Apache-2.0) → Android vector drawables (profile D23: standard icons, never hand-drawn paths).

    python -I tools/icons.py <dir with downloaded <name>.svg files>

The SVGs come from github.com/google/material-design-icons symbols/web/<name>/materialsymbolsoutlined/<name>_24px.svg.
Each becomes app/src/main/res/drawable/ic_<name>.xml, white filled (the Icon composable tints it).
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable")
NAMES = [
    "extension", "grid_view", "menu_book", "settings", "undo", "lightbulb", "auto_fix_high", "volume_off", "volume_up",
    "restart_alt", "rotate_left", "rotate_right", "flip", "arrow_forward", "slow_motion_video", "replay", "skip_next",
    "draw", "center_focus_strong", "delete_sweep", "logout", "check_circle", "play_arrow",
]


def convert(name: str, svg_path: str) -> None:
    text = open(svg_path, encoding="utf-8").read()
    m = re.search(r'viewBox="([-\d.\s]+)"', text)
    if m:
        x0, y0, w, h = (float(v) for v in m.group(1).split())
    else:
        x0, y0, w, h = 0.0, 0.0, float(re.search(r'width="([\d.]+)"', text).group(1)), float(re.search(r'height="([\d.]+)"', text).group(1))
    paths = [f'        <path android:fillColor="#FFFFFFFF" android:pathData="{d}" />' for d in re.findall(r'<path[^>]*\bd="([^"]+)"', text)]
    xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f'<!-- Material Symbols "{name}" (outlined, 24 px), Apache License 2.0 -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" '
        f'android:viewportWidth="{w:g}" android:viewportHeight="{h:g}">\n'
        f'    <group android:translateX="{-x0:g}" android:translateY="{-y0:g}">\n' + "\n".join(paths) + "\n    </group>\n</vector>\n"
    )
    with open(os.path.join(OUT, f"ic_{name}.xml"), "w", encoding="utf-8", newline="\n") as f:
        f.write(xml)


if __name__ == "__main__":
    src = sys.argv[1]
    for n in NAMES:
        p = os.path.join(src, f"{n}.svg")
        if not os.path.exists(p):
            print("missing", n)
            continue
        convert(n, p)
    print("wrote", len(NAMES), "icons to", OUT)
