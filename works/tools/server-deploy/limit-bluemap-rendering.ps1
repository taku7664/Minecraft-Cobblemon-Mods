# Install and apply the BlueMap ALWAYS startup rule to the development server.
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$serverRoot = Join-Path $repoRoot 'develop-product/server'
$launcherPath = Join-Path $serverRoot 'run.bat'
$launcher = [IO.File]::ReadAllText($launcherPath)
$anchor = 'echo [run] Checking server startup settings...'
$hookCall = 'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\server-startup-bluemap.ps1" -ServerRoot "%~dp0."'
if (-not $launcher.Contains($hookCall)) {
    if (([regex]::Matches($launcher, [regex]::Escape($anchor))).Count -ne 1) {
        throw 'Expected exactly one startup hook insertion point in run.bat.'
    }
    $newline = if ($launcher.Contains("`r`n")) { "`r`n" } else { "`n" }
    $launcher = $launcher.Replace($anchor, $anchor + $newline + $hookCall + $newline + 'if errorlevel 1 goto end')
}
$sourceHook = Join-Path $PSScriptRoot 'server-startup-bluemap.ps1'
& $sourceHook -ServerRoot $serverRoot
Copy-Item -LiteralPath $sourceHook -Destination (Join-Path $serverRoot 'tools/server-startup-bluemap.ps1') -Force
[IO.File]::WriteAllText($launcherPath, $launcher, [Text.UTF8Encoding]::new($false))
Write-Output 'BlueMap single-worker ALWAYS startup hook installed.'
