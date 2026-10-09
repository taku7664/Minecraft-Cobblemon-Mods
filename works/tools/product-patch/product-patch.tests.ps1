param([string]$RepositoryRoot=(Join-Path $env:USERPROFILE 'Documents/GitHub/Cobblemon-Mods'))
$ErrorActionPreference='Stop'
$testRepo=[IO.Path]::GetFullPath($RepositoryRoot).TrimEnd('\','/')
. (Join-Path $PSScriptRoot 'patch-products.ps1') -LibraryOnly
$repo=$testRepo
# A BAT launcher passes develop-product\.., not a canonical repository path.
$window=New-ProductPatchWindow (Join-Path $repo 'develop-product/..') 'Client' -QuietErrors
try{
    $expected=Join-Path ([IO.Path]::GetDirectoryName($repo)) 'MinecraftPPakemonServer'
    $targets=@($window.Server.Form.Controls|Where-Object {$_.GetType().Name -eq 'TextBox' -and $_.Text -eq $expected})
    if($targets.Count -ne 1){throw 'BAT relative repository path selected the wrong production server'}
    if($window.Tabs.TabPages.Count -ne 2 -or $window.Tabs.SelectedIndex -ne 1){throw 'Server/client tabs missing'}
    if($window.Server.Deploy.Enabled -or $window.Client.Deploy.Enabled){throw 'Apply enabled before preview'}
    Write-Host 'PASS: BAT relative root, server/client tabs, apply disabled until preview'
}finally{$window.Form.Dispose()}
