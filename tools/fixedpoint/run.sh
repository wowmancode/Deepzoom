#!/usr/bin/env bash
# Validates FixedPoint.kt / ReferenceOrbit.kt on a desktop JVM. Needs the same kotlinc
# as tools/harness (KOTLINC, default $HOME/kotlinc/bin) and a JDK.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../../app/src/main/java/com/fractal/deepzoom"
KOTLINC_BIN="${KOTLINC:-$HOME/kotlinc/bin}"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
"$KOTLINC_BIN/kotlinc" -nowarn -d "$OUT/v.jar" \
    "$SRC/FixedPoint.kt" "$SRC/ReferenceOrbit.kt" "$HERE/ValidateFixedOrbit.kt"
java -cp "$OUT/v.jar:$KOTLINC_BIN/../lib/kotlin-stdlib.jar" ValidateFixedOrbitKt
