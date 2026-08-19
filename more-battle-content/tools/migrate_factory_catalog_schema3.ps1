param(
    [Parameter(Mandatory = $true)]
    [string]$CobblemonJar,
    [string]$CatalogPath = (Join-Path $PSScriptRoot '..\more-battle-content\src\main\resources\data\cobblemon_more_battle_content\battle_factory\catalog\mbc_core.json'),
    [switch]$Write
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $CobblemonJar -PathType Leaf)) {
    throw "Cobblemon JAR does not exist: $CobblemonJar"
}
if (-not (Test-Path -LiteralPath $CatalogPath -PathType Leaf)) {
    throw "Factory catalog does not exist: $CatalogPath"
}

$catalog = Get-Content -Raw -LiteralPath $CatalogPath | ConvertFrom-Json
if ($catalog.schema_version -ne 2) {
    throw "Expected schema 2 input, found schema $($catalog.schema_version)"
}

$statusFamilies = @(
    @('protect', 'detect', 'kingsshield'),
    @('reflect', 'lightscreen', 'auroraveil', 'safeguard'),
    @('tailwind'),
    @('swordsdance', 'dragondance', 'bulkup', 'curse', 'coil', 'honeclaws', 'howl', 'bellydrum'),
    @('nastyplot', 'calmmind', 'quiverdance', 'tailglow'),
    @('agility', 'rockpolish', 'autotomize'),
    @('recover', 'roost', 'slackoff', 'strengthsap', 'morningsun', 'synthesis', 'softboiled', 'wish'),
    @('taunt', 'encore', 'disable', 'trick', 'switcheroo'),
    @('willowisp', 'thunderwave', 'glare', 'toxic', 'sleeppowder', 'spore', 'yawn'),
    @('haze', 'whirlwind', 'roar', 'clearsmog'),
    @('stealthrock', 'spikes', 'toxicspikes', 'stickyweb'),
    @('followme', 'ragepowder', 'helpinghand'),
    @('leechseed', 'strengthsap'),
    @('substitute', 'protect'),
    @('trickroom'),
    @('rest'),
    @('sleeptalk'),
    @('clangoroussoul'),
    @('shellsmash'),
    @('chillyreception')
)

