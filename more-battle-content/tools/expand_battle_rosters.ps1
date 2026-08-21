param(
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
)

$ErrorActionPreference = 'Stop'

$towerPath = Join-Path $ProjectRoot 'src\main\resources\data\cobblemon_more_battle_content\battle_tower\opponents\mbc_core.json'
$factoryPath = Join-Path $ProjectRoot 'src\main\resources\data\cobblemon_more_battle_content\battle_factory\catalog\mbc_core.json'
$englishPath = Join-Path $ProjectRoot 'src\main\resources\assets\cobblemon_more_battle_content\lang\en_us.json'
$koreanPath = Join-Path $ProjectRoot 'src\main\resources\assets\cobblemon_more_battle_content\lang\ko_kr.json'

$tower = Get-Content -Raw -LiteralPath $towerPath | ConvertFrom-Json
$factory = Get-Content -Raw -LiteralPath $factoryPath | ConvertFrom-Json
$english = Get-Content -Raw -LiteralPath $englishPath | ConvertFrom-Json
$korean = Get-Content -Raw -LiteralPath $koreanPath | ConvertFrom-Json

$englishFirstNames = @(
    'Aiden', 'Bianca', 'Caleb', 'Daphne', 'Elliot', 'Fiona',
    'Gavin', 'Hazel', 'Isaac', 'Jenna', 'Kai', 'Leah'
)
$englishLastNames = @(
    'Brooks', 'Chen', 'Diaz', 'Foster', 'Grant',
    'Hayes', 'Ito', 'Klein', 'Morgan', 'Park'
)
$koreanSurnames = @('김', '이', '박', '최', '정', '강', '조', '윤', '장', '임')
$koreanGivenNames = @(
    '도윤', '서진', '하준', '지안', '예준', '서우',
    '민호', '가람', '유림', '태민', '나희', '주원'
)

function Get-EnglishTrainerName([int]$index) {
    $first = $englishFirstNames[$index % $englishFirstNames.Count]
    $last = $englishLastNames[[math]::Floor($index / $englishFirstNames.Count) % $englishLastNames.Count]
    return "$first $last"
}

function Get-KoreanTrainerName([int]$index) {
    $surname = $koreanSurnames[$index % $koreanSurnames.Count]
    $given = $koreanGivenNames[[math]::Floor($index / $koreanSurnames.Count) % $koreanGivenNames.Count]
    return "$surname$given"
}

function Add-Translation($language, [string]$key, [string]$value) {
    $language | Add-Member -MemberType NoteProperty -Name $key -Value $value -Force
}

function Copy-StatSpread($spread) {
    return [ordered]@{
        hp = [int]$spread.hp
        attack = [int]$spread.attack
        defense = [int]$spread.defense
        special_attack = [int]$spread.special_attack
        special_defense = [int]$spread.special_defense
        speed = [int]$spread.speed
    }
}

$baseTowerProfiles = @($tower.profiles | Where-Object { $_.profile_id -notmatch '_roster_[234]$' })
$baseTowerSets = @($tower.sets | Where-Object { $_.set_id -notlike 'roster_*' })
$expandedTowerSets = [System.Collections.Generic.List[object]]::new()
$towerPools = @{}

foreach ($mechanic in @('mega', 'dynamax', 'tera')) {
    foreach ($tier in 1..2) {
        $selected = [System.Collections.Generic.List[object]]::new()
        $usedSpecies = [System.Collections.Generic.HashSet[string]]::new()
        foreach ($set in @($baseTowerSets | Where-Object { $_.set_tier -eq $tier -and $_.set_id -like "$mechanic`_*" })) {
            if ($usedSpecies.Add($set.species_id)) { $selected.Add($set) }
        }
        foreach ($source in @($baseTowerSets | Where-Object { $_.set_tier -ne $tier -and $_.set_id -like "$mechanic`_*" })) {
            if ($selected.Count -eq 12) { break }
            if (-not $usedSpecies.Add($source.species_id)) { continue }
            $clone = $source | ConvertTo-Json -Depth 30 | ConvertFrom-Json
            $speciesName = $source.species_id.Substring($source.species_id.IndexOf(':') + 1)
            $clone.set_id = "roster_${mechanic}_tier${tier}_$speciesName"
            $clone.set_tier = $tier
            $statValue = if ($tier -eq 1) { 15 } else { 20 }
            foreach ($stat in @('hp', 'attack', 'defense', 'special_attack', 'special_defense', 'speed')) {
                $clone.ivs.$stat = $statValue
                $clone.evs.$stat = 0
            }
            if ($tier -eq 2) { $clone.evs.speed = 252 }
            $selected.Add($clone)
        }
        if ($selected.Count -ne 12) {
            throw "Tower mechanic $mechanic tier $tier expected 12 distinct species, found $($selected.Count)"
        }
        $towerPools["$mechanic-$tier"] = @($selected)
        foreach ($set in $selected) {
            if (-not ($expandedTowerSets | Where-Object { $_.set_id -eq $set.set_id })) {
                $expandedTowerSets.Add($set)
            }
        }
    }
}

