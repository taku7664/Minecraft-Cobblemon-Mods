# Better AI Router 보스 전투 기본 활성화 후속 결정

| 항목 | 값 |
|---|---|
| Status | `shared` |
| Effective | 2026-08-19 |
| Updates | `../architecture/BRAIN_OWNERSHIP.md` §§1, 4, 7; `../behavior/DIFFICULTY_ACTIVATION.md` §2 |
| Scope | 배틀 시작 시 Router·로컬 Brain 선택과 Better AI 설정 스키마 3 |
| 주 독자 | 빡대리님과 본체 PvE·Better AI 구현 담당자 |

## 1. 전투 단위 선택 계약

- 본체는 관리 NPC 전투마다 안정적인 콘텐츠 ID, 실제 전투 역할 `REGULAR|BOSS`와 공개 난도 티어를 Router 선택 컨텍스트로 제공해야 한다(MUST).
- 기본 설정에서 배틀타워의 `TIER_BOSS`, `MASTER_BALL_BOSS`와 배틀팩토리 싱글 21·49전의 헤드만 Router Brain을 선택해야 한다(MUST).
- 팩토리 일반전의 `aiSkill=5`가 `BOSS` 난도 프로필을 사용하더라도 실제 역할은 `REGULAR`로 유지해야 한다(MUST). `BOSS_ONLY`는 난도 이름이 아니라 실제 보스 역할을 뜻한다(MUST).
- Brain 선택은 전투 시작 시 한 번만 수행해야 한다(MUST). 한 전투의 턴마다 Router와 로컬을 다시 선택해서는 안 된다(MUST NOT).
- Router가 선택된 전투에서는 합법 후보가 둘 이상인 모든 판단 요청을 Router가 직접 결정해야 한다(MUST). 후보가 하나뿐인 요청만 HTTP 전송을 생략할 수 있다(MAY).
- Router가 선택되지 않은 전투는 Better AI 로컬 Brain이 직접 판단해야 한다(MUST). Router 실패 시 기존 `Router → 로컬 → 본체 기준선 → 비상 행동` 폴백은 유지해야 한다(MUST).

## 2. 설정 스키마 3

새 설정은 다음 구조를 생성해야 한다(MUST).

```json
{
  "schemaVersion": 3,
  "routerPolicy": {
    "defaultMode": "LOCAL_ONLY",
    "contentRules": {
      "cobblemon_more_battle_content:battle_tower": {
        "mode": "BOSS_ONLY"
      },
      "cobblemon_more_battle_content:battle_factory": {
        "mode": "BOSS_ONLY"
      }
    }
  }
}
```

- `LOCAL_ONLY`는 해당 범위에서 Router를 선택하지 않아야 한다(MUST NOT).
- `BOSS_ONLY`는 실제 `BOSS` 역할만 Router로 보내야 한다(MUST).
- `ALL`은 해당 콘텐츠의 모든 관리 NPC 전투를 Router로 보내야 한다(MUST).
- `DIFFICULTY_TIERS`는 같은 규칙의 `tiers` 배열에 명시된 `INTRODUCTORY|STANDARD|ADVANCED|BOSS`만 Router로 보내야 한다(MUST).
- 알 수 없는 콘텐츠는 `defaultMode`를 따라야 한다(MUST). 기본값은 `LOCAL_ONLY`다(MUST).
- 스키마 1·2는 자동 재작성하지 않고 과거 전역 Router 선택과 같은 `ALL`로 읽어야 한다(MUST). 스키마 3으로 전환하려면 운영자가 설정 파일을 명시적으로 갱신해야 한다(MUST).
- 잘못된 모드, 콘텐츠 ID 또는 티어 조합은 외부 Brain 설정을 실패 처리해야 한다(MUST). Better AI 로컬 Brain 등록과 본체 기준선은 계속 사용할 수 있어야 한다(MUST).

## 3. 채택하지 않은 대안

- 난도 티어 `BOSS`만 기본 필터로 쓰는 방식은 팩토리 일반 `aiSkill=5`까지 외부 호출하므로 채택하지 않았다.
- 타워·팩토리 런타임이 Better AI 설정 파일을 직접 읽는 방식은 본체가 Better AI를 참조하게 하므로 채택하지 않았다.
- Router가 세션을 연 뒤 각 턴에서 로컬로 넘길지 판단하는 방식은 두 Brain의 최종 판단 소유권을 다시 섞으므로 채택하지 않았다.
- 알 수 없는 미래 콘텐츠를 자동으로 Router에 보내는 방식은 비용과 전용 컨텍스트 준비 여부를 운영자가 통제할 수 없으므로 채택하지 않았다.

## 4. 종료 선언과 검증

2026-08-19부터 “API 키·모델이 유효하면 모든 관리 NPC 전투가 자동으로 Router를 선택한다”는 기존 기본 선택은 종료한다. “Router가 선택된 전투의 복수 후보 판단은 매번 Router가 소유한다”는 계약은 계속 유효하다.

- 타워 일반전·승급 보스·MAX 챔피언, 팩토리 일반 `aiSkill=5`·21/49 헤드와 알 수 없는 콘텐츠를 각각 검증해야 한다(MUST).
- 스키마 1·2의 `ALL` 호환, 스키마 3 기본값과 네 모드의 파싱·거부를 검증해야 한다(MUST).
- 기존 Java 4인자와 Kotlin 기본 인자 `BattleBrainProvider` 생성자 바이너리 표면을 유지해야 한다(MUST).
- 선택 정책의 예외는 해당 외부 provider를 부적격으로 처리하고 전투 시작을 깨뜨리지 않아야 한다(MUST).
