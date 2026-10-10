[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$ServerRoot)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
. (Join-Path $ServerRoot 'tools/server-startup-hooks.ps1')
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('startup-linux-tests-' + [guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($fixture) | Out-Null
function Assert($Condition, $Message) { if (-not $Condition) { throw $Message } }
try {
    $local = Resolve-HookLocalFile $fixture 'config/sample.json'
    Assert ($local -ceq [IO.Path]::GetFullPath((Join-Path $fixture 'config/sample.json'))) 'Local path rejected or changed'
    $rejected = $false
    try { $null = Resolve-HookLocalFile $fixture '../outside.json' } catch { $rejected = $true }
    Assert $rejected 'Path traversal accepted'
    $jsonPath = Resolve-HookJsonFile $fixture 'config/sample.json'
    Assert ($jsonPath -ceq $local) 'JSON path resolution disagrees'
    Assert (-not (Test-HookPathWithin ($fixture + '-sibling/file') $fixture)) 'Sibling directory accepted'
    $comparisonRoot = $fixture + [IO.Path]::DirectorySeparatorChar + 'CaseRoot'
    $comparisonPath = $fixture + [IO.Path]::DirectorySeparatorChar + 'caseroot' + [IO.Path]::DirectorySeparatorChar + 'file'
    Assert ((Test-HookPathWithin $comparisonPath $comparisonRoot) -eq ([IO.Path]::DirectorySeparatorChar -eq '\')) 'Platform case comparison incorrect'
    Write-Output 'PASS: local/config path containment and traversal rejection'
    $runtime = Get-HookCompatibilityRuntime $ServerRoot 'fabric-server-mc.1.21.1-loader.0.19.5-launcher.1.1.2.jar'
    $results = Invoke-HookVersionEngine $runtime @(
        [pscustomobject]@{ Version='1.21.1'; Predicate='>=1.21'; Label='accepted' },
        [pscustomobject]@{ Version='1.21.1'; Predicate='<1.0'; Label='rejected' }
    )
    Assert ($results.Count -eq 2 -and $results[0] -and -not $results[1]) 'Fabric version adapter/classpath failed'
    Write-Output 'PASS: actual Java/Fabric version adapter accepts and rejects predicates'
    $command = Join-Path $fixture 'argument check.ps1'
    [IO.File]::WriteAllText($command, 'param([string]$Value) if ($Value -cne ''space "quote" and \slash'') { exit 9 }')
    Invoke-HookCommandCheck 'pwsh' @('-NoProfile','-NonInteractive','-File',$command,'space "quote" and \slash') 10 $fixture
    Write-Output 'PASS: prerequisite command arguments preserve spaces, quotes and backslashes'
    [IO.File]::WriteAllText((Join-Path $fixture 'server.properties'), "level-name=world`ndifficulty=normal`n")
    # Use the actual schema consumed by the hook, without a Minecraft process.
    [IO.File]::WriteAllText((Join-Path $fixture 'startup-hooks.json'), '{"schema_version":1,"server_properties":{"difficulty":{"value":"hard","mode":"ALWAYS"}},"gamerules":{}}')
    Invoke-ServerStartupHooks $fixture | Out-Null
    Assert ([IO.File]::ReadAllText((Join-Path $fixture 'server.properties')) -match 'difficulty=hard') 'Property hook not applied'
    Assert ([IO.File]::Exists((Join-Path $fixture 'world/datapacks/cobblemon-startup-hooks.zip'))) 'Gamerule datapack not created'
    Write-Output 'PASS: startup hook writes properties and gamerule datapack in isolated fixture'
    $before = [IO.File]::ReadAllText((Join-Path $fixture 'server.properties'))
    $lockSource = Join-Path $fixture 'LockProbe.java'
    [IO.File]::WriteAllText($lockSource, @'
import java.nio.channels.*;
import java.nio.file.*;
class LockProbe {
    public static void main(String[] args) throws Exception {
        try (FileChannel file = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = file.lock()) {
            System.out.println("LOCKED");
            System.out.flush();
            System.in.read();
        }
    }
}
'@)
    $lockInfo = [Diagnostics.ProcessStartInfo]::new()
    $lockInfo.FileName = $runtime.Java
    $lockPath = Join-Path $fixture 'world/session.lock'
    if ($null -ne $lockInfo.PSObject.Properties['ArgumentList']) {
        $lockInfo.ArgumentList.Add($lockSource); $lockInfo.ArgumentList.Add($lockPath)
    } else { $lockInfo.Arguments = '"' + $lockSource + '" "' + $lockPath + '"' }
    $lockInfo.UseShellExecute = $false
    $lockInfo.RedirectStandardInput = $true; $lockInfo.RedirectStandardOutput = $true; $lockInfo.RedirectStandardError = $true
    $lockProcess = [Diagnostics.Process]::new(); $lockProcess.StartInfo = $lockInfo
    try {
        Assert ($lockProcess.Start()) 'Could not start Java world lock fixture'
        $ready = $lockProcess.StandardOutput.ReadLineAsync()
        Assert ($ready.Wait(10000) -and $ready.Result -ceq 'LOCKED') 'Java world lock fixture did not become ready'
        $rejected = $false
        try { Invoke-ServerStartupHooks $fixture | Out-Null } catch { $rejected = $_.Exception.Message -like 'World is running*' }
        Assert $rejected 'Java-held world lock was ignored'
        Assert ([IO.File]::ReadAllText((Join-Path $fixture 'server.properties')) -ceq $before) 'Locked server properties were modified'
        Write-Output 'PASS: Java-held world session lock prevents startup hook writes'
    } finally {
        if (-not $lockProcess.HasExited) { $lockProcess.StandardInput.Close(); if (-not $lockProcess.WaitForExit(5000)) { $lockProcess.Kill(); $lockProcess.WaitForExit() } }
        $lockProcess.Dispose()
    }
} finally {
    # This is a newly-created fixture; never points at a live server root.
    $resolvedFixture = [IO.Path]::GetFullPath($fixture)
    $expectedParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
    if ([IO.Path]::GetDirectoryName($resolvedFixture) -eq $expectedParent -and
        [IO.Path]::GetFileName($resolvedFixture).StartsWith('startup-linux-tests-')) {
        Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
    } else { throw 'Refusing to remove a fixture outside the temporary directory.' }
}
