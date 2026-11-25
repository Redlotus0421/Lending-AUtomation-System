#!/bin/bash
# Serialized Kotlin compile check for the Android app (several people/agents may run it at once).
# usage: android/tools/compile.sh [path-filter]   e.g.  android/tools/compile.sh feature/payments
# Prints the Kotlin errors (paths relative to app/src/main/java/com/lendflow/app/), filtered by the
# optional substring, then "ERRORS: n (all files: m)".
cd "$(dirname "$0")/.." || exit 1
LOCK=/tmp/lendflow-android-compile.lock
# stale lock (> 15 min) protection
if [ -d "$LOCK" ] && [ -n "$(find "$LOCK" -maxdepth 0 -mmin +15 2>/dev/null)" ]; then rmdir "$LOCK" 2>/dev/null; fi
while ! mkdir "$LOCK" 2>/dev/null; do sleep 2; done
trap 'rmdir "$LOCK" 2>/dev/null' EXIT
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
OUT=$(./gradlew :app:compileDebugKotlin --console=plain 2>&1)
ALL=$(echo "$OUT" | grep -E '^e: ' | sed 's|file:///Users/crisborbon/Documents/Lending-AUtomation-System/android/app/src/main/java/com/lendflow/app/||')
if [ -n "$1" ]; then MINE=$(echo "$ALL" | grep -F "$1"); else MINE="$ALL"; fi
[ -n "$MINE" ] && echo "$MINE" | head -150
N_ALL=$(echo -n "$ALL" | grep -c '^e: ')
N_MINE=$(echo -n "$MINE" | grep -c '^e: ')
if [ "$N_ALL" = "0" ] && ! echo "$OUT" | grep -q "BUILD SUCCESSFUL"; then
  echo "$OUT" | grep -E "What went wrong|error:|Exception" -A3 | head -30
fi
echo "ERRORS: $N_MINE (all files: $N_ALL)"
