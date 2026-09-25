# Better Cobblemon Music 리소스 카탈로그와 매핑 구조 결정

| 항목 | 값 |
|---|---|
| Status | `shared` |
| Effective | 2026-09-25 |
| Updates | `BETTER_COBBLEMON_MUSIC_ARCHITECTURE_AND_MIGRATION_DECISION.md` §4.2·§4.6 |
| Supersedes after migration | 같은 문서 §13, `BETTER_COBBLEMON_MUSIC_MBC_INTEGRATION_DECISION.md` §2·§5, `RELIABILITY_UPDATE_2026-08-22.md` §1·§2·§6, `CONFIG_SCHEMA_V2_2026-08-23.md` |
| 대상 | 음악 리소스 배포, 기본 매핑, 사용자 리매핑, 개인 음악 확장 |
| 주 독자 | 빡대리님과 이후 구현·배포·검증 담당자 |

## 1. 결정 요약

- Better Cobblemon Music JAR은 상황 판정과 재생만 담당해야 **MUST** 한다.
- 배포용 리소스팩 ZIP은 OGG, `sounds.json`, 트랙 카탈로그와 기본 매핑을 함께 제공해야 **MUST** 한다.
- 공식 OGG를 `config`로 복사하거나 다시 생성 리소스팩으로 만드는 경로는 제거해야 **MUST** 한다.
- 활성 구성은 기본 카탈로그 정확히 1개와 확장 카탈로그 0개 이상이어야 **MUST** 한다.
- 사용자 전역 설정은 `settings.json`, 기본값과 다른 매핑은 `overrides.json`에만 저장해야 **MUST** 한다.
- 매핑은 OGG 파일 경로가 아니라 네임스페이스가 있는 안정적인 트랙·플레이리스트 ID를 사용해야 **MUST** 한다.
- OGG 하나는 독립 사운드 이벤트 하나에 대응해야 **MUST** 한다. 여러 곡이 든 Minecraft 사운드 이벤트를 플레이리스트로 사용해서는 안 된다 **MUST NOT**.

이 문서는 목표 구조를 결정한다. 현재 배포된 JAR과 프로필은 아직 구형 `music.json`·`music/`·생성 리소스팩 구조를 사용한다. §10의 전환 조건을 모두 통과하기 전에는 이 문서를 런타임 구현 완료 증거로 해석해서는 안 된다 **MUST NOT**.

## 2. 반례 검증과 결정 보정

### 2.1 검증한 주장

> ZIP 카탈로그가 음원과 기본 매핑을 함께 소유하고, 사용자는 설정과 오버라이드만 유지하면 배포와 리매핑이 단순해진다.

### 2.2 CCT 결과

- **Claim-crux:** 활성 카탈로그를 하나만 허용해도 개인 음악 추가와 타 모드 확장을 충분히 지원할 수 있는가?
- **Counter-fit:** 단일 카탈로그는 출처 충돌을 막지만, 개인곡 한 곡을 추가하려는 사용자에게 공식 카탈로그 전체 복제를 요구한다.
- **Cause-chain:** 충돌 방지를 위해 카탈로그를 하나로 제한한 것이 오히려 확장 배포 비용을 키운다.
- **Project relevance:** `material`. 쉬운 리매핑과 배포가 이번 구조 변경의 직접 목적이므로 다음 구현 방식이 달라진다.

| Original claim | Counter-argument | Claim fragility | Status after verification |
|---|---|---|---|
| 활성 카탈로그 하나면 충분하다 | 작은 개인팩도 전체 카탈로그를 복제해야 하므로 확장이 더 어려워진다 | strong | needs revision |
| ZIP이 음원과 카탈로그를 함께 소유해야 한다 | 확장 카탈로그를 별도 층으로 허용하면 이 소유권을 유지할 수 있다 | weak | holds |
| 사용자 매핑은 config에 남겨야 한다 | 리소스팩 우선순위와 사용자 의도를 분리하므로 타당하다 | weak | holds |

확인된 편향은 단순성이 항상 사용 편의로 이어진다고 본 **단일 서사 편향**이다. 결론은 전체 방향을 뒤집지 않고, `카탈로그 하나`를 `기본 1개 + 확장 0개 이상`으로 수정한다.

## 3. 소유권과 디렉터리

### 3.1 설치 결과

```text
.minecraft/
├─ mods/
│  └─ better-cobblemon-music.jar
├─ resourcepacks/
│  ├─ cobleserver-music-0.5.0.zip
│  └─ user-music-extension.zip          # 선택
└─ config/better_cobblemon_music/
   ├─ settings.json
   └─ overrides.json
```

