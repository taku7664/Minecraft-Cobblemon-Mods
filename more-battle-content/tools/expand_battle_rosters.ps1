param(
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
)

$ErrorActionPreference = 'Stop'

# Compatibility entry point retained for existing developer workflows. This refreshes only the
# independent Battle Factory rental-set fragments and never reads or rewrites Battle Tower data.
$factoryRoot = Join-Path $ProjectRoot 'src\main\resources\data\cobblemon_more_battle_content\mbc-battle-factory'
& node (Join-Path $PSScriptRoot 'generate_factory_catalog.mjs') $factoryRoot
if ($LASTEXITCODE -ne 0) {
    throw "Battle Factory catalog generation failed with exit code $LASTEXITCODE"
}
