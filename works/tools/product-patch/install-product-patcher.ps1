param([string]$RepositoryRoot=([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))),[switch]$MigrateLegacy)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath($RepositoryRoot).TrimEnd('\','/')
if(-not(Test-Path -LiteralPath (Join-Path $repo 'AGENTS.md'))){throw '개발 저장소 루트를 지정해 주세요.'}
$product=Join-Path $repo 'develop-product'
$runtime=Join-Path $product 'tools/patcher'
$inputs=@{
    'patch-products.ps1'=(Join-Path $PSScriptRoot 'patch-products.ps1')
    'client/patch-client.ps1'=(Join-Path $repo 'works/tools/client-patch/patch-client.ps1')
    'client/client-patch.psm1'=(Join-Path $repo 'works/tools/client-patch/client-patch.psm1')
    'server/deploy-server.ps1'=(Join-Path $repo 'works/tools/server-deploy/deploy-server.ps1')
    'server/server-deployment.psm1'=(Join-Path $repo 'works/tools/server-deploy/server-deployment.psm1')
}
# Missing local-only server sources must be discovered before any installed file changes.
foreach($source in $inputs.Values){if(-not(Test-Path -LiteralPath $source -PathType Leaf)){throw ('패쳐 원본이 없습니다: '+$source)}}
foreach($relative in $inputs.Keys){
    $target=Join-Path $runtime $relative
    $cursor=$target
    while($cursor.Length -gt $repo.Length){
        if((Test-Path -LiteralPath $cursor) -and ([IO.File]::GetAttributes($cursor) -band [IO.FileAttributes]::ReparsePoint)){throw '패쳐 설치 경로에 링크가 있습니다.'}
        $cursor=[IO.Path]::GetDirectoryName($cursor)
    }
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target))|Out-Null
    [IO.File]::Copy($inputs[$relative],$target,$true)
    if((Get-FileHash -LiteralPath $inputs[$relative]).Hash -ne (Get-FileHash -LiteralPath $target).Hash){throw '패쳐 설치본의 해시가 다릅니다.'}
}
$launcher=Join-Path $product 'patch-products.bat'
$batch="@echo off`r`nsetlocal`r`npowershell.exe -NoProfile -STA -ExecutionPolicy Bypass -File `"%~dp0tools\patcher\patch-products.ps1`" -RepositoryRoot `"%~dp0..`"`r`nif errorlevel 1 pause`r`n"
[IO.File]::WriteAllText($launcher,$batch,[Text.ASCIIEncoding]::new())
if($MigrateLegacy){
    $server=[IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetDirectoryName($repo)) 'MinecraftPPakemonServer'))
    $legacy=@('tools/deploy-server.ps1','tools/server-deployment.psm1')
    $archive=Join-Path $env:LOCALAPPDATA ('MinecraftProductPatcher/legacy/'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8))
    foreach($relative in $legacy){
        $old=[IO.Path]::GetFullPath((Join-Path $server $relative))
        $new=Join-Path $runtime ('server/'+[IO.Path]::GetFileName($old))
        if(-not(Test-Path -LiteralPath $old)){continue}
        if(-not $old.StartsWith($server+'\',[StringComparison]::OrdinalIgnoreCase) -or ([IO.File]::GetAttributes($old) -band [IO.FileAttributes]::ReparsePoint)){throw '이전 패쳐 경로를 확인할 수 없습니다.'}
        # Ignore encoding/line-ending changes, but preserve actual local code divergence.
        if(([IO.File]::ReadAllLines($old) -join "`n") -ne ([IO.File]::ReadAllLines($new) -join "`n")){Write-Warning ('다른 구현의 이전 패쳐를 보존했습니다: '+$relative);continue}
        [IO.Directory]::CreateDirectory($archive)|Out-Null
        $backup=Join-Path $archive ([IO.Path]::GetFileName($old));[IO.File]::Copy($old,$backup,$false)
        if((Get-FileHash -LiteralPath $old).Hash -ne (Get-FileHash -LiteralPath $backup).Hash){throw '이전 패쳐 백업 검증 실패'}
        Remove-Item -LiteralPath $old -Force
    }
    $oldLauncher=Join-Path $server 'deploy-from-dev.bat'
    if(Test-Path -LiteralPath $oldLauncher){
        if([IO.File]::GetAttributes($oldLauncher) -band [IO.FileAttributes]::ReparsePoint){throw '이전 실행 파일이 링크입니다.'}
        [IO.Directory]::CreateDirectory($archive)|Out-Null
        [IO.File]::Copy($oldLauncher,(Join-Path $archive 'deploy-from-dev.bat'),$false)
        $redirect="@echo off`r`nsetlocal`r`npowershell.exe -NoProfile -STA -ExecutionPolicy Bypass -File `"%~dp0..\Cobblemon-Mods\develop-product\tools\patcher\patch-products.ps1`" -RepositoryRoot `"%~dp0..\Cobblemon-Mods`" -Tab Server`r`nif errorlevel 1 pause`r`n"
        [IO.File]::WriteAllText($oldLauncher,$redirect,[Text.ASCIIEncoding]::new())
    }
}
Write-Output $launcher
