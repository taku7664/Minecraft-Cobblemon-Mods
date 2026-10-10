#!/usr/bin/env bash
set -euo pipefail

# Run from the server root, even when invoked from another directory.
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cd -- "$root"
fail() { printf '[run] %s\n' "$*" >&2; exit 1; }
launcher=fabric-server-mc.1.21.1-loader.0.19.5-launcher.1.1.2.jar
port_args=()

if [[ -n ${JAVA_HOME:-} ]]; then
    java="$JAVA_HOME/bin/java"
    [[ -x "$java" ]] || fail 'JAVA_HOME/bin/java is missing or not executable.'
else
    java=$(command -v java) || fail 'Java 21+ is required. Set JAVA_HOME or install java in PATH.'
fi
version=$("$java" -version 2>&1) || fail 'Java could not be executed.'
[[ $version =~ version[[:space:]]+\"([0-9]+) ]] || fail 'Could not determine the Java version.'
(( BASH_REMATCH[1] >= 21 )) || fail 'Java 21 or newer is required.'
command -v pwsh >/dev/null || fail 'PowerShell 7 (pwsh) is required for the existing startup hooks.'
version=$(pwsh -Version) || fail 'PowerShell could not be executed.'
[[ $version =~ PowerShell[[:space:]]+([0-9]+) ]] || fail 'Could not determine the PowerShell version.'
(( BASH_REMATCH[1] >= 7 )) || fail 'PowerShell 7 or newer is required.'
[[ -f "$launcher" ]] || fail "Fabric launcher is missing: $launcher"
[[ -f tools/server-startup-hooks.ps1 ]] || fail 'tools/server-startup-hooks.ps1 is missing.'

printf '[run] Checking server startup settings...\n'
if pwsh -NoLogo -NoProfile -NonInteractive -File "$root/tools/server-startup-hooks.ps1" -ServerRoot "$root"; then
    printf '[run] Starting server...\n'
else
    status=$?
    printf '[run] Startup hooks failed; the server was not started.\n' >&2
    exit "$status"
fi
# exec forwards console input, signals and the server exit code to the host.
exec "$java" "-Xms${JAVA_XMS:-2G}" "-Xmx${JAVA_XMX:-4G}" -jar "$launcher" "${port_args[@]}" nogui "$@"
