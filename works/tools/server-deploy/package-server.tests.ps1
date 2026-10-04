$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root=Join-Path ([IO.Path]::GetTempPath()) ('cobblemon-package-test-'+[guid]::NewGuid().ToString('N'))
$source=Join-Path $root '한글 서버'
New-Item -ItemType Directory -Path "$source/mods","$source/config","$source/logs","$source/live-world" -Force | Out-Null
function Assert($condition,$message){if(-not $condition){throw $message}}
function Write-TestJar($path,$environment){
    $zip=[IO.Compression.ZipFile]::Open($path,[IO.Compression.ZipArchiveMode]::Create)
    try{$entry=$zip.CreateEntry('fabric.mod.json');$writer=[IO.StreamWriter]::new($entry.Open());try{$writer.Write('{"id":"fixture","version":"1","environment":"'+$environment+'"}')}finally{$writer.Dispose()}}finally{$zip.Dispose()}
}
try{
    Write-TestJar "$source/fabric-server-mc.1.21.1-loader.0.19.5-launcher.1.1.2.jar" '*'
    Write-TestJar "$source/mods/server.jar" '*'
    Write-TestJar "$source/mods/client.jar" 'client'
    [IO.File]::WriteAllText("$source/config/설정.json",'{"enabled":true}')
    [IO.File]::WriteAllText("$source/config/private.json",'{"apiKey":"fixture-secret"}')
    [IO.File]::WriteAllText("$source/config/local-path.json",'{"path":"C:\\Users\\fixture\\data"}')
    [IO.File]::WriteAllText("$source/server.properties","difficulty=hard`nrcon.password=fixture-secret`nserver-ip=192.0.2.1`nlevel-name=live-world`nresource-pack=https://example.invalid/private`n")
    [IO.File]::WriteAllText("$source/ops.json",'fixture-private')
    [IO.File]::WriteAllText("$source/logs/latest.log",'fixture-private')
    $original=[IO.File]::ReadAllText("$source/server.properties")
    $script=Join-Path $PSScriptRoot 'package-server.ps1'
    $dry=(& $script -Source $source -DryRun) | ConvertFrom-Json
    Assert ($dry.Files -contains 'release/config/설정.json') 'Unicode entry missing'
    Assert ($dry.Files -notcontains 'release/mods/client.jar') 'Client mod leaked'
    Assert ($dry.Files -notcontains 'release/config/private.json') 'Secret config leaked'
    Assert ($dry.Files -notcontains 'release/config/local-path.json') 'Private local path leaked'
    Assert (-not(Test-Path -LiteralPath "$root/test.zip")) 'Dry run created archive'
    & $script -Source $source -OutputZip "$root/test.zip"
    $zip=[IO.Compression.ZipFile]::OpenRead("$root/test.zip")
    try{
        $names=@($zip.Entries.FullName)
        Assert ($names -contains 'release/config/설정.json') 'Unicode ZIP entry missing'
        Assert (-not($names | Where-Object {$_ -match '(^/|\\|(^|/)\.\.(/|$)|:)'})) 'Unsafe entry path'
        Assert (-not($names | Where-Object {$_ -match 'ops.json|latest.log|live-world|client.jar|private.json'})) 'Excluded file leaked'
        $reader=[IO.StreamReader]::new($zip.GetEntry('release/server.properties.example').Open())
        try{$properties=$reader.ReadToEnd()}finally{$reader.Dispose()}
        Assert ($properties -match 'level-name=world') 'Persistent world setting missing'
        Assert ($properties -match 'difficulty=hard') 'Gameplay setting lost'
        Assert ($properties -notmatch 'fixture-secret|192.0.2.1|example.invalid|live-world') 'Property secret leaked'
    }finally{$zip.Dispose()}
    Assert ([IO.File]::ReadAllText("$source/server.properties") -eq $original) 'Original changed'
    $rejected=$false;try{& $script -Source $source -OutputZip "$root/test.zip"}catch{$rejected=$true};Assert $rejected 'Existing archive overwritten'
    $rejected=$false;try{& $script -Source $source -DryRun -WorldSnapshot "$source/live-world"}catch{$rejected=$true};Assert $rejected 'Live world accepted'
    $snapshot=Join-Path $root 'offline-world'
    New-Item -ItemType Directory -Path "$snapshot/region","$snapshot/playerdata","$snapshot/datapacks" -Force | Out-Null
    [IO.File]::WriteAllText("$snapshot/region/r.0.0.mca",'fixture-region')
    [IO.File]::WriteAllText("$snapshot/playerdata/player.dat",'fixture-private')
    $output=[IO.File]::Create("$snapshot/level.dat");$gzip=[IO.Compression.GZipStream]::new($output,[IO.Compression.CompressionMode]::Compress)
    try{$bytes=[byte[]]@(10,0,0,0);$gzip.Write($bytes,0,$bytes.Length)}finally{$gzip.Dispose();$output.Dispose()}
    & $script -Source $source -OutputZip "$root/world.zip" -WorldSnapshot $snapshot
    $zip=[IO.Compression.ZipFile]::OpenRead("$root/world.zip")
    try{Assert ($zip.Entries.FullName -contains 'world-seed/world/region/r.0.0.mca') 'World seed missing';Assert (-not($zip.Entries.FullName -match 'playerdata')) 'Player data leaked';Assert (-not($zip.Entries.FullName -contains 'release/world/level.dat')) 'World merged into release'}finally{$zip.Dispose()}
    Write-Output 'PASS: dry-run, ZIP paths, Unicode, client mod exclusion, secret omission, properties sanitization, original preservation, overwrite/live-world rejection, isolated snapshot and player-data exclusion.'
}finally{
    $resolvedRoot=[IO.Path]::GetFullPath($root)
    $resolvedTemp=[IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if($resolvedRoot.StartsWith($resolvedTemp,[StringComparison]::OrdinalIgnoreCase) -and (Split-Path $resolvedRoot -Leaf) -like 'cobblemon-package-test-*'){Remove-Item -LiteralPath $resolvedRoot -Recurse -Force}
}
