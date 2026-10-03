# Music 메인 화면과 차원별 낮·밤 매핑

상태: shared. 작성일: 2026-10-03.
Updates: [카탈로그 및 매핑 결정](BETTER_COBBLEMON_MUSIC_RESOURCE_CATALOG_AND_MAPPING_DECISION.md).
대상 독자: Music 리소스팩 제작자와 모드 유지보수자.

## 계약

- JAR는 게임 상황을 판단하고, ZIP는 음원·플레이리스트·기본 매핑을 소유해야 합니다(MUST). 개인 `settings.json`과 `overrides.json`은 배포로 덮어쓰면 안 됩니다(MUST).
- `mappings.field.dayDimensions`와 `nightDimensions`는 선택적인 `차원 ID → 플레이리스트 ID` 객체입니다(MAY). 없으면 기존 매핑 결과를 유지해야 합니다(MUST). 같은 키를 개인 `overrides.json`의 `field`에도 사용할 수 있습니다.
- 필드 선택 우선순위는 해당 시간의 차원 매핑 → 공통 차원 매핑 → 정확한 바이옴 → 지하 → 바이옴 태그 → 바이옴 경로 포함 → 기본값입니다(MUST). 잘못된 개인 플레이리스트 참조는 비활성화하고 팩 기본값으로 돌아가야 합니다(MUST).
- 낮·밤은 차원의 게임 시계로 구분해야 합니다(MUST). 고정 시간이 있는 차원은 고정 시간을 사용합니다. 24,000틱 중 `[13,000, 23,000)`을 밤, 나머지를 낮으로 정합니다. 날씨나 PC 실제 시간으로 바꾸지 않습니다.
- 시간대 전환은 기존 공용 상황 검사 주기와 필드 전환 대기·페이드를 따라야 합니다(MUST). 매 틱 바이옴이나 체력 상황을 새로 검사하지 않습니다. 애니메이션·음향 플레이어의 매 틱 갱신은 별개입니다.
- 기본 제공 화면 키 `minecraft:title`은 월드 밖의 메인 화면에서만 반환합니다. 메인 화면 매핑은 월드가 없어도 재생할 수 있어야 합니다(MUST). 하위 메뉴와 로딩 화면에 이 키를 확대하지 않습니다.
- 재생 우선순위는 전투 → 매핑된 화면 → 필드입니다(MUST). 월드 이탈 시 마지막 포켓몬 HP 효과를 해제하고 필드·전투 상태를 다음 상황 검사에서 제거해야 합니다(MUST).
- `random`은 첫 곡을 모든 후보에서 무작위로 고르고, 이후 직전 곡을 제외합니다(MUST). 두 곡이면 첫 곡은 무작위, 다음 곡부터는 교대로 재생됩니다. `shuffle`은 별도의 전체 섞기 방식입니다.

## 적용 예

```json
{
  "dayDimensions": {"jbro_policy:plaza": "cobleserver:field_plaza"},
  "nightDimensions": {"jbro_policy:plaza": "cobleserver:track/field/plaza/pallet_town"}
}
```

이는 `mappings.field` 내부 예입니다. `cobblemon_policy:plaza`는 과거 별칭이며, 실제 정책 모드의 차원은 `jbro_policy:plaza`입니다. 공식 팩은 두 키를 연결하되 서버나 정책 모드 자체는 변경하지 않습니다.

설정 화면은 낮 차원·밤 차원 항목을 따로 제공하며, 다른 필드 항목을 저장해도 이 두 객체가 보존되어야 합니다(MUST). 영어·한국어 이름을 함께 제공합니다.

## inspect: 의존성과 가정 점검

트리거: 설정 모델과 공용 재생 흐름 변경(Blast radius / Shared code).
검사: 의존 경로 추적, 입력·실행 순서 가정 점검.

| 위치 | 관찰 및 영향 | 조치 |
|---|---|---|
| `jbro-policy/.../dimension/plaza.json`, 기존 `resource-pack/catalog-layout.json` | 실제 광장 ID와 옛 매핑 ID가 다름 | 실제 ID를 연결하고 옛 별칭 유지 |
| `FieldMusicConfig`, `CatalogMappings.Field`, `MusicMappingOverrides.Field`의 생성자 호출 | 레거시 파서·마이그레이터·테스트가 기존 생성자를 사용 | 기존 인자 생성자는 빈 시간 매핑으로 연결; 기존 팩 회귀 검사 |
| `MusicCatalogCompiler.requireBaseMappings`, `compileField` | 새 객체의 참조 검사와 개인 우선 적용이 빠지면 null 또는 무시되는 설정 발생 | 두 객체 모두 기존 검증·비활성 참조 처리에 포함 |
| `MusicCatalogConfigStore.overridesJson`, `BetterMusicConfigScreen.withFieldCore/withFieldMap` | 설정을 재구성·저장할 때 새 객체가 빠지면 개인 시간 매핑 손실 | 두 객체를 모든 재구성 경로에서 보존; 파일 저장·재독해 테스트 |
| `BetterMusicClientRuntime.tick/leaveWorld/scanContextIfDue` | 월드 밖에서는 상황 검사를 하지 않아 메인 화면 음악 재생 불가 | 공용 검사를 월드 밖에서도 사용; HP 효과 및 stale MCC 화면 컨텍스트는 제외 |
| `PlaylistNavigator.random` | 첫 선택 시 마지막 곡이 후보에서 제외됨 | 재현 테스트 후 전체 후보 선택으로 수정; 직전 곡 반복 방지는 유지 |

위 항목은 구현·단위 테스트로 점검합니다. 실제 Minecraft 설정 UI의 표시와 실제 음악 청취는 별도의 게임 검증입니다.

## 호환성·대안·복구

- 기존 schemaVersion 1의 선택적 속성을 확장했습니다. 새 JAR는 옛 ZIP를 읽을 수 있습니다. 옛 JAR는 새 속성을 모르는 엄격한 파서 때문에 새 ZIP를 거부하므로 JAR와 ZIP를 함께 배포해야 합니다(MUST).
- 시간별 차원 매핑만 추가했습니다. 바이옴 전부를 낮·밤으로 복제하는 대안은 README에서 요청되지 않았고 유지 부담이 커서 채택하지 않았습니다. 제목에 `(Night)`가 있는 음원도 광장 외에는 자동 시간 조건이 아닙니다.
- 별도 메뉴 플레이어나 매 틱 시간 검사 대신 기존 화면 매핑 및 공용 검사 흐름을 사용합니다. 이로써 음량·페이드·리소스 재로드를 같은 경로로 유지합니다.
- 직전 곡 반복을 허용하는 독립 추첨은 기존 UI의 “직전 곡을 피해 무작위” 계약과 다르므로 채택하지 않았습니다.
- 실패 시 백업 JAR·ZIP 및 이전 `options.txt`의 선택 항목을 복구합니다. 개인 설정 스키마 마이그레이션은 없고, 원본 오디오도 변경하지 않습니다.