### 3.2 기본 리소스팩

```text
cobleserver-music-0.5.0.zip
├─ pack.mcmeta
└─ assets/
   ├─ cobleserver/
   │  ├─ sounds.json
   │  └─ sounds/music/**/*.ogg
   └─ better_cobblemon_music/
      └─ catalogs/base/cobleserver.json
```

기본 카탈로그는 다음 데이터를 함께 제공해야 **MUST** 한다.

- `packId`, 카탈로그 스키마 버전과 호환 모드 버전
- 안정적인 트랙 ID와 실제 사운드 이벤트 ID
- 플레이리스트와 재생 순서 기본값
- 필드·전투·포켓몬·외부 콘텐츠 기본 매핑
- 타격음과 HP 효과용 사운드 이벤트

### 3.3 확장 리소스팩

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

- 확장 카탈로그는 트랙과 플레이리스트를 추가할 수 있어야 **MAY** 한다.
- 확장 카탈로그는 기본 필드·전투 매핑을 자동으로 덮어써서는 안 된다 **MUST NOT**.
- 확장 트랙을 실제 상황에 연결하는 행위는 사용자의 `overrides.json`에서만 일어나야 **MUST** 한다.
- 확장팩 하나를 끄더라도 기본 카탈로그와 다른 확장팩은 계속 사용할 수 있어야 **MUST** 한다.

## 4. ID와 매핑 계약

### 4.1 트랙과 이벤트

```json
{
  "tracks": {
    "cobleserver:sinnoh_route_201_day": {
      "event": "cobleserver:music.track.sinnoh_route_201_day",
      "title": "Sinnoh Route 201 - Day"
    }
  }
}
```

- 트랙 ID는 리소스팩 업데이트 뒤에도 같은 음악의 사용자 매핑을 유지하기 위한 공개 ID여야 **MUST** 한다.
- 실제 OGG 경로는 카탈로그 밖의 사용자 설정에 노출해서는 안 된다 **MUST NOT**.
- 확장 카탈로그가 기본 카탈로그의 트랙 ID를 다시 선언하면 해당 확장 선언을 거부하고 기본 선언을 유지해야 **MUST** 한다.
- 둘 이상의 확장 카탈로그가 같은 새 트랙 ID를 선언하면 충돌한 선언을 모두 비활성화하고 정확한 카탈로그·트랙 ID를 진단해야 **MUST** 한다. 리소스팩 순서로 한쪽을 임의 선택해서는 안 된다 **MUST NOT**.
- 이벤트 하나에 여러 OGG를 넣어 Minecraft의 임의 선택에 맡겨서는 안 된다 **MUST NOT**. 셔플·랜덤·순차와 직전곡 방지는 모드의 `PlaylistNavigator`가 소유해야 **MUST** 한다.

### 4.2 플레이리스트

```json
{
  "playlists": {
    "cobleserver:field_plains": {
      "selection": "shuffle",
      "tracks": [
        "cobleserver:sinnoh_route_201_day"
      ]
    }
  }
}
```

- 플레이리스트는 트랙 ID만 참조해야 **MUST** 한다.
- 확장 플레이리스트는 기본·다른 확장 카탈로그의 공개 트랙을 참조할 수 있어야 **MAY** 한다.
- 존재하지 않는 트랙을 참조하는 플레이리스트는 활성 후보에서 제외해야 **MUST** 한다.
- 한 확장 카탈로그의 오류가 정상 기본 카탈로그 전체를 중단시켜서는 안 된다 **MUST NOT**.

### 4.3 기본 매핑과 사용자 오버라이드

기본 카탈로그의 매핑 예시는 다음과 같다.

```json
{
  "mappings": {
    "field": {
      "default": "cobleserver:field_plains",
      "biomes": {
        "minecraft:plains": "cobleserver:field_plains"
      }
    },
    "battle": {
      "wild": "cobleserver:battle_wild",
      "trainer": "cobleserver:battle_trainer"
    }
  }
}
```

사용자 파일은 바뀐 값만 기록한다.

```json
{
  "schemaVersion": 1,
  "battle": {
    "content": {
      "cobblemon_more_battle_content:battle_tower": "username:my_battle_playlist"
    }
  }
}
```

최종 매핑은 다음 순서로 만들어야 **MUST** 한다.

