Set-StrictMode -Version 2
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$script:Utf8=[Text.UTF8Encoding]::new($false)
function Hash([string]$path) { if([IO.File]::Exists($path)){return (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()};return $null }
function Root([string]$path) {
    if(-not $path -or $path.StartsWith('\\')){throw '로컬 폴더를 선택해 주세요.'}
    $full=[IO.Path]::GetFullPath($path).TrimEnd('\','/')
    if($full.Length -lt 4 -or -not [IO.Directory]::Exists($full)){throw '폴더가 존재하지 않습니다.'}
    # Modrinth's development profile may be reached through a root junction.
    return $full
}
function Local([string]$root,[string]$relative) {
    if(-not $relative -or $relative -match '(^/|\\|:|(^|/)\.\.?(/|$))'){throw ('허용하지 않는 경로: '+$relative)}
    $path=[IO.Path]::GetFullPath((Join-Path $root $relative))
    if(-not $path.StartsWith($root+'\',[StringComparison]::OrdinalIgnoreCase)){throw '폴더 밖의 파일은 변경할 수 없습니다.'}
    $cursor=$path
    while($cursor.Length -gt $root.Length){
        if((Test-Path -LiteralPath $cursor) -and ([IO.File]::GetAttributes($cursor) -band [IO.FileAttributes]::ReparsePoint)){throw ('하위 경로에 링크가 있습니다: '+$relative)}
        $cursor=[IO.Path]::GetDirectoryName($cursor)
    }
    return $path
}
function Allowed([string]$path) {
    return $path -match '^mods/[^/]+\.jar$|^config/more-cobblemon-contents/wiki/(index\.html|pages/[^/]+\.html|assets/.+\.(js|css|png|jpg|jpeg|webp|svg|ico|woff2?|ttf|txt))$'
}
function StateRoot([string]$target,[string]$state) {
    if(-not $state){
        $sha=[Security.Cryptography.SHA256]::Create()
        try{$key=([BitConverter]::ToString($sha.ComputeHash($script:Utf8.GetBytes($target.ToLowerInvariant())))).Replace('-','').Substring(0,16)}finally{$sha.Dispose()}
        $state=Join-Path $env:LOCALAPPDATA ('MinecraftClientPatch/'+$key)
    }
    $state=[IO.Path]::GetFullPath($state).TrimEnd('\','/')
    if($state.StartsWith('\\') -or $state -eq $target -or $state.StartsWith($target+'\',[StringComparison]::OrdinalIgnoreCase) -or $target.StartsWith($state+'\',[StringComparison]::OrdinalIgnoreCase)){throw '백업 폴더는 클라이언트 밖에 두어야 합니다.'}
    $cursor=$state
    while($cursor.Length -gt 3){if((Test-Path -LiteralPath $cursor) -and ([IO.File]::GetAttributes($cursor) -band [IO.FileAttributes]::ReparsePoint)){throw '백업 경로에 링크가 있습니다.'};$cursor=[IO.Path]::GetDirectoryName($cursor)}
    return $state
}
function AssertStopped([string]$target) {
    if(Test-Path -LiteralPath (Join-Path $target 'server.properties')){throw '서버 폴더를 클라이언트로 선택할 수 없습니다.'}
    # Command lines are examined only in memory; never expose account arguments.
    $running=@(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction Stop | Where-Object {$_.CommandLine -match '(?i)knotclient|net\.minecraft\.client\.main\.Main|org\.multimc\.EntryPoint|net\.fabricmc\.loader.*\.launchwrapper\.FabricClient'})
    if($running.Count){throw 'Minecraft 클라이언트를 모두 종료한 뒤 패치하세요.'}
    foreach($file in @(Get-ChildItem -LiteralPath (Local $target 'mods') -Filter '*.jar' -File)){
        try{$stream=[IO.File]::Open($file.FullName,'Open','ReadWrite','None');$stream.Dispose()}catch{throw ('사용 중이거나 쓸 수 없는 모드: '+$file.Name)}
    }
}
function ModInfo([string]$path) {
    $zip=[IO.Compression.ZipFile]::OpenRead($path)
    try{
        $entry=$zip.GetEntry('fabric.mod.json');if($null -eq $entry){throw ('Fabric 모드 정보가 없습니다: '+[IO.Path]::GetFileName($path))}
        $reader=[IO.StreamReader]::new($entry.Open());try{$meta=$reader.ReadToEnd()|ConvertFrom-Json}finally{$reader.Dispose()}
        if(-not $meta.id -or $meta.id -notmatch '^[a-z][a-z0-9_-]+$'){throw '유효하지 않은 모드 ID입니다.'}
        return $meta
    }finally{$zip.Dispose()}
}
function Save([string]$path,$value) {
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path))|Out-Null
    $temp=$path+'.tmp';[IO.File]::WriteAllText($temp,($value|ConvertTo-Json -Depth 12),$script:Utf8)
    if(Test-Path -LiteralPath $path){[IO.File]::Replace($temp,$path,[Management.Automation.Language.NullString]::Value)}else{[IO.File]::Move($temp,$path)}
}
function State([string]$root) {
    $path=Local $root 'current.json'
    if(Test-Path -LiteralPath $path){$value=[IO.File]::ReadAllText($path)|ConvertFrom-Json;if($value.Schema -ne 1){throw '지원하지 않는 복구 기록입니다.'};return $value}
    return [pscustomobject]@{Schema=1;Managed=@();LastTransaction=$null}
}
function AssertNoPending([string]$state) {
    if(-not(Test-Path -LiteralPath (Join-Path $state 'history'))){return}
    foreach($dir in Get-ChildItem -LiteralPath (Join-Path $state 'history') -Directory){
        $journal=Local $state ('history/'+$dir.Name+'/transaction.json')
        if(Test-Path -LiteralPath $journal){$record=[IO.File]::ReadAllText($journal)|ConvertFrom-Json;if($record.Status -in @('applying','restoring','recovery-needed')){throw '중단된 패치가 있습니다. 먼저 최근 패치 복구를 실행하세요.'}}
    }
}
function ClientInventory([string]$source,[string]$wiki) {
    $files=[Collections.Generic.List[object]]::new();$mods=[Collections.Generic.List[object]]::new();$packs=[Collections.Generic.List[object]]::new()
    foreach($file in Get-ChildItem -LiteralPath (Local $source 'mods') -Filter '*.jar' -File){
        $relative='mods/'+$file.Name;$path=Local $source $relative;$meta=ModInfo $path
        if($meta.PSObject.Properties['environment'] -and $meta.environment -eq 'server'){continue}
        $files.Add([pscustomobject]@{Path=$relative;Source=$path;Sha256=(Hash $path);Size=$file.Length})
        $mods.Add([pscustomobject]@{Id=$meta.id;Version=$meta.version;Path=$relative})
    }
    foreach($family in @('better-cobblemon-music-resourcepack','cobblemon-korean-translation-bundle','E19-Xaero-Icons','galmuri11-8px','RCT Trainers+ [1.7] v2.2','spawn-notification-ment')){
        $matches=@(Get-ChildItem -LiteralPath (Local $source 'resourcepacks') -Filter '*.zip' -File|Where-Object {$_.Name.StartsWith($family,[StringComparison]::Ordinal)})
        if($matches.Count -ne 1){throw ('기능 리소스팩은 계열별 한 개여야 합니다: '+$family)}
        $file=$matches[0];$relative='resourcepacks/'+$file.Name;$path=Local $source $relative
        $zip=[IO.Compression.ZipFile]::OpenRead($path);try{if(-not $zip.GetEntry('pack.mcmeta')){throw ('리소스팩 정보가 없습니다: '+$file.Name)}}finally{$zip.Dispose()}
        $files.Add([pscustomobject]@{Path=$relative;Source=$path;Sha256=(Hash $path);Size=$file.Length});$packs.Add([pscustomobject]@{Family=$family;Path=$relative})
    }
    $wiki=Root $wiki
    $wikiPaths=@(Get-Item -LiteralPath (Local $wiki 'index.html'))
    foreach($folder in @('pages','assets')){
        $root=Local $wiki $folder
        $links=@(Get-ChildItem -LiteralPath $root -Recurse -Force|Where-Object {$_.Attributes -band [IO.FileAttributes]::ReparsePoint})
        if($links.Count){throw '위키 소스에 링크가 있습니다.'}
        $wikiPaths+=@(Get-ChildItem -LiteralPath $root -Recurse -File)
    }
    foreach($file in $wikiPaths){
        $relative='config/more-cobblemon-contents/wiki/'+$file.FullName.Substring($wiki.Length+1).Replace('\','/')
        if(-not(Allowed $relative) -or $relative -match '(^|/)\.|/_template\.html$'){continue}
        $files.Add([pscustomobject]@{Path=$relative;Source=$file.FullName;Sha256=(Hash $file.FullName);Size=$file.Length})
    }
    $ids=@($mods|ForEach-Object {$_.Id})
    foreach($required in @('cobblemon','cobblemon_client_setup','more_cobblemon_contents')){if($required -notin $ids){throw ('필수 클라이언트 모드가 없습니다: '+$required)}}
    return [pscustomobject]@{Schema=1;Files=@($files.ToArray());Mods=@($mods.ToArray());ResourcePacks=@($packs.ToArray())}
}
function Get-ClientPatchPlan {
    [CmdletBinding()]param([string]$PackageRoot,[Parameter(Mandatory=$true)][string]$TargetRoot,[string]$BackupRoot,[switch]$SkipProcessCheck,[string]$SourceRoot,[string]$WikiRoot)
    $localMode=-not [string]::IsNullOrWhiteSpace($SourceRoot)
    $package=if($localMode){Root $SourceRoot}else{Root $PackageRoot};$target=Root $TargetRoot;$state=StateRoot $target $BackupRoot
    if($package -eq $target){throw '개발 클라이언트와 배포 클라이언트는 달라야 합니다.'}
    if(-not(Test-Path -LiteralPath (Local $target 'mods') -PathType Container)){throw 'mods 폴더가 있는 기존 클라이언트를 선택하세요.'}
    if(-not $SkipProcessCheck){AssertStopped $target}
    AssertNoPending $state
    $manifestPath=$null
    if($localMode){$manifest=ClientInventory $package $WikiRoot}else{$manifestPath=Local $package 'patch.json';$manifest=[IO.File]::ReadAllText($manifestPath)|ConvertFrom-Json}
    if($manifest.Schema -ne 1 -or @($manifest.Files).Count -eq 0){throw '유효한 패치 목록이 없습니다.'}
    $desired=@{};$ids=@{};$packs=@{};$retired=@('cobblemon_client_defaults')
    # Only these project-owned packs can be replaced. Visual preference packs are excluded.
    $packNames=@('better-cobblemon-music-resourcepack','cobblemon-korean-translation-bundle','E19-Xaero-Icons','galmuri11-8px','RCT Trainers+ [1.7] v2.2','spawn-notification-ment')
    foreach($pack in @($manifest.ResourcePacks)){
        if($pack.Family -notin $packNames -or $pack.Path -notmatch '^resourcepacks/[^/]+\.zip$' -or -not ([IO.Path]::GetFileName($pack.Path).StartsWith($pack.Family,[StringComparison]::Ordinal))){throw '허용하지 않는 리소스팩입니다.'}
        if($packs.ContainsKey($pack.Family)){throw '리소스팩 계열이 중복됐습니다.'};$packs[$pack.Family]=$pack.Path
    }
    foreach($file in @($manifest.Files)){
        $relative=[string]$file.Path;$source=if($localMode){$file.Source}else{Local $package ('payload/'+$relative)};$null=Local $target $relative
        if(-not(Allowed $relative) -and $relative -notin @($packs.Values)){throw ('패치 범위 밖의 파일: '+$relative)}
        if($relative -match '(^|/)\.|(^|/)(README|MEMORY)\.md$' -or $desired.ContainsKey($relative)){throw '중복 또는 비공개 파일입니다.'}
        if($file.Sha256 -notmatch '^[a-fA-F0-9]{64}$' -or (Hash $source) -ne $file.Sha256.ToLowerInvariant() -or (Get-Item -LiteralPath $source).Length -ne $file.Size){throw ('패치 파일 검증 실패: '+$relative)}
        if($relative.StartsWith('mods/')){
            $meta=ModInfo $source
            if($meta.PSObject.Properties['environment'] -and $meta.environment -eq 'server'){throw '서버 전용 모드는 패치할 수 없습니다.'}
            if($ids.ContainsKey($meta.id)){throw ('중복된 모드 ID: '+$meta.id)};$ids[$meta.id]=$relative
        }
        $desired[$relative]=[pscustomobject]@{Path=$relative;Source=$source;Hash=$file.Sha256.ToLowerInvariant();Size=[long]$file.Size}
    }
    if(-not $ids.ContainsKey('cobblemon') -or -not $desired.ContainsKey('config/more-cobblemon-contents/wiki/index.html')){throw 'Cobblemon 또는 위키 홈이 빠진 패치입니다.'}
    foreach($packPath in $packs.Values){if(-not $desired.ContainsKey($packPath)){throw '리소스팩 파일이 빠졌습니다.'}}
    # Keep existing ZIP names so options.txt and the user's pack order stay byte-identical.
    if(Test-Path -LiteralPath (Local $target 'resourcepacks')){
        foreach($file in Get-ChildItem -LiteralPath (Local $target 'resourcepacks') -Filter '*.zip' -File){
            foreach($family in $packs.Keys){
                if($file.Name.StartsWith($family,[StringComparison]::Ordinal)){
                    $relative='resourcepacks/'+$file.Name;$null=Local $target $relative;$canonical=$desired[$packs[$family]]
                    $desired[$relative]=[pscustomobject]@{Path=$relative;Source=$canonical.Source;Hash=$canonical.Hash;Size=$canonical.Size}
                }
            }
        }
    }
    $deletes=@{};$existing=State $state
    foreach($file in Get-ChildItem -LiteralPath (Local $target 'mods') -Filter '*.jar' -File){
        $relative='mods/'+$file.Name;$null=Local $target $relative;$meta=ModInfo $file.FullName
        if(($ids.ContainsKey($meta.id) -and $ids[$meta.id] -ne $relative) -or $meta.id -in $retired){$deletes[$relative]=$true}
    }
    # Retire only files previously owned by this patcher, never arbitrary client files.
    foreach($path in @($existing.Managed)){
        if((Allowed $path) -or $path -in @($packs.Values)){
            if(-not $desired.ContainsKey($path) -and (Test-Path -LiteralPath (Local $target $path))){$deletes[$path]=$true}
        }
    }
    $operations=[Collections.Generic.List[object]]::new()
    foreach($file in @($desired.Values|Sort-Object Path)){
        $old=Hash (Local $target $file.Path)
        if($old -eq $file.Hash){continue}
        $operations.Add([pscustomobject]@{Path=$file.Path;Action=$(if($old){'update'}else{'add'});OldHash=$old;NewHash=$file.Hash;Size=$file.Size;Source=$file.Source})
    }
    foreach($path in @($deletes.Keys|Sort-Object)){
        if($desired.ContainsKey($path)){continue}
        $operations.Add([pscustomobject]@{Path=$path;Action='delete';OldHash=(Hash (Local $target $path));NewHash=$null;Size=0;Source=$null})
    }
    $fingerprint=@($operations|ForEach-Object {$_.Path+'|'+$_.Action+'|'+$_.OldHash+'|'+$_.NewHash}) -join "`n"
    $total=[long]0;foreach($op in $operations){$total+=$op.Size}
    $manifestHash=if($manifestPath){Hash $manifestPath}else{($manifest.Files|ForEach-Object {$_.Path+'|'+$_.Sha256}) -join "`n"}
    return [pscustomobject]@{PackageRoot=$package;SourceRoot=$package;WikiRoot=$WikiRoot;LocalMode=$localMode;TargetRoot=$target;BackupRoot=$state;Operations=@($operations.ToArray());Managed=@($desired.Keys|Sort-Object);Fingerprint=$fingerprint;ManifestHash=$manifestHash;TotalBytes=$total;Warnings=@();Mods=$manifest.Mods;ResourcePacks=$manifest.ResourcePacks}
}
function Install([string]$source,[string]$destination) {
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))|Out-Null
    $temp=$destination+'.patch-'+[guid]::NewGuid().ToString('N')
    try{
        [IO.File]::Copy($source,$temp,$false)
        if(Test-Path -LiteralPath $destination){[IO.File]::Replace($temp,$destination,[Management.Automation.Language.NullString]::Value)}else{[IO.File]::Move($temp,$destination)}
    }finally{if(Test-Path -LiteralPath $temp){[IO.File]::Delete($temp)}}
}
function RestoreJournal($journal,[string]$transaction,[string]$target,[switch]$CheckCurrent) {
    # Validate all files before restoring any of them.
    foreach($op in @($journal.Operations)){
        $destination=Local $target $op.Path
        if(-not(Allowed $op.Path) -and $op.Path -notmatch '^resourcepacks/(better-cobblemon-music-resourcepack|cobblemon-korean-translation-bundle|E19-Xaero-Icons|galmuri11-8px|RCT Trainers\+ \[1\.7\] v2\.2|spawn-notification-ment)[^/]*\.zip$'){throw '복구 기록에 허용하지 않는 경로가 있습니다.'}
        if($op.OldHash -and (Hash (Local $transaction ('backup/'+$op.Path))) -ne $op.OldHash){throw ('백업 검증 실패: '+$op.Path)}
        $current=Hash $destination
        if($CheckCurrent -and $current -ne $op.NewHash -and $current -ne $op.OldHash){throw ('패치 후 별도로 변경한 파일이 있습니다. 복구 중단: '+$op.Path)}
    }
    $journal.Status='restoring';Save (Local $transaction 'transaction.json') $journal
    foreach($op in @($journal.Operations)){
        $destination=Local $target $op.Path
        if($op.OldHash){Install (Local $transaction ('backup/'+$op.Path)) $destination}else{if(Test-Path -LiteralPath $destination){[IO.File]::Delete($destination)}}
        if((Hash $destination) -ne $op.OldHash){throw ('복구 파일 검증 실패: '+$op.Path)}
    }
    Save (Local ([IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($transaction))) 'current.json') $journal.PreviousState
    $journal.Status='restored';Save (Local $transaction 'transaction.json') $journal
}
function Invoke-ClientPatch {
    [CmdletBinding()]param([Parameter(Mandatory=$true)]$Plan,[switch]$SkipProcessCheck,[scriptblock]$ProgressCallback)
    $target=Root $Plan.TargetRoot;$state=StateRoot $target $Plan.BackupRoot
    [IO.Directory]::CreateDirectory($state)|Out-Null
    $mutex=[IO.File]::Open((Local $state 'patch.lock'),'OpenOrCreate','ReadWrite','None')
    try{
        if($Plan.LocalMode){$fresh=Get-ClientPatchPlan -SourceRoot $Plan.SourceRoot -WikiRoot $Plan.WikiRoot -TargetRoot $target -BackupRoot $state -SkipProcessCheck:$SkipProcessCheck}
        else{$fresh=Get-ClientPatchPlan $Plan.PackageRoot $target $state -SkipProcessCheck:$SkipProcessCheck}
        if($fresh.Fingerprint -ne $Plan.Fingerprint -or $fresh.ManifestHash -ne $Plan.ManifestHash){throw '검사 후 파일이 변경됐습니다. 변경 목록을 다시 검사하세요.'}
        if($fresh.Operations.Count -eq 0){return [pscustomobject]@{Status='unchanged';Changed=0}}
        $id=(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8)
        $transaction=Local $state ('history/'+$id);[IO.Directory]::CreateDirectory($transaction)|Out-Null
        $journal=[pscustomobject]@{Schema=1;TargetRoot=$target;Status='preparing';PreviousState=(State $state);Operations=$fresh.Operations}
        $current=0
        foreach($op in $fresh.Operations){
            if($op.OldHash){$backup=Local $transaction ('backup/'+$op.Path);[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($backup))|Out-Null;[IO.File]::Copy((Local $target $op.Path),$backup);if((Hash $backup) -ne $op.OldHash){throw '백업 중 파일이 변경됐습니다.'}}
            if($op.NewHash){$stage=Local $transaction ('stage/'+$op.Path);[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($stage))|Out-Null;[IO.File]::Copy($op.Source,$stage);if((Hash $stage) -ne $op.NewHash){throw '패치 원본이 변경됐습니다.'}}
            $current++;if($ProgressCallback){& $ProgressCallback 'stage' $current $fresh.Operations.Count $op.Path}
        }
        if(-not $SkipProcessCheck){AssertStopped $target}
        foreach($op in $fresh.Operations){if((Hash (Local $target $op.Path)) -ne $op.OldHash){throw '백업 중 대상 파일이 변경됐습니다.'}}
        $journal.Status='applying';Save (Local $transaction 'transaction.json') $journal
        try{
            $current=0
            foreach($op in $fresh.Operations){$destination=Local $target $op.Path;if($op.Action -eq 'delete'){[IO.File]::Delete($destination)}else{Install (Local $transaction ('stage/'+$op.Path)) $destination};if((Hash $destination) -ne $op.NewHash){throw ('패치 검증 실패: '+$op.Path)};$current++;if($ProgressCallback){& $ProgressCallback 'apply' $current $fresh.Operations.Count $op.Path}}
            Save (Local $state 'current.json') @{Schema=1;Managed=$fresh.Managed;LastTransaction=$id}
            $journal.Status='complete';Save (Local $transaction 'transaction.json') $journal
        }catch{
            $failure=$_.Exception.Message
            try{RestoreJournal $journal $transaction $target}catch{$journal.Status='recovery-needed';Save (Local $transaction 'transaction.json') $journal;throw ('패치 실패. 최근 패치 복구가 필요합니다: '+$failure+' / '+$_.Exception.Message)}
            throw ('패치 실패. 교체 파일은 자동 복구했습니다: '+$failure)
        }
        return [pscustomobject]@{Status='complete';Changed=$fresh.Operations.Count;BackupRoot=$transaction}
    }finally{$mutex.Dispose()}
}
function Restore-ClientPatch {
    [CmdletBinding()]param([Parameter(Mandatory=$true)][string]$TargetRoot,[string]$BackupRoot,[switch]$SkipProcessCheck)
    $target=Root $TargetRoot;$state=StateRoot $target $BackupRoot
    if(-not $SkipProcessCheck){AssertStopped $target}
    if(-not(Test-Path -LiteralPath $state)){throw '복구할 패치가 없습니다.'}
    $mutex=[IO.File]::Open((Local $state 'patch.lock'),'OpenOrCreate','ReadWrite','None')
    try{
        $last=(State $state).LastTransaction
        $pending=@(Get-ChildItem -LiteralPath (Local $state 'history') -Directory|Sort-Object Name -Descending|Where-Object { $file=Local $state ('history/'+$_.Name+'/transaction.json');(Test-Path -LiteralPath $file) -and (([IO.File]::ReadAllText($file)|ConvertFrom-Json).Status -in @('applying','restoring','recovery-needed')) })
        if($pending.Count){$last=$pending[0].Name}
        if(-not $last -or $last -notmatch '^\d{8}-\d{6}-[a-f0-9]{8}$'){throw '복구할 패치가 없습니다.'}
        $transaction=Local $state ('history/'+$last);$journal=[IO.File]::ReadAllText((Local $transaction 'transaction.json'))|ConvertFrom-Json
        if($journal.TargetRoot -ne $target){throw '다른 클라이언트의 백업입니다.'}
        RestoreJournal $journal $transaction $target -CheckCurrent
        return [pscustomobject]@{Status='restored';Changed=@($journal.Operations).Count}
    }finally{$mutex.Dispose()}
}
Export-ModuleMember -Function Get-ClientPatchPlan,Invoke-ClientPatch,Restore-ClientPatch