$itemCandidates = @{
    'cobblemon:air_balloon'          = @('cobblemon:air_balloon', 'cobblemon:leftovers', 'cobblemon:sitrus_berry')
    'cobblemon:assault_vest'         = @('cobblemon:assault_vest', 'cobblemon:expert_belt', 'cobblemon:sitrus_berry')
    'cobblemon:babiri_berry'         = @('cobblemon:babiri_berry', 'cobblemon:leftovers', 'cobblemon:sitrus_berry')
    'cobblemon:black_glasses'        = @('cobblemon:black_glasses', 'cobblemon:life_orb', 'cobblemon:focus_sash')
    'cobblemon:black_sludge'         = @('cobblemon:black_sludge', 'cobblemon:leftovers', 'cobblemon:rocky_helmet')
    'cobblemon:chesto_berry'         = @('cobblemon:chesto_berry', 'cobblemon:leftovers', 'cobblemon:sitrus_berry')
    'cobblemon:choice_band'          = @('cobblemon:choice_band', 'cobblemon:life_orb', 'cobblemon:expert_belt')
    'cobblemon:choice_scarf'         = @('cobblemon:choice_scarf', 'cobblemon:focus_sash', 'cobblemon:expert_belt')
    'cobblemon:choice_specs'         = @('cobblemon:choice_specs', 'cobblemon:life_orb', 'cobblemon:expert_belt')
    'cobblemon:clear_amulet'         = @('cobblemon:clear_amulet', 'cobblemon:lum_berry', 'cobblemon:life_orb')
    'cobblemon:colbur_berry'         = @('cobblemon:colbur_berry', 'cobblemon:leftovers', 'cobblemon:life_orb')
    'cobblemon:damp_rock'            = @('cobblemon:damp_rock', 'cobblemon:focus_sash', 'cobblemon:safety_goggles')
    'cobblemon:eviolite'             = @('cobblemon:eviolite')
    'cobblemon:expert_belt'          = @('cobblemon:expert_belt', 'cobblemon:life_orb', 'cobblemon:focus_sash')
    'cobblemon:flame_orb'            = @('cobblemon:flame_orb', 'cobblemon:choice_band', 'cobblemon:focus_sash')
    'cobblemon:focus_sash'           = @('cobblemon:focus_sash', 'cobblemon:life_orb', 'cobblemon:lum_berry')
    'cobblemon:heavy_duty_boots'     = @('cobblemon:heavy_duty_boots', 'cobblemon:leftovers', 'cobblemon:life_orb')
    'cobblemon:icy_rock'             = @('cobblemon:icy_rock', 'cobblemon:focus_sash', 'cobblemon:light_clay')
    'cobblemon:leftovers'            = @('cobblemon:leftovers', 'cobblemon:sitrus_berry', 'cobblemon:rocky_helmet')
    'cobblemon:life_orb'             = @('cobblemon:life_orb', 'cobblemon:focus_sash', 'cobblemon:lum_berry')
    'cobblemon:light_clay'           = @('cobblemon:light_clay', 'cobblemon:mental_herb', 'cobblemon:focus_sash')
    'cobblemon:loaded_dice'          = @('cobblemon:loaded_dice', 'cobblemon:focus_sash', 'cobblemon:white_herb')
    'cobblemon:lum_berry'            = @('cobblemon:lum_berry', 'cobblemon:life_orb', 'cobblemon:clear_amulet')
    'cobblemon:magnet'               = @('cobblemon:magnet', 'cobblemon:life_orb', 'cobblemon:focus_sash')
    'cobblemon:mental_herb'          = @('cobblemon:mental_herb', 'cobblemon:focus_sash', 'cobblemon:sitrus_berry')
    'cobblemon:metal_coat'           = @('cobblemon:metal_coat', 'cobblemon:life_orb', 'cobblemon:leftovers')
    'cobblemon:miracle_seed'         = @('cobblemon:miracle_seed', 'cobblemon:life_orb', 'cobblemon:focus_sash')
    'cobblemon:mystic_water'         = @('cobblemon:mystic_water', 'cobblemon:life_orb', 'cobblemon:choice_band')
    'cobblemon:never_melt_ice'       = @('cobblemon:never_melt_ice', 'cobblemon:leftovers', 'cobblemon:heavy_duty_boots')
    'cobblemon:power_herb'           = @('cobblemon:power_herb', 'cobblemon:life_orb', 'cobblemon:focus_sash')
    'cobblemon:red_card'             = @('cobblemon:red_card', 'cobblemon:leftovers', 'cobblemon:focus_sash')
    'cobblemon:rocky_helmet'         = @('cobblemon:rocky_helmet', 'cobblemon:leftovers', 'cobblemon:sitrus_berry')
    'cobblemon:safety_goggles'       = @('cobblemon:safety_goggles', 'cobblemon:heavy_duty_boots', 'cobblemon:leftovers')
    'cobblemon:sharp_beak'           = @('cobblemon:sharp_beak', 'cobblemon:life_orb', 'cobblemon:focus_sash')
    'cobblemon:sitrus_berry'         = @('cobblemon:sitrus_berry', 'cobblemon:leftovers', 'cobblemon:lum_berry')
    'cobblemon:smooth_rock'          = @('cobblemon:smooth_rock', 'cobblemon:leftovers', 'cobblemon:rocky_helmet')
    'cobblemon:spell_tag'            = @('cobblemon:spell_tag', 'cobblemon:life_orb', 'cobblemon:focus_sash')
    'cobblemon:throat_spray'         = @('cobblemon:throat_spray', 'cobblemon:life_orb', 'cobblemon:sitrus_berry')
    'cobblemon:toxic_orb'            = @('cobblemon:toxic_orb', 'cobblemon:leftovers', 'cobblemon:rocky_helmet')
    'cobblemon:weakness_policy'      = @('cobblemon:weakness_policy', 'cobblemon:lum_berry', 'cobblemon:life_orb')
    'cobblemon:white_herb'           = @('cobblemon:white_herb', 'cobblemon:focus_sash', 'cobblemon:lum_berry')
    'cobblemon:wide_lens'            = @('cobblemon:wide_lens', 'cobblemon:focus_sash', 'cobblemon:life_orb')
    'minecraft:charcoal'             = @('minecraft:charcoal', 'cobblemon:life_orb', 'cobblemon:focus_sash')
}

