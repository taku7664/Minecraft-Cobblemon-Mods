# Shared helpers: extract Mega Showdown's runtime Showdown entries and hand native paths to Node.
native_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
msd_extract() {
  local repo="$1" jar dir base="data/mega_showdown/mega_showdown/showdown"
  jar="$(ls "$repo"/dev-server/mods/mega_showdown-*.jar | head -1)"
  dir="$(mktemp -d)"
  # This unzip's `*` stops at `/`, so each directory depth needs its own pattern.
  unzip -q -o "$jar" "$base/*/*" "$base/*/*/*" -d "$dir"
  printf '%s' "$dir"
}
