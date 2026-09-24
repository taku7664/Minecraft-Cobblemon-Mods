[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Module,

    [string]$ProfilePath,

    [switch]$SkipBuild,
    [switch]$Check,
    [switch]$DryRun,
    [switch]$List,
    [switch]$Watch,
    [switch]$Help
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $utf8NoBom
$OutputEncoding = $utf8NoBom

trap {
    [Console]::Error.WriteLine("[error] " + $_.Exception.Message)
    exit 1
}

if ($Help) {
    @"
Usage:
  .\deploy-dev.cmd                         Choose a client module interactively
  .\deploy-dev.cmd <module>                Build and deploy once
  .\deploy-dev.cmd <module> -Watch         Deploy now and after source changes

Options:
  -Check        Run the module's Gradle check task before deployment
  -DryRun       Build and show the deployment plan without changing the profile
  -SkipBuild    Deploy the current-version artifact already in build\libs
  -List         Show discovered modules and client eligibility
  -ProfilePath  Override the cobblemon-dev profile path
  -Help         Show this help
"@
    exit 0
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$repoRoot = Split-Path -Parent $PSScriptRoot
$gradleWrapper = Join-Path $repoRoot "gradlew.bat"

if ([string]::IsNullOrWhiteSpace($ProfilePath)) {
    if (-not [string]::IsNullOrWhiteSpace($env:COBBLEMON_DEV_PROFILE)) {
        $ProfilePath = $env:COBBLEMON_DEV_PROFILE
    }
    else {
        $ProfilePath = Join-Path $env:APPDATA "ModrinthApp\profiles\cobblemon-dev"
    }
}

function Get-SourceModules {
    $modules = @()
    foreach ($directory in @(Get-ChildItem -LiteralPath $repoRoot -Directory)) {
        $metadataPath = Join-Path $directory.FullName "src\main\resources\fabric.mod.json"
        if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf)) {
            continue
        }

        $metadata = Get-Content -LiteralPath $metadataPath -Raw -Encoding UTF8 | ConvertFrom-Json
        $environmentProperty = $metadata.PSObject.Properties["environment"]
        $environment = if ($null -eq $environmentProperty) { "*" } else { [string]$environmentProperty.Value }
        $modules += [pscustomobject]@{
            Name = $directory.Name
            Path = $directory.FullName
            ModId = [string]$metadata.id
            Environment = $environment
            ClientDeployable = $environment -ne "server"
        }
    }

    return @($modules | Sort-Object Name)
}

function Get-JarMetadata {
    param([Parameter(Mandatory = $true)][string]$Path)

    $fileStream = [System.IO.File]::Open(
        $Path,
        [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read,
        [System.IO.FileShare]::Read
    )
    try {
        $archive = [System.IO.Compression.ZipArchive]::new(
            $fileStream,
            [System.IO.Compression.ZipArchiveMode]::Read,
            $false
        )
        try {
            $entry = $archive.GetEntry("fabric.mod.json")
            if ($null -eq $entry) {
                return $null
            }

            $reader = [System.IO.StreamReader]::new($entry.Open(), [System.Text.Encoding]::UTF8, $true)
            try {
                $json = $reader.ReadToEnd() | ConvertFrom-Json
            }
            finally {
                $reader.Dispose()
            }

            $environmentProperty = $json.PSObject.Properties["environment"]
            return [pscustomobject]@{
                Id = [string]$json.id
                Version = [string]$json.version
                Environment = if ($null -eq $environmentProperty) { "*" } else { [string]$environmentProperty.Value }
            }
        }
        finally {
            $archive.Dispose()
        }
    }
    finally {
        $fileStream.Dispose()
    }
}

function Get-JarTool {
    $knownPath = "C:\Program Files\Java\jdk-21\bin\jar.exe"
    if (Test-Path -LiteralPath $knownPath -PathType Leaf) {
        return $knownPath
    }

    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $javaHomeJar = Join-Path $env:JAVA_HOME "bin\jar.exe"
        if (Test-Path -LiteralPath $javaHomeJar -PathType Leaf) {
            return $javaHomeJar
        }
    }

    $command = Get-Command "jar.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $command) {
        return $command.Source
    }

    return $null
}