$expandedTowerProfiles = [System.Collections.Generic.List[object]]::new()
$nextNameIndex = 0

foreach ($profile in $baseTowerProfiles) {
    $tier = if ($profile.profile_id -like '*_low') { 1 } else { 2 }
    $pool = @($towerPools["$($profile.mechanic_id)-$tier"] | Select-Object -ExpandProperty set_id)
    if ($pool.Count -ne 12) {
        throw "Tower profile $($profile.profile_id) expected a 12-set mechanic/tier pool, found $($pool.Count)"
    }

    foreach ($variant in 1..4) {
        $profileId = if ($variant -eq 1) { $profile.profile_id } else { "$($profile.profile_id)_roster_$variant" }
        $displayNameKey = "trainer.cobblemon_more_battle_content.$profileId"
        $expandedTowerProfiles.Add([ordered]@{
            profile_id = $profileId
            display_name_key = $displayNameKey
            rank_ids = @($profile.rank_ids)
            format = $profile.format
            opponent_kind = $profile.opponent_kind
            mechanic_id = $profile.mechanic_id
            weight = [int]$profile.weight
            ai_skill = [int]$profile.ai_skill
            theme = $profile.theme
            set_pool = $pool
        })
        if ($variant -ne 1) {
            Add-Translation $english $displayNameKey (Get-EnglishTrainerName $nextNameIndex)
            Add-Translation $korean $displayNameKey (Get-KoreanTrainerName $nextNameIndex)
            $nextNameIndex++
        }
    }
}

$towerOutput = [ordered]@{
    schema_version = [int]$tower.schema_version
    catalog_id = $tower.catalog_id
    profiles = @($expandedTowerProfiles)
    sets = @($expandedTowerSets)
}

$baseFactoryConcepts = @($factory.concepts | Where-Object { $_.concept_id -notmatch '_roster_[23]$' })
$baseFactorySets = @($factory.sets | Where-Object { $_.set_id -notlike 'x_*' })
$factorySetsById = @{}
foreach ($set in $baseFactorySets) { $factorySetsById[$set.set_id] = $set }

$windows = @(
    [pscustomobject]@{ Key = 'starter-1'; Prefix = 's1'; Group = 'starter'; Variant = 1 },
    [pscustomobject]@{ Key = 'intermediate-1'; Prefix = 'i1'; Group = 'intermediate'; Variant = 1 },
    [pscustomobject]@{ Key = 'intermediate-2'; Prefix = 'i2'; Group = 'intermediate'; Variant = 2 },
    [pscustomobject]@{ Key = 'advanced-1'; Prefix = 'a1'; Group = 'advanced'; Variant = 1 },
    [pscustomobject]@{ Key = 'advanced-2'; Prefix = 'a2'; Group = 'advanced'; Variant = 2 },
    [pscustomobject]@{ Key = 'advanced-3'; Prefix = 'a3'; Group = 'advanced'; Variant = 3 },
    [pscustomobject]@{ Key = 'advanced-4'; Prefix = 'a4'; Group = 'advanced'; Variant = 4 }
)

$towerImportCandidates = @(
    $expandedTowerSets |
        Where-Object {
            $_.ability_id -ne $null -and
            $_.form_id -eq $null -and
            $_.held_item_id -ne $null -and
            $_.held_item_id -notlike 'mega_showdown:*' -and
            @($_.moves).Count -eq 4
        } |
        Sort-Object set_id
)
if ($towerImportCandidates.Count -lt 32) {
    throw "Expected at least 32 ordinary Tower sets for Factory expansion, found $($towerImportCandidates.Count)"
}

$expandedFactorySets = [System.Collections.Generic.List[object]]::new()
$baseFactorySets | ForEach-Object { $expandedFactorySets.Add($_) }
$windowSets = @{}

for ($windowIndex = 0; $windowIndex -lt $windows.Count; $windowIndex++) {
    $window = $windows[$windowIndex]
    $existing = @(
        $baseFactorySets | Where-Object {
            $_.pool_group -eq $window.Group -and [int]$_.variant -eq $window.Variant
        }
    )
    if ($existing.Count -ne 16) {
        throw "Factory window $($window.Key) expected 16 base sets, found $($existing.Count)"
    }
    $usedSpecies = [System.Collections.Generic.HashSet[string]]::new()
    $existing | ForEach-Object { [void]$usedSpecies.Add($_.species_id) }
    $rotatedCandidates = @(
        for ($offset = 0; $offset -lt $towerImportCandidates.Count; $offset++) {
            $towerImportCandidates[($offset + ($windowIndex * 7)) % $towerImportCandidates.Count]
        }
    )
    $imports = [System.Collections.Generic.List[object]]::new()
    foreach ($source in $rotatedCandidates) {
        if (-not $usedSpecies.Add($source.species_id)) { continue }
        $moveSlots = [System.Collections.Generic.List[object]]::new()
        foreach ($move in @($source.moves)) { $moveSlots.Add(@($move)) }
        $generated = [ordered]@{
            set_id = "x_$($window.Prefix)_$($source.set_id)"
            pool_group = $window.Group
            variant = [int]$window.Variant
            species_id = $source.species_id
            ability_id = $source.ability_id
            ivs = Copy-StatSpread $source.ivs
            evs = Copy-StatSpread $source.evs
            move_slots = @($moveSlots)
            held_items = @($source.held_item_id)
            nature_pool = 'all'
        }
        $imports.Add($generated)
        $expandedFactorySets.Add($generated)
    }
    if ($imports.Count -lt 34) {
        throw "Factory window $($window.Key) expected at least 34 distinct imports, found $($imports.Count)"
    }
    $windowSets[$window.Key] = @($existing) + @($imports)
}

