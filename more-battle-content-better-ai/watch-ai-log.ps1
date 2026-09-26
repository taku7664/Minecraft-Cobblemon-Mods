param(
    [string]$LogPath = (Join-Path $env:APPDATA 'ModrinthApp\profiles\cobblemon-dev\logs\latest.log')
)

$ErrorActionPreference = 'Stop'
$reader = $null
$stream = $null
$creationTicks = $null
$firstAttach = $true

function Show-AiLine([string]$Line) {
    if ($Line.Contains('[BetterAI Trace]') -and $Line.Contains('phase=selected')) {
        Write-Host $Line -ForegroundColor Green
    } elseif ($Line.Contains('[BetterAI Trace]') -and $Line.Contains('phase=search')) {
        Write-Host $Line -ForegroundColor Yellow
    } elseif ($Line.Contains('[BetterAI Trace]') -and $Line.Contains('phase=confirmed')) {
        Write-Host $Line -ForegroundColor Magenta
    } elseif ($Line.Contains('[BetterAI Trace]') -and $Line.Contains('phase=events')) {
        Write-Host $Line -ForegroundColor DarkGray
    } elseif ($Line.Contains('[BetterAI Trace]')) {
        Write-Host $Line -ForegroundColor Cyan
    } elseif ($Line.Contains('Brain decision resolved:')) {
        Write-Host $Line -ForegroundColor Green
    } elseif ($Line.Contains('Brain threw with') -or $Line.Contains('Native product decision failed:')) {
        Write-Host $Line -ForegroundColor Yellow
    } elseif ($Line.Contains('Native Showdown generation activated')) {
        Write-Host $Line -ForegroundColor DarkCyan
    }
}

Write-Host "Watching Better AI in: $LogPath"
Write-Host 'Press Ctrl+C to stop. The watcher follows the next client launch too.'

try {
    while ($true) {
        if (-not (Test-Path -LiteralPath $LogPath -PathType Leaf)) {
            if ($null -ne $reader) { $reader.Dispose(); $reader = $null; $stream = $null }
            Start-Sleep -Milliseconds 500
            continue
        }

        $info = Get-Item -LiteralPath $LogPath
        $reopen = $null -eq $stream -or $creationTicks -ne $info.CreationTimeUtc.Ticks -or
            $info.Length -lt $stream.Position
        if ($reopen) {
            if ($null -ne $reader) { $reader.Dispose() }
            $stream = [System.IO.File]::Open(
                $LogPath,
                [System.IO.FileMode]::Open,
                [System.IO.FileAccess]::Read,
                [System.IO.FileShare]::ReadWrite -bor [System.IO.FileShare]::Delete
            )
            $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8, $true)
            $creationTicks = $info.CreationTimeUtc.Ticks
            if ($firstAttach) {
                Write-Host '--- recent saved lines (history) ---' -ForegroundColor DarkGray
                Get-Content -LiteralPath $LogPath -Tail 120 -Encoding UTF8 | ForEach-Object { Show-AiLine $_ }
                $stream.Seek(0, [System.IO.SeekOrigin]::End) | Out-Null
                $reader.DiscardBufferedData()
                $firstAttach = $false
                Write-Host '--- live decisions ---' -ForegroundColor DarkGray
            } else {
                Write-Host 'Client log restarted; following the new file.' -ForegroundColor DarkGray
            }
        }

        while ($null -ne ($line = $reader.ReadLine())) { Show-AiLine $line }
        Start-Sleep -Milliseconds 500
    }
} finally {
    if ($null -ne $reader) { $reader.Dispose() }
}