function Assert-ReadableJar {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$ExpectedModId
    )

    $fileStream = [System.IO.File]::Open(
        $Path,
        [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read,
        [System.IO.FileShare]::Read
    )
    try {
        $archive = [System.IO.Compression.ZipArchive]::new(
            $fileStream,
            [System.IO.Compression.ZipArchiveMode]::Read,
            $false
        )
        try {
            $buffer = New-Object byte[] 65536
            foreach ($entry in $archive.Entries) {
                $entryStream = $entry.Open()
                try {
                    while ($entryStream.Read($buffer, 0, $buffer.Length) -gt 0) {
                    }
                }
                finally {
                    $entryStream.Dispose()
                }
            }
        }
        finally {
            $archive.Dispose()
        }
    }
    finally {
        $fileStream.Dispose()
    }

    $metadata = Get-JarMetadata -Path $Path
    if ($null -eq $metadata) {
        throw "fabric.mod.json이 없는 JAR입니다: $Path"
    }
    if ($metadata.Id -ne $ExpectedModId) {
        throw "JAR mod ID가 예상과 다릅니다. expected=$ExpectedModId actual=$($metadata.Id)"
    }

    $jarTool = Get-JarTool
    if ($null -ne $jarTool) {
        $jarValidationOutput = @(& $jarTool --validate --file $Path 2>&1)
        if ($LASTEXITCODE -ne 0) {
            throw "JDK jar 검증에 실패했습니다: $Path`n$($jarValidationOutput -join [Environment]::NewLine)"
        }
    }

    return $metadata
}

function Get-ProjectVersion {
    param([Parameter(Mandatory = $true)]$ModuleInfo)

    $buildFile = Join-Path $ModuleInfo.Path "build.gradle.kts"
    if (-not (Test-Path -LiteralPath $buildFile -PathType Leaf)) {
        throw "모듈 build.gradle.kts가 없습니다: $($ModuleInfo.Name)"
    }

    $buildText = Get-Content -LiteralPath $buildFile -Raw -Encoding UTF8
    $match = [regex]::Match($buildText, 'version\s*=\s*property\("([^\"]+)"\)')
    if (-not $match.Success) {
        throw "모듈 버전 속성을 찾지 못했습니다: $buildFile"
    }

    $propertyName = $match.Groups[1].Value
    $propertiesPath = Join-Path $repoRoot "gradle.properties"
    foreach ($line in Get-Content -LiteralPath $propertiesPath -Encoding UTF8) {
        if ($line -match '^\s*([^#!][^=]*?)\s*=\s*(.*?)\s*$' -and $matches[1].Trim() -eq $propertyName) {
            return $matches[2].Trim()
        }
    }

    throw "gradle.properties에서 버전을 찾지 못했습니다: $propertyName"
}

function Get-BuiltArtifact {
    param([Parameter(Mandatory = $true)]$ModuleInfo)

    $expectedVersion = Get-ProjectVersion -ModuleInfo $ModuleInfo
    $libsPath = Join-Path $ModuleInfo.Path "build\libs"
    if (-not (Test-Path -LiteralPath $libsPath -PathType Container)) {
        throw "빌드 결과 폴더가 없습니다: $libsPath"
    }

    $candidateJars = @()
    foreach ($jar in @(Get-ChildItem -LiteralPath $libsPath -File -Filter "*.jar")) {
        if ($jar.Name -match '-(sources|dev|javadoc)\.jar$') {
            continue
        }

        try {
            $metadata = Get-JarMetadata -Path $jar.FullName
        }
        catch {
            Write-Warning "읽을 수 없는 빌드 JAR을 건너뜁니다: $($jar.FullName)"
            continue
        }

        if ($null -ne $metadata -and $metadata.Id -eq $ModuleInfo.ModId -and $metadata.Version -eq $expectedVersion) {
            $candidateJars += $jar
        }
    }

    if ($candidateJars.Count -eq 0) {
        throw "현재 버전 $expectedVersion / mod ID $($ModuleInfo.ModId)에 맞는 배포 JAR을 찾지 못했습니다."
    }

    return @(
        $candidateJars |
            Sort-Object @{ Expression = "LastWriteTimeUtc"; Descending = $true }, Name |
            Select-Object -First 1
    )[0]
}

