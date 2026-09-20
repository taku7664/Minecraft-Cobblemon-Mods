#requires -Version 7.0

param(
    [string]$Version = "0.1.0"
)

$ErrorActionPreference = "Stop"
$packRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$shaderRoot = Join-Path $packRoot "shaders"
$shaderRootPrefix = [System.IO.Path]::GetFullPath($shaderRoot).TrimEnd(
    [System.IO.Path]::DirectorySeparatorChar,
    [System.IO.Path]::AltDirectorySeparatorChar
) + [System.IO.Path]::DirectorySeparatorChar
$errors = [System.Collections.Generic.List[string]]::new()

function Expand-ShaderIncludes {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()]
        [System.Collections.Generic.HashSet[string]]$Stack
    )

    $resolved = [System.IO.Path]::GetFullPath($Path)
    if (-not $resolved.StartsWith($shaderRootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Shader include escapes shaders/: $resolved"
    }
    if (-not $Stack.Add($resolved)) {
        throw "Circular shader include: $resolved"
    }
    try {
        $source = Get-Content -LiteralPath $resolved -Raw
        return [regex]::Replace(
            $source,
            '(?m)^\s*#include\s+"/([^"]+)"\s*$',
            {
                param($match)
                $included = Join-Path $shaderRoot $match.Groups[1].Value
                Expand-ShaderIncludes -Path $included -Stack $Stack
            }
        )
    }
    finally {
        [void]$Stack.Remove($resolved)
    }
}

$propertiesPath = Join-Path $shaderRoot "shaders.properties"
$propertiesSource = Get-Content -LiteralPath $propertiesPath -Raw
if ($propertiesSource -match '(?m)^\s*clouds\s*=\s*(default|true|false)\s*$') {
    $errors.Add("Unsupported clouds value; use fast, fancy, off, or omit the directive")
}

$compositePath = Join-Path $shaderRoot "composite.fsh"
$compositeSource = Get-Content -LiteralPath $compositePath -Raw
if ($compositeSource -notmatch 'uniform\s+sampler2DShadow\s+shadowtex0\s*;') {
    $errors.Add("Composite must use Iris hardware shadow comparison sampling")
}
if ($compositeSource -match 'texture\s*\(\s*shadowtex0\s*,\s*[^\r\n]*\)\.r') {
    $errors.Add("Composite must not manually compare raw shadowtex0 depth")
}

$distortPath = Join-Path $shaderRoot "lib\distort.glsl"
$distortSource = Get-Content -LiteralPath $distortPath -Raw
if ($distortSource -notmatch 'const\s+bool\s+shadowHardwareFiltering\s*=\s*true\s*;') {
    $errors.Add("Iris hardware shadow filtering must be enabled")
}

$shadowSource = Get-Content -LiteralPath (Join-Path $shaderRoot "shadow.fsh") -Raw
if ($shadowSource -match '\bgl_FragDepth\b') {
    $errors.Add("Base shadow pass must use automatic depth writes so early depth tests remain available")
}
foreach ($stage in @("vsh", "fsh")) {
    if (-not (Test-Path -LiteralPath (Join-Path $shaderRoot "shadow_solid.$stage") -PathType Leaf)) {
        $errors.Add("Missing texture-free solid shadow stage: shadow_solid.$stage")
    }
}
if ($compositeSource -notmatch 'const\s+vec2\s+SHADOW_KERNEL') {
    $errors.Add("Composite must reuse a precomputed shadow kernel instead of evaluating trigonometry per tap")
}
if ($compositeSource -notmatch '(?s)if\s*\(isPrelitCloud\).*?return\s*;.*?texture\s*\(\s*colortex2\s*,') {
    $errors.Add("Prelit clouds must return before the terrain normal-buffer lookup")
}

$fogLibraryPath = Join-Path $shaderRoot "lib\fog.glsl"
if (-not (Test-Path -LiteralPath $fogLibraryPath -PathType Leaf)) {
    $errors.Add("Missing volumetric fog library: lib/fog.glsl")
}
else {
    $fogLibrarySource = Get-Content -LiteralPath $fogLibraryPath -Raw
    foreach ($requiredFunction in @("integrateGroundMist", "computeCaveFog", "biomeFogColor")) {
        if ($fogLibrarySource -notmatch "\b$requiredFunction\s*\(") {
            $errors.Add("Fog library is missing required function: $requiredFunction")
        }
    }
}
if ($compositeSource -notmatch '#include\s+"/lib/fog\.glsl"') {
    $errors.Add("Composite must include the shared volumetric fog library")
}

