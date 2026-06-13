#!/usr/bin/env bash
# Syncs the engine-parity behavior contract from the web repo (the canonical
# source) into this repo's test resources. Run after coordinated fixture
# changes; ParityFixtureManifestTest fails when the local copy drifts.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="${FJ_WEB_REPO:-$ROOT/../forever-jukebox}/test-fixtures/engine-parity"
DEST="$ROOT/app/src/test/resources/engine-parity"

if [[ ! -f "$SRC/manifest.json" ]]; then
  echo "Error: $SRC/manifest.json not found." >&2
  echo "Checkout forever-jukebox next to this repo or set FJ_WEB_REPO." >&2
  exit 1
fi

mkdir -p "$DEST"
rsync -a --delete \
  --include='*-cases.json' --include='manifest.json' --exclude='*' \
  "$SRC/" "$DEST/"

echo "Synced $(ls "$DEST" | wc -l | tr -d ' ') files from $SRC"
echo "Verify: ./gradlew :app:testDebugUnitTest --tests '*ParityFixtureManifestTest'"