function Test-ProfileRunning {
    param([Parameter(Mandatory = $true)][string]$TargetProfile)

    try {
        $normalizedProfile = [System.IO.Path]::GetFullPath($TargetProfile)
        $processes = @(Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'")
        foreach ($process in $processes) {
            if ($null -ne $process.CommandLine -and
                $process.CommandLine.IndexOf($normalizedProfile, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
                return $true
            }
        }
    }
    catch {
        Write-Warning "Minecraft 실행 여부를 확인하지 못했습니다: $($_.Exception.Message)"
    }

    return $false
}

function Get-InstalledModJars {
    param(
        [Parameter(Mandatory = $true)][string]$ModsPath,
        [Parameter(Mandatory = $true)][string]$ModId
    )

    $installedJars = @()
    foreach ($jar in @(Get-ChildItem -LiteralPath $ModsPath -File -Filter "*.jar")) {
        try {
            $metadata = Get-JarMetadata -Path $jar.FullName
        }
        catch {
            Write-Warning "메타데이터를 읽지 못한 설치 JAR을 건너뜁니다: $($jar.Name)"
            continue
        }

        if ($null -ne $metadata -and $metadata.Id -eq $ModId) {
            $installedJars += [pscustomobject]@{
                File = $jar
                Metadata = $metadata
            }
        }
    }

    return @($installedJars)
}

function Invoke-ModuleBuild {
    param([Parameter(Mandatory = $true)]$ModuleInfo)

    if ($SkipBuild) {
        Write-Host "[build] 건너뜀 (-SkipBuild)"
        return
    }

    if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
        throw "Gradle wrapper를 찾지 못했습니다: $gradleWrapper"
    }

    $tasks = @()
    if ($Check) {
        $tasks += ":$($ModuleInfo.Name):check"
    }
    $tasks += ":$($ModuleInfo.Name):remapJar"

    Write-Host "[build] $($tasks -join ' ')"
    & $gradleWrapper @tasks --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle 빌드에 실패했습니다: $($ModuleInfo.Name)"
    }
}

