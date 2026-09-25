# Better Cobblemon Music

Cobblemon의 필드·전투 상황에 맞춰 리소스팩 음악을 재생하는 Fabric 클라이언트 모드입니다. 타격음과 마지막 포켓몬 HP 효과도 제공합니다.

- Mod ID: `better_cobblemon_music`
- 실행 환경: 클라이언트 전용
- 필요 모드: Fabric API, Cobblemon 1.8.1
- 설정 화면: Mod Menu + Cloth Config

## 설치 단위

모드 JAR과 공식 음악 리소스팩 ZIP을 함께 설치해야 합니다.

```text
.minecraft/
├─ mods/
│  └─ better-cobblemon-music-<version>.jar
├─ resourcepacks/
│  └─ cobleserver-music-resourcepack-<version>.zip
└─ config/better_cobblemon_music/
   ├─ settings.json
   └─ overrides.json
```

JAR은 상황 판정과 재생을 담당합니다. ZIP은 OGG, `sounds.json`, 트랙·플레이리스트 카탈로그와 기본 매핑을 함께 소유합니다. 공식 음원을 `config`에 복사하거나 임시 리소스팩으로 다시 만드는 과정은 없습니다.

## 설정

`settings.json`은 재생과 효과 동작만 저장합니다.

```json
{
  "schemaVersion": 1,
  "basePackId": "cobleserver:official",
  "scanIntervalSeconds": 1.0,
  "fieldChangeDelaySeconds": 4.0,
  "betweenTracksSeconds": 0.0,
  "fadeInSeconds": 1.0,
  "fadeOutSeconds": 1.0,
  "selection": "shuffle",
  "volume": 1.0,
  "hitSoundsEnabled": true,
  "hitSoundVolume": 1.0,
  "lastPokemonHpEffectsEnabled": true,
  "lastPokemonHpEffectVolume": 1.0
}
```

`overrides.json`은 리소스팩 기본 매핑과 다른 값만 안정적인 플레이리스트 ID로 저장합니다.

```json
{
  "schemaVersion": 1,
  "battle": {
    "content": {
      "cobblemon_more_battle_content:battle_tower": "cobleserver:track/battle/pvp/swsh_gym_leader_battle"
    }
  }
}
```

Mod Menu 설정 화면에서 재생·효과 설정과 리소스팩에 이미 정의된 필드·전투·콘텐츠·포켓몬 매핑을 선택할 수 있습니다. 저장하면 두 파일을 원자적으로 갱신하고 Minecraft 리소스를 다시 불러옵니다. 새 바이옴 키나 새 콘텐츠 ID처럼 규칙 자체를 추가할 때만 `overrides.json`을 직접 편집합니다.

기존 `music.json`이 있고 `overrides.json`이 없으면 첫 카탈로그 로드 때 사용자 변경분만 변환합니다. 기존 `music.json`과 `music/`은 롤백을 위해 삭제하거나 덮어쓰지 않습니다.

## 선곡 순서

- 필드: 차원 → 정확한 바이옴 → 지하 → 바이옴 경로 포함 → 기본곡
- 전투: 포켓몬 규칙 → 콘텐츠 ID → 야생 특수 분류 → 야생·트레이너·PvP 기본곡

RCT NPC는 NPC 여부만 사용합니다. 역할·사천왕·챔피언 같은 분류는 조회하지 않습니다.

마지막 전투 가능 포켓몬이 한 마리이고 HP가 절반 이하이면 Better Cobblemon Music이 재생한 곡에만 먹먹한 효과를 적용합니다. 빨간 HP 구간에서는 카탈로그가 지정한 심장박동 이벤트도 재생합니다.

## 다시 불러오기

```text
/bcm reload
```

활성 리소스팩의 기본·확장 카탈로그, `settings.json`, `overrides.json`을 다시 읽습니다. 후보 전체가 유효할 때만 실행 스냅샷을 교체하며, 실패하면 직전 정상 구성을 유지합니다.

## 개인 음악 확장팩

공식 카탈로그를 복제하지 않고 확장 리소스팩으로 트랙과 플레이리스트를 추가할 수 있습니다.

```text
user-music-extension.zip
├─ pack.mcmeta
└─ assets/
   ├─ username/
   │  ├─ sounds.json
   │  └─ sounds/music/my_song.ogg
   └─ better_cobblemon_music/
      └─ catalogs/extensions/username.json
```

확장 카탈로그는 기본 매핑을 자동으로 바꾸지 않습니다. 팩을 활성화한 뒤 Mod Menu나 `overrides.json`에서 새 플레이리스트를 상황에 연결합니다.

## More Battle Content 연동

More Battle Content가 설치돼 있으면 다음 콘텐츠 ID를 자동으로 인식합니다.

- `cobblemon_more_battle_content:battle_tower`
- `cobblemon_more_battle_content:battle_factory`
- `cobblemon_more_battle_content:pvp`

연동 모드가 없거나 콘텐츠 ID를 얻지 못하면 일반 전투 매핑으로 돌아갑니다.

## 빌드

```powershell
.\gradlew.bat :better-cobblemon-music:build
```

`better-cobblemon-music/build/libs/`에 모드 JAR과 재현 가능한 공식 리소스팩 ZIP이 생성됩니다. 상세 계약과 이행 근거는 [`../docs/BETTER_COBBLEMON_MUSIC_RESOURCE_CATALOG_AND_MAPPING_DECISION.md`](../docs/BETTER_COBBLEMON_MUSIC_RESOURCE_CATALOG_AND_MAPPING_DECISION.md)를 따릅니다.
