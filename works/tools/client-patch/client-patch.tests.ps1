$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'client-patch.psm1') -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Assert($value,$message) { if(-not $value){throw $message} }
function Jar($path,$id,$version) {
    $zip=[IO.Compression.ZipFile]::Open($path,'Create')
    try {$writer=[IO.StreamWriter]::new($zip.CreateEntry('fabric.mod.json').Open());try{$writer.Write('{"id":"'+$id+'","version":"'+$version+'","environment":"*"}')}finally{$writer.Dispose()}}finally{$zip.Dispose()}
}
$root=Join-Path $env:TEMP ('client-patch-test-'+[guid]::NewGuid().ToString('N'))
$payload=Join-Path $root 'package';$target=Join-Path $root 'client';$state=Join-Path $root 'state'
New-Item -ItemType Directory -Path "$payload/payload/mods","$payload/payload/config/more-cobblemon-contents/wiki/pages","$target/mods","$target/config/cobblemon-client-setup","$target/config/more-cobblemon-contents/wiki","$target/saves" -Force | Out-Null
try {
    Jar "$payload/payload/mods/new.jar" 'cobblemon' '2'
    Jar "$target/mods/renamed-old.jar" 'cobblemon' '1'
    Jar "$target/mods/personal.jar" 'personal' '1'
    [IO.File]::WriteAllText("$payload/payload/config/more-cobblemon-contents/wiki/index.html",'new wiki')
    [IO.File]::WriteAllText("$target/config/more-cobblemon-contents/wiki/index.html",'old wiki')
    $protected=@('options.txt','servers.dat','config/cobblemon-client-setup/client.toml','config/cobblemon-client-setup/applied-defaults.properties','saves/player.dat')
    foreach($path in $protected){[IO.File]::WriteAllText((Join-Path $target $path),'personal bytes')}
    $files=@(Get-ChildItem "$payload/payload" -Recurse -File | ForEach-Object { @{Path=$_.FullName.Substring(("$payload/payload").Length+1).Replace('\','/');Sha256=(Get-FileHash $_.FullName).Hash;Size=$_.Length} })
    $manifest=@{Schema=1;Files=$files;Mods=@(@{Id='cobblemon';Path='mods/new.jar';Version='2'});RetiredModIds=@();ResourcePacks=@()}
    function SaveManifest { [IO.File]::WriteAllText("$payload/patch.json",($manifest|ConvertTo-Json -Depth 8)) }
    SaveManifest
    $plan=Get-ClientPatchPlan $payload $target $state -SkipProcessCheck
    Assert ($plan.Operations.Count -eq 3) 'Expected mod add + old ID removal + wiki update'
    Assert (@($plan.Operations|Where-Object Path -eq 'mods/personal.jar').Count -eq 0) 'Personal mod selected'
    # Preview must be invalidated by either source or destination edits.
    [IO.File]::WriteAllText("$target/config/more-cobblemon-contents/wiki/index.html",'changed since preview')
    $blocked=$false;try{Invoke-ClientPatch $plan -SkipProcessCheck}catch{$blocked=$true};Assert $blocked 'Stale preview applied'
    [IO.File]::WriteAllText("$target/config/more-cobblemon-contents/wiki/index.html",'old wiki')
    $plan=Get-ClientPatchPlan $payload $target $state -SkipProcessCheck
    $result=Invoke-ClientPatch $plan -SkipProcessCheck
    Assert ($result.Changed -eq 3) 'Patch failed'
    Assert (-not(Test-Path "$target/mods/renamed-old.jar")) 'Old version survived'
    Assert (Test-Path "$target/mods/personal.jar") 'Personal mod removed'
    foreach($path in $protected){Assert ([IO.File]::ReadAllText((Join-Path $target $path)) -eq 'personal bytes') ('Protected file changed: '+$path)}
    Assert ((Get-ClientPatchPlan $payload $target $state -SkipProcessCheck).Operations.Count -eq 0) 'Second run not idempotent'
    Restore-ClientPatch $target $state -SkipProcessCheck | Out-Null
    Assert (Test-Path "$target/mods/renamed-old.jar") 'Old version not restored'
    Assert (-not(Test-Path "$target/mods/new.jar")) 'Added mod not removed on restore'
    Assert ([IO.File]::ReadAllText("$target/config/more-cobblemon-contents/wiki/index.html") -eq 'old wiki') 'Wiki not restored'
    # Updating a selected pack must retain its original name and options bytes.
    New-Item -ItemType Directory -Path "$payload/payload/resourcepacks","$target/resourcepacks" -Force|Out-Null
    [IO.File]::WriteAllText("$payload/payload/resourcepacks/E19-Xaero-Icons-2.zip",'new pack')
    [IO.File]::WriteAllText("$target/resourcepacks/E19-Xaero-Icons-1.zip",'old pack')
    [IO.File]::WriteAllText("$target/resourcepacks/personal.zip",'personal pack')
    $manifest.Files+=@{Path='resourcepacks/E19-Xaero-Icons-2.zip';Sha256=(Get-FileHash "$payload/payload/resourcepacks/E19-Xaero-Icons-2.zip").Hash;Size=8}
    $manifest.ResourcePacks=@(@{Family='E19-Xaero-Icons';Path='resourcepacks/E19-Xaero-Icons-2.zip'});SaveManifest
    $plan=Get-ClientPatchPlan $payload $target $state -SkipProcessCheck
    $module=Get-Module client-patch
    # Inject a one-shot disk failure after an earlier operation was already installed.
    & $module {
        $script:originalInstall=${function:Install};$script:installCalls=0
        function script:Install([string]$source,[string]$destination){$script:installCalls++;if($script:installCalls -eq 2){throw 'fixture disk failure'};& $script:originalInstall $source $destination}
    }
    $blocked=$false;try{Invoke-ClientPatch $plan -SkipProcessCheck|Out-Null}catch{$blocked=$true};Assert $blocked 'Injected failure ignored'
    Assert ([IO.File]::ReadAllText("$target/config/more-cobblemon-contents/wiki/index.html") -eq 'old wiki') 'Partial patch was not rolled back'
    Assert (Test-Path "$target/mods/renamed-old.jar") 'Rollback lost original mod'
    Assert (-not(Test-Path "$target/mods/new.jar")) 'Rollback left installed file'
    Import-Module (Join-Path $PSScriptRoot 'client-patch.psm1') -Force
    $plan=Get-ClientPatchPlan $payload $target $state -SkipProcessCheck
    Invoke-ClientPatch $plan -SkipProcessCheck|Out-Null
    Assert ([IO.File]::ReadAllText("$target/resourcepacks/E19-Xaero-Icons-1.zip") -eq 'new pack') 'Selected ZIP name not patched'
    Assert ([IO.File]::ReadAllText("$target/resourcepacks/personal.zip") -eq 'personal pack') 'Personal pack changed'
    Assert ([IO.File]::ReadAllText("$target/options.txt") -eq 'personal bytes') 'Pack activation options changed'
    [IO.File]::WriteAllText("$target/config/more-cobblemon-contents/wiki/index.html",'later user edit')
    $blocked=$false;try{Restore-ClientPatch $target $state -SkipProcessCheck|Out-Null}catch{$blocked=$true};Assert $blocked 'Restore overwrote later user edit'
    [IO.File]::WriteAllText("$target/config/more-cobblemon-contents/wiki/index.html",'new wiki')
    Restore-ClientPatch $target $state -SkipProcessCheck|Out-Null
    # Retired web-map mods must not return from an old package, even under renamed JARs.
    Jar "$payload/payload/mods/renamed-map.jar" 'bluemap' '1'
    Jar "$payload/payload/mods/renamed-link.jar" 'maplink' '1'
    Jar "$target/mods/old-map.jar" 'bluemap' '1'
    Jar "$target/mods/old-link.jar" 'maplink' '1'
    foreach($name in @('renamed-map.jar','renamed-link.jar')){
        $p="$payload/payload/mods/$name"
        $manifest.Files+=@{Path="mods/$name";Sha256=(Get-FileHash $p).Hash;Size=(Get-Item $p).Length}
    }
    foreach($rel in @('config/more-cobblemon-contents/wiki/pages/map.html','config/more-cobblemon-contents/wiki/assets/map.js')){
        $p=Join-Path "$payload/payload" $rel
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($p))|Out-Null
        [IO.File]::WriteAllText($p,'retired map')
        $manifest.Files+=@{Path=$rel;Sha256=(Get-FileHash $p).Hash;Size=(Get-Item $p).Length}
    }
    SaveManifest
    $plan=Get-ClientPatchPlan $payload $target $state -SkipProcessCheck
    Assert (-not(@($plan.Managed)|Where-Object {$_ -match 'renamed-(map|link)'})) 'Retired map mods selected'
    Assert (-not(@($plan.Managed)|Where-Object {$_ -match '/(pages/map\.html|assets/map\.js)$'})) 'Retired wiki map files selected'
    foreach($name in @('old-map.jar','old-link.jar')){
        Assert (@($plan.Operations|Where-Object {$_.Path -eq "mods/$name" -and $_.Action -eq 'delete'}).Count -eq 1) 'Retired installed map mod not removed'
    }
    Assert (@($plan.Operations|Where-Object Path -eq 'mods/personal.jar').Count -eq 0) 'Unrelated personal mod selected'
    # Tampered packages must never introduce arbitrary config paths or traversal.
    foreach($bad in @('options.txt','config/settings.json','../outside.txt','mods/../../outside.jar','resourcepacks/personal.zip')) {
        $manifest.Files[0].Path=$bad;SaveManifest
        $blocked=$false;try{Get-ClientPatchPlan $payload $target $state -SkipProcessCheck | Out-Null}catch{$blocked=$true};Assert $blocked ('Unsafe manifest accepted: '+$bad)
    }
    Write-Host 'PASS: preview invalidation, mod-ID replacement, personal preservation, wiki, idempotency, restore, path rejection, pack aliases, failure rollback, later-edit protection'
} finally {
    $full=[IO.Path]::GetFullPath($root)
    if($full.StartsWith([IO.Path]::GetFullPath($env:TEMP).TrimEnd('\')+'\') -and $full -match 'client-patch-test-[a-f0-9]+$'){Remove-Item -LiteralPath $full -Recurse -Force}
}
