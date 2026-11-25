#!/usr/bin/env bash
# Ghost AI: makes the generated R class land in package com.ghost.ai.
# Run from the repo root (the folder that contains app/):   bash fix_namespace.sh
set -e

NS="com.ghost.ai"
F=""
for c in app/build.gradle.kts app/build.gradle; do
  if [ -f "$c" ]; then F="$c"; break; fi
done
if [ -z "$F" ]; then
  echo "ERROR: app/build.gradle(.kts) not found. Run this from the repo root."
  exit 1
fi

echo "File: $F"
echo "--- BEFORE ---"
grep -nE "namespace|applicationId" "$F" || echo "(no namespace/applicationId line)"

if grep -qE "^[[:space:]]*namespace" "$F"; then
  # replace the value of the existing namespace line
  sed -i -E "s/^([[:space:]]*namespace[[:space:]]*=?[[:space:]]*)[\"'][^\"']*[\"']/\1\"$NS\"/" "$F"
else
  # no namespace yet: add it right after the first 'android {'
  sed -i -E "0,/^[[:space:]]*android[[:space:]]*\{/s//android {\n    namespace = \"$NS\"/" "$F"
fi

if grep -qE "^[[:space:]]*applicationId" "$F"; then
  sed -i -E "s/^([[:space:]]*applicationId[[:space:]]*=?[[:space:]]*)[\"'][^\"']*[\"']/\1\"$NS\"/" "$F"
fi

echo "--- AFTER ---"
grep -nE "namespace|applicationId" "$F"

# The manifest must not carry its own package= attribute with a different value.
M="app/src/main/AndroidManifest.xml"
if [ -f "$M" ] && grep -qE "<manifest[^>]*package=" "$M"; then
  echo "NOTE: $M has a package= attribute. Removing it (namespace in gradle is the source of truth)."
  sed -i -E '0,/<manifest/s/[[:space:]]+package="[^"]*"//' "$M"
fi

echo "Done. Now: git add -A && git commit -m 'fix namespace' && git push"
