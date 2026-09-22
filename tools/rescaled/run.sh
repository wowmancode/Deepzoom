#!/usr/bin/env bash
# Emulates the rescaled shader in float32 against ground truth. Needs the same kotlinc
# as tools/harness (KOTLINC, default $HOME/kotlinc/bin) and a JDK. Optional argument:
# grid size per case (default 24).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../../app/src/main/java/com/fractal/deepzoom"
KOTLINC_BIN="${KOTLINC:-$HOME/kotlinc/bin}"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
"$KOTLINC_BIN/kotlinc" -nowarn -d "$OUT/e.jar" \
    "$SRC/FixedPoint.kt" "$SRC/ReferenceOrbit.kt" "$SRC/BlaTable.kt" \
    "$SRC/OrbitGpuData.kt" "$SRC/ViewState.kt" "$HERE/EmulateRescaled.kt"
java -cp "$OUT/e.jar:$KOTLINC_BIN/../lib/kotlin-stdlib.jar" EmulateRescaledKt "$@"
