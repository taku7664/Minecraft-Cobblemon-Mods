@echo off
rem Builds the test-side battle runner (betterai-arena-runner.jar + lib\) into the runner folder of arena.properties.
rem Every Gradle write goes to F (AGENTS.md "Gradle 쓰기 경로"); the build folder is removed afterwards.
setlocal
set B=F:/AI/builds/cobblemon-mods-arena
set RUNNER=F:/AI/betterai-arena/runner
set GRADLE_USER_HOME=F:/AI/caches/gradle
set TEMP=%B%/tmp
set TMP=%B%/tmp
if not exist "%B%\tmp" mkdir "%B%\tmp"
> "%B%/f-build-dirs.init.gradle" echo allprojects { layout.buildDirectory.set(file("%B%/out/${project.name}")) }
pushd "%~dp0..\.."
call gradlew.bat --no-daemon --max-workers=1 --project-cache-dir %B%/project-cache -I %B%/f-build-dirs.init.gradle "-Dorg.gradle.jvmargs=-Xmx2G -Djava.io.tmpdir=%B%/tmp" -Pkotlin.compiler.execution.strategy=in-process :more-cobblemon-contents:arenaRunner -ParenaRunnerDir=%RUNNER%
set CODE=%ERRORLEVEL%
popd
if not "%CODE%"=="0" exit /b %CODE%
rmdir /s /q "F:\AI\builds\cobblemon-mods-arena"
echo runner: %RUNNER%
