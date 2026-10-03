# Better Cobblemon Music

Cobblemon의 필드·전투 상황에 맞춰 리소스팩 음악을 재생하는 Fabric 클라이언트 모드입니다. 타격음과 마지막 포켓몬 HP 효과도 제공합니다.

공식 팩의 BGM 표시명은 한국어이며, 게임 구분은 `전투! 루기아 (HG·SS)`처럼 영문 약자로 표시합니다. [한국어 곡명 대조와 미확정 항목](KOREAN_TRACK_TITLES_2026-10-03.md)을 참고하세요.

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

공식 음악팩 ZIP과 복사한 배포용 JAR, `deployment-backups/`는 Git 커밋 대상에서 제외합니다. 현재 클라이언트와 빌드 출력에는 최신 버전만 유지하며, 이전 Music 버전·Music 배포 백업은 2026-10-03 요청에 따라 삭제했습니다. 제작용 OGG·카탈로그·매핑 소스는 유지합니다.

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
  "lastPokemonHpEffectVolume": 1.0,
  "nowPlayingEnabled": true
}
```

`overrides.json`은 리소스팩 기본 매핑과 다른 값만 안정적인 플레이리스트 ID로 저장합니다.

```json
{
  "schemaVersion": 1,
  "battle": {
    "content": {
      "more_cobblemon_contents:battle_tower": "cobleserver:track/battle/pvp/swsh_gym_leader_battle"
    }
  },
  "screens": {
    "more_cobblemon_contents:hub": "cobleserver:field_plaza"
  }
}
```

Mod Menu 설정 화면에서 기본 음악팩, 재생·효과 설정과 리소스팩에 이미 정의된 필드·전투·콘텐츠·포켓몬·화면 매핑을 선택할 수 있습니다. 연동한 모드가 알려 준 콘텐츠 키와 화면 키도 매핑이 없더라도 목록에 나옵니다. 저장할 때 각 파일을 임시 파일에서 원자 교체하고 Minecraft 리소스를 다시 불러옵니다. 새 바이옴 키나 새 콘텐츠 ID처럼 규칙 자체를 추가할 때만 `overrides.json`을 직접 편집합니다.

기존 `music.json`이 있고 `overrides.json`이 없으면 첫 카탈로그 로드 때 사용자 변경분만 변환합니다. 기존 `music.json`과 `music/`은 롤백을 위해 삭제하거나 덮어쓰지 않습니다.

## 선곡 순서

공식 전투 음악의 기반 선곡은 [2026-10-03 라인업](MUSIC_LINEUP_2026-10-03.md)을 따릅니다. 이후 제공받은 `pokemusic/readme.txt`의 메인 화면·MCC·트레이너·리그·필드·광장 선곡은 [1.3.5 적용 기록](POKEMUSIC_IMPORT_2026-10-03.md)이 갱신합니다. 일반 야생은 DP 음악이며, 루기아는 `75 - Battle! (Lugia).flac`로 [교체](LUGIA_REPLACEMENT_2026-10-03.md)한 음원과 기존 매핑 ID를 유지합니다.

- 재생 우선순위: 전투 → 열린 화면 → 필드. 화면 음악은 다음 공용 상황 검사에서 바뀌고, 매핑이 없는 화면에서는 필드 음악이 이어집니다. 월드 밖 메인 화면은 `minecraft:title` 화면 키를 사용합니다.
- 필드: 해당 시간의 차원 → 공통 차원 → 팩의 `ruleOrder` 순서 → 미지정 개인 규칙/기본곡. 공식 바이옴 순서는 고대 도시 → 동굴 → 강 → 바다 → 늪지 → 정글 → 설원 → 산 → 숲 → 평원 → 사막 → 악지입니다. [겹침 판정 및 호환성](FIELD_PRIORITY_2026-10-03.md)을 참고하세요.
- 전투: 포켓몬 규칙 → 콘텐츠 키(구체적인 키부터) → 야생 특수 분류 → 야생·트레이너·PvP 기본곡

- 화면: 화면 키(구체적인 키부터)

`field.dayDimensions`와 `field.nightDimensions`로 차원별 시간 매핑을 지정할 수 있고 설정 화면에도 낮·밤 항목이 표시됩니다. 밤은 게임 시간 13,000 이상 23,000 미만이며 날씨와 실제 시계는 사용하지 않습니다. 광장은 낮 Route 1 / 밤 Pallet Town으로 전환하고, 기존 필드 전환 대기와 페이드를 따릅니다. 두 곡을 지정한 `pokemusic` 항목은 명시적 `random`입니다. 첫 곡은 무작위로 고르고 이후 직전 곡을 피하므로 두 곡이면 교대로 이어집니다.

1.3.6부터 마이룸 차원 `myroom:rooms`는 제공받은 `43 - Eterna Forest.flac`의 [변환 음원](MYROOM_MUSIC_2026-10-03.md)을 사용합니다. 낮·밤·바이옴·지하 여부와 무관하며, Mod Menu의 차원 음악 매핑에서 변경할 수 있습니다. 전투 음악과 매핑된 화면 음악의 우선순위는 유지합니다.

1.3.7은 실제 BGM 곡이 바뀔 때 좌측 상단에 [곡명 텍스트](NOW_PLAYING_TEXT_2026-10-03.md)를 약 3초간 슬라이드·페이드 표시합니다. 패널이나 배경은 없습니다. Mod Menu → 재생 → `BGM 곡명 알림 표시`에서 ON/OFF를 바꿉니다(기본 ON). [리소스팩 곡명 입력](TRACK_TITLES_2026-10-03.md)에서 알림 이름을 편집할 수 있습니다. 타격음·HP 경고음·같은 곡 반복은 알림을 띄우지 않습니다.

1.3.8은 표시명을 한국어로 바꾸고 게임명을 `HG·SS` 등 영문 약자로 줄였습니다. [배포 검증 기록](RELEASE_1_3_8_2026-10-03.md)에 음원·매핑·개인 설정 보존 결과를 정리했습니다.

1.3.9부터 곡명 알림은 `[Music] 전투! 루기아 (HG·SS)`처럼 표시합니다. `[Music]`은 알림 표시 단계에서만 붙이며, 리소스팩의 곡명·음원·매핑은 변경하지 않습니다.

야생 특수 분류는 알파 → 울트라비스트 → 전설 순입니다. [알파 음악](ALPHA_MUSIC_2026-10-03.md)은 서버가 전투에 전달한 `alpha` 표시로 판단하며, 포켓몬 전용곡이 있으면 전용곡을 유지합니다. 전용곡이 없는 야생 알파는 보스 2곡을 사용합니다. Mod Menu의 전투 매핑에서 `알파 포켓몬 전투` 음악을 변경할 수 있습니다.

RCT NPC는 NPC 여부만 사용합니다. 역할·사천왕·챔피언 같은 분류는 조회하지 않습니다.

마지막 전투 가능 포켓몬이 한 마리이고 HP가 절반 이하이면 Better Cobblemon Music이 재생한 곡에만 먹먹한 효과를 적용합니다. 빨간 HP 구간에서는 제공받은 `low-health-critical-health-pokemon.mp3`를 변환한 경고음 하나를 반복 재생합니다. 경고음을 계속 새로 겹쳐 재생하지 않으며, 조건 해제·기절·전투 종료 시 해당 경고음을 멈춥니다. 원본 음량과 사용자의 경고음 음량 설정은 유지합니다. 변환·재생 방식은 [빨피 경고음 교체 기록](LOW_HP_ALERT_2026-10-03.md)을 참고하세요.

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

[`extension-template/my-music-pack`](extension-template/my-music-pack)은 바로 쓸 수 있는 빈 확장팩입니다. `assets/mymusic/sounds/music`에 OGG를 넣고 `update-music.bat`을 실행하면 `sounds.json`과 카탈로그가 만들어지며, 곡마다(`mymusic:track/<경로>`), 폴더마다(`mymusic:folder/<폴더>`), 전체(`mymusic:all`) 플레이리스트가 생깁니다. 자세한 사용법은 팩 안의 `사용법.txt`에 있습니다.

## 다른 모드 연동 API

콘텐츠 모드는 `jbro.cobblemon.bettermusic.api`에 공급자를 등록해 자기 전투와 화면에 음악을 붙일 수 있습니다. 키는 소문자 네임스페이스 ID이고, 구체적인 키부터 차례로 찾아 처음 매핑된 키의 곡을 재생합니다.

- `BattleMusicContentProviders.global().register(id, provider)`: `contentKeys(battleId)`가 `battle.content` 키 목록을 돌려줍니다. 예: `example:league/champion`, `example:league`.
- `ScreenMusicProviders.global().register(id, provider)`: `screenKeys()`가 열린 화면의 `screens` 키 목록을, 닫혀 있으면 빈 목록을 돌려줍니다.
- `knownContentKeys()`·`knownScreenKeys()`로 알려 준 키는 설정 화면에 미리 표시됩니다.

공급자가 예외를 던지거나 잘못된 키를 돌려주면 한 번만 로그를 남기고 다음 공급자로 넘어갑니다.

## More Cobblemon Contents 연동

More Cobblemon Contents(MCC)가 설치돼 있으면 위 API로 내장 연동을 등록합니다. MCC는 선택 의존성이라, 없거나 연동 API가 없는 옛 버전이면 일반 매핑으로 동작합니다. MCC 클래스는 `integration/mcc`의 `MccMusicProviders`에서만 쓰고, MCC가 로드됐을 때만 이 클래스를 불러옵니다.

전투 키는 `<콘텐츠>/<단계>/<상대>` → `<콘텐츠>/<단계>` → `<콘텐츠>` 순으로 찾습니다. 상대는 ID의 경로 부분만 씁니다.

| 콘텐츠 | 단계 |
|---|---|
| `more_cobblemon_contents:league_challenge` | `gym`, `elite_four`, `champion`, `hard_gym`, `hard_elite_four`, `hard_champion`, `wild_trainer`, `wild_trainer_ace` |
| `more_cobblemon_contents:battle_tower` | `regular`, `tier_boss`, `master_ball_boss` |
| `more_cobblemon_contents:battle_factory` | `regular`, `factory_head` |
| `more_cobblemon_contents:pvp` | `single`, `double` |

예를 들어 신오 챔피언전은 `more_cobblemon_contents:league_challenge/champion/cynthia`, `.../league_challenge/champion`, `.../league_challenge` 순서로 찾습니다. 야생 트레이너도 리그 챌린지 콘텐츠이므로 `more_cobblemon_contents:league_challenge` 자체에 곡을 걸면 야생 트레이너에게도 적용됩니다. 공식 팩은 리그 단계에만 체육관·사천왕·챔피언 곡을 걸어 두어, 야생 트레이너는 일반 트레이너 곡을 씁니다.

배틀 허브가 열려 있으면 `more_cobblemon_contents:hub/<탭>` → `more_cobblemon_contents:hub` 순으로 화면 키를 찾습니다. 탭은 `dashboard`, `shop`, `league_challenge`, `battle_tower`, `battle_factory`, `pvp`입니다. 1.3.5 공식 팩의 기본 허브는 Boutique, 상점은 Poké Mart이며, 전투가 시작하면 전투 음악이 우선합니다.

## 빌드

```powershell
.\gradlew.bat :better-cobblemon-music:build
```

`better-cobblemon-music/build/libs/`에 모드 JAR과 재현 가능한 공식 리소스팩 ZIP이 생성됩니다. 상세 계약과 이행 근거는 [`../docs/BETTER_COBBLEMON_MUSIC_RESOURCE_CATALOG_AND_MAPPING_DECISION.md`](../docs/BETTER_COBBLEMON_MUSIC_RESOURCE_CATALOG_AND_MAPPING_DECISION.md)를 따릅니다.
