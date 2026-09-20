param(
    [string]$Version = "0.1.0"
)

$ErrorActionPreference = "Stop"
$packRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$shaderRoot = Join-Path $packRoot "shaders"
$distRoot = Join-Path $packRoot "dist"
$outputPath = Join-Path $distRoot "LumaVale-$Version.zip"

if (-not (Test-Path -LiteralPath (Join-Path $shaderRoot "shaders.properties"))) {
    throw "Missing shaders/shaders.properties"
}

New-Item -ItemType Directory -Force -Path $distRoot | Out-Null
if (Test-Path -LiteralPath $outputPath) {
    [System.IO.File]::Delete($outputPath)
}

Compress-Archive -LiteralPath $shaderRoot -DestinationPath $outputPath -CompressionLevel Optimal
Get-Item -LiteralPath $outputPath
