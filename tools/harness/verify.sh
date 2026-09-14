#!/usr/bin/env bash
# Type-checks the whole Kotlin source set against a real android.jar plus stubs.
#
# This catches the class of bug that otherwise only shows up on-device: wrong types,
# renamed members, bad overload resolution. It does NOT run the app.
#
# Needs:
#   KOTLINC      path to a kotlinc bin dir  (must match KOTLIN_VERSION below)
#   ANDROID_JAR  path to an android.jar     (API 33 or later)
set -euo pipefail

# Must match org.jetbrains.kotlin.android in build.gradle.kts.
#
# This is not a detail. 1.9.x is the K1 compiler and 2.0+ is K2, and K2 accepts code
# K1 rejects — smart casts after reassigning a nullable var, among others. Checking
# with the wrong compiler produces a clean local run and a red CI build, which is
# worse than not checking at all.
KOTLIN_VERSION="1.9.24"

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$HERE/../.."

KOTLINC_BIN="${KOTLINC:-$HOME/kotlinc/bin}"
JAR="${ANDROID_JAR:-$HOME/android.jar}"

[ -x "$KOTLINC_BIN/kotlinc" ] || { echo "kotlinc not found at $KOTLINC_BIN (set KOTLINC)"; exit 1; }

FOUND="$("$KOTLINC_BIN/kotlinc" -version 2>&1 | grep -o '[0-9]\+\.[0-9]\+\.[0-9]\+' | head -1)"
GRADLE_VERSION="$(grep -o 'kotlin.android") version "[^"]*' "$ROOT/build.gradle.kts" | grep -o '[0-9.]*$' || echo "$KOTLIN_VERSION")"
if [ "$FOUND" != "$GRADLE_VERSION" ]; then
    echo "Compiler version mismatch: kotlinc is $FOUND, build.gradle.kts uses $GRADLE_VERSION."
    echo "Checking with the wrong compiler gives false results. Install $GRADLE_VERSION:"
    echo "  https://github.com/JetBrains/kotlin/releases/tag/v$GRADLE_VERSION"
    exit 1
fi
[ -f "$JAR" ] || { echo "android.jar not found at $JAR (set ANDROID_JAR)"; exit 1; }

# R must track the current resources, or the check passes on a stale R.
python3 "$HERE/gen_r.py"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

"$KOTLINC_BIN/kotlinc" -nowarn -cp "$JAR" -d "$OUT" \
    "$HERE"/stubs/*.kt \
    "$ROOT"/app/src/main/java/com/fractal/deepzoom/*.kt

echo "OK: source set type-checks clean"