$excludedAlternatives = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
@(
    'explosion', 'selfdestruct', 'mistyexplosion', 'finalgambit',
    'hyperbeam', 'gigaimpact', 'blastburn', 'frenzyplant', 'hydrocannon', 'rockwrecker', 'eternabeam', 'prismaticlaser',
    'lastresort', 'naturalgift', 'frustration', 'return', 'hiddenpower', 'flail', 'reversal', 'present', 'bide', 'metronome', 'assist',
    'dig', 'dive', 'fly', 'skyattack', 'solarbeam', 'solarblade', 'focuspunch', 'dreameater', 'steelbeam', 'meteorbeam',
    'round', 'uproar', 'risingvoltage', 'expandingforce', 'venoshock', 'hex', 'assurance', 'revenge', 'avalanche', 'brine',
    'retaliate', 'wakeupslap', 'upperhand', 'skydrop', 'steelroller', 'chipaway', 'headbutt', 'hornattack', 'covet', 'stomp',
    'dizzypunch', 'strength', 'secretpower', 'phantomforce',
    'fissure', 'guillotine', 'horndrill', 'sheercold'
) | ForEach-Object { [void]$excludedAlternatives.Add($_) }

$fixedRoleMoves = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
@(
    'fakeout', 'extremespeed', 'aquajet', 'bulletpunch', 'iceshard', 'shadowsneak', 'machpunch', 'grassyglide', 'nuzzle',
    'uturn', 'voltswitch', 'flipturn', 'rapidspin', 'mortalspin', 'quickguard', 'wideguard', 'weatherball'
) | ForEach-Object { [void]$fixedRoleMoves.Add($_) }

$moveOverrides = @{
    'bravebird'   = @('dualwingbeat')
    'crosspoison' = @('poisonfang')
    'facade'      = @('doubleedge', 'bodyslam')
    'bodyslam'    = @('doubleedge', 'facade')
}