```text
기본 카탈로그의 기본 매핑
  → 유효한 기본·확장 플레이리스트 집합 구성
  → overrides.json 적용
  → 모든 참조 재검증
  → 불변 실행 스냅샷 원자 교체
```

확장 카탈로그의 발견 순서나 Minecraft 리소스팩 우선순위를 기본 매핑 충돌 해결 규칙으로 사용해서는 안 된다 **MUST NOT**.

## 5. 설정 계약

`settings.json`은 재생 동작만 소유해야 **MUST** 한다.

```json
{
  "schemaVersion": 1,
  "basePackId": "cobleserver:official",
  "scanIntervalSeconds": 1.0,
  "fieldChangeDelaySeconds": 4.0,
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

- `settings.json`은 OGG 경로, 트랙 목록 또는 상황 매핑을 포함해서는 안 된다 **MUST NOT**.
- `overrides.json`은 기본값과 다른 상황 매핑만 포함해야 **MUST** 한다.
- 첫 설치에서 기본 카탈로그가 정확히 하나 발견되고 `settings.json`이 없으면 그 `packId`를 초기값으로 사용할 수 있다 **MAY**.
- 기본 카탈로그가 둘 이상인데 `basePackId`가 없으면 하나를 임의 선택해서는 안 된다 **MUST NOT**. 설정 화면에서 사용자가 선택할 때까지 커스텀 음악 소유권을 얻지 않아야 **MUST** 한다.
- 선택된 `basePackId`가 사라졌으면 다른 기본 카탈로그로 자동 영구 변경해서는 안 된다 **MUST NOT**.
- 설정 화면은 활성 카탈로그에서 실제로 선택 가능한 플레이리스트만 표시해야 **MUST** 한다.
- 제거된 트랙이나 플레이리스트를 가리키는 오버라이드는 삭제하지 않고 비활성 항목으로 표시해야 **SHOULD** 한다. 사용자의 선택을 자동으로 다른 곡으로 영구 저장해서는 안 된다 **MUST NOT**.

## 6. 모드 내부 흐름

```text
Minecraft 리소스 재로드
  → CatalogDiscovery: 기본·확장 카탈로그 발견
  → CatalogValidator: 스키마·ID·이벤트·참조 검증
  → SettingsLoader: settings.json 읽기
  → OverrideLoader: overrides.json 읽기
  → MappingCompiler: 기본 매핑 + 사용자 오버라이드 컴파일
  → MusicMappingSnapshot: 완전한 후보 스냅샷
  → Field/Battle Resolver: 현재 상황을 플레이리스트 ID로 결정
  → PlaylistNavigator: 다음 트랙 ID 선택
  → Sound Backend: 트랙의 이벤트 ID 재생
