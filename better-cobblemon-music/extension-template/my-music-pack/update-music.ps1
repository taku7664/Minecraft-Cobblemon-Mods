# Better Cobblemon Music 개인 음악팩 갱신 스크립트
# assets/mymusic/sounds/music 아래의 .ogg 파일을 찾아 sounds.json과 음악 카탈로그를 다시 만듭니다.
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$ns = 'mymusic'
$musicDir = Join-Path $root "assets\$ns\sounds\music"
$soundsFile = Join-Path $root "assets\$ns\sounds.json"
$catalogDir = Join-Path $root 'assets\better_cobblemon_music\catalogs\extensions'
$catalogFile = Join-Path $catalogDir "$ns.json"
$utf8 = New-Object System.Text.UTF8Encoding $false

function Quote([string]$value) {
    '"' + $value.Replace('\', '\\').Replace('"', '\"') + '"'
}

function Write-Json([string]$path, [string]$text) {
    [System.IO.File]::WriteAllText($path, $text, $utf8)
}

New-Item -ItemType Directory -Force $musicDir | Out-Null
$validPath = '^[a-z0-9_.-]+(/[a-z0-9_.-]+)*$'
$tracks = New-Object System.Collections.Generic.List[string]
$skipped = New-Object System.Collections.Generic.List[string]

Get-ChildItem -LiteralPath $musicDir -Recurse -File | Sort-Object FullName | ForEach-Object {
    $relative = $_.FullName.Substring($musicDir.Length + 1).Replace('\', '/')
    if ($_.Name -eq 'put_ogg_files_here.txt') { return }
    if (-not $relative.EndsWith('.ogg', [System.StringComparison]::Ordinal)) {
        $skipped.Add("$relative  -> .ogg 파일만 쓸 수 있습니다 (mp3, wav는 ogg로 변환하세요)")
        return
    }
    $path = $relative.Substring(0, $relative.Length - 4)
    if ($path -cnotmatch $validPath) {
        $skipped.Add("$relative  -> 파일·폴더 이름은 영어 소문자, 숫자, _ - . 만 쓸 수 있습니다")
        return
    }
    $tracks.Add($path)
}

if ($tracks.Count -eq 0) {
    Remove-Item -LiteralPath $soundsFile, $catalogFile -ErrorAction SilentlyContinue
    Write-Host ''
    Write-Host '넣은 노래가 없습니다. assets\mymusic\sounds\music 폴더에 .ogg 파일을 넣고 다시 실행하세요.'
} else {
    $sounds = New-Object System.Text.StringBuilder
    $trackEntries = New-Object System.Collections.Generic.List[string]
    $playlists = [ordered]@{}
    $playlists["${ns}:all"] = New-Object System.Collections.Generic.List[string]

    foreach ($path in $tracks) {
        $trackId = "${ns}:$path"
        $event = 'music.' + $path.Replace('/', '.')
        $title = $path.Substring($path.LastIndexOf('/') + 1)
        if ($sounds.Length -gt 0) { [void]$sounds.Append(",`n") }
        [void]$sounds.Append("  $(Quote $event): {`n    `"sounds`": [{ `"name`": $(Quote "${ns}:music/$path"), `"stream`": true }]`n  }")
        $trackEntries.Add("    $(Quote $trackId): { `"event`": $(Quote "${ns}:$event"), `"title`": $(Quote $title) }")

        $playlists["${ns}:all"].Add($trackId)
        $playlists["${ns}:track/$path"] = New-Object System.Collections.Generic.List[string]
        $playlists["${ns}:track/$path"].Add($trackId)
        $slash = $path.LastIndexOf('/')
        if ($slash -gt 0) {
            $folderId = "${ns}:folder/" + $path.Substring(0, $slash)
            if (-not $playlists.Contains($folderId)) {
                $playlists[$folderId] = New-Object System.Collections.Generic.List[string]
            }
            $playlists[$folderId].Add($trackId)
        }
    }

    $playlistEntries = foreach ($id in $playlists.Keys) {
        $members = ($playlists[$id] | ForEach-Object { Quote $_ }) -join ', '
        "    $(Quote $id): { `"tracks`": [$members] }"
    }

    New-Item -ItemType Directory -Force $catalogDir | Out-Null
    Write-Json $soundsFile ("{`n" + $sounds.ToString() + "`n}`n")
    Write-Json $catalogFile (@(
        '{',
        '  "schemaVersion": 1,',
        "  `"packId`": `"${ns}:my_music`",",
        '  "kind": "extension",',
        '  "tracks": {',
        ($trackEntries -join ",`n"),
        '  },',
        '  "playlists": {',
        ($playlistEntries -join ",`n"),
        '  }',
        '}',
        ''
    ) -join "`n")

    Write-Host ''
    Write-Host "노래 $($tracks.Count)곡을 등록했습니다. 설정 화면에서 고를 수 있는 플레이리스트:"
    foreach ($id in $playlists.Keys) { Write-Host "  $id  ($($playlists[$id].Count)곡)" }
    Write-Host ''
    Write-Host '게임에서 F3+T로 리소스를 다시 불러온 뒤, 모드 메뉴의 Better Cobblemon Music 설정에서 연결하세요.'
}

if ($skipped.Count -gt 0) {
    Write-Host ''
    Write-Host '건너뛴 파일:'
    foreach ($line in $skipped) { Write-Host "  $line" }
}