$setItemOverrides = @{
    's1_whimsicott' = @('cobblemon:focus_sash', 'cobblemon:mental_herb', 'cobblemon:sitrus_berry')
    'a2_sableye'    = @('cobblemon:focus_sash', 'cobblemon:mental_herb', 'cobblemon:sitrus_berry')
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$outer = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $CobblemonJar))
$tempRoot = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ("mbc-factory-schema3-" + [guid]::NewGuid().ToString('N'))))
[void][IO.Directory]::CreateDirectory($tempRoot)
try {
    $showdownEntry = $outer.GetEntry('data/cobblemon/showdown.zip')
    if ($null -eq $showdownEntry) { throw 'Cobblemon JAR has no data/cobblemon/showdown.zip' }
    $showdownBytes = [IO.MemoryStream]::new()
    $showdownStream = $showdownEntry.Open()
    try { $showdownStream.CopyTo($showdownBytes) } finally { $showdownStream.Dispose() }
    $showdownBytes.Position = 0
    $showdown = [IO.Compression.ZipArchive]::new($showdownBytes, [IO.Compression.ZipArchiveMode]::Read, $false)
    try {
        $movesEntry = $showdown.GetEntry('data/moves.js')
        if ($null -eq $movesEntry) { throw 'Cobblemon showdown bundle has no data/moves.js' }
        $movesPath = Join-Path $tempRoot 'moves.js'
        $target = [IO.File]::Create($movesPath)
        $source = $movesEntry.Open()
        try { $source.CopyTo($target) } finally { $source.Dispose(); $target.Dispose() }
    } finally {
        $showdown.Dispose()
    }

    $nodeScript = 'const m=require(process.argv[1]).Moves; console.log(JSON.stringify(m));'
    $moveDataJson = (& node -e $nodeScript $movesPath) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'Node failed to read Cobblemon move metadata' }
    $moveData = $moveDataJson | ConvertFrom-Json

    $speciesLearnsets = @{}
    foreach ($set in $catalog.sets) {
        $speciesName = [string]$set.species_id -replace '^cobblemon:', ''
        $cacheKey = "$speciesName|$($set.form_id)"
        if ($speciesLearnsets.ContainsKey($cacheKey)) { continue }
        $speciesEntry = $outer.Entries | Where-Object {
            $_.FullName -like "data/cobblemon/species/*/$speciesName.json"
        } | Select-Object -First 1
        if ($null -eq $speciesEntry) { throw "Missing Cobblemon species data for $($set.species_id)" }
        $reader = [IO.StreamReader]::new($speciesEntry.Open())
        try { $species = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
        $learnset = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        @($species.moves) | ForEach-Object { [void]$learnset.Add(([string]$_).Substring(([string]$_).IndexOf(':') + 1)) }
        if ($null -ne $set.form_id) {
            $form = @($species.forms) | Where-Object { $_.name -eq $set.form_id } | Select-Object -First 1
            if ($null -ne $form) {
                @($form.moves) | ForEach-Object { [void]$learnset.Add(([string]$_).Substring(([string]$_).IndexOf(':') + 1)) }
            }
        }
        $speciesLearnsets[$cacheKey] = $learnset
    }

    $slotCounts = @()
    foreach ($set in $catalog.sets) {
        $speciesName = [string]$set.species_id -replace '^cobblemon:', ''
        $learnset = $speciesLearnsets["$speciesName|$($set.form_id)"]
        $used = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        $baseMoves = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        @($set.moves) | ForEach-Object { [void]$baseMoves.Add(([string]$_ -replace '^cobblemon:', '')) }
        $slots = [Collections.Generic.List[object]]::new()
        foreach ($moveId in @($set.moves)) {
            $moveName = [string]$moveId -replace '^cobblemon:', ''
            if (-not $learnset.Contains($moveName)) {
                throw "$($set.set_id) cannot learn its existing move $moveId in Cobblemon 1.7.3"
            }
            $current = $moveData.PSObject.Properties[$moveName].Value
            if ($null -eq $current) { throw "Missing Showdown metadata for $moveId" }
            $candidateNames = [Collections.Generic.List[string]]::new()
            [void]$candidateNames.Add($moveName)
            foreach ($candidate in @($moveOverrides[$moveName])) {
                if (
                    $null -ne $candidate -and
                    -not $baseMoves.Contains($candidate) -and
                    $learnset.Contains($candidate) -and
                    -not $used.Contains($candidate)
                ) {
                    [void]$candidateNames.Add($candidate)
                }
            }
            if ($fixedRoleMoves.Contains($moveName)) {
                # These moves define their slot through priority, switching, hazard removal, or weather conversion.
            } elseif ($current.category -eq 'Status') {
                $family = $statusFamilies | Where-Object { $moveName -in $_ } | Select-Object -First 1
                foreach ($candidate in @($family)) {
                    if (
                        $candidate -ne $moveName -and
                        -not $baseMoves.Contains($candidate) -and
                        $learnset.Contains($candidate) -and
                        -not $used.Contains($candidate)
                    ) {
                        [void]$candidateNames.Add($candidate)
                    }
                }
            } else {
                $alternatives = foreach ($candidate in $learnset) {
                    if (
                        $candidate -eq $moveName -or
                        $baseMoves.Contains($candidate) -or
                        $used.Contains($candidate) -or
                        $excludedAlternatives.Contains($candidate)
                    ) { continue }
                    $metadata = $moveData.PSObject.Properties[$candidate].Value
                    if ($null -eq $metadata -or $metadata.category -ne $current.category -or $metadata.type -ne $current.type) { continue }
                    $power = [int]$metadata.basePower
                    $accuracy = if ($metadata.accuracy -is [bool]) { 100 } else { [int]$metadata.accuracy }
                    $priority = [int]$metadata.priority
                    if ($power -lt 70 -and $priority -le 0) { continue }
                    if ($accuracy -lt 70) { continue }
                    if (([int]$current.priority -gt 0) -ne ($priority -gt 0)) { continue }
                    [pscustomobject]@{
                        Name = $candidate
                        Score = [Math]::Abs(([int]$current.basePower) - $power) + $(if ($accuracy -lt 90) { 10 } else { 0 })
                    }
                }
                foreach ($alternative in @($alternatives | Sort-Object Score, Name | Select-Object -First 2)) {
                    [void]$candidateNames.Add($alternative.Name)
                }
            }
            $uniqueCandidates = @($candidateNames | Select-Object -Unique | Select-Object -First 3)
            foreach ($candidate in $uniqueCandidates) { [void]$used.Add($candidate) }
            [void]$slots.Add([string[]]($uniqueCandidates | ForEach-Object { "cobblemon:$_" }))
        }
        if (@($slots).Count -ne 4) { throw "$($set.set_id) does not have exactly four move slots" }
        $flattened = @($slots | ForEach-Object { @($_) })
        if (@($flattened | Select-Object -Unique).Count -ne $flattened.Count) {
            throw "$($set.set_id) reuses a move candidate across slots"
        }
        if (-not (@($slots) | Where-Object { @($_).Count -gt 1 })) {
            throw "$($set.set_id) has no randomized move slot after role-preserving filtering"
        }
        $items = $setItemOverrides[[string]$set.set_id]
        if ($null -eq $items) { $items = $itemCandidates[[string]$set.held_item_id] }
        if ($null -eq $items) { throw "No item candidates defined for $($set.held_item_id)" }

        $set.PSObject.Properties.Remove('moves')
        $set.PSObject.Properties.Remove('held_item_id')
        $set.PSObject.Properties.Remove('nature_id')
        $set | Add-Member -NotePropertyName move_slots -NotePropertyValue ([object[]]$slots)
        $set | Add-Member -NotePropertyName held_items -NotePropertyValue ([string[]]$items)
        $set | Add-Member -NotePropertyName nature_pool -NotePropertyValue 'all'
        $slotCounts += @($slots | Where-Object { @($_).Count -gt 1 }).Count
    }

    $catalog.schema_version = 3
    $json = $catalog | ConvertTo-Json -Depth 100
    "sets=$($catalog.sets.Count) randomized_slots=$($slotCounts | Measure-Object -Sum | Select-Object -ExpandProperty Sum) min_randomized_slots=$($slotCounts | Measure-Object -Minimum | Select-Object -ExpandProperty Minimum) max_randomized_slots=$($slotCounts | Measure-Object -Maximum | Select-Object -ExpandProperty Maximum)"
    if ($Write) {
        [IO.File]::WriteAllText((Resolve-Path -LiteralPath $CatalogPath), $json + "`n", [Text.UTF8Encoding]::new($false))
        "wrote=$((Resolve-Path -LiteralPath $CatalogPath).Path)"
    }
} finally {
    $outer.Dispose()
    $resolvedTemp = [IO.Path]::GetFullPath($tempRoot)
    $systemTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if (-not $resolvedTemp.StartsWith($systemTemp, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to delete a non-temporary path: $resolvedTemp"
    }
    if ([IO.Directory]::Exists($resolvedTemp)) { [IO.Directory]::Delete($resolvedTemp, $true) }
}