function Invoke-DeployOnce {
    param([Parameter(Mandatory = $true)]$ModuleInfo)

    Invoke-ModuleBuild -ModuleInfo $ModuleInfo
    $artifact = Get-BuiltArtifact -ModuleInfo $ModuleInfo
    $artifactMetadata = Assert-ReadableJar -Path $artifact.FullName -ExpectedModId $ModuleInfo.ModId
    $artifactHash = (Get-FileHash -LiteralPath $artifact.FullName -Algorithm SHA256).Hash

    if (-not (Test-Path -LiteralPath $ProfilePath -PathType Container)) {
        throw "대상 프로필이 없습니다: $ProfilePath"
    }

    $modsPath = Join-Path $ProfilePath "mods"
    if (-not (Test-Path -LiteralPath $modsPath -PathType Container)) {
        throw "대상 mods 폴더가 없습니다: $modsPath"
    }

    $installed = @(Get-InstalledModJars -ModsPath $modsPath -ModId $ModuleInfo.ModId)
    if ($installed.Count -gt 1) {
        $names = ($installed | ForEach-Object { $_.File.Name }) -join ", "
        throw "동일 mod ID의 JAR이 여러 개라 자동 교체를 중단합니다: $names"
    }

    $targetPath = Join-Path $modsPath $artifact.Name
    if ((Test-Path -LiteralPath $targetPath -PathType Leaf) -and
        -not ($installed.Count -eq 1 -and $installed[0].File.FullName -eq $targetPath)) {
        throw "대상 파일명과 충돌하는 다른 JAR이 있습니다: $targetPath"
    }

    Write-Host "[plan] $($ModuleInfo.Name) -> $ProfilePath"
    Write-Host "[jar]  $($artifact.Name) / $($artifactMetadata.Version) / $artifactHash"
    if ($installed.Count -eq 1) {
        Write-Host "[old]  $($installed[0].File.Name) / $($installed[0].Metadata.Version)"
    }
    else {
        Write-Host "[old]  설치된 동일 mod ID 없음 (새 설치)"
    }

    if ($installed.Count -eq 1) {
        $installedHash = (Get-FileHash -LiteralPath $installed[0].File.FullName -Algorithm SHA256).Hash
        if ($installedHash -eq $artifactHash) {
            Write-Host "[done] 이미 같은 해시가 설치되어 있어 변경하지 않았습니다."
            return
        }
    }

    if ($DryRun) {
        Write-Host "[dry-run] 파일을 변경하지 않았습니다."
        return
    }

    if (Test-ProfileRunning -TargetProfile $ProfilePath) {
        throw "cobblemon-dev가 실행 중이라 배포하지 않았습니다. 게임을 종료하면 안전하게 다시 실행할 수 있습니다."
    }

    $stagingPath = Join-Path $modsPath (".{0}.{1}.deploying" -f $artifact.BaseName, $PID)
    $backupRoot = Join-Path $ProfilePath ("codex-deploy-backups\{0}\{1}" -f (Get-Date -Format "yyyyMMdd-HHmmssfff"), $ModuleInfo.ModId)
    $movedOld = @()
    $newTargetCreated = $false

    try {
        [System.IO.File]::Copy($artifact.FullName, $stagingPath, $false)
        $stagingMetadata = Assert-ReadableJar -Path $stagingPath -ExpectedModId $ModuleInfo.ModId
        $stagingHash = (Get-FileHash -LiteralPath $stagingPath -Algorithm SHA256).Hash
        if ($stagingHash -ne $artifactHash -or $stagingMetadata.Version -ne $artifactMetadata.Version) {
            throw "임시 복사본이 빌드 JAR과 일치하지 않습니다."
        }

        if (Test-ProfileRunning -TargetProfile $ProfilePath) {
            throw "복사 준비 중 cobblemon-dev가 실행되어 교체를 중단했습니다."
        }

        if ($installed.Count -eq 1) {
            [System.IO.Directory]::CreateDirectory($backupRoot) | Out-Null
            $backupPath = Join-Path $backupRoot $installed[0].File.Name
            [System.IO.File]::Move($installed[0].File.FullName, $backupPath)
            $movedOld += [pscustomobject]@{
                Original = $installed[0].File.FullName
                Backup = $backupPath
            }
        }

        [System.IO.File]::Move($stagingPath, $targetPath)
        $newTargetCreated = $true

        $deployedMetadata = Assert-ReadableJar -Path $targetPath -ExpectedModId $ModuleInfo.ModId
        $deployedHash = (Get-FileHash -LiteralPath $targetPath -Algorithm SHA256).Hash
        if ($deployedHash -ne $artifactHash -or $deployedMetadata.Version -ne $artifactMetadata.Version) {
            throw "최종 배포 JAR이 빌드 JAR과 일치하지 않습니다."
        }

        $finalMatches = @(Get-InstalledModJars -ModsPath $modsPath -ModId $ModuleInfo.ModId)
        if ($finalMatches.Count -ne 1 -or $finalMatches[0].File.FullName -ne $targetPath) {
            throw "배포 후 동일 mod ID JAR 상태가 올바르지 않습니다."
        }

        Write-Host "[done] 배포 및 해시 검증 완료"
        if ($movedOld.Count -eq 1) {
            Write-Host "[backup] $($movedOld[0].Backup)"
        }
    }
    catch {
        $failure = $_
        if (Test-Path -LiteralPath $stagingPath -PathType Leaf) {
            [System.IO.File]::Delete($stagingPath)
        }
        if ($newTargetCreated -and (Test-Path -LiteralPath $targetPath -PathType Leaf)) {
            [System.IO.File]::Delete($targetPath)
        }
        foreach ($move in $movedOld) {
            if ((Test-Path -LiteralPath $move.Backup -PathType Leaf) -and
                -not (Test-Path -LiteralPath $move.Original -PathType Leaf)) {
                [System.IO.File]::Move($move.Backup, $move.Original)
            }
        }
        throw $failure
    }
}

