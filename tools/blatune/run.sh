#!/usr/bin/env bash
# BLA tolerance sweep; see TuneBla.kt. Needs kotlinc 1.9.24 (KOTLINC, default
# $HOME/kotlinc/bin) and a JDK. Optional argument: grid size per view (default 24).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../../app/src/main/java/com/fractal/deepzoom"
KOTLINC_BIN="${KOTLINC:-$HOME/kotlinc/bin}"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
"$KOTLINC_BIN/kotlinc" -nowarn -d "$OUT/t.jar" \
    "$SRC/FixedPoint.kt" "$SRC/ReferenceOrbit.kt" "$SRC/BlaTable.kt" \
    "$SRC/OrbitGpuData.kt" "$SRC/ViewState.kt" "$HERE/TuneBla.kt" 2>&1 | grep -v JAVA_TOOL || true
java -Xss64m -cp "$OUT/t.jar:$KOTLINC_BIN/../lib/kotlin-stdlib.jar" TuneBlaKt "$@" 2>&1 | grep -v JAVA_TOOL
