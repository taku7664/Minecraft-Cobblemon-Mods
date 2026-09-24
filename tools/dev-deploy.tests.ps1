$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "dev-deploy.ps1"
$shellPath = (Get-Process -Id $PID).Path
$artifact = Get-ChildItem -LiteralPath (Join-Path $repoRoot "rounding-block\build\libs") -File -Filter "rounding-block-*.jar" |
    Where-Object { $_.Name -notmatch "-(sources|dev|javadoc)\.jar$" } |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1

if ($null -eq $artifact) {
    throw "Test prerequisite missing: build rounding-block first."
}
$artifactHash = (Get-FileHash -LiteralPath $artifact.FullName -Algorithm SHA256).Hash

function New-TestModJar {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Id,
        [Parameter(Mandatory = $true)][string]$Version
    )

    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::CreateNew)
    try {
        $archive = [System.IO.Compression.ZipArchive]::new(
            $stream,
            [System.IO.Compression.ZipArchiveMode]::Create,
            $false
        )
        try {
            $entry = $archive.CreateEntry("fabric.mod.json")
            $writer = [System.IO.StreamWriter]::new($entry.Open(), [System.Text.UTF8Encoding]::new($false))
            try {
                $writer.Write((@{
                    schemaVersion = 1
                    id = $Id
                    version = $Version
                    name = "Test mod"
                    environment = "client"
                } | ConvertTo-Json))
            }
            finally {
                $writer.Dispose()
            }
        }
        finally {
            $archive.Dispose()
        }
    }
    finally {
        $stream.Dispose()
    }
}

function Assert-True {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )

    if (-not $Condition) {
        throw "Assertion failed: $Message"
    }
}

$helpOutput = & $shellPath -NoLogo -NoProfile -ExecutionPolicy Bypass -File $scriptPath -Help 2>&1
$helpExitCode = $LASTEXITCODE
Assert-True ($helpExitCode -eq 0) "help exits successfully"
Assert-True (($helpOutput -join "`n") -match "Usage:") "help includes a usage line"

$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("cobblemon-dev-deploy-test-" + [guid]::NewGuid().ToString("N"))
$profile = Join-Path $testRoot "profile"
$mods = Join-Path $profile "mods"
[System.IO.Directory]::CreateDirectory($mods) | Out-Null

try {
    $oldJar = Join-Path $mods "rounding-block-0.0.1.jar"
    New-TestModJar -Path $oldJar -Id "rounding_block" -Version "0.0.1"
    $oldHash = (Get-FileHash -LiteralPath $oldJar -Algorithm SHA256).Hash

    $deployOutput = & $shellPath -NoLogo -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -Module "rounding-block" `
        -ProfilePath $profile `
        -SkipBuild 2>&1
    $deployExitCode = $LASTEXITCODE
    if ($deployExitCode -ne 0) {
        Write-Host ($deployOutput -join [Environment]::NewLine)
    }
    Assert-True ($deployExitCode -eq 0) "single-module deployment exits successfully"

    $deployed = @(Get-ChildItem -LiteralPath $mods -File -Filter "*.jar")
    Assert-True ($deployed.Count -eq 1) "exactly one deployed JAR remains"
    Assert-True ($deployed[0].Name -eq $artifact.Name) "deployed filename matches the selected artifact"
    Assert-True ((Get-FileHash -LiteralPath $deployed[0].FullName -Algorithm SHA256).Hash -eq $artifactHash) "deployed hash matches the artifact"

    $backup = @(Get-ChildItem -LiteralPath (Join-Path $profile "codex-deploy-backups") -File -Filter "rounding-block-0.0.1.jar" -Recurse)
    Assert-True ($backup.Count -eq 1) "old JAR is backed up once"
    Assert-True ((Get-FileHash -LiteralPath $backup[0].FullName -Algorithm SHA256).Hash -eq $oldHash) "backup preserves the old JAR"
    Assert-True (@(Get-ChildItem -LiteralPath $mods -File -Filter "*.deploying" -ErrorAction SilentlyContinue).Count -eq 0) "no staging residue remains"

    $secondDeployOutput = & $shellPath -NoLogo -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -Module "rounding-block" `
        -ProfilePath $profile `
        -SkipBuild 2>&1
    Assert-True ($LASTEXITCODE -eq 0) "same-hash redeployment exits successfully"
    $allBackups = @(Get-ChildItem -LiteralPath (Join-Path $profile "codex-deploy-backups") -File -Recurse)
    Assert-True ($allBackups.Count -eq 1) "same-hash redeployment creates no extra backup"

    $duplicateProfile = Join-Path $testRoot "duplicate-profile"
    $duplicateMods = Join-Path $duplicateProfile "mods"
    [System.IO.Directory]::CreateDirectory($duplicateMods) | Out-Null
    New-TestModJar -Path (Join-Path $duplicateMods "duplicate-a.jar") -Id "rounding_block" -Version "0.0.1"
    New-TestModJar -Path (Join-Path $duplicateMods "duplicate-b.jar") -Id "rounding_block" -Version "0.0.2"

    $previousErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $duplicateOutput = & $shellPath -NoLogo -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
            -Module "rounding-block" `
            -ProfilePath $duplicateProfile `
            -SkipBuild 2>&1
    }
    finally {
        $ErrorActionPreference = $previousErrorPreference
    }
    $duplicateExitCode = $LASTEXITCODE
    Assert-True ($duplicateExitCode -ne 0) "duplicate installed mod IDs are rejected"
    Assert-True (@(Get-ChildItem -LiteralPath $duplicateMods -File -Filter "*.jar").Count -eq 2) "duplicate preflight failure does not mutate the profile"

    Write-Host "PASS: dev deployment tests"
}
finally {
    if (Test-Path -LiteralPath $testRoot) {
        [System.IO.Directory]::Delete($testRoot, $true)
    }
}