function Get-WatchFingerprint {
    param([Parameter(Mandatory = $true)]$ModuleInfo)

    $paths = @(
        (Join-Path $ModuleInfo.Path "src"),
        (Join-Path $ModuleInfo.Path "build.gradle.kts"),
        (Join-Path $repoRoot "build.gradle.kts"),
        (Join-Path $repoRoot "settings.gradle.kts"),
        (Join-Path $repoRoot "gradle.properties")
    )
    $records = @()
    foreach ($path in $paths) {
        if (Test-Path -LiteralPath $path -PathType Container) {
            foreach ($file in @(Get-ChildItem -LiteralPath $path -File -Recurse)) {
                $records += "{0}|{1}|{2}" -f $file.FullName, $file.Length, $file.LastWriteTimeUtc.Ticks
            }
        }
        elseif (Test-Path -LiteralPath $path -PathType Leaf) {
            $file = Get-Item -LiteralPath $path
            $records += "{0}|{1}|{2}" -f $file.FullName, $file.Length, $file.LastWriteTimeUtc.Ticks
        }
    }

    $text = (@($records | Sort-Object) -join "`n")
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($text)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "")
    }
    finally {
        $sha.Dispose()
    }
}

$modules = @(Get-SourceModules)

if ($List) {
    $modules | Select-Object Name, ModId, Environment, ClientDeployable | Format-Table -AutoSize
    exit 0
}

if ([string]::IsNullOrWhiteSpace($Module)) {
    $deployable = @($modules | Where-Object ClientDeployable)
    Write-Host "cobblemon-dev에 배포할 모듈을 고르세요:"
    for ($index = 0; $index -lt $deployable.Count; $index++) {
        Write-Host ("  {0}. {1}" -f ($index + 1), $deployable[$index].Name)
    }
    $selection = Read-Host "번호"
    $number = 0
    if (-not [int]::TryParse($selection, [ref]$number) -or $number -lt 1 -or $number -gt $deployable.Count) {
        throw "올바른 모듈 번호가 아닙니다: $selection"
    }
    $Module = $deployable[$number - 1].Name
}

$moduleInfo = $modules | Where-Object Name -eq $Module | Select-Object -First 1
if ($null -eq $moduleInfo) {
    throw "알 수 없는 모듈입니다: $Module. -List로 목록을 확인하세요."
}
if (-not $moduleInfo.ClientDeployable) {
    throw "서버 전용 모듈은 클라이언트 프로필에 배포할 수 없습니다: $Module"
}

if (-not $Watch) {
    Invoke-DeployOnce -ModuleInfo $moduleInfo
    exit 0
}

Write-Host "[watch] $($moduleInfo.Name) 변경 감시 시작. 종료: Ctrl+C"
$fingerprint = Get-WatchFingerprint -ModuleInfo $moduleInfo
$pending = $true
$waitingForGameExit = $false
while ($true) {
    if ($pending) {
        if (-not $DryRun -and (Test-ProfileRunning -TargetProfile $ProfilePath)) {
            if (-not $waitingForGameExit) {
                Write-Host "[watch] 게임이 실행 중입니다. 종료 후 최신 변경을 배포합니다."
                $waitingForGameExit = $true
            }
        }
        else {
            $waitingForGameExit = $false
            try {
                Invoke-DeployOnce -ModuleInfo $moduleInfo
                $pending = $false
            }
            catch {
                Write-Warning $_.Exception.Message
                $pending = $false
            }
            $fingerprint = Get-WatchFingerprint -ModuleInfo $moduleInfo
        }
    }

    Start-Sleep -Milliseconds 1000
    $nextFingerprint = Get-WatchFingerprint -ModuleInfo $moduleInfo
    if ($nextFingerprint -ne $fingerprint) {
        Write-Host "[watch] 변경 감지. 저장이 끝날 때까지 잠시 기다립니다."
        do {
            $fingerprint = $nextFingerprint
            Start-Sleep -Milliseconds 1200
            $nextFingerprint = Get-WatchFingerprint -ModuleInfo $moduleInfo
        } while ($nextFingerprint -ne $fingerprint)
        $pending = $true
    }
}
