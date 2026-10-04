[CmdletBinding()]
param(
    [string]$Source = (Join-Path $PSScriptRoot '..\..\..\develop-product\server'),
    [string]$OutputZip,
    [switch]$DryRun,
    [string]$WorldSnapshot
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$Source = (Resolve-Path -LiteralPath $Source).Path
foreach($inputRoot in @($Source,(Join-Path $Source 'mods'))){if((Get-Item -LiteralPath $inputRoot).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Linked source roots are not allowed.'}}
$launcher = 'fabric-server-mc.1.21.1-loader.0.19.5-launcher.1.1.2.jar'
$files = [System.Collections.Generic.List[object]]::new()
$omitted = [System.Collections.Generic.List[object]]::new()
$seen = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
function Add-Input([string]$Path, [string]$Entry, [bool]$Inspect = $true) {
    $item = Get-Item -LiteralPath $Path
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Links/reparse points are not allowed.' }
    if ($Entry -match '(^/|\\|(^|/)\.\.(/|$)|:)') { throw 'Unsafe ZIP path.' }
    if (-not $seen.Add($Entry)) { throw 'Duplicate ZIP path.' }
    if ($Inspect) {
        if ($Entry -match '^release/(config|defaultconfigs)/' -and $item.Extension -notin @('.json','.json5','.toml','.properties','.txt','.yaml','.yml','.conf','.cfg','.html','.css','.js','.map','.md','.png','.jpg','.jpeg','.webp','.ogg','.ttf','.woff','.woff2')) {
            $omitted.Add([pscustomobject]@{Path=$Entry;Reason='Unreviewed configuration binary/database/archive'})
            return
        }
        # Omit complete files rather than risk publishing credentials or player records.
        if ($item.Extension -notin @('.jar','.zip','.png','.jpg','.jpeg','.webp','.ogg','.nbt','.mca','.dat')) {
            $content = [IO.File]::ReadAllText($item.FullName)
            $inspectContent = $content
            $sensitive = $inspectContent -match '(?i)["\x27]?(password|passwd|secret|token|api[_-]?key|apiKey|accessToken|authorization|private[_-]?key|webhook)["\x27]?\s*[:=]'
            if ($Entry -like 'release/showdown/*') {
                # Simulator code uses token/secret as variable names. Only literal credentials are sensitive here.
                $sensitive = $inspectContent -match '(?i)["\x27]?(password|passwd|api[_-]?secret|client[_-]?secret|token|api[_-]?key|apiKey|accessToken|authorization|private[_-]?key|webhook)["\x27]?\s*[:=]\s*["\x27][^"\x27\r\n]+["\x27]'
            }
            $privateConfig = $Entry -match '^release/(config|defaultconfigs)/' -and $inspectContent -match '(?i)(https?://|(?:\d{1,3}\.){3}\d{1,3}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})'
            if ($item.Name -match '^(LICENSE|COPYING)(\..*)?$') { $privateConfig = $false }
            $privateLocalPath = $inspectContent -match '(?i)[A-Z]:[\\/]+Users[\\/]|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|https?://[^\s/]+:[^\s/]+@'
            if ($sensitive -or $privateConfig -or $privateLocalPath) {
                $omitted.Add([pscustomobject]@{Path=$Entry;Reason='Sensitive key, address, URL or player identifier; recreate locally'})
                return
            }
        }
    }
    $files.Add([pscustomobject]@{Path=$item.FullName;Entry=$Entry})
}
function Add-Tree([string]$Root,[string]$Prefix) {
    if (-not (Test-Path -LiteralPath $Root)) { return }
    $rootItem = Get-Item -LiteralPath $Root
    if ($rootItem.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Linked input directory is not allowed.' }
    foreach ($item in Get-ChildItem -LiteralPath $Root -Force) {
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Linked input is not allowed.' }
        if ($item.Extension -eq '.map') { continue }
        if ($Prefix -like 'release/plaza-assets*' -and ($item.Extension -in @('.dat','.mca') -or $item.Name -in @('region','entities','poi','playerdata','stats','advancements'))) { continue }
        if ($item.Name -ieq 'openrouter.json') { $omitted.Add([pscustomobject]@{Path="$Prefix/$($item.Name)";Reason='AI endpoint/credential configuration; recreate locally'}); continue }
        if ($item.Name -match '(?i)^(logs?|backups?|cache|\.git|node_modules|playerdata|stats|advancements|sessions?|replays?)$' -or $item.Name -match '(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}') { continue }
        if ($item.PSIsContainer) { Add-Tree $item.FullName "$Prefix/$($item.Name)" }
        else { Add-Input $item.FullName "$Prefix/$($item.Name)" }
    }
}
if (-not (Test-Path -LiteralPath (Join-Path $Source $launcher))) { throw 'Expected Fabric launcher is missing.' }
Add-Input (Join-Path $Source $launcher) "release/$launcher" $false
# Libraries and versions are downloaded by the Fabric launcher on Linux.
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($mod in Get-ChildItem -LiteralPath (Join-Path $Source 'mods') -Filter '*.jar') {
    $zip = [IO.Compression.ZipFile]::OpenRead($mod.FullName)
    try {
        $metadata = $zip.GetEntry('fabric.mod.json')
        if ($metadata) {
            $reader = [IO.StreamReader]::new($metadata.Open())
            try { $environment = ($reader.ReadToEnd() | ConvertFrom-Json).environment } finally { $reader.Dispose() }
            if ($environment -eq 'client') { $omitted.Add([pscustomobject]@{Path="release/mods/$($mod.Name)";Reason='Fabric client-only mod'}); continue }
        }
    } finally { $zip.Dispose() }
    Add-Input $mod.FullName "release/mods/$($mod.Name)" $false
}
foreach ($name in @('config','defaultconfigs','plaza-assets')) { Add-Tree (Join-Path $Source $name) "release/$name" }
# Cobblemon simulator assets, excluding its operational configuration.
foreach ($name in @('cobbled-exports','data','lib','sim','translations')) { Add-Tree (Join-Path $Source "showdown/$name") "release/showdown/$name" }
foreach ($name in @('index.js','MSDPatch.json','showdown.json')) { $p=Join-Path $Source "showdown/$name"; if(Test-Path -LiteralPath $p){Add-Input $p "release/showdown/$name"} }
foreach ($name in @('formats.js','custom-formats.js')) { $p=Join-Path $Source "showdown/config/$name"; if(Test-Path -LiteralPath $p){Add-Input $p "release/showdown/config/$name"} }
foreach($required in @('index.js','sim/battle.js','sim/pokemon.js','sim/dex.js','sim/battle-stream.js','config/formats.js')) {
    if(Test-Path -LiteralPath (Join-Path $Source "showdown/$required")){
        if(-not($files.Entry -contains "release/showdown/$required")){throw 'Required simulator input was omitted by safety checks; review it locally before packaging.'}
    }
}
Add-Input (Join-Path $PSScriptRoot 'start-linux.sh') 'release/start-linux.sh' $false
Add-Input (Join-Path $PSScriptRoot 'LINUX-SERVER.md') 'README-ko.md' $false
# Only harmless gameplay settings survive; connection details and secrets never enter the template.
$safeKeys = @('difficulty','gamemode','hardcore','pvp','allow-flight','allow-nether','spawn-animals','spawn-monsters','spawn-npcs','view-distance','simulation-distance','max-players','spawn-protection','generate-structures','max-world-size')
$properties = @('level-name=world','server-ip=','online-mode=true','enable-rcon=false','enable-query=false','resource-pack=','require-resource-pack=false')
$propertiesPath = Join-Path $Source 'server.properties'
if(Test-Path -LiteralPath $propertiesPath){
    foreach($line in [IO.File]::ReadAllLines($propertiesPath)){
        if($line -match '^([^=]+)=(.*)$' -and $Matches[1] -in $safeKeys -and $Matches[2] -match '^(true|false|easy|normal|hard|peaceful|survival|creative|adventure|spectator|[0-9]+)$') { $properties += $line }
    }
}
if ($WorldSnapshot) {
    $snapshot = (Resolve-Path -LiteralPath $WorldSnapshot).Path
    if((Get-Item -LiteralPath $snapshot).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Linked snapshot roots are not allowed.'}
    if ($snapshot -eq $Source -or $snapshot.StartsWith($Source + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Use an offline snapshot outside the development server, never its live world.' }
    foreach($name in @('region','entities','poi','DIM-1','DIM1','dimensions','datapacks','serverconfig')){Add-Tree (Join-Path $snapshot $name) "world-seed/world/$name"}
    foreach($name in @('level.dat','level.dat_old')){$p=Join-Path $snapshot $name;if(Test-Path -LiteralPath $p){Add-Input $p "world-seed/world/$name" $false}}
    if(-not(Test-Path -LiteralPath (Join-Path $snapshot 'level.dat'))){throw 'Snapshot requires level.dat.'}
    foreach($name in @('level.dat','level.dat_old')) {
        $p=Join-Path $snapshot $name
        if(Test-Path -LiteralPath $p){
            $input=[IO.File]::OpenRead($p);$gzip=[IO.Compression.GZipStream]::new($input,[IO.Compression.CompressionMode]::Decompress);$memory=[IO.MemoryStream]::new()
            try{$gzip.CopyTo($memory);if([Text.Encoding]::UTF8.GetString($memory.ToArray()).Contains('Player')){throw 'Snapshot level metadata contains a Player tag; provide a dedicated-server snapshot without player metadata.'}}finally{$memory.Dispose();$gzip.Dispose();$input.Dispose()}
        }
    }
}
$manifest = [ordered]@{Minecraft='1.21.1';FabricLoader='0.19.5';Launcher='1.1.2';Java=21;Files=@($files | ForEach-Object {$_.Entry});Omitted=@($omitted);WorldSnapshot=[bool]$WorldSnapshot;DefaultExclusions=@('worlds','logs','backups','caches','player/operator/whitelist/ban records','libraries/versions (downloaded by launcher)','showdown operational server/tools/config-example','plaza-assets world data','source maps','unreviewed configuration binaries')}
if($DryRun){$manifest | ConvertTo-Json -Depth 5; return}
if(-not $OutputZip){throw 'Specify -OutputZip or use -DryRun.'}
$OutputZip=[IO.Path]::GetFullPath($OutputZip)
if($OutputZip.StartsWith($Source + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Output must be outside the development server.'}
if(Test-Path -LiteralPath $OutputZip){throw 'Output already exists; choose a new ZIP path.'}
$stream=[IO.File]::Open($OutputZip,[IO.FileMode]::CreateNew)
$archive=[IO.Compression.ZipArchive]::new($stream,[IO.Compression.ZipArchiveMode]::Create,$false)
try {
    foreach($file in $files){[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$file.Path,$file.Entry,[IO.Compression.CompressionLevel]::Optimal) | Out-Null}
    foreach($textEntry in @(@{Name='release/server.properties.example';Text=($properties -join "`n")+"`n"},@{Name='manifest.json';Text=($manifest | ConvertTo-Json -Depth 5)})){
        $entry=$archive.CreateEntry($textEntry.Name);$writer=[IO.StreamWriter]::new($entry.Open(),[Text.UTF8Encoding]::new($false))
        try{$writer.Write($textEntry.Text)}finally{$writer.Dispose()}
    }
} finally {$archive.Dispose();$stream.Dispose()}
Write-Output ('Created ZIP with '+$files.Count+' input files; omitted '+$omitted.Count+' files. Review manifest.json before deployment.')
