#!/usr/bin/env bash
# Needs kotlinc 1.9.24 (KOTLINC, default $HOME/kotlinc/bin), a JDK, gcc, and EGL/GLES
# with a GLES 3.1 driver:  apt install libegl-dev libgles-dev libegl-mesa0 libgl1-mesa-dri
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../../app/src/main/java/com/fractal/deepzoom"
KOTLINC_BIN="${KOTLINC:-$HOME/kotlinc/bin}"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
"$KOTLINC_BIN/kotlinc" -nowarn -d "$OUT/d.jar" "$SRC/Shaders.kt" "$SRC/Palette.kt" "$HERE/DumpShaders.kt"
gcc -O1 -w "$HERE/present_test.c" -o "$OUT/t" -lEGL -lGLESv2 -lm
cd "$OUT"
java -cp "$OUT/d.jar:$KOTLINC_BIN/../lib/kotlin-stdlib.jar" DumpShadersKt
EGL_PLATFORM=surfaceless ./t
