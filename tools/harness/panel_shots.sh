#!/usr/bin/env bash
# tools/harness/panel_shots.sh OUTDIR LANG : every control-window page / state, console shot cropped to the window.
# Camera must already face the window (CROP=x0,y0,x1,y1 in console pixels). Items: "goto:S02", "cmd:name:arg", "shot:name".
set -u
OUT="$1"; L="$2"; shift 2
H="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"; export SHOTS="$OUT"
tw() { bash "$H/../tw.sh" cmd "$@"; }
snap() { bash "$H/emu.sh" fast on >/dev/null; sleep "${WAIT:-5}"; bash "$H/emu.sh" shot "$L-$1" >/dev/null && python -c "
from PIL import Image;im=Image.open(r'$OUT/$L-$1.png').convert('RGB');im.crop(tuple(int(v) for v in '${CROP}'.split(','))).save(r'$OUT/$L-$1-panel.png')" && echo "shot $L-$1"; }
tw lang "$L"; sleep 2
for it in "$@"; do
  IFS=: read -r k a b <<<"$it"
  case "$k" in
    goto) tw goto "$a"; snap "$a" ;;
    cmd) tw "$a" "${b:-}"; sleep 1 ;;
    shot) snap "$a" ;;
  esac
done
