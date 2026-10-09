$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'server-deployment.psm1') -Force
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('server-deploy-tests-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
$passed = 0
function Assert($condition, $message) { if (-not $condition) { throw $message } }
function Write-File($root, $relative, $text) {
    $path = Join-Path $root $relative
    New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($path)) -Force | Out-Null
    [IO.File]::WriteAllText($path, $text, [Text.UTF8Encoding]::new($false))
}
function Write-Jar($root, $name, $id, $environment='*') {
    $path = Join-Path $root ('mods/' + $name)
    New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($path)) -Force | Out-Null
    $zip = [IO.Compression.ZipFile]::Open($path, [IO.Compression.ZipArchiveMode]::Create)
    try { $entry=$zip.CreateEntry('fabric.mod.json'); $writer=[IO.StreamWriter]::new($entry.Open()); try { $writer.Write('{"id":"'+$id+'","version":"1.0.0","environment":"'+$environment+'"}') } finally { $writer.Dispose() } } finally { $zip.Dispose() }
}
function Fixture($name) {
    $root=Join-Path $testRoot $name; $src=Join-Path $root '개발 서버'; $dst=Join-Path $root '배포 서버'; $state=Join-Path $root 'state'
    New-Item -ItemType Directory -Path $src,$dst -Force|Out-Null
    Write-Jar $src 'cobblemon-new.jar' 'cobblemon'
    $launcher=Join-Path $src 'fabric-server-mc.1.21.1-loader.0.19.5-launcher.1.1.2.jar'
    $zip=[IO.Compression.ZipFile]::Open($launcher,[IO.Compression.ZipArchiveMode]::Create)
    try{$entry=$zip.CreateEntry('install.properties');$writer=[IO.StreamWriter]::new($entry.Open());try{$writer.Write("game-version=1.21.1`nfabric-loader-version=0.19.5`n")}finally{$writer.Dispose()}}finally{$zip.Dispose()}
    Write-File $src 'libraries/net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar' 'fixture-loader'
    Write-File $src 'run.bat' 'java -Xmx4G -jar fabric-server-mc.1.21.1-loader.0.19.5-launcher.1.1.2.jar --port 25566 nogui'
    Write-File $src 'server.properties' "level-name=world`nserver-port=25566`nserver-ip=127.0.0.1`nmotd=Dev`ndifficulty=peaceful`nmax-players=20`n"
    Write-File $dst 'server.properties' "level-name=world`nserver-port=25565`nserver-ip=`nmotd=Production`ndifficulty=hard`nmax-players=40`n"
    Write-File $src 'startup-hooks.json' ('{"wiki_files":{"sha256":"'+('0'*64)+'","bundle":"startup-assets/server-wiki.zip"}}')
    Write-File $src 'config/more-cobblemon-contents/wiki/index.html' '<html>fixture</html>'
    Write-File $src 'config/gameplay.json' '{"difficulty":2}'
    Write-File $src 'world/datapacks/policy/pack.mcmeta' '{"pack":{"pack_format":48,"description":"fixture"}}'
    Write-File $src 'world/dimensions/jbro_policy/plaza/region/r.0.0.mca' 'plaza-new'
    Write-File $src 'world/data/jbro_policy_plaza_biome.dat' 'biome-state'
    return @{SourceRoot=$src;TargetRoot=$dst;StateRoot=$state}
}
function Test($name, [scriptblock]$body) { & $body; $script:passed++; Write-Output ('PASS '+$name) }
function Reject([scriptblock]$body) { $rejected=$false;try{& $body|Out-Null}catch{$rejected=$true};Assert $rejected 'Unsafe operation was accepted' }

