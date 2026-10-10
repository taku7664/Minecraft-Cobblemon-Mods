param(
    [string]$InstallRoot = (Join-Path $env:LOCALAPPDATA 'PPakemonVpsManager'),
    [string]$HostAddress = 'ubuntu@210.207.108.196',
    [string]$KeyPath = (Join-Path $env:USERPROFILE 'Downloads/ppakemon.pem'),
    [string]$ServerRoot = '/srv/MinecraftPPakemonServer',
    [string]$Session = 'ppakemon'
)
$ErrorActionPreference = 'Stop'
$utf8 = [System.Text.UTF8Encoding]::new($false)
$root = [IO.Path]::GetFullPath($InstallRoot)
New-Item -ItemType Directory -Force -Path $root | Out-Null
foreach($name in @('vps-manager.ps1','remote_manager.py')) {
    $source=Join-Path $PSScriptRoot $name
    $target=Join-Path $root $name
    Copy-Item -LiteralPath $source -Destination $target -Force
    if((Get-FileHash -LiteralPath $source).Hash -ne (Get-FileHash -LiteralPath $target).Hash) {throw ('설치 파일 해시 불일치: '+$name)}
}
$settingsPath=Join-Path $root 'settings.json'
if(-not(Test-Path -LiteralPath $settingsPath)) {
    $authorName=(& git config --get user.name)
    $authorEmail=(& git config --get user.email)
    if([string]::IsNullOrWhiteSpace($authorName) -or [string]::IsNullOrWhiteSpace($authorEmail)) {throw '기존 Git 작성자 정보를 찾지 못했습니다.'}
    $ssh=(Get-Command ssh.exe -ErrorAction Stop).Source
    $desktop=[Environment]::GetFolderPath('Desktop')
    $settings=@{host=$HostAddress;port=22;keyPath=[IO.Path]::GetFullPath($KeyPath);root=$ServerRoot;session=$Session;
        authorName=$authorName;authorEmail=$authorEmail;sshPath=$ssh;consoleShortcut=(Join-Path $desktop '빡케몬 서버 콘솔.lnk')}
    [IO.File]::WriteAllText($settingsPath,($settings|ConvertTo-Json),$utf8)
}
$pwsh=(Get-Command pwsh.exe -ErrorAction Stop).Source
$desktop=[Environment]::GetFolderPath('Desktop')
$shortcutPath=Join-Path $desktop '빡케몬 VPS 관리.lnk'
$shell=New-Object -ComObject WScript.Shell
$shortcut=$shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath=$pwsh
$shortcut.Arguments='-NoLogo -NoProfile -STA -WindowStyle Hidden -File "'+(Join-Path $root 'vps-manager.ps1')+'"'
$shortcut.WorkingDirectory=$root
$shortcut.Description='VPS 상태·선택 파일 Git 커밋/푸시·기능 업데이트·서버 콘솔'
$shortcut.IconLocation=$pwsh+',0'
$shortcut.Save()
[PSCustomObject]@{InstallRoot=$root;Shortcut=$shortcutPath;Settings=$settingsPath;HashVerified=$true}|ConvertTo-Json -Compress
