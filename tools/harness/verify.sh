#!/usr/bin/env bash
# Type-checks the whole Kotlin source set against a real android.jar plus stubs.
#
# This catches the class of bug that otherwise only shows up on-device: wrong types,
# renamed members, bad overload resolution. It does NOT run the app.
#
# Needs:
#   KOTLINC   path to a kotlinc bin dir   (https://github.com/JetBrains/kotlin/releases)
#   ANDROID_JAR  path to an android.jar   (API 33 or later)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$HERE/../.."

KOTLINC_BIN="${KOTLINC:-$HOME/kotlinc/bin}"
JAR="${ANDROID_JAR:-$HOME/android.jar}"

[ -x "$KOTLINC_BIN/kotlinc" ] || { echo "kotlinc not found at $KOTLINC_BIN (set KOTLINC)"; exit 1; }
[ -f "$JAR" ] || { echo "android.jar not found at $JAR (set ANDROID_JAR)"; exit 1; }

# R must track the current resources, or the check passes on a stale R.
python3 "$HERE/gen_r.py"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

"$KOTLINC_BIN/kotlinc" -nowarn -cp "$JAR" -d "$OUT" \
    "$HERE"/stubs/*.kt \
    "$ROOT"/app/src/main/java/com/fractal/deepzoom/*.kt

echo "OK: source set type-checks clean"
