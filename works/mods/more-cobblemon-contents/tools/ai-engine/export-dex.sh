#!/usr/bin/env bash
# Regenerates the AI engine's dex resource from the dev server's Showdown plus Mega Showdown's runtime entries.
# Usage (repo root): bash more-cobblemon-contents/tools/ai-engine/export-dex.sh [repo root]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
MODULE="$(cd "$HERE/../.." && pwd)"
REPO="${1:-$(cd "$MODULE/.." && pwd)}"
source "$HERE/msd-extract.sh"
MSD_DIR="$(msd_extract "$REPO")"
trap 'rm -rf "$MSD_DIR"' EXIT
node "$HERE/export-dex.cjs" \
  --showdown "$(native_path "$REPO/dev-server/showdown")" \
  --msd "$(native_path "$MSD_DIR/data/mega_showdown/mega_showdown/showdown")" \
  --out "$(native_path "$MODULE/src/main/resources/ai-engine/dex.json.gz")"