$expandedFactoryConcepts = [System.Collections.Generic.List[object]]::new()
foreach ($window in $windows) {
    $concepts = @(
        $baseFactoryConcepts | Where-Object {
            $firstSetId = $_.members[0].set_pool[0]
            $firstSet = $factorySetsById[$firstSetId]
            $firstSet.pool_group -eq $window.Group -and [int]$firstSet.variant -eq $window.Variant
        }
    )
    if ($concepts.Count -ne 4) {
        throw "Factory window $($window.Key) expected 4 base concepts, found $($concepts.Count)"
    }
    $sets = @($windowSets[$window.Key])
    $setsById = @{}
    foreach ($set in $sets) { $setsById[$set.set_id] = $set }
    $imports = @($sets | Where-Object { $_.set_id -like 'x_*' })
    $roleBuckets = @{}
    foreach ($memberIndex in 0..3) {
        $bucket = [System.Collections.Generic.List[object]]::new()
        foreach ($baseConcept in $concepts) {
            $bucket.Add($setsById[$baseConcept.members[$memberIndex].set_pool[0]])
        }
        for ($importIndex = $memberIndex; $importIndex -lt $imports.Count; $importIndex += 4) {
            $bucket.Add($imports[$importIndex])
        }
        $roleBuckets[$memberIndex] = @($bucket)
    }

    for ($conceptIndex = 0; $conceptIndex -lt $concepts.Count; $conceptIndex++) {
        $concept = $concepts[$conceptIndex]
        foreach ($variant in 1..3) {
            $conceptId = if ($variant -eq 1) { $concept.concept_id } else { "$($concept.concept_id)_roster_$variant" }
            $displayNameKey = if ($variant -eq 1) {
                $concept.display_name_key
            } else {
                "factory.cobblemon_more_battle_content.concept.$conceptId.name"
            }
            $members = [System.Collections.Generic.List[object]]::new()
            for ($memberIndex = 0; $memberIndex -lt $concept.members.Count; $memberIndex++) {
                $member = $concept.members[$memberIndex]
                $originalId = $member.set_pool[0]
                $bucket = @($roleBuckets[$memberIndex])
                $alternatives = @(
                    for ($offset = 0; $offset -lt $bucket.Count; $offset++) {
                        $bucket[($offset + $conceptIndex + (($variant - 1) * 2)) % $bucket.Count].set_id
                    }
                ) | Where-Object { $_ -ne $originalId } | Select-Object -Unique -First 3
                $candidateIds = @($originalId) + @($alternatives)
                if ($candidateIds.Count -ne 4) {
                    throw "Factory concept $conceptId member $($member.plan_id) could not build four candidates"
                }
                $members.Add([ordered]@{
                    plan_id = $member.plan_id
                    required = [bool]$member.required
                    roles = @($member.roles)
                    tactical_summary = $member.tactical_summary
                    preferred_move_ids = @($member.preferred_move_ids)
                    lead_priority = [int]$member.lead_priority
                    preservation_priority = [int]$member.preservation_priority
                    set_pool = $candidateIds
                })
            }
            $expandedFactoryConcepts.Add([ordered]@{
                concept_id = $conceptId
                display_name_key = $displayNameKey
                description_key = $concept.description_key
                formats = @($concept.formats)
                weight = [int]$concept.weight
                ai_skill = [int]$concept.ai_skill
                ai_summary = $concept.ai_summary
                objectives = @($concept.objectives)
                members = @($members)
            })
            if ($variant -ne 1) {
                Add-Translation $english $displayNameKey (Get-EnglishTrainerName $nextNameIndex)
                Add-Translation $korean $displayNameKey (Get-KoreanTrainerName $nextNameIndex)
                $nextNameIndex++
            }
        }
    }
}

$factoryOutput = [ordered]@{
    schema_version = [int]$factory.schema_version
    catalog_id = $factory.catalog_id
    concepts = @($expandedFactoryConcepts)
    sets = @($expandedFactorySets)
}

$towerOutput | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $towerPath -Encoding utf8
$factoryOutput | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $factoryPath -Encoding utf8
$english | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $englishPath -Encoding utf8
$korean | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $koreanPath -Encoding utf8

[pscustomobject]@{
    TowerProfiles = $expandedTowerProfiles.Count
    TowerSets = $expandedTowerSets.Count
    FactoryConcepts = $expandedFactoryConcepts.Count
    FactorySets = $expandedFactorySets.Count
    AddedNames = $nextNameIndex
}
