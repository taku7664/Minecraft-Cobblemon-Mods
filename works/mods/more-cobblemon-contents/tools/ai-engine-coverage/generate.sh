#!/usr/bin/env bash
# Regenerates docs/betterai/engine/AI_엔진_구현_현황.xlsx from the dev server's Showdown data,
# the Cobblemon and Mega Showdown Korean language files, and the bundled usage data.
# Usage (repo root): bash more-cobblemon-contents/tools/ai-engine-coverage/generate.sh [repo root]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
MODULE="$(cd "$HERE/../.." && pwd)"
REPO="${1:-$(cd "$MODULE/.." && pwd)}"
MODS="$REPO/dev-server/mods"
LANG_DIR="$(mktemp -d)"
trap 'rm -rf "$LANG_DIR"' EXIT
unzip -q -o "$(ls "$MODS"/Cobblemon-fabric-*.jar | head -1)" assets/cobblemon/lang/ko_kr.json -d "$LANG_DIR/c"
unzip -q -o "$(ls "$MODS"/mega_showdown-*.jar | head -1)" assets/cobblemon/lang/ko_kr.json assets/mega_showdown/lang/ko_kr.json -d "$LANG_DIR/m"
[ -d "$HERE/node_modules/exceljs" ] || (cd "$HERE" && npm install --silent)
USAGE="$MODULE/src/main/resources/data/more_cobblemon_contents"
node "$HERE/generate.cjs" \
  --showdown "$REPO/dev-server/showdown" \
  --lang "$LANG_DIR/c/assets/cobblemon/lang/ko_kr.json" \
  --lang "$LANG_DIR/m/assets/cobblemon/lang/ko_kr.json" \
  --lang "$LANG_DIR/m/assets/mega_showdown/lang/ko_kr.json" \
  --usage "$USAGE/opponent_build_usage/gen9bssregj-2025-12-1500.json" \
  --usage "$USAGE/opponent_build_usage/gen9vgc2025regj-2025-12-1500.json" \
  --moves "$USAGE/opponent_move_usage/gen9bssregj-2025-12-1500.json" \
  --moves "$USAGE/opponent_move_usage/gen9vgc2025regj-2025-12-1500.json" \
  --source "$MODULE/src/main/kotlin/jbro/cobblemon/mcc/betterai" \
  --coverage "$MODULE/build/reports/ai-engine-coverage.json" \
  --out "$MODULE/docs/betterai/engine/AI_엔진_구현_현황.xlsx"
