# 간호순 설치 자료만 생성합니다. 서버 파일 변경·명령 실행·기동은 하지 않습니다.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$OutputDirectory,
    [Parameter(Mandatory)][ValidateRange(-29999984, 29999984)][double]$X,
    [Parameter(Mandatory)][ValidateRange(-64, 319)][double]$Y,
    [Parameter(Mandatory)][ValidateRange(-29999984, 29999984)][double]$Z,
    [ValidateRange(-180, 180)][double]$Yaw = 0,
    [ValidateSet('1', '2')][string]$SkinVariant = '1'
)
$ErrorActionPreference = 'Stop'
$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $outputRoot) {
    throw '출력 경로가 이미 있습니다. 덮어쓰지 않으므로 새 폴더를 지정하세요.'
}
if ($outputRoot -match '(^|[\\/])(develop-product|deploy-product|world|mods|config)([\\/]|$)') {
    throw '실행 서버·제품 폴더 대신 별도의 작업 폴더를 지정하세요.'
}
$source = Join-Path $PSScriptRoot '../../mods/cobblemon-npc/docs/dialogues/plaza_nurse_joy.json'
$dialogue = Get-Content -LiteralPath $source -Raw -Encoding UTF8 | ConvertFrom-Json
$suffix = if ($SkinVariant -eq '2') { '_2' } else { '' }
$skin = "more_cobblemon_contents_league_challenge:textures/npcs/wild/nurse_joy$suffix.png"
$dialogue.skin = $skin
$culture = [Globalization.CultureInfo]::InvariantCulture
$position = @($X, $Y, $Z) | ForEach-Object { $_.ToString('0.########', $culture) }
$rotation = $Yaw.ToString('0.########', $culture)
$nbt = '{Tags:["plaza_nurse_joy"],CustomName:''{"text":"간호순"}'',CustomNameVisible:1b,NpcSkin:"' + $skin + '",NpcDialogue:"plaza_nurse_joy",Rotation:[' + $rotation + 'f,0f]}'
$summon = 'execute in jbro_policy:plaza positioned ' + ($position -join ' ') + ' if loaded ~ ~ ~ unless entity @e[type=cobblemon_npc:npc,tag=plaza_nurse_joy] run summon cobblemon_npc:npc ~ ~ ~ ' + $nbt
$verify = 'execute in jbro_policy:plaza run data get entity @e[type=cobblemon_npc:npc,tag=plaza_nurse_joy,limit=1]'
$dialogueDir = Join-Path $outputRoot 'config/cobblemon_npc/dialogues'
New-Item -ItemType Directory -Path $dialogueDir -Force | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText((Join-Path $dialogueDir 'plaza_nurse_joy.json'), ($dialogue | ConvertTo-Json -Depth 20) + "`n", $utf8)
[IO.File]::WriteAllText((Join-Path $outputRoot 'commands.txt'), "npc reload`n$summon`n$verify`nsave-all`n", $utf8)
$guide = @"
간호순 설치 준비 자료 — 아직 서버에 반영되지 않았습니다.

배치 위치: jbro_policy:plaza $($position -join ' '), Yaw=$rotation
스킨: $skin
대화: plaza_nurse_joy / 파티 전체 무료 회복 / 반복 사용 가능

1. 대상 서버와 좌표를 확인합니다. 해당 좌표의 발밑이 바닥이고 몸 위치 2칸이 비어 있어야 합니다.
2. 대상 서버에 Cobblemon NPC와 MCC League Challenge가 설치되어 있어야 합니다.
   클라이언트에도 두 모드가 있어야 선택한 스킨과 대화창이 표시됩니다.
3. 이 자료의 config/cobblemon_npc/dialogues/plaza_nurse_joy.json 한 파일을
   대상 서버의 같은 상대 경로에 설치합니다. 같은 ID가 있으면 먼저 내용을 비교합니다.
4. 운영자가 plaza의 배치 좌표 가까이 가서 청크를 불러옵니다.
5. commands.txt를 위에서부터 서버 콘솔에 한 줄씩 입력합니다.
   게임에서 입력하려면 각 줄 앞에 /를 붙이고 운영자 권한으로 실행합니다.
   if loaded가 실패하면 소환하지 않습니다. verify 줄로 실제 생성 여부를 확인합니다.
   같은 태그가 있는 NPC가 로드되어 있으면 중복으로 소환하지 않습니다.
6. 완드로 이름·스킨·대화를 확인하고, 다친 포켓몬과 기절한 포켓몬을 포함해 말을 걸어 회복을 확인합니다.
   배틀 중 회복은 Cobblemon이 거절합니다. 실패하면 회복 완료 대사를 보여 주지 않습니다.

서버를 자동으로 켜거나 끄지 않으며, 설치 자료를 만든 것만으로 NPC가 생성되지는 않습니다.
"@
[IO.File]::WriteAllText((Join-Path $outputRoot '설치안내.txt'), $guide + "`n", $utf8)
Write-Output "설치 자료 생성 완료: $outputRoot (서버 변경 없음)"
