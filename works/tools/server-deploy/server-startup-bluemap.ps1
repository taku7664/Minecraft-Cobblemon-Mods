# BlueMap server-only rule: ALWAYS enforce one rendering worker before launch.
[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$ServerRoot)
$ErrorActionPreference = 'Stop'
$configPath = Join-Path $ServerRoot 'config/bluemap/core.conf'
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw "BlueMap configuration not found: $configPath"
}
$content = [IO.File]::ReadAllText($configPath)
$pattern = '(?m)^[ \t]*render-thread-count[ \t]*:[^\r\n]*'
$matchesFound = [regex]::Matches($content, $pattern)
if ($matchesFound.Count -gt 1) {
    throw 'Multiple render-thread-count entries found; resolve duplicates before starting.'
}
if ($matchesFound.Count -eq 1) {
    $updated = [regex]::Replace($content, $pattern, 'render-thread-count: 1')
} else {
    $newline = if ($content.Contains("`r`n")) { "`r`n" } else { "`n" }
    $separator = if ($content.Length -gt 0 -and -not $content.EndsWith("`n")) { $newline } else { '' }
    $updated = $content + $separator + 'render-thread-count: 1' + $newline
}
if ($updated -cne $content) {
    [IO.File]::WriteAllText($configPath, $updated, [Text.UTF8Encoding]::new($false))
}
Write-Output '[startup-hooks] BlueMap render-thread-count=1 (ALWAYS)'