try {
    Test 'preview writes nothing; client-only mods, secrets and private player state are excluded' {
        $f=Fixture 'preview'
        Write-Jar $f.SourceRoot 'client.jar' 'client_fixture' 'client'
        Write-File $f.SourceRoot 'config/jbro-policy-discord.json' '{"botToken":"fixture-secret"}'
        Write-File $f.SourceRoot 'config/private-extra.json' '{"apiKey":"fixture-secret"}'
        Write-File $f.SourceRoot 'world/playerdata/player.dat' 'private-player'
        Write-File $f.SourceRoot 'logs/latest.log' 'private-log'
        $plan=Get-ServerDeploymentPlan @f
        Assert (-not(Test-Path $f.StateRoot)) 'Preview created state/backups'
        Assert (-not(Test-Path (Join-Path $f.TargetRoot 'mods'))) 'Preview changed server'
        $paths=@($plan.Operations.Path)
        Assert ($paths -contains 'world/datapacks/policy/pack.mcmeta') 'Datapack omitted'
        Assert ($paths -contains 'world/dimensions/jbro_policy/plaza/region/r.0.0.mca') 'Plaza omitted'
        Assert (-not($paths|Where-Object {$_ -match 'client.jar|discord.json|private-extra|playerdata|logs/'})) 'Private/client file selected'
    }
    Test 'deploy replaces old mod, preserves production addresses, credentials and player data; repeated deploy is empty' {
        $f=Fixture 'deploy'
        Write-Jar $f.TargetRoot 'cobblemon-old.jar' 'cobblemon'
        Write-File $f.TargetRoot 'config/jbro-policy-discord.json' '{"botToken":"production-secret"}'
        Write-File $f.TargetRoot 'world/playerdata/player.dat' 'production-player'
        Write-File $f.TargetRoot 'world/region/r.0.0.mca' 'production-overworld'
        $plan=Get-ServerDeploymentPlan @f
        Invoke-ServerDeployment -Plan $plan|Out-Null
        Assert (-not(Test-Path (Join-Path $f.TargetRoot 'mods/cobblemon-old.jar'))) 'Old mod left behind'
        Assert (Test-Path (Join-Path $f.TargetRoot 'mods/cobblemon-new.jar')) 'New mod missing'
        $props=[IO.File]::ReadAllText((Join-Path $f.TargetRoot 'server.properties'))
        Assert ($props -match 'server-port=25565' -and $props -match 'motd=Production' -and $props -match 'difficulty=hard') 'Existing server settings overwritten'
        Assert ([IO.File]::ReadAllText((Join-Path $f.TargetRoot 'config/jbro-policy-discord.json')) -match 'production-secret') 'Credentials overwritten'
        Assert ([IO.File]::ReadAllText((Join-Path $f.TargetRoot 'world/playerdata/player.dat')) -eq 'production-player') 'Player overwritten'
        Assert ([IO.File]::ReadAllText((Join-Path $f.TargetRoot 'world/region/r.0.0.mca')) -eq 'production-overworld') 'Overworld overwritten'
        Assert (@((Get-ServerDeploymentPlan @f).Operations).Count -eq 0) 'Repeat is not empty'
        Restore-ServerDeployment -TargetRoot $f.TargetRoot -StateRoot $f.StateRoot|Out-Null
        Assert (Test-Path (Join-Path $f.TargetRoot 'mods/cobblemon-old.jar')) 'Restore did not recover old mod'
        Assert (-not(Test-Path (Join-Path $f.TargetRoot 'mods/cobblemon-new.jar'))) 'Restore left new mod'
    }
    Test 'source and target modification after preview stops before writes' {
        $f=Fixture 'stale-source'; $plan=Get-ServerDeploymentPlan @f
        Write-File $f.SourceRoot 'config/gameplay.json' '{"difficulty":3}'
        Reject {Invoke-ServerDeployment -Plan $plan}
        Assert (-not(Test-Path (Join-Path $f.TargetRoot 'mods'))) 'Stale preview wrote files'
        $f=Fixture 'stale-target'; $plan=Get-ServerDeploymentPlan @f
        Write-File $f.TargetRoot 'server.properties' "level-name=world`nserver-port=25600`n"
        Reject {Invoke-ServerDeployment -Plan $plan}
        Assert (-not(Test-Path (Join-Path $f.TargetRoot 'mods'))) 'Target conflict wrote files'
    }
    Test 'world session lock rejects running servers' {
        $f=Fixture 'locked'; Write-File $f.SourceRoot 'world/session.lock' 'lock'
        $held=[IO.File]::Open((Join-Path $f.SourceRoot 'world/session.lock'),[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
        try { Reject {Get-ServerDeploymentPlan @f} } finally {$held.Dispose()}
    }
    Test 'same or nested roots are refused' {
        $f=Fixture 'roots'
        Reject {Get-ServerDeploymentPlan -SourceRoot $f.SourceRoot -TargetRoot $f.SourceRoot -StateRoot $f.StateRoot}
        New-Item -ItemType Directory -Path (Join-Path $f.SourceRoot 'nested')|Out-Null
        Reject {Get-ServerDeploymentPlan -SourceRoot $f.SourceRoot -TargetRoot (Join-Path $f.SourceRoot 'nested') -StateRoot $f.StateRoot}
    }
    Test 'broken source JAR and duplicate mod IDs stop deployment' {
        $f=Fixture 'bad-jar'; Write-File $f.SourceRoot 'mods/broken.jar' 'not-a-zip'
        Reject {Get-ServerDeploymentPlan @f}
        $f=Fixture 'duplicate'; Write-Jar $f.SourceRoot 'duplicate.jar' 'cobblemon'
        Reject {Get-ServerDeploymentPlan @f}
    }
    Test 'changed files are rolled back if an exception occurs mid-commit' {
        $f=Fixture 'rollback'; Write-Jar $f.TargetRoot 'cobblemon-old.jar' 'cobblemon'
        $before=[IO.File]::ReadAllText((Join-Path $f.TargetRoot 'server.properties'))
        $plan=Get-ServerDeploymentPlan @f
        Reject {Invoke-ServerDeployment -Plan $plan -ProgressCallback {param($stage,$current,$total,$path) if($stage -eq 'apply' -and $current -eq 2){throw 'fixture interruption'}}}
        Assert (Test-Path (Join-Path $f.TargetRoot 'mods/cobblemon-old.jar')) 'Rollback lost old mod'
        Assert (-not(Test-Path (Join-Path $f.TargetRoot 'mods/cobblemon-new.jar'))) 'Rollback left added mod'
        Assert ([IO.File]::ReadAllText((Join-Path $f.TargetRoot 'server.properties')) -eq $before) 'Rollback changed properties'
    }
    Test 'deletion follows managed files, and leaves unknown local config alone' {
        $f=Fixture 'deletion'; Invoke-ServerDeployment -Plan (Get-ServerDeploymentPlan @f)|Out-Null
        Remove-Item -LiteralPath (Join-Path $f.SourceRoot 'config/gameplay.json')
        Write-File $f.TargetRoot 'config/local-only.json' '{"enabled":true}'
        Invoke-ServerDeployment -Plan (Get-ServerDeploymentPlan @f)|Out-Null
        Assert (Test-Path (Join-Path $f.TargetRoot 'config/gameplay.json')) 'Existing config was deleted'
        Assert (Test-Path (Join-Path $f.TargetRoot 'config/local-only.json')) 'Local config deleted'
    }
    Test 'junctions are refused without traversing outside the roots' {
        $f=Fixture 'junction'; $outside=Join-Path $testRoot 'outside'; New-Item -ItemType Directory -Path $outside|Out-Null
        Write-File $outside 'private.json' '{"private":true}'
        New-Item -ItemType Junction -Path (Join-Path $f.SourceRoot 'config/linked') -Target $outside|Out-Null
        Reject {Get-ServerDeploymentPlan @f}
    }
    Test 'restore refuses overwriting edits made after deployment' {
        $f=Fixture 'restore-conflict'; Invoke-ServerDeployment -Plan (Get-ServerDeploymentPlan @f)|Out-Null
        Write-File $f.TargetRoot 'config/gameplay.json' '{"operatorEdit":true}'
        Reject {Restore-ServerDeployment -TargetRoot $f.TargetRoot -StateRoot $f.StateRoot}
        Assert ([IO.File]::ReadAllText((Join-Path $f.TargetRoot 'config/gameplay.json')) -match 'operatorEdit') 'Restore overwrote operator edit'
    }
    Test 'unquoted credentials and operational endpoint configs are protected' {
        $f=Fixture 'credentials'
        Write-File $f.SourceRoot 'config/private.properties' 'api-key=fixture-secret'
        Write-File $f.SourceRoot 'config/voicechat/voicechat-server.properties' 'port=24455'
        Write-File $f.TargetRoot 'config/voicechat/voicechat-server.properties' 'port=24454'
        $plan=Get-ServerDeploymentPlan @f
        Assert (-not($plan.Operations.Path -contains 'config/private.properties')) 'Unquoted secret selected'
        Invoke-ServerDeployment -Plan $plan|Out-Null
        Assert ([IO.File]::ReadAllText((Join-Path $f.TargetRoot 'config/voicechat/voicechat-server.properties')) -eq 'port=24454') 'Endpoint overwritten'
    }
    Test 'missing world assets or non-server target refuse deployment before deletes' {
        $f=Fixture 'missing-world';Remove-Item -LiteralPath (Join-Path $f.SourceRoot 'world/datapacks/policy/pack.mcmeta')
        Reject {Get-ServerDeploymentPlan @f}
        $f=Fixture 'wrong-target';Remove-Item -LiteralPath (Join-Path $f.TargetRoot 'server.properties')
        Reject {Get-ServerDeploymentPlan @f}
    }
    Test 'backup path overlap and destination junction refuse writes' {
        $f=Fixture 'backup-overlap';$plan=Get-ServerDeploymentPlan @f;$plan.StateRoot=Join-Path $f.SourceRoot 'backup'
        Reject {Invoke-ServerDeployment -Plan $plan}
        Assert (-not(Test-Path $plan.StateRoot)) 'Invalid backup wrote files'
        $outside=Join-Path $testRoot 'target-outside';New-Item -ItemType Directory -Path $outside|Out-Null
        New-Item -ItemType Junction -Path (Join-Path $f.TargetRoot 'config') -Target $outside|Out-Null
        Reject {Get-ServerDeploymentPlan @f}
    }
    Test 'interrupted journal blocks another deployment and can recover after restart' {
        $f=Fixture 'pending';$result=Invoke-ServerDeployment -Plan (Get-ServerDeploymentPlan @f)
        $journalPath=Join-Path $result.BackupRoot 'transaction.json';$journal=[IO.File]::ReadAllText($journalPath)|ConvertFrom-Json;$journal.Status='applying'
        [IO.File]::WriteAllText($journalPath,($journal|ConvertTo-Json -Depth 12))
        $temp='mods/cobblemon-new.jar.deploy-'+[IO.Path]::GetFileName($result.BackupRoot)+'.tmp'
        Write-File $f.TargetRoot $temp 'partial-copy'
        Reject {Get-ServerDeploymentPlan @f}
        Restore-ServerDeployment -TargetRoot $f.TargetRoot -StateRoot $f.StateRoot|Out-Null
        Assert (-not(Test-Path (Join-Path $f.TargetRoot 'mods/cobblemon-new.jar'))) 'Pending recovery left changed file'
        Assert (-not(Test-Path (Join-Path $f.TargetRoot $temp))) 'Crash temporary file left behind'
    }
    Test 'damaged backup refuses restoring before modifying any file' {
        $f=Fixture 'bad-backup-existing';Write-Jar $f.TargetRoot 'cobblemon-old.jar' 'cobblemon';$result=Invoke-ServerDeployment -Plan (Get-ServerDeploymentPlan @f)
        Write-File $result.BackupRoot 'backup/mods/cobblemon-old.jar' 'corrupt'
        Reject {Restore-ServerDeployment -TargetRoot $f.TargetRoot -StateRoot $f.StateRoot}
        Assert (Test-Path (Join-Path $f.TargetRoot 'mods/cobblemon-new.jar')) 'Bad backup caused partial restore'
    }
    Test 'window runs inspection, deployment and recovery on background workers' {
        . (Join-Path $PSScriptRoot 'deploy-server.ps1') -LibraryOnly
        $f=Fixture 'window';$window=New-DeploymentWindow $f.SourceRoot $f.TargetRoot $f.StateRoot -QuietErrors
        try{
            $window.Form.ShowInTaskbar=$false;$window.Form.Opacity=0;$window.Form.Show()
            foreach($action in @('inspect','deploy','restore')){
                & $window.Start $action
                $deadline=[DateTime]::UtcNow.AddSeconds(30)
                while($window.Context.Busy -and [DateTime]::UtcNow -lt $deadline){[Windows.Forms.Application]::DoEvents();Start-Sleep -Milliseconds 20}
                Assert (-not $window.Context.Busy) 'Window worker timed out'
                Assert (-not $window.Context.Error) ('Window worker failed: '+$window.Context.Error)
                if($action -eq 'inspect'){
                    Assert ($window.Grid.Rows.Count -gt 0 -and $window.Deploy.Enabled) 'Window did not render plan'
                    $window.Form.CreateControl();$bitmap=[Drawing.Bitmap]::new($window.Form.Width,$window.Form.Height)
                    try{$window.Form.DrawToBitmap($bitmap,[Drawing.Rectangle]::new(0,0,$bitmap.Width,$bitmap.Height));$bitmap.Save((Join-Path $testRoot 'window.png'))}finally{$bitmap.Dispose()}
                }
            }
            Assert (-not(Test-Path (Join-Path $f.TargetRoot 'mods/cobblemon-new.jar'))) 'Window recovery failed'
        }finally{$window.Timer.Stop();$window.Timer.Dispose();$window.Form.Dispose()}
    }
    Test 'feature update leaves all existing worlds, plaza, progress and settings byte-identical' {
        $f=Fixture 'preservation'
        $existing=@{
            'world/level.dat'='production-level';'world/data/custom-progress.dat'='production-progress'
            'world/playerdata/player.dat'='production-player';'world/region/r.0.0.mca'='production-overworld'
            'world/DIM-1/region/r.0.0.mca'='production-nether';'world/DIM1/region/r.0.0.mca'='production-end'
            'world/dimensions/myroom/rooms/region/r.0.0.mca'='production-room'
            'world/dimensions/jbro_policy/plaza/region/r.0.0.mca'='production-plaza'
            'world/data/jbro_policy_plaza_biome.dat'='production-biome'
            'config/gameplay.json'='{"operatorSetting":true}';'defaultconfigs/custom.toml'='enabled=false'
            'ops.json'='production-operators';'whitelist.json'='production-whitelist'
        }
        foreach($path in $existing.Keys){Write-File $f.TargetRoot $path $existing[$path]}
        Write-File $f.SourceRoot 'defaultconfigs/custom.toml' 'enabled=true'
        Write-File $f.SourceRoot 'world/dimensions/jbro_policy/plaza/region/r.1.1.mca' 'dev-only-chunk'
        $props=[IO.File]::ReadAllBytes((Join-Path $f.TargetRoot 'server.properties'))
        $deployment=Invoke-ServerDeployment -Plan (Get-ServerDeploymentPlan @f)
        foreach($path in $existing.Keys){Assert ([IO.File]::ReadAllText((Join-Path $f.TargetRoot $path)) -eq $existing[$path]) ('Existing data overwritten: '+$path)}
        foreach($path in ($existing.Keys|Where-Object {$_.StartsWith('world/')})){Assert ([IO.File]::ReadAllText((Join-Path $deployment.BackupRoot ('world-snapshot/'+$path))) -eq $existing[$path]) ('World snapshot omitted data: '+$path)}
        Assert (-not(Test-Path (Join-Path $f.TargetRoot 'world/dimensions/jbro_policy/plaza/region/r.1.1.mca'))) 'Existing plaza was imported again'
        Assert (([Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $f.TargetRoot 'server.properties')))) -eq [Convert]::ToBase64String($props)) 'Properties bytes changed'
        Assert (Test-Path (Join-Path $f.TargetRoot 'world/datapacks/policy/pack.mcmeta')) 'Feature datapack missing'
    }
    Test 'existing boot options and startup settings survive while wiki bundle is updated' {
        $f=Fixture 'startup-preservation'
        Write-File $f.TargetRoot 'run.bat' 'java -Xmx12G -jar custom-startup.jar nogui'
        Write-File $f.TargetRoot 'startup-hooks.json' ('{"server_properties":{"difficulty":{"mode":"ONCE","value":"hard"}},"wiki_files":{"sha256":"'+('1'*64)+'","bundle":"startup-assets/server-wiki.zip"}}')
        Write-File $f.TargetRoot 'world/datapacks/cobblemon-startup-hooks.zip' 'production-boot-rules'
        Write-File $f.SourceRoot 'world/datapacks/cobblemon-startup-hooks.zip' 'dev-boot-rules'
        Invoke-ServerDeployment -Plan (Get-ServerDeploymentPlan @f)|Out-Null
        Assert ([IO.File]::ReadAllText((Join-Path $f.TargetRoot 'run.bat')) -eq 'java -Xmx12G -jar custom-startup.jar nogui') 'Boot options overwritten'
        $hooks=[IO.File]::ReadAllText((Join-Path $f.TargetRoot 'startup-hooks.json'))|ConvertFrom-Json
        Assert ($hooks.server_properties.difficulty.value -eq 'hard') 'Boot setting overwritten'
        Assert ($hooks.wiki_files.sha256 -eq (Get-FileHash (Join-Path $f.TargetRoot 'startup-assets/server-wiki.zip')).Hash.ToLowerInvariant()) 'Wiki hash not updated'
        Assert ([IO.File]::ReadAllText((Join-Path $f.TargetRoot 'world/datapacks/cobblemon-startup-hooks.zip')) -eq 'production-boot-rules') 'Generated gamerules imported'
    }
    Test 'base engine version change is blocked before world conversion' {
        $f=Fixture 'engine-change';Write-File $f.TargetRoot 'fabric-server-mc.1.20.1-loader.0.19.5-launcher.1.1.2.jar' 'old-engine'
        Reject {Get-ServerDeploymentPlan @f}
        Assert (-not(Test-Path (Join-Path $f.TargetRoot 'mods'))) 'Engine mismatch wrote files'
    }
    Test 'successive deployments restore the actual latest transaction, then its predecessor' {
        $f=Fixture 'restore-chain';Invoke-ServerDeployment -Plan (Get-ServerDeploymentPlan @f)|Out-Null
        Write-File $f.SourceRoot 'config/more-cobblemon-contents/wiki/index.html' '<html>second version</html>'
        Invoke-ServerDeployment -Plan (Get-ServerDeploymentPlan @f)|Out-Null
        Restore-ServerDeployment -TargetRoot $f.TargetRoot -StateRoot $f.StateRoot|Out-Null
        Assert ([IO.File]::ReadAllText((Join-Path $f.TargetRoot 'config/more-cobblemon-contents/wiki/index.html')) -eq '<html>fixture</html>') 'Wrong deployment restored'
        Restore-ServerDeployment -TargetRoot $f.TargetRoot -StateRoot $f.StateRoot|Out-Null
        Assert (-not(Test-Path (Join-Path $f.TargetRoot 'mods/cobblemon-new.jar'))) 'Predecessor restore failed'
    }
    Test 'unsupported wiki assets stop before producing a bundle that would fail at startup' {
        $f=Fixture 'wiki-contract';Write-File $f.SourceRoot 'config/more-cobblemon-contents/wiki/assets/unsupported.json' '{"fixture":true}'
        Reject {Get-ServerDeploymentPlan @f}
        Assert (-not(Test-Path (Join-Path $f.TargetRoot 'mods'))) 'Bad wiki wrote files'
    }
    Test 'interrupted restore blocks deployment and resumes the remaining original files' {
        $f=Fixture 'partial-restore';$result=Invoke-ServerDeployment -Plan (Get-ServerDeploymentPlan @f)
        $journalPath=Join-Path $result.BackupRoot 'transaction.json';$journal=[IO.File]::ReadAllText($journalPath)|ConvertFrom-Json;$journal.Status='restoring'
        [IO.File]::WriteAllText($journalPath,($journal|ConvertTo-Json -Depth 12))
        Remove-Item -LiteralPath (Join-Path $f.TargetRoot 'mods/cobblemon-new.jar')
        Reject {Get-ServerDeploymentPlan @f}
        Restore-ServerDeployment -TargetRoot $f.TargetRoot -StateRoot $f.StateRoot|Out-Null
        Assert (-not(Test-Path (Join-Path $f.TargetRoot 'startup-hooks.json'))) 'Partial restore did not resume'
        Assert (@((Get-ServerDeploymentPlan @f).Operations).Count -gt 0) 'Restored state cannot deploy again'
    }
    Write-Output ("ALL PASS: $passed test groups. Fixtures retained at $testRoot")
} catch { Write-Output ('Fixtures: '+$testRoot); throw }
