#!/usr/bin/env bash
# tools/harness/shots.sh OUTDIR zh|en STATE... : debug goto each state, wait, console screenshot OUTDIR/<lang>-<state>.png
# needs DEV / AVD exported (emu.sh conventions). Each item may be "S03" (goto) or "cmd:<name>:<arg>" (any debug cmd).
set -u
OUT="$1"; LANG_="$2"; shift 2
H="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"
export SHOTS="$OUT"
bash "$H/../tw.sh" cmd lang "$LANG_"; sleep 2
for s in "$@"; do
  case "$s" in
    cmd:*) IFS=: read -r _ c a <<<"$s"; bash "$H/../tw.sh" cmd "$c" "$a"; sleep 2; continue ;;
  esac
  bash "$H/../tw.sh" cmd goto "$s"
  bash "$H/emu.sh" fast on >/dev/null
  sleep "${WAIT:-6}"
  bash "$H/emu.sh" shot "$LANG_-$s" >/dev/null && echo "shot $LANG_-$s"
done