foreach ($uniformName in @("lumavaleBiomeDry", "lumavaleBiomeRainy", "lumavaleBiomeSnowy", "lumavaleSkyExposure")) {
    if ($propertiesSource -notmatch "(?m)^\s*uniform\.float\.$uniformName\s*=") {
        $errors.Add("Missing smoothed Iris fog uniform: $uniformName")
    }
}

$settingsPath = Join-Path $shaderRoot "lib\settings.glsl"
$settingsSource = Get-Content -LiteralPath $settingsPath -Raw
foreach ($settingName in @("GROUND_FOG_STRENGTH", "CAVE_FOG_STRENGTH", "BIOME_FOG_STRENGTH", "GROUND_FOG_SAMPLES")) {
    if ($settingsSource -notmatch "(?m)^\s*#define\s+$settingName\b") {
        $errors.Add("Missing fog setting: $settingName")
    }
    if ($propertiesSource -notmatch "\b$settingName\b") {
        $errors.Add("Fog setting is not exposed in shaders.properties: $settingName")
    }
    foreach ($locale in @("ko_kr", "en_us")) {
        $languageSource = Get-Content -LiteralPath (Join-Path $shaderRoot "lang\$locale.lang") -Raw
        if ($languageSource -notmatch "(?m)^option\.$settingName=") {
            $errors.Add("Fog setting is missing $locale localization: $settingName")
        }
    }
}

$cloudFragmentPath = Join-Path $shaderRoot "gbuffers_clouds.fsh"
$cloudFragmentSource = Get-Content -LiteralPath $cloudFragmentPath -Raw
if ($cloudFragmentSource -notmatch '/\*\s*DRAWBUFFERS:01\s*\*/') {
    $errors.Add("Cloud pass must write only color and light-data buffers")
}
if ($cloudFragmentSource -match 'gl_FragData\s*\[\s*2\s*\]') {
    $errors.Add("Prelit clouds must not write the unused terrain normal buffer")
}

$texturedVertexPath = Join-Path $shaderRoot "program\gbuffer_textured.vsh"
$texturedVertexSource = Get-Content -LiteralPath $texturedVertexPath -Raw
if ($texturedVertexSource -match 'normalize\s*\(\s*gl_NormalMatrix\s*\*\s*gl_Normal\s*\)') {
    $errors.Add("Textured vertex path normalizes twice; keep only the final world-space normalization")
}

Get-ChildItem -LiteralPath $shaderRoot -Recurse -File -Include *.vsh, *.fsh, *.glsl | ForEach-Object {
    $lines = Get-Content -LiteralPath $_.FullName
    $relativePath = [System.IO.Path]::GetRelativePath($shaderRoot, $_.FullName)
    $isEntryPoint = -not $relativePath.StartsWith("program$([System.IO.Path]::DirectorySeparatorChar)") -and $_.Extension -in @(".vsh", ".fsh")

    if ($isEntryPoint -and ($lines.Count -eq 0 -or $lines[0] -notmatch '^#version ')) {
        $errors.Add("#version is not first: $relativePath")
    }
    $insideBlockComment = $false
    foreach ($line in $lines) {
        if ($line -match '/\*') {
            $insideBlockComment = $true
        }
        if (-not $insideBlockComment -and $line -match '^\s*const\s+int\s+(color|colortex|shadowcolor)\w*Format\s*=\s*[A-Z][A-Z0-9_]*\s*;') {
            $errors.Add("Buffer format directives must be inside a block comment: $relativePath")
        }
        if ($line -match '\*/') {
            $insideBlockComment = $false
        }
    }

    $source = $lines -join "`n"
    if ($source -match 'const\s+vec4\s+colortex\d+ClearColor\s*=\s*vec4\s*\(\s*[^,()]+\s*\)') {
        $errors.Add("Iris 1.8.8 requires four explicit clear-color components: $relativePath")
    }
    $openBraces = ($source.ToCharArray() | Where-Object { $_ -eq "{" }).Count
    $closeBraces = ($source.ToCharArray() | Where-Object { $_ -eq "}" }).Count
    if ($openBraces -ne $closeBraces) {
        $errors.Add("Unbalanced braces: $relativePath")
    }

    foreach ($match in [regex]::Matches($source, '#include\s+"/([^"]+)"')) {
        $includePath = Join-Path $shaderRoot $match.Groups[1].Value
        if (-not (Test-Path -LiteralPath $includePath -PathType Leaf)) {
            $errors.Add("Missing include: $relativePath -> $($match.Groups[1].Value)")
        }
    }
}

