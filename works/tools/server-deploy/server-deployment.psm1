Set-StrictMode -Version 2
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$script:Utf8 = [Text.UTF8Encoding]::new($false)

function Hash-Bytes([byte[]]$Bytes) {
    $sha=[Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-','').ToLowerInvariant() } finally {$sha.Dispose()}
}
function Hash-File([string]$Path) {
    if (-not [IO.File]::Exists($Path)) { return $null }
    $stream=[IO.File]::OpenRead($Path);$sha=[Security.Cryptography.SHA256]::Create()
    try{return ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}finally{$sha.Dispose();$stream.Dispose()}
}
function Canonical-Root([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path) -or $Path.StartsWith('\\')) { throw '로컬 서버 폴더를 선택해 주세요.' }
    $root=[IO.Path]::GetFullPath($Path).TrimEnd('\','/')
    if ($root.Length -lt 4 -or -not [IO.Directory]::Exists($root)) { throw '서버 폴더가 없거나 드라이브 루트입니다.' }
    $current=$root
    while ($current.Length -gt 3) {
        if ([IO.File]::GetAttributes($current) -band [IO.FileAttributes]::ReparsePoint) { throw '정션·심볼릭 링크 경로는 배포에 사용할 수 없습니다.' }
        $current=[IO.Path]::GetDirectoryName($current)
    }
    return $root
}
function Local-Path([string]$Root,[string]$Relative) {
    if ([string]::IsNullOrWhiteSpace($Relative) -or $Relative -match '(^[/\\]|(^|[/\\])\.\.([/\\]|$)|:)' ) { throw '안전하지 않은 상대 경로입니다.' }
    $path=[IO.Path]::GetFullPath((Join-Path $Root $Relative))
    if (-not $path.StartsWith($Root+'\',[StringComparison]::OrdinalIgnoreCase)) { throw '서버 밖으로 벗어나는 경로입니다.' }
    $current=$path
    while ($current.Length -gt $Root.Length) {
        if (([IO.File]::Exists($current) -or [IO.Directory]::Exists($current)) -and ([IO.File]::GetAttributes($current) -band [IO.FileAttributes]::ReparsePoint)) { throw '배포 경로에 정션·심볼릭 링크가 있습니다.' }
        $current=[IO.Path]::GetDirectoryName($current)
    }
    return $path
}
function State-Path([string]$Target,[string]$StateRoot) {
    if (-not $StateRoot) {
        $key=(Hash-Bytes $script:Utf8.GetBytes($Target.ToLowerInvariant())).Substring(0,16)
        $StateRoot=Join-Path $env:LOCALAPPDATA ('MinecraftServerDeploy/'+$key)
    }
    $path=[IO.Path]::GetFullPath($StateRoot).TrimEnd('\','/')
    if ($path.StartsWith('\\') -or $path.Equals($Target,[StringComparison]::OrdinalIgnoreCase) -or $path.StartsWith($Target+'\',[StringComparison]::OrdinalIgnoreCase) -or $Target.StartsWith($path+'\',[StringComparison]::OrdinalIgnoreCase)) { throw '백업 폴더는 서버 폴더와 분리해야 합니다.' }
    $current=$path
    while ($current.Length -gt 3) {
        if (([IO.File]::Exists($current) -or [IO.Directory]::Exists($current)) -and ([IO.File]::GetAttributes($current) -band [IO.FileAttributes]::ReparsePoint)) { throw '백업 경로에 정션·심볼릭 링크가 있습니다.' }
        $current=[IO.Path]::GetDirectoryName($current)
    }
    return $path
}
function Test-PrivatePath([string]$Path) {
    return $Path -match '(?i)(^|/)(\.archive-unpack|cache|caches|logs?|backups?|node_modules|\.git|playerdata|stats|advancements|sessions?|replays?)(/|$)|(^|/)(openrouter|jbro-policy-discord|jbro-policy-discord-status)\.json$|(^|/)metrics\.properties$|\.(md|map|log|bak|tmp|key|pem|secret|secrets)$|(^|/)(desktop\.ini|Thumbs\.db|manifest\.json|server-wiki\.bundle\.json|cobblemon-startup-hooks\.zip)$|(^|/)\.[^/]+$|(^|/)[^/]+-client\.[^/]+$'
}
function Test-Endpoint([string]$Path) {
    return $Path -in @('config/more-cobblemon-contents/wiki.json','config/bluemap/webserver.conf','config/voicechat/voicechat-server.properties')
}
function Test-PrivateContent([string]$Path) {
    if ([IO.Path]::GetExtension($Path) -notin @('.json','.json5','.toml','.properties','.conf','.cfg','.yml','.yaml')) { return $false }
    $text=[IO.File]::ReadAllText($Path)
    return $text -match '(?im)["\x27]?(botToken|api[_-]?key|apiKey|accessToken|webhookUrl|password|passwd|secret|private[_-]?key|authorization|token)["\x27]?\s*[:=]\s*(?:["\x27][^"\x27\r\n]+["\x27]|[^\s"\x27{},#\r\n][^\r\n]*)' -or $text -match '(?i)[A-Z]:[\\/]+Users[\\/]'
}
function Get-TreeFiles([string]$Root,[string]$Relative) {
    $path=Local-Path $Root $Relative
    if (-not [IO.Directory]::Exists($path)) { return }
    foreach ($item in ([IO.DirectoryInfo]::new($path).GetFileSystemInfos())) {
        $rel=$item.FullName.Substring($Root.Length+1).Replace('\','/')
        if (Test-PrivatePath $rel) { continue }
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw ('링크 파일 또는 폴더를 발견했습니다: '+$rel) }
        if ($item.Attributes -band [IO.FileAttributes]::Directory) { Get-TreeFiles $Root $rel } else { $item }
    }
}
function Get-WorldSnapshotFiles([string]$Root,[string]$Relative='world') {
    $path=Local-Path $Root $Relative
    if(-not [IO.Directory]::Exists($path)){return}
    foreach($item in ([IO.DirectoryInfo]::new($path).GetFileSystemInfos())){
        $rel=$item.FullName.Substring($Root.Length+1).Replace('\','/')
        if($rel -eq 'world/session.lock'){continue}
        if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw ('월드 백업에 링크 경로가 있습니다: '+$rel)}
        if($item.Attributes -band [IO.FileAttributes]::Directory){Get-WorldSnapshotFiles $Root $rel}
        else{[pscustomobject]@{Path=$rel;Source=$item.FullName;Size=$item.Length}}
    }
}
function Get-Properties([string]$Text) {
    $values=@{}
    foreach ($line in ($Text -split '\r?\n')) {
        if ($line -match '^\s*([^#!\s=]+)=(.*)$') {
            if ($values.ContainsKey($Matches[1])) { throw ('서버 설정 키가 중복됐습니다: '+$Matches[1]) }
            $values[$Matches[1]]=$Matches[2]
        }
    }
    return $values
}
function Get-PreservedProperties([string]$Source,[string]$Target) {
    $sourceValues=Get-Properties ([IO.File]::ReadAllText($Source))
    $targetText=[IO.File]::ReadAllText($Target)
    $targetValues=Get-Properties $targetText
    if (($sourceValues.ContainsKey('level-name') -and $sourceValues['level-name'] -ne 'world') -or ($targetValues.ContainsKey('level-name') -and $targetValues['level-name'] -ne 'world')) { throw '이 배포 도구는 level-name=world 서버만 지원합니다.' }
    # Existing server settings are operator-owned; preserve their exact bytes.
    return ,[IO.File]::ReadAllBytes($Target)
}
function Open-ServerLocks([string[]]$Roots,[switch]$Create) {
    $held=[Collections.Generic.List[object]]::new()
    try {
        foreach ($root in ($Roots|Sort-Object)) {
            $path=Local-Path $root 'world/session.lock'; $exists=[IO.File]::Exists($path)
            if (-not $exists -and -not $Create) { continue }
            if(-not $exists){[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path))|Out-Null}
            try { $stream=[IO.File]::Open($path,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None) }
            catch { throw ('서버가 실행 중이거나 월드 잠금을 얻을 수 없습니다: '+$root) }
            $held.Add([pscustomobject]@{Stream=$stream;Path=$path;Created=(-not $exists)})
        }
        return ,$held.ToArray()
    } catch { Close-ServerLocks $held.ToArray(); throw }
}
function Close-ServerLocks($Locks) {
    foreach($held in $Locks){$held.Stream.Dispose();if($held.Created){[IO.File]::Delete($held.Path)}}
}
function Read-State([string]$Root) {
    $path=Local-Path $Root 'current.json'
    if (-not [IO.File]::Exists($path)) { return @{Managed=@();LastTransaction=$null} }
    try {$state=[IO.File]::ReadAllText($path)|ConvertFrom-Json} catch { throw '배포 상태 기록을 읽을 수 없습니다. 백업 폴더를 확인해 주세요.' }
    if ($state.Schema -ne 1) { throw '지원하지 않는 배포 상태 버전입니다.' }
    return @{Managed=@($state.Managed);LastTransaction=$state.LastTransaction}
}
function Save-Json([string]$Path,$Value) {
    $text=($Value|ConvertTo-Json -Depth 12)+"`n"
    $temp=$Path+'.tmp'
    [IO.File]::WriteAllText($temp,$text,$script:Utf8)
    if([IO.File]::Exists($Path)){[IO.File]::Replace($temp,$Path,[System.Management.Automation.Language.NullString]::Value)}else{[IO.File]::Move($temp,$Path)}
}
function Assert-NoPending([string]$StateRoot) {
    $history=Local-Path $StateRoot 'history'
    if (-not [IO.Directory]::Exists($history)) { return }
    foreach ($dir in Get-ChildItem -LiteralPath $history -Directory) {
        $path=Local-Path $StateRoot ('history/'+$dir.Name+'/transaction.json')
        if([IO.File]::Exists($path)){
            try {$status=([IO.File]::ReadAllText($path)|ConvertFrom-Json).Status}catch{throw '읽을 수 없는 배포 복구 기록이 있습니다.'}
            if($status -in @('applying','restoring','recovery-needed')){throw '중단된 배포가 있습니다. 먼저 최근 배포 복구를 실행해 주세요.'}
        }
    }
}
function Add-Input($Map,[string]$Root,[string]$Relative,[byte[]]$Bytes=$null) {
    $path=Local-Path $Root $Relative
    $hash=Hash-File $path
    if($null -eq $hash){throw ('필수 입력이 없습니다: '+$Relative)}
    $Map[$Relative]=[pscustomobject]@{Path=$Relative;Source=$path;SourceHash=$hash;Bytes=$Bytes;Hash=$(if($null -ne $Bytes){Hash-Bytes $Bytes}else{$hash});Size=$(if($null -ne $Bytes){$Bytes.Length}else{(Get-Item -LiteralPath $path).Length})}
}
function Add-Generated($Map,[string]$Relative,[byte[]]$Bytes) {
    $Map[$Relative]=[pscustomobject]@{Path=$Relative;Source=$null;SourceHash=$null;Bytes=$Bytes;Hash=(Hash-Bytes $Bytes);Size=$Bytes.Length}
}
function Get-WikiBundle($Files) {
    $memory=[IO.MemoryStream]::new();$zip=[IO.Compression.ZipArchive]::new($memory,[IO.Compression.ZipArchiveMode]::Create,$true)
    try {
        $prefix='config/more-cobblemon-contents/wiki/'
        $count=0;$total=[long]0
        foreach($record in ($Files|Where-Object {$_.Path.StartsWith($prefix)}|Sort-Object Path)){
            $name=$record.Path.Substring($prefix.Length);$count++;$total+=$record.Size
            if(($name -cne 'index.html' -and -not $name.StartsWith('pages/') -and -not $name.StartsWith('assets/')) -or [IO.Path]::GetExtension($name).ToLowerInvariant() -notin @('.html','.js','.css','.woff2','.txt','.png','.svg','.webp','.ico','.jpg','.jpeg')){throw ('시작 훅이 지원하지 않는 위키 에셋입니다: '+$name)}
            if($count -gt 5000 -or $record.Size -gt 8MB -or $total -gt 32MB){throw '위키 에셋이 시작 훅의 안전한 압축 해제 한도를 넘었습니다.'}
            $entry=$zip.CreateEntry($name,[IO.Compression.CompressionLevel]::Optimal)
            $entry.LastWriteTime=[DateTimeOffset]::new(1980,1,1,0,0,0,[TimeSpan]::Zero)
            $input=[IO.File]::OpenRead($record.Source);$output=$entry.Open()
            try{$input.CopyTo($output)}finally{$input.Dispose();$output.Dispose()}
        }
    }finally{$zip.Dispose()}
    try{return ,$memory.ToArray()}finally{$memory.Dispose()}
}
function Test-ManagedPath([string]$Path) {
    if(Test-PrivatePath $Path){return $false}
    if(Test-Endpoint $Path){return $false}
    return $Path -match '^(mods/[^/]+\.jar|config/more-cobblemon-contents/wiki/|showdown/(cobbled-exports|data|lib|sim|translations)/|plaza-assets/|world/datapacks/|tools/server-startup-[^/]+\.(ps1|cs|jar)$|libraries/net/fabricmc/fabric-loader/[^/]+/fabric-loader-[^/]+\.jar$|fabric-server-mc\.[^/]+\.jar$|run\.bat$|startup-hooks\.json$|startup-assets/server-wiki\.zip$|showdown/(index\.js|MSDPatch\.json|showdown\.json|config/(formats|custom-formats)\.js)$)'
}
function Build-DeploymentPlan([string]$SourceRoot,[string]$TargetRoot,[string]$StateRoot) {
    Assert-NoPending $StateRoot
    if(-not [IO.File]::Exists((Local-Path $TargetRoot 'server.properties'))){throw '배포 대상에 server.properties가 없습니다. 배포 서버 폴더를 선택해 주세요.'}
    $packs=@(Get-TreeFiles $SourceRoot 'world/datapacks' | Where-Object {$_.Extension -eq '.zip' -or $_.Name -eq 'pack.mcmeta'})
    $plaza=@(Get-TreeFiles $SourceRoot 'world/dimensions/jbro_policy/plaza/region' | Where-Object {$_.Extension -eq '.mca'})
    if($packs.Count -eq 0 -or $plaza.Count -eq 0){throw '개발 월드의 데이터팩 또는 광장 청크가 없습니다. 삭제 사고를 막기 위해 배포를 중단했습니다.'}
    $existingPlaza=[IO.Directory]::Exists((Local-Path $TargetRoot 'world/dimensions/jbro_policy/plaza'))
    $desired=@{};$warnings=[Collections.Generic.List[string]]::new();$protected=@{};$ids=@{}
    $mods=@(Get-ChildItem -LiteralPath (Local-Path $SourceRoot 'mods') -Filter '*.jar' -File)
    if($mods.Count -eq 0){throw '개발 서버의 모드 폴더가 비었습니다.'}
    foreach($mod in $mods){
        $relative='mods/'+$mod.Name;$null=Local-Path $SourceRoot $relative
        try{
            $zip=[IO.Compression.ZipFile]::OpenRead($mod.FullName)
            try{$entry=$zip.GetEntry('fabric.mod.json');if($null -eq $entry){throw 'metadata'};$reader=[IO.StreamReader]::new($entry.Open());try{$meta=$reader.ReadToEnd()|ConvertFrom-Json}finally{$reader.Dispose()}}finally{$zip.Dispose()}
        }catch{throw ('손상된 모드 또는 Git LFS 포인터입니다: '+$mod.Name)}
        if(-not $meta.PSObject.Properties['id'] -or -not $meta.id){throw ('모드 ID가 없습니다: '+$mod.Name)}
        if($meta.PSObject.Properties['environment'] -and $meta.environment -eq 'client'){$warnings.Add('제외: 클라이언트 전용 '+$mod.Name);continue}
        if($ids.ContainsKey($meta.id)){throw ('모드 ID가 중복됐습니다: '+$meta.id)}
        $ids[$meta.id]=$true;Add-Input $desired $SourceRoot $relative
    }
    if(-not $ids.ContainsKey('cobblemon')){throw '개발 서버에서 Cobblemon 모드를 찾을 수 없습니다.'}
    $launchers=@(Get-ChildItem -LiteralPath $SourceRoot -Filter 'fabric-server-mc.*-launcher.*.jar' -File)
    if($launchers.Count -ne 1){throw 'Fabric 서버 런처는 정확히 한 개여야 합니다.'}
    try{
        $zip=[IO.Compression.ZipFile]::OpenRead($launchers[0].FullName)
        try{$reader=[IO.StreamReader]::new($zip.GetEntry('install.properties').Open());try{$install=Get-Properties $reader.ReadToEnd()}finally{$reader.Dispose()}}finally{$zip.Dispose()}
    }catch{throw 'Fabric 런처의 버전 정보를 읽을 수 없습니다.'}
    if(-not $install.ContainsKey('fabric-loader-version') -or $install['fabric-loader-version'] -notmatch '^[0-9.]+$'){throw 'Fabric Loader 버전이 유효하지 않습니다.'}
    if(-not $install.ContainsKey('game-version') -or $install['game-version'] -ne '1.21.1'){throw 'Minecraft 1.21.1 기능 업데이트만 지원합니다. 월드 버전 변환은 자동으로 실행하지 않습니다.'}
    $targetLaunchers=@(Get-ChildItem -LiteralPath $TargetRoot -Filter 'fabric-server-mc.*-launcher.*.jar' -File)
    if($targetLaunchers.Count -gt 1 -or ($targetLaunchers.Count -eq 1 -and $targetLaunchers[0].Name -ne $launchers[0].Name)){throw '기본 서버 런처 버전이 다릅니다. Minecraft·Fabric 버전 변경은 별도로 진행해 주세요.'}
    Add-Input $desired $SourceRoot $launchers[0].Name
    $loader=$install['fabric-loader-version'];Add-Input $desired $SourceRoot ('libraries/net/fabricmc/fabric-loader/'+$loader+'/fabric-loader-'+$loader+'.jar')
    foreach($root in @('config','defaultconfigs','plaza-assets','world/datapacks','world/dimensions/jbro_policy/plaza')){
        if($root -eq 'world/dimensions/jbro_policy/plaza' -and $existingPlaza){continue}
        foreach($file in @(Get-TreeFiles $SourceRoot $root)){
            $relative=$file.FullName.Substring($SourceRoot.Length+1).Replace('\','/');$extension=$file.Extension.ToLowerInvariant()
            if($root -in @('config','defaultconfigs') -and $extension -notin @('.json','.json5','.toml','.properties','.txt','.yaml','.yml','.conf','.cfg','.html','.css','.js','.png','.jpg','.jpeg','.webp','.svg','.ico','.ogg','.ttf','.woff','.woff2')){continue}
            if($root -eq 'plaza-assets' -and $extension -notin @('.schem','.nbt','.png','.jpg','.jpeg','.webp')){continue}
            if($root -eq 'world/dimensions/jbro_policy/plaza' -and $extension -notin @('.mca','.mcc','.dat','.dat_old')){continue}
            $target=Local-Path $TargetRoot $relative
            if($root -in @('config','defaultconfigs') -and -not $relative.StartsWith('config/more-cobblemon-contents/wiki/') -and [IO.File]::Exists($target)){$protected[$relative]=$true;continue}
            if((Test-Endpoint $relative) -and [IO.File]::Exists($target)){$protected[$relative]=$true;continue}
            if(($root -in @('config','defaultconfigs')) -and ((Test-PrivateContent $file.FullName) -or ([IO.File]::Exists($target) -and (Test-PrivateContent $target)))){$protected[$relative]=$true;$warnings.Add('보존/제외: 민감한 설정 '+$relative);continue}
            Add-Input $desired $SourceRoot $relative
        }
    }
    foreach($name in @('cobbled-exports','data','lib','sim','translations')){foreach($file in @(Get-TreeFiles $SourceRoot ('showdown/'+$name))){Add-Input $desired $SourceRoot ($file.FullName.Substring($SourceRoot.Length+1).Replace('\','/'))}}
    foreach($relative in @('showdown/index.js','showdown/MSDPatch.json','showdown/showdown.json','showdown/config/formats.js','showdown/config/custom-formats.js')){if([IO.File]::Exists((Local-Path $SourceRoot $relative))){Add-Input $desired $SourceRoot $relative}}
    $biome='world/data/jbro_policy_plaza_biome.dat'
    if(-not $existingPlaza -and -not [IO.File]::Exists((Local-Path $TargetRoot $biome)) -and [IO.File]::Exists((Local-Path $SourceRoot $biome))){Add-Input $desired $SourceRoot $biome}
    foreach($file in @(Get-ChildItem -LiteralPath (Local-Path $SourceRoot 'tools') -File -ErrorAction SilentlyContinue)){if($file.Name -match '^server-startup-[^/]+\.(ps1|cs|jar)$'){Add-Input $desired $SourceRoot ('tools/'+$file.Name)}}
    Add-Input $desired $SourceRoot 'server.properties' (Get-PreservedProperties (Local-Path $SourceRoot 'server.properties') (Local-Path $TargetRoot 'server.properties'))
    if([IO.File]::Exists((Local-Path $TargetRoot 'run.bat'))){Add-Generated $desired 'run.bat' ([IO.File]::ReadAllBytes((Local-Path $TargetRoot 'run.bat')))}
    else{
        $run=[IO.File]::ReadAllText((Local-Path $SourceRoot 'run.bat'))
        $run=[regex]::Replace($run,'\s+--port\s+[0-9]+(?=\s+nogui)','')
        Add-Input $desired $SourceRoot 'run.bat' $script:Utf8.GetBytes($run)
    }
    if(-not $desired.ContainsKey('config/more-cobblemon-contents/wiki/index.html')){throw '개발 서버 위키의 index.html이 없습니다.'}
    $bundle=Get-WikiBundle @($desired.Values);Add-Generated $desired 'startup-assets/server-wiki.zip' $bundle
    $hooksPath=Local-Path $TargetRoot 'startup-hooks.json'
    if(-not [IO.File]::Exists($hooksPath)){$hooksPath=Local-Path $SourceRoot 'startup-hooks.json'}
    $hooks=[IO.File]::ReadAllText($hooksPath)
    $pattern='(?s)("wiki_files"\s*:\s*\{.*?"sha256"\s*:\s*")[0-9a-fA-F]{64}(")'
    if([regex]::Matches($hooks,$pattern).Count -ne 1){throw '시작 훅에 위키 SHA-256 설정이 정확히 한 개 있어야 합니다.'}
    $bundleHash=Hash-Bytes $bundle
    $replacement={param($match) $match.Groups[1].Value+$bundleHash+$match.Groups[2].Value}.GetNewClosure()
    $hooks=[regex]::Replace($hooks,$pattern,[Text.RegularExpressions.MatchEvaluator]$replacement)
    Add-Generated $desired 'startup-hooks.json' $script:Utf8.GetBytes($hooks)
    $state=Read-State $StateRoot;$managed=@{}
    foreach($path in $state.Managed){if(Test-ManagedPath $path){$managed[$path]=$true}}
    if([IO.Directory]::Exists((Join-Path $TargetRoot '.git'))){
        $tracked=@(& git -C $TargetRoot -c core.quotePath=false ls-files 2>$null)
        if($LASTEXITCODE -ne 0){throw '배포 저장소 파일 목록을 읽을 수 없습니다.'}
        foreach($path in $tracked){if(Test-ManagedPath $path){$managed[$path]=$true}}
    }
    foreach($root in @('mods','config/more-cobblemon-contents/wiki','showdown','plaza-assets','world/datapacks','world/dimensions/jbro_policy/plaza')){
        foreach($file in @(Get-TreeFiles $TargetRoot $root)){$path=$file.FullName.Substring($TargetRoot.Length+1).Replace('\','/');if(Test-ManagedPath $path){$managed[$path]=$true}}
    }
    $operations=[Collections.Generic.List[object]]::new();$fingerprint=[Collections.Generic.List[string]]::new()
    foreach($path in ($desired.Keys|Sort-Object)){
        $record=$desired[$path];$oldHash=Hash-File (Local-Path $TargetRoot $path)
        $fingerprint.Add($path+'|'+$record.SourceHash+'|'+$record.Hash+'|'+$oldHash)
        if($oldHash -ne $record.Hash){$operations.Add([pscustomobject]@{Path=$path;Action=$(if($null -eq $oldHash){'add'}else{'update'});OldHash=$oldHash;NewHash=$record.Hash;Size=$record.Size;Input=$record})}
    }
    foreach($path in ($managed.Keys|Sort-Object)){
        if($desired.ContainsKey($path) -or $protected.ContainsKey($path) -or (Test-Endpoint $path)){continue}
        $file=Local-Path $TargetRoot $path
        if(-not [IO.File]::Exists($file)){continue}
        if($path -match '^(config|defaultconfigs)/' -and (Test-PrivateContent $file)){$warnings.Add('보존: 민감한 설정 '+$path);continue}
        $old=Hash-File $file;$fingerprint.Add('delete|'+$path+'|'+$old)
        $operations.Add([pscustomobject]@{Path=$path;Action='delete';OldHash=$old;NewHash=$null;Size=0;Input=$null})
    }
    return [pscustomobject]@{SourceRoot=$SourceRoot;TargetRoot=$TargetRoot;StateRoot=$StateRoot;Operations=@($operations.ToArray());Desired=@($desired.Values);Warnings=@($warnings.ToArray());Token=(Hash-Bytes $script:Utf8.GetBytes(($fingerprint -join "`n")));TotalBytes=$( $sum=[long]0; foreach($operation in $operations){$sum+=$operation.Size}; $sum )}
}

function Get-ServerDeploymentPlan {
    [CmdletBinding()]param([Parameter(Mandatory=$true)][string]$SourceRoot,[Parameter(Mandatory=$true)][string]$TargetRoot,[string]$StateRoot)
    $source=Canonical-Root $SourceRoot;$target=Canonical-Root $TargetRoot
    if($source.Equals($target,[StringComparison]::OrdinalIgnoreCase) -or $source.StartsWith($target+'\',[StringComparison]::OrdinalIgnoreCase) -or $target.StartsWith($source+'\',[StringComparison]::OrdinalIgnoreCase)){throw '개발 서버와 배포 서버는 서로 분리된 폴더여야 합니다.'}
    $state=State-Path $target $StateRoot
    if($state.Equals($source,[StringComparison]::OrdinalIgnoreCase) -or $state.StartsWith($source+'\',[StringComparison]::OrdinalIgnoreCase) -or $source.StartsWith($state+'\',[StringComparison]::OrdinalIgnoreCase)){throw '백업 폴더는 개발 서버 밖에 있어야 합니다.'}
    $locks=Open-ServerLocks @($source,$target)
    try{return Build-DeploymentPlan $source $target $state}finally{Close-ServerLocks $locks}
}
function Assert-FreeSpace($Plan,[long]$WorldBytes=0) {
    $backup=[long]0
    foreach($op in $Plan.Operations){if($null -ne $op.OldHash){$backup+=(Get-Item -LiteralPath (Local-Path $Plan.TargetRoot $op.Path)).Length}}
    $targetDrive=[IO.DriveInfo]::new([IO.Path]::GetPathRoot($Plan.TargetRoot));$stateDrive=[IO.DriveInfo]::new([IO.Path]::GetPathRoot($Plan.StateRoot))
    $targetNeed=[long]$Plan.TotalBytes+8MB;$stateNeed=[long]$Plan.TotalBytes+$backup+$WorldBytes+8MB
    if($targetDrive.Name -eq $stateDrive.Name){if($targetDrive.AvailableFreeSpace -lt ($targetNeed+$stateNeed)){throw '배포와 복구 백업을 만들 디스크 공간이 부족합니다.'}}
    elseif($targetDrive.AvailableFreeSpace -lt $targetNeed -or $stateDrive.AvailableFreeSpace -lt $stateNeed){throw '배포 또는 백업 디스크 공간이 부족합니다.'}
}
function Install-File([string]$From,[string]$To,[string]$TransactionId) {
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($To))|Out-Null
    if($TransactionId -notmatch '^\d{8}-\d{6}-[0-9a-f]{8}$'){throw '복구 작업 ID가 유효하지 않습니다.'}
    $temp=$To+'.deploy-'+$TransactionId+'.tmp'
    try{[IO.File]::Copy($From,$temp,$false);if([IO.File]::Exists($To)){[IO.File]::Replace($temp,$To,[System.Management.Automation.Language.NullString]::Value)}else{[IO.File]::Move($temp,$To)}}finally{if([IO.File]::Exists($temp)){[IO.File]::Delete($temp)}}
}
function Assert-Recoverable($Journal,[string]$TransactionRoot,[string]$TargetRoot) {
    if($Journal.Schema -ne 1){throw '지원하지 않는 복구 기록 버전입니다.'}
    $id=[IO.Path]::GetFileName($TransactionRoot)
    if($id -notmatch '^\d{8}-\d{6}-[0-9a-f]{8}$'){throw '복구 기록 폴더 이름이 유효하지 않습니다.'}
    # Preflight every file before restoring any: never erase changes made after our write.
    foreach($op in $Journal.Operations){
        $current=Hash-File (Local-Path $TargetRoot $op.Path)
        if($current -ne $op.OldHash -and $current -ne $op.NewHash){throw ('배포 후 다른 변경이 있어 복구를 중단했습니다: '+$op.Path)}
        if($null -ne $op.OldHash -and (Hash-File (Local-Path $TransactionRoot ('backup/'+$op.Path))) -ne $op.OldHash){throw ('원본 백업이 없거나 손상됐습니다: '+$op.Path)}
    }
}
function Recover-Transaction($Journal,[string]$TransactionRoot,[string]$TargetRoot) {
    Assert-Recoverable $Journal $TransactionRoot $TargetRoot
    $id=[IO.Path]::GetFileName($TransactionRoot)
    # A process crash can leave a partially copied atomic-replacement file.
    foreach($op in $Journal.Operations){$temp=Local-Path $TargetRoot ($op.Path+'.deploy-'+$id+'.tmp');if([IO.File]::Exists($temp)){[IO.File]::Delete($temp)}}
    $reversed=@($Journal.Operations);[array]::Reverse($reversed)
    foreach($op in $reversed){
        $path=Local-Path $TargetRoot $op.Path;$current=Hash-File $path
        if($current -eq $op.OldHash){continue}
        if($null -ne $op.OldHash){Install-File (Local-Path $TransactionRoot ('backup/'+$op.Path)) $path $id}else{[IO.File]::Delete($path)}
        if((Hash-File $path) -ne $op.OldHash){throw ('원본 복구 검증에 실패했습니다: '+$op.Path)}
    }
}
function Invoke-ServerDeployment {
    [CmdletBinding()]param([Parameter(Mandatory=$true)]$Plan,[scriptblock]$ProgressCallback)
    # Re-resolve caller-controlled objects and compute a fresh plan under both world locks.
    $source=Canonical-Root $Plan.SourceRoot;$target=Canonical-Root $Plan.TargetRoot;$state=State-Path $target $Plan.StateRoot
    if($source.Equals($target,[StringComparison]::OrdinalIgnoreCase) -or $source.StartsWith($target+'\',[StringComparison]::OrdinalIgnoreCase) -or $target.StartsWith($source+'\',[StringComparison]::OrdinalIgnoreCase)){throw '개발 서버와 배포 서버 경로가 겹칩니다.'}
    if($state.Equals($source,[StringComparison]::OrdinalIgnoreCase) -or $state.StartsWith($source+'\',[StringComparison]::OrdinalIgnoreCase) -or $source.StartsWith($state+'\',[StringComparison]::OrdinalIgnoreCase)){throw '백업 폴더는 개발 서버 밖에 있어야 합니다.'}
    $mutex=[Threading.Mutex]::new($false,('Local\MinecraftServerDeploy.'+(Hash-Bytes $script:Utf8.GetBytes($target.ToLowerInvariant())).Substring(0,24)))
    $acquired=$false;$locks=@();$journal=$null;$transaction=$null
    try {
        try{$acquired=$mutex.WaitOne(0)}catch [Threading.AbandonedMutexException]{$acquired=$true}
        if(-not $acquired){throw '이 서버에 다른 배포 또는 복구가 실행 중입니다.'}
        $locks=Open-ServerLocks @($source,$target) -Create
        $fresh=Build-DeploymentPlan $source $target $state
        if($fresh.Token -ne $Plan.Token){throw '미리보기 뒤 파일이 변경됐습니다. 변경 목록을 다시 확인해 주세요.'}
        if($fresh.Operations.Count -eq 0){return [pscustomobject]@{Changed=0;BackupRoot=$null;Status='unchanged'}}
        $worldFiles=@(Get-WorldSnapshotFiles $target);$worldBytes=[long]0;foreach($file in $worldFiles){$worldBytes+=$file.Size}
        Assert-FreeSpace $fresh $worldBytes
        $id=(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8)
        $transaction=Local-Path $state ('history/'+$id)
        [IO.Directory]::CreateDirectory($transaction)|Out-Null
        $worldSnapshot=[Collections.Generic.List[object]]::new();$worldIndex=0
        foreach($file in $worldFiles){
            $worldIndex++;if($ProgressCallback){& $ProgressCallback 'data' $worldIndex $worldFiles.Count $file.Path|Out-Null}
            $hash=Hash-File (Local-Path $target $file.Path)
            $snapshot=Local-Path $transaction ('world-snapshot/'+$file.Path)
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($snapshot))|Out-Null
            [IO.File]::Copy($file.Source,$snapshot,$false)
            if((Hash-File $snapshot) -ne $hash){throw ('기존 월드 백업 검증에 실패했습니다: '+$file.Path)}
            $worldSnapshot.Add([pscustomobject]@{Path=$file.Path;Hash=$hash})
        }
        $index=0
        foreach($op in $fresh.Operations){
            $index++;if($ProgressCallback){& $ProgressCallback 'stage' $index $fresh.Operations.Count $op.Path|Out-Null}
            if($null -ne $op.NewHash){
                $staged=Local-Path $transaction ('staged/'+$op.Path);[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($staged))|Out-Null
                if($null -ne $op.Input.Bytes){[IO.File]::WriteAllBytes($staged,$op.Input.Bytes)}else{[IO.File]::Copy($op.Input.Source,$staged,$false)}
                if((Hash-File $staged) -ne $op.NewHash){throw ('복사 중 입력 파일이 변경됐습니다: '+$op.Path)}
            }
            $old=Local-Path $target $op.Path
            if((Hash-File $old) -ne $op.OldHash){throw ('배포 서버 파일이 변경됐습니다: '+$op.Path)}
            if($null -ne $op.OldHash){$backup=Local-Path $transaction ('backup/'+$op.Path);[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($backup))|Out-Null;[IO.File]::Copy($old,$backup,$false);if((Hash-File $backup) -ne $op.OldHash){throw ('백업 검증에 실패했습니다: '+$op.Path)}}
        }
        foreach($input in $fresh.Desired){if($null -ne $input.Source -and (Hash-File $input.Source) -ne $input.SourceHash){throw ('개발 파일이 변경됐습니다: '+$input.Path)}}
        $lastCheck=Build-DeploymentPlan $source $target $state
        if($lastCheck.Token -ne $fresh.Token){throw '준비 중 서버 파일 목록이 바뀌었습니다. 다시 검사해 주세요.'}
        $previous=Read-State $state
        $journal=[ordered]@{Schema=1;TargetRoot=$target;Status='applying';PreviousState=$previous;WorldSnapshot=@($worldSnapshot.ToArray());Operations=@($fresh.Operations|Select-Object Path,Action,OldHash,NewHash);Applied=0}
        Save-Json (Local-Path $transaction 'transaction.json') $journal
        $index=0
        foreach($op in $fresh.Operations){
            $path=Local-Path $target $op.Path
            if((Hash-File $path) -ne $op.OldHash){throw ('배포 중 대상 파일이 변경됐습니다: '+$op.Path)}
            if($null -ne $op.NewHash){Install-File (Local-Path $transaction ('staged/'+$op.Path)) $path $id}else{[IO.File]::Delete($path)}
            if((Hash-File $path) -ne $op.NewHash){throw ('설치 파일 검증에 실패했습니다: '+$op.Path)}
            $index++;$journal.Applied=$index;Save-Json (Local-Path $transaction 'transaction.json') $journal
            if($ProgressCallback){& $ProgressCallback 'apply' $index $fresh.Operations.Count $op.Path|Out-Null}
        }
        Save-Json (Local-Path $state 'current.json') ([ordered]@{Schema=1;Managed=@($fresh.Desired.Path|Sort-Object);LastTransaction=$id})
        $journal.Status='completed';Save-Json (Local-Path $transaction 'transaction.json') $journal
        return [pscustomobject]@{Changed=$fresh.Operations.Count;BackupRoot=$transaction;Status='completed'}
    } catch {
        $reason=$_.Exception.Message
        if($null -ne $journal){
            try{Recover-Transaction $journal $transaction $target;Save-Json (Local-Path $state 'current.json') ([ordered]@{Schema=1;Managed=@($journal.PreviousState.Managed);LastTransaction=$journal.PreviousState.LastTransaction});$journal.Status='rolled-back';Save-Json (Local-Path $transaction 'transaction.json') $journal}
            catch{$journal.Status='recovery-needed';Save-Json (Local-Path $transaction 'transaction.json') $journal;throw ('배포 실패 후 일부 복구가 막혔습니다. 백업: '+$transaction+' / '+$_.Exception.Message)}
            throw ('배포 실패. 변경한 파일은 원래대로 복구했습니다. '+$reason)
        }
        throw $reason
    } finally {Close-ServerLocks $locks;if($acquired){$mutex.ReleaseMutex()};$mutex.Dispose()}
}
function Restore-ServerDeployment {
    [CmdletBinding()]param([Parameter(Mandatory=$true)][string]$TargetRoot,[string]$StateRoot)
    $target=Canonical-Root $TargetRoot;$state=State-Path $target $StateRoot
    $mutex=[Threading.Mutex]::new($false,('Local\MinecraftServerDeploy.'+(Hash-Bytes $script:Utf8.GetBytes($target.ToLowerInvariant())).Substring(0,24)))
    $acquired=$false;$locks=@()
    try{
        try{$acquired=$mutex.WaitOne(0)}catch [Threading.AbandonedMutexException]{$acquired=$true}
        if(-not $acquired){throw '다른 배포 또는 복구가 실행 중입니다.'}
        $locks=Open-ServerLocks @($target) -Create
        $history=Local-Path $state 'history';$candidate=$null;$completed=@{}
        if([IO.Directory]::Exists($history)){
            foreach($dir in (Get-ChildItem -LiteralPath $history -Directory|Sort-Object Name -Descending)){
                $path=Local-Path $state ('history/'+$dir.Name+'/transaction.json');if(-not [IO.File]::Exists($path)){continue}
                $record=[IO.File]::ReadAllText($path)|ConvertFrom-Json
                if($record.Status -in @('applying','restoring','recovery-needed')){$candidate=@{Root=$dir.FullName;Journal=$record};break}
                if($record.Status -eq 'completed'){$completed[$dir.Name]=@{Root=$dir.FullName;Journal=$record}}
            }
        }
        if($null -eq $candidate){$current=Read-State $state;if($current.LastTransaction -and $completed.ContainsKey($current.LastTransaction)){$candidate=$completed[$current.LastTransaction]}}
        if($null -eq $candidate){throw '복구할 배포 기록이 없습니다.'}
        if($candidate.Journal.TargetRoot -ne $target){throw '백업 대상 서버가 현재 서버와 다릅니다.'}
        Assert-Recoverable $candidate.Journal $candidate.Root $target
        $candidate.Journal.Status='restoring';Save-Json (Local-Path $candidate.Root 'transaction.json') $candidate.Journal
        Recover-Transaction $candidate.Journal $candidate.Root $target
        Save-Json (Local-Path $state 'current.json') ([ordered]@{Schema=1;Managed=@($candidate.Journal.PreviousState.Managed);LastTransaction=$candidate.Journal.PreviousState.LastTransaction})
        $candidate.Journal.Status='rolled-back';Save-Json (Local-Path $candidate.Root 'transaction.json') $candidate.Journal
        return [pscustomobject]@{Status='restored';BackupRoot=$candidate.Root}
    }finally{Close-ServerLocks $locks;if($acquired){$mutex.ReleaseMutex()};$mutex.Dispose()}
}
Export-ModuleMember -Function Get-ServerDeploymentPlan,Invoke-ServerDeployment,Restore-ServerDeployment
