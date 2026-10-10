#!/usr/bin/env bash
set -euo pipefail
launcher=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/run.sh
scratch=$(mktemp -d)
trap 'rm -rf -- "$scratch"' EXIT
root="$scratch/server with spaces"
mkdir -p "$root/tools" "$scratch/bin" "$scratch/java home/bin"
cp -- "$launcher" "$root/run.sh"
touch "$root/tools/server-startup-hooks.ps1" "$root/fabric-server-mc.1.21.1-loader.0.19.5-launcher.1.1.2.jar"
export TEST_LOG="$scratch/log" TEST_JAVA_VERSION=21 TEST_HOOK_EXIT=0 TEST_SERVER_EXIT=0
cat > "$scratch/bin/java" <<'EOF'
#!/usr/bin/env bash
if [[ ${1-} == -version ]]; then echo "openjdk version \"$TEST_JAVA_VERSION.0.1\"" >&2; exit 0; fi
printf 'java:%s\n' "$PWD" >> "$TEST_LOG"
printf 'arg:%s\n' "$@" >> "$TEST_LOG"
exit "$TEST_SERVER_EXIT"
EOF
cat > "$scratch/bin/pwsh" <<'EOF'
#!/usr/bin/env bash
if [[ ${1-} == -Version ]]; then echo 'PowerShell 7.4.0'; exit 0; fi
printf 'hook:%s\n' "$PWD" >> "$TEST_LOG"
printf 'hookarg:%s\n' "$@" >> "$TEST_LOG"
exit "$TEST_HOOK_EXIT"
EOF
chmod +x "$scratch/bin/java" "$scratch/bin/pwsh"
cp "$scratch/bin/java" "$scratch/java home/bin/java"
export PATH="$scratch/bin:/usr/bin:/bin"
unset JAVA_HOME
run_case() {
    local expected=$1 actual=0
    : > "$TEST_LOG"
    bash "$root/run.sh" 'argument with spaces' > "$scratch/output" 2>&1 || actual=$?
    if [[ $actual != "$expected" ]]; then cat "$scratch/output"; echo "FAIL: expected $expected, got $actual"; exit 1; fi
}
run_case 0
[[ $(head -n1 "$TEST_LOG") == "hook:$root" ]]
grep -Fxq 'arg:argument with spaces' "$TEST_LOG"
grep -Fxq 'arg:-Xms2G' "$TEST_LOG"
grep -Fxq 'arg:-Xmx4G' "$TEST_LOG"
echo 'PASS: hooks precede Java; working directory and arguments preserve spaces'
export JAVA_HOME="$scratch/java home" JAVA_XMS=3G JAVA_XMX=8G
run_case 0
grep -Fxq 'arg:-Xms3G' "$TEST_LOG"
grep -Fxq 'arg:-Xmx8G' "$TEST_LOG"
echo 'PASS: JAVA_HOME and memory overrides'
export TEST_HOOK_EXIT=7
run_case 7
! grep -q '^java:' "$TEST_LOG"
echo 'PASS: failed hook prevents server startup'
export TEST_HOOK_EXIT=0 TEST_SERVER_EXIT=42
run_case 42
echo 'PASS: server exit code propagated without automatic restart'
export TEST_JAVA_VERSION=17 TEST_SERVER_EXIT=0
run_case 1
[[ ! -s "$TEST_LOG" ]]
echo 'PASS: unsupported Java rejected before hooks'
export TEST_JAVA_VERSION=21 JAVA_HOME="$scratch/missing java"
run_case 1
echo 'PASS: invalid explicit JAVA_HOME rejected'
unset JAVA_HOME
mv "$scratch/bin/pwsh" "$scratch/bin/pwsh-off"
run_case 1
[[ ! -s "$TEST_LOG" ]]
mv "$scratch/bin/pwsh-off" "$scratch/bin/pwsh"
echo 'PASS: missing PowerShell rejected before hooks'
mv "$root/tools/server-startup-hooks.ps1" "$root/tools/hooks-off"
run_case 1
[[ ! -s "$TEST_LOG" ]]
echo 'PASS: missing runtime file rejected before hooks'