$glslang = Get-Command glslangValidator -ErrorAction SilentlyContinue
if ($null -eq $glslang) {
    $errors.Add("glslangValidator is required for release shader validation")
}
else {
    $compiledPrograms = 0
    Get-ChildItem -LiteralPath $shaderRoot -File -Include *.vsh, *.fsh | ForEach-Object {
        $stage = if ($_.Extension -eq ".vsh") { "vert" } else { "frag" }
        $temporaryPath = [System.IO.Path]::Combine(
            [System.IO.Path]::GetTempPath(),
            "lumavale-$([System.Guid]::NewGuid().ToString('N')).$stage"
        )
        try {
            $includeStack = [System.Collections.Generic.HashSet[string]]::new(
                [System.StringComparer]::OrdinalIgnoreCase
            )
            $expanded = Expand-ShaderIncludes -Path $_.FullName -Stack $includeStack
            [System.IO.File]::WriteAllText($temporaryPath, $expanded, [System.Text.UTF8Encoding]::new($false))
            $compilerOutput = & $glslang.Source -S $stage $temporaryPath 2>&1
            if ($LASTEXITCODE -ne 0) {
                $relativePath = [System.IO.Path]::GetRelativePath($shaderRoot, $_.FullName)
                $errors.Add("GLSL compile failed: $relativePath`n$($compilerOutput -join "`n")")
            }
            else {
                $compiledPrograms++
            }
        }
        finally {
            if ([System.IO.File]::Exists($temporaryPath)) {
                [System.IO.File]::Delete($temporaryPath)
            }
        }
    }
    Write-Output "Compiled $compiledPrograms GLSL entry stages with glslangValidator"

    $linkedPrograms = 0
    Get-ChildItem -LiteralPath $shaderRoot -File -Filter *.vsh | ForEach-Object {
        $fragmentPath = [System.IO.Path]::ChangeExtension($_.FullName, ".fsh")
        if (-not (Test-Path -LiteralPath $fragmentPath -PathType Leaf)) {
            $errors.Add("Missing fragment stage for vertex entry: $($_.Name)")
            return
        }
        $vertexTemporaryPath = [System.IO.Path]::Combine(
            [System.IO.Path]::GetTempPath(),
            "lumavale-$([System.Guid]::NewGuid().ToString('N')).vert"
        )
        $fragmentTemporaryPath = [System.IO.Path]::Combine(
            [System.IO.Path]::GetTempPath(),
            "lumavale-$([System.Guid]::NewGuid().ToString('N')).frag"
        )
        try {
            $vertexStack = [System.Collections.Generic.HashSet[string]]::new(
                [System.StringComparer]::OrdinalIgnoreCase
            )
            $fragmentStack = [System.Collections.Generic.HashSet[string]]::new(
                [System.StringComparer]::OrdinalIgnoreCase
            )
            $expandedVertex = Expand-ShaderIncludes -Path $_.FullName -Stack $vertexStack
            $expandedFragment = Expand-ShaderIncludes -Path $fragmentPath -Stack $fragmentStack
            [System.IO.File]::WriteAllText(
                $vertexTemporaryPath, $expandedVertex, [System.Text.UTF8Encoding]::new($false)
            )
            [System.IO.File]::WriteAllText(
                $fragmentTemporaryPath, $expandedFragment, [System.Text.UTF8Encoding]::new($false)
            )
            $linkerOutput = & $glslang.Source -l $vertexTemporaryPath $fragmentTemporaryPath 2>&1
            if ($LASTEXITCODE -ne 0) {
                $errors.Add("GLSL link failed: $($_.BaseName)`n$($linkerOutput -join "`n")")
            }
            else {
                $linkedPrograms++
            }
        }
        finally {
            foreach ($temporaryPath in @($vertexTemporaryPath, $fragmentTemporaryPath)) {
                if ([System.IO.File]::Exists($temporaryPath)) {
                    [System.IO.File]::Delete($temporaryPath)
                }
            }
        }
    }
    Write-Output "Linked $linkedPrograms GLSL program pairs with glslangValidator"
}

if ($errors.Count -gt 0) {
    $errors | ForEach-Object { Write-Error $_ -ErrorAction Continue }
    throw "LumaVale validation failed with $($errors.Count) error(s)"
}

& (Join-Path $packRoot "build.ps1") -Version $Version | Out-Null
$zipPath = Join-Path $packRoot "dist\LumaVale-$Version.zip"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
try {
    $invalidEntries = @($archive.Entries | Where-Object { -not $_.FullName.StartsWith("shaders/") })
    if ($invalidEntries.Count -gt 0) {
        throw "ZIP contains entries outside shaders/: $($invalidEntries.FullName -join ', ')"
    }
    Write-Output "Validated $($archive.Entries.Count) ZIP entries in LumaVale-$Version.zip"
}
finally {
    $archive.Dispose()
}

$global:LASTEXITCODE = 0