```

- 각 단계의 외부 인터페이스는 검증된 불변 객체로 좁혀야 **SHOULD** 한다.
- 카탈로그 로더가 Minecraft 리소스 API를 소유하고, 순수 매핑 컴파일러와 판정기는 Minecraft 클래스를 참조해서는 안 된다 **MUST NOT**.
- 새 후보가 실패하면 직전 정상 스냅샷을 유지해야 **MUST** 한다.
- 첫 실행에서 기본 카탈로그가 없거나 잘못됐으면 커스텀 음악 소유권을 얻지 않고 바닐라 음악을 유지해야 **MUST** 한다.
- 이벤트 등록 여부만으로 ZIP 내부 OGG 존재와 형식을 증명했다고 간주해서는 안 된다 **MUST NOT**.

## 7. 빌드와 배포 계약

- 공식 리소스팩은 소스 디렉터리와 재현 가능한 ZIP 빌드 절차를 가져야 **MUST** 한다.
- 대용량 OGG는 Git LFS로 관리해야 **SHOULD** 한다.
- 빌드는 카탈로그의 모든 트랙 ID가 정확히 한 이벤트를 가리키는지 검사해야 **MUST** 한다.
- 빌드는 각 이벤트가 정확히 하나의 실제 Ogg/Vorbis 파일을 가리키는지 검사해야 **MUST** 한다.
- 빌드는 중복 ID, 누락 OGG, 다중 OGG 이벤트, 잘못된 형식과 호환 버전 불일치를 실패 처리해야 **MUST** 한다.
- JAR과 공식 리소스팩 ZIP은 호환 버전을 선언하는 한 배포 단위여야 **MUST** 한다.
- 빌드 성공은 Minecraft 리소스 재로드, 실제 선곡, 페이드와 청감 성공을 증명하지 않는다 **MUST NOT**.

## 8. 현재 배포물에서 확인된 이행 입력

2026-09-25의 `cobblemon-dev` 프로필에서 다음을 확인했다.

- 공식 ZIP은 음악 50개와 타격음 3개를 포함한다.
- 구형 config `music/`의 OGG 51개는 모두 공식 ZIP 안의 OGG와 SHA-256이 같다.
- `battle/content/swsh_gym_leader_battle.ogg`도 ZIP의 `battle/pvp/swsh_gym_leader_battle.ogg`와 같은 파일이다.
- 현재 `music.json`은 고유 트랙 72개를 참조하지만 40개가 없고, 생성팩에는 32개만 들어간다.
- 현재 사용자 설정은 스키마 1이며 MBC 타워·팩토리·PvP를 `swsh_gym_leader_battle`에 연결한다.

따라서 이행 시 공식 ZIP에 없는 40개 음원을 새로 존재하는 것처럼 선언해서는 안 된다 **MUST NOT**. 실제 50곡으로 카탈로그를 만들고, 없는 포켓몬 전용곡 매핑은 기존 일반 전설·야생 폴백을 따라야 **MUST** 한다.

## 9. 채택하지 않은 접근

- `config/music` 원본에서 생성팩을 계속 만드는 방식은 공식 ZIP과 음원을 중복하고 부분 생성팩을 허용하므로 채택하지 않는다.
- 활성 카탈로그를 정확히 하나만 허용하는 방식은 작은 개인 확장팩도 전체 카탈로그 복제를 요구하므로 채택하지 않는다.
- 모든 활성 카탈로그의 기본 매핑을 리소스팩 순서대로 자동 병합하는 방식은 사용자 의도와 팩 우선순위를 혼동하므로 채택하지 않는다.
- 플레이리스트 하나를 여러 OGG가 든 사운드 이벤트 하나로 표현하는 방식은 모드의 재생 순서 계약을 Minecraft 무작위 선택에 넘기므로 채택하지 않는다.
- OGG 상대 경로를 공개 매핑 ID로 계속 사용하는 방식은 파일 이동이 사용자 설정 파손으로 이어지므로 채택하지 않는다.
- 공식 OGG를 JAR에 포함하는 방식은 JAR 크기와 모드 코드 릴리스를 음악 배포에 결합하므로 채택하지 않는다.

## 10. 이행과 구형 구조 종료

### 10.1 구현 순서

1. 카탈로그·설정·오버라이드 순수 파서와 검증 실패 테스트
2. 현재 ZIP 50곡·타격음 3개를 사용하는 공식 카탈로그와 ZIP 빌드 검증기
3. 기본 1개·확장 N개 발견과 충돌 격리
4. 기본 매핑과 오버라이드 컴파일러
5. 트랙 ID 기반 재생 경로와 Mod Menu 매핑 화면
6. 구형 `music.json`의 사용자 차이만 `settings.json`·`overrides.json` 후보로 변환하는 명시적 마이그레이션
7. JAR·ZIP 빌드, 클라이언트 배치와 실제 필드·전투·효과 검증
8. 빡대리님 확인 뒤 구형 생성팩과 중복 config OGG 정리

### 10.2 보존과 롤백

- 마이그레이션은 구형 `music.json`과 `music/`을 즉시 삭제하거나 덮어써서는 안 된다 **MUST NOT**.
- 새 파일은 별도 후보로 생성하고 변환 결과를 검증한 뒤에만 활성화해야 **MUST** 한다.
- 현재 MBC 세 콘텐츠의 사용자 매핑은 같은 음원의 안정 트랙 ID를 가리키는 오버라이드로 보존해야 **MUST** 한다.
- 새 JAR 또는 ZIP이 실패하면 구형 JAR·ZIP·설정으로 되돌릴 수 있어야 **MUST** 한다.

### 10.3 종료 선언

- 2026-09-25부터 구형 `music.json`·`config/music`·생성팩 경로에는 새 기능을 추가해서는 안 된다 **MUST NOT**.
- 구형 구조의 실제 종료일은 새 JAR과 공식 ZIP의 실게임 검증 완료일이다.
- 종료가 선언되면 `GeneratedMusicResourcePack`, `GeneratedMusicPackController`, 파일 경로 기반 `MusicFileSoundIds`를 제거해야 **MUST** 하며 호환 구현으로 다시 만들지 않아야 **MUST NOT** 한다.
- 실게임 검증 전까지 구형 구조는 현재 배포의 롤백 수단으로만 유지해야 **MUST** 한다.
