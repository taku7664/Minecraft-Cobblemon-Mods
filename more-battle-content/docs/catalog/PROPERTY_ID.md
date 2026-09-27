# 배틀 카탈로그 Cobblemon 속성 ID 정정

| 항목 | 값 |
|---|---|
| Status | `applied` |
| Effective | 2026-08-19 |
| Updates | `MORE_BATTLE_CONTENT_FACTORY_CATALOG_SCHEMA3_RANDOMIZATION_DECISION.md`(2026-09-27 삭제, `MEMORY.md` 참고), [`TOWER_OPPONENT_SCHEMA3.md`](TOWER_OPPONENT_SCHEMA3.md) |
| 대상 | 배틀팩토리·배틀타워 내장 포켓몬 카탈로그와 Cobblemon 1.7.3 `PokemonProperties` 호환 계층 |

## 1. 정정 이유

배틀팩토리 스키마 3의 112세트 중 54세트가 `speed_boost`, `rough_skin`, `clear_body`처럼 단어 사이에 밑줄이 들어간 특성 경로를 사용했다. Cobblemon 1.7.3의 실제 특성 이름은 `speedboost`, `roughskin`, `clearbody`처럼 붙여 쓴 Showdown 이름이다. 잘못된 경로는 팩토리 화면에서 원문 번역 키를 노출하고 고정 특성 적용을 실패시켰다.

또한 팩토리와 타워 호환 계층이 카탈로그의 전체 리소스 ID를 `PokemonProperties.ability`와 `PokemonProperties.moves`에 전달했다. 두 필드는 `cobblemon:speedboost`나 `cobblemon:flareblitz`가 아니라 `speedboost`와 `flareblitz`를 요구하므로, 지정 특성과 기술이 조용히 무시되고 초기화된 기본값이 남을 수 있었다.

## 2. 데이터 계약

- 카탈로그의 `species_id`, `ability_id`, `nature_id`, `held_item_id`, `moves`와 `move_slots`는 네임스페이스가 있는 리소스 ID를 계속 사용해야 한다(MUST).
- `ability_id`의 경로는 Cobblemon 1.7.3 종·폼 JSON에 적힌 특성 이름과 정확히 일치해야 한다(MUST). 다단어 특성에 임의의 밑줄을 넣으면 안 된다(MUST NOT).
- 기술 ID의 경로는 해당 종 또는 선택한 폼의 실제 learnset에 있어야 한다(MUST).
- 본체 호환 계층은 `PokemonProperties`에 넣기 직전에 특성과 기술의 네임스페이스만 제거해야 한다(MUST). 종·성격·지닌 도구는 전체 리소스 ID를 유지해야 한다(MUST).
- 화면 표시만 보정해 잘못된 데이터나 런타임 적용 실패를 숨기면 안 된다(MUST NOT).

## 3. 적용 결과

- 팩토리 35종의 잘못된 특성 ID를 정식 경로로 바꿔 54세트를 정정했다.
- 팩토리 112세트와 타워 72세트의 특성이 Cobblemon 1.7.3 종·폼 데이터에 포함되는지 자동 검증한다.
- 팩토리와 타워의 모든 기술이 정확한 종·폼 learnset에 포함되는지 자동 검증한다.
- 두 런타임 변환기가 특성과 기술만 Showdown 이름으로 바꾸고 종·성격·도구의 리소스 ID는 유지하는 회귀 테스트를 제공한다.

## 4. 채택하지 않은 대안

- **JSON에 네임스페이스 없는 이름 저장:** 본체의 리소스 ID 계약과 데이터팩 검증 경계를 깨므로 채택하지 않았다.
- **밑줄과 구두점을 런타임에서 임의 제거:** 잘못된 카탈로그가 검증을 통과하고 서로 다른 사용자 정의 ID가 충돌할 수 있으므로 채택하지 않았다.
- **클라이언트 번역 키만 정규화:** 실제 전투 특성과 기술 적용 실패를 남기므로 채택하지 않았다.

## 5. 검증·배치 상태

- 구현 전 본체 496개 테스트 중 새 회귀 테스트 3개가 예상대로 실패했다.
- 정정 후 496개 테스트, 캐시 없는 클린 빌드와 JDK 21 `jar --validate`가 통과했다.
- 제품 JAR SHA-256은 `569B82770E943CC16A1EC636D4A4DB70797BF83EB5C3B6D91B49BFD3D233946B`다.
- 동일 JAR을 `dev-server`와 `cobblemon-dev` 클라이언트에 배치했다. 서버는 Java PID 37576이 `:::25566`을 수신하고 `[17:49:11] Done (1.368s)`에 도달했으며 타워·팩토리 카탈로그가 로드됐다.
- 실제 드래프트 화면의 번치코 특성 표시와 새 전투에서 지정 기술·특성이 일치하는지는 게임 내 검증이 남아 있다.
