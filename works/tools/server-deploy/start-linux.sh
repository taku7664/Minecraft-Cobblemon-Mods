#!/usr/bin/env bash
set -euo pipefail
release=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
data=${SERVER_DATA_DIR:?Set SERVER_DATA_DIR to an existing persistent data directory}
data=$(cd -- "$data" && pwd)
[[ "$data" != "$release" && "$data" != "$release/"* ]] || { echo 'Data must be outside the release directory.' >&2; exit 1; }
java=${JAVA_HOME:+$JAVA_HOME/bin/}java
version=$("$java" -version 2>&1)
[[ "$version" =~ version\ \"([0-9]+) ]] && (( BASH_REMATCH[1] >= 21 )) || { echo 'Java 21 or newer is required.' >&2; exit 1; }
[[ -f "$data/eula.txt" ]] && grep -Eq '^eula=true\s*$' "$data/eula.txt" || { echo 'Accept the Minecraft EULA in the data directory first.' >&2; exit 1; }
[[ -f "$data/server.properties" ]] || { echo 'Create data/server.properties from the supplied template first.' >&2; exit 1; }
grep -qx 'level-name=world' "$data/server.properties" || { echo 'Persistent properties must use level-name=world.' >&2; exit 1; }
# Persistent world is only linked; never copied or overwritten by releases.
[[ -d "$data/world" ]] || { echo 'Create data/world (or restore a snapshot) first.' >&2; exit 1; }
for name in world server.properties eula.txt; do
  if [[ -L "$release/$name" && "$(readlink -- "$release/$name")" == "$data/$name" ]]; then continue; fi
  [[ ! -e "$release/$name" && ! -L "$release/$name" ]] || { echo "Release already has $name pointing elsewhere; use a fresh release directory." >&2; exit 1; }
done
for name in world server.properties eula.txt; do
  [[ -L "$release/$name" ]] || ln -s -- "$data/$name" "$release/$name"
done
cd -- "$release"
exec "$java" "-Xms${JAVA_XMS:-2G}" "-Xmx${JAVA_XMX:-6G}" -jar fabric-server-mc.1.21.1-loader.0.19.5-launcher.1.1.2.jar nogui "$@"
