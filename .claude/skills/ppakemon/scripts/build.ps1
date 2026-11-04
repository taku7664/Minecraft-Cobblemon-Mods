# Builds own mods from a clean origin/main snapshot with every Gradle write on F:.
# usage: build.ps1 -BuildRoot F:\AI\build\release-1.3.0 [-Tasks ':jbro-policy:assemble', ...]
param(
    [Parameter(Mandatory)][string]$BuildRoot,
    [string[]]$Tasks = @(
        ':better-cobblemon-battlecam:assemble', ':better-cobblemon-music:assemble', ':cobblemon-battle-speed:assemble', ':cobblemon-custom-species:assemble',
        ':cobblemon-dimensions:assemble', ':cobblemon-npc:assemble', ':cobblemon-party-sidebar:assemble',
        ':cobblemon-to-item:assemble', ':cobblemon-ui:assemble', ':font-glyph-race-fix:assemble', ':jbro-policy:assemble',
        ':more-cobblemon-contents:assemble', ':more-cobblemon-contents-battle-factory:assemble',
        ':more-cobblemon-contents-battle-tower:assemble', ':more-cobblemon-contents-league-challenge:assemble',
        ':more-cobblemon-contents-pvp:assemble', ':pokefusion:assemble', ':ppakemon-client-setup:assemble',
        ':more-cobblemon-contents-pwt:assemble', ':simple-trash-can:assemble'),
    [string[]]$ExtraTasks = @(),
    [string]$Ref = 'origin/main',
    [string]$Repo = 'C:\Users\박주형\Documents\GitHub\Cobblemon-Mods'
)
$Tasks = @($Tasks) + @($ExtraTasks)
$ErrorActionPreference = 'Stop'
if (-not $BuildRoot.StartsWith('F:')) { throw "BuildRoot must be on F: ($BuildRoot)" }
$root = $BuildRoot.Replace('\', '/')
New-Item -ItemType Directory -Force "$BuildRoot\tmp", "$BuildRoot\kotlin-daemon" | Out-Null

# Fresh source from origin/main (git archive through Git Bash: PowerShell's tar pipe breaks).
if (Test-Path "$BuildRoot\src") { Remove-Item -Recurse -Force -LiteralPath "$BuildRoot\src" }
New-Item -ItemType Directory "$BuildRoot\src" | Out-Null
git -C $Repo fetch -q
& 'C:\Program Files\Git\bin\bash.exe' -c "cd '$($Repo.Replace('\','/'))' && git archive --format=tar $Ref works | tar -x -C '$root/src'"
if (-not (Test-Path "$BuildRoot\src\works\gradlew.bat")) { throw 'source extraction failed' }
(git -C $Repo rev-parse $Ref) | Set-Content "$BuildRoot\src\SOURCE_COMMIT.txt"

@"
def outputRoot = new File('$root')
allprojects { p ->
    p.layout.buildDirectory.set(new File(outputRoot, p == p.rootProject ? 'root-build' : 'modules/' + p.name))
    p.tasks.withType(JavaExec).configureEach { systemProperty 'java.io.tmpdir', new File(outputRoot, 'tmp').absolutePath }
    p.tasks.withType(Test).configureEach { systemProperty 'java.io.tmpdir', new File(outputRoot, 'tmp').absolutePath }
}
gradle.projectsEvaluated {
    gradle.rootProject.allprojects.each { p ->
        if (!p.layout.buildDirectory.get().asFile.absolutePath.startsWith('F:')) throw new GradleException('Build output must be on F: ' + p.path)
    }
    if (!gradle.gradleUserHomeDir.absolutePath.startsWith('F:')) throw new GradleException('Gradle home must be on F')
    if (!gradle.startParameter.projectCacheDir.absolutePath.startsWith('F:')) throw new GradleException('Project cache must be on F')
    if (!System.getProperty('java.io.tmpdir').startsWith('F:')) throw new GradleException('JVM temporary path must be on F')
}
"@ | Set-Content "$BuildRoot\f-drive.init.gradle" -Encoding UTF8

# Stale jars of older versions would be picked up by the install scripts.
Get-ChildItem "$BuildRoot\modules\*\libs\*.jar" -ErrorAction SilentlyContinue | Remove-Item

$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
$env:GRADLE_USER_HOME = 'F:/AI/caches/gradle'
$env:TEMP = "$root/tmp"; $env:TMP = $env:TEMP
$env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=$root/tmp -Dkotlin.daemon.runFilesPath=$root/kotlin-daemon"
Push-Location "$BuildRoot\src\works"
try {
    & .\gradlew.bat @Tasks --offline --no-daemon --configure-on-demand --console=plain --max-workers=2 `
        --project-cache-dir "$root/project-cache" --init-script "$root/f-drive.init.gradle" `
        "-Dorg.gradle.jvmargs=-Xmx2G -Dfile.encoding=UTF-8 -Djava.io.tmpdir=$root/tmp" `
        '-Pkotlin.compiler.execution.strategy=in-process' "-Pkotlin.project.persistent.dir=$root/kotlin-project" '-Dfabric.loom.ci=true'
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed: $LASTEXITCODE" }
} finally { Pop-Location }

$bad = Get-ChildItem "$BuildRoot\modules\*\libs\*.jar" | Where-Object { $_.Name -notmatch '-(sources|dev|javadoc)\.jar$' } | ForEach-Object {
    & 'C:\Program Files\Java\jdk-21\bin\jar.exe' --validate --file $_.FullName 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { $_.Name }
}
if ($bad) { throw "jar --validate failed: $($bad -join ', ')" }
"BUILD OK from $(Get-Content "$BuildRoot\src\SOURCE_COMMIT.txt")"
