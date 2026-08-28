# Better Cobblemon Music

Cobblemon의 필드와 전투 음악을 사용자가 직접 바꿀 수 있는 클라이언트 모드입니다. 기술이 명중하면 보통·굉장한 효과·별로인 효과에 맞는 타격음도 재생합니다.

타격음은 `cobleserver:battle.hit.normal`, `cobleserver:battle.hit.super_effective`, `cobleserver:battle.hit.not_very_effective` 사운드 이벤트를 사용하므로, 해당 이벤트와 OGG가 든 리소스팩이 활성화돼 있어야 합니다.

- Mod ID: `better_cobblemon_music`
- 실행 환경: 클라이언트 전용
- 필요 모드: Fabric API, Cobblemon 1.7.3

## 설정 위치

첫 실행 뒤 다음 폴더가 만들어집니다.

```text
config/better_cobblemon_music/
├─ music.json
└─ music/
```

음원은 `music/` 아래에 넣고 `music.json`에서 상대 경로로 지정합니다. 경로는 Minecraft 리소스 이름과 같은 소문자 영문·숫자·`_`·`-`·`.`·`/`만 사용할 수 있으며 파일은 실제 Ogg/Vorbis 형식이어야 합니다.

```json
{
  "schemaVersion": 2,
  "field": {
    "default": "field/plains/theme.ogg",
    "biomes": {
      "minecraft:plains": {
        "selection": "shuffle",
        "betweenTracksSeconds": 0.0,
        "tracks": [
          "field/plains/theme_1.ogg",
          "field/plains/theme_2.ogg",
          "field/plains/theme_3.ogg"
        ]
      }
    },
    "biomeTags": [
      {"tag": "minecraft:is_forest", "playlist": "field/forest/theme.ogg"}
    ],
    "biomePathContains": [
      {"contains": "frozen_river", "playlist": "field/river/frozen.ogg"},
      {"contains": "river", "playlist": [
        "field/river/theme_1.ogg",
        "field/river/theme_2.ogg"
      ]}
    ]
  },
  "battle": {
    "wild": "battle/wild/theme.ogg",
    "trainer": [
      "battle/trainer/theme_1.ogg",
      "battle/trainer/theme_2.ogg"
    ],
    "pvp": {
      "selection": "sequential",
      "volume": 0.8,
      "betweenTracksSeconds": 2.0,
      "tracks": ["battle/pvp/theme.ogg"]
    }
  }
}
```

플레이리스트는 단일 음원 문자열, 음원 배열, `selection`·음량·곡 사이 간격을 지정하는 객체 중 하나로 적을 수 있습니다. 같은 바이옴에 여러 곡을 넣으면 한 곡이 끝난 뒤 같은 플레이리스트의 다음 곡을 재생합니다. `shuffle`은 모든 곡을 섞어 한 번씩, `random`은 직전 곡을 피해서 무작위로, `sequential`은 적힌 순서대로 고릅니다.

위 예시는 주요 구조만 보여 줍니다. 실제 `music.json`에는 재생 설정과 필드·전투의 필수 항목이 모두 있어야 하므로 처음 생성된 기본 파일을 기준으로 편집해 주세요. 기존 스키마 1 파일도 계속 읽으며 자동으로 덮어쓰거나 변환하지 않습니다.

### 선곡 순서

- 필드: 차원 → 정확한 바이옴 → 지하 → 바이옴 태그 → `biomePathContains` → 기본곡
- 전투: 포켓몬 규칙 → 콘텐츠 ID → RCT 역할 → 야생 특수 분류 → 야생·트레이너·PvP 기본곡
- RCT 역할 키: `champion`, `elite`, `gym`, `rival`

스키마 2의 `biomeTags`와 `biomePathContains`는 배열에 먼저 적은 항목이 우선합니다. `frozen_river`처럼 구체적인 조건을 `river`보다 앞에 적어 주세요. 스키마 1에서는 기존 객체의 키 순서를 그대로 유지합니다.

`scanIntervalSeconds`는 최소 `0.25`초입니다. 기본값 `1.0`초면 일반적인 사용에서 충분합니다.

## 실행 중 다시 불러오기

```text
/bcm reload
```

새 설정과 음원을 임시 생성팩에서 먼저 검증하고 Minecraft 리소스 재로드까지 끝난 뒤에만 새 설정을 활성화합니다. 중간에 실패하면 이전 설정과 생성팩을 복원하며, 채팅에는 최종 성공 또는 실패 결과가 표시됩니다.

## More Battle Content 연동

More Battle Content가 설치돼 있으면 Better Cobblemon Music 본체가 자동으로 감지합니다. 별도 애드온 없이 `battle.content`에서 다음 ID를 사용할 수 있습니다.

- `cobblemon_more_battle_content:battle_tower`
- `cobblemon_more_battle_content:battle_factory`
- `cobblemon_more_battle_content:pvp`

음원과 설정은 Better Cobblemon Music의 `music.json`과 `music/`을 그대로 사용합니다. MBC가 없거나 연동 API를 읽지 못하면 기본 전투 선곡으로 돌아갑니다.

## 빌드

저장소 루트에서 실행합니다.

```powershell
.\gradlew.bat :better-cobblemon-music:build
```

JAR은 `better-cobblemon-music/build/libs`에 생성됩니다.
