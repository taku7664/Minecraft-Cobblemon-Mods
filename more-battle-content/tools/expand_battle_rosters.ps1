param(
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
)

$ErrorActionPreference = 'Stop'

# Compatibility entry point retained for existing developer workflows. Tower generation is no
# longer coupled to Factory data; this command refreshes only the complete schema 4 rental presets.
$catalogPath = Join-Path $ProjectRoot 'src\main\resources\data\cobblemon_more_battle_content\battle_factory\catalog\mbc_core.json'
& node (Join-Path $PSScriptRoot 'generate_factory_catalog.mjs') $catalogPath
if ($LASTEXITCODE -ne 0) {
    throw "Battle Factory catalog generation failed with exit code $LASTEXITCODE"
}
