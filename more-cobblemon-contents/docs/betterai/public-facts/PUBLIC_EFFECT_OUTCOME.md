# Better AI 공개 방어 효과 결과 후속 결정

| 항목 | 값 |
|---|---|
| Status | `shared` |
| Effective | 2026-08-19 |
| Updates | `PUBLIC_MOVE_OUTCOME.md` §5 |
| Scope | 일반 싱글·더블의 Substitute 피해와 Protect 공개 시작 상태 |
| 주 독자 | 빡대리님과 Better AI 구현·검증 담당자 |

## 1. 결정

- 본체 공개 관측 계층은 현재 Cobblemon 1.7.3 서버가 사용하는 Showdown 출력 중 `-activate ... move: Substitute [damage]`를 `SUBSTITUTE_DAMAGED` 결과로 보존해야 한다(MUST).
- 본체 공개 관측 계층은 `-singleturn ... move: Protect`를 `PROTECTION_STARTED` 결과로 보존해야 한다(MUST).
- 두 결과는 기존 `MOVE_OUTCOME` 사건과 `BattleMoveOutcomeView.publicEffectId`를 사용해야 한다(MUST). 새 사건 종류나 공개 DTO 필드를 추가하지 않는다(MUST NOT).
- 두 결과 종류는 기존 `BattleMoveOutcomeKind` 값의 순서를 바꾸지 않고 끝에 추가해야 한다(MUST).
- 이 결정은 공개 사건을 점수, 추천, Router 호출 제한 또는 합법 후보 삭제로 바꾸지 않는다(MUST NOT).

## 2. 좁은 프로토콜 매핑

| 공개 메시지 | 사건 자료 |
|---|---|
| `-activate POKEMON move: Substitute [damage]` | 명시된 포켓몬을 대상으로 하고 `publicEffectId=substitute`인 `SUBSTITUTE_DAMAGED` |
| `-singleturn POKEMON move: Protect` | 명시된 포켓몬을 대상으로 하고 `publicEffectId=protect`인 `PROTECTION_STARTED` |

- `SUBSTITUTE_DAMAGED`는 대체물이 피해를 받았다는 공개 사실만 뜻한다. 실제 포켓몬 HP 감소, 피해량, 공격 사용자 또는 원인 기술을 뜻해서는 안 된다(MUST NOT).
- `PROTECTION_STARTED`는 해당 포켓몬에 Protect 공개 효과가 시작됐다는 사실만 뜻한다. 뒤의 공격이 실제로 차단됐거나 Protect가 다음 사건까지 유지된다고 단정해서는 안 된다(MUST NOT).
- 실제 차단은 기존 `-block` 메시지가 있을 때만 `BLOCKED`로 별도 기록해야 한다(MUST).
- Showdown의 `-activate`는 여러 특성·도구·상태·기술에 쓰이는 포괄 메시지이므로, 위 Substitute 형식 외의 `-activate`를 일반 공개 효과로 변환해서는 안 된다(MUST NOT).
- `-singleturn` 역시 여러 임시 효과에 쓰이므로, 공개 효과 ID가 정확히 `protect`인 경우 외에는 이 결정으로 변환해서는 안 된다(MUST NOT).

## 3. 인과관계와 HP 제한

- 두 결과의 `moveId`, 사용자와 명중 횟수는 비어 있어야 한다(MUST). `publicEffectId`는 공개 메시지에 적힌 효과 ID만 담아야 한다(MUST).
- 두 결과는 대상 포켓몬의 공개 HP를 갱신하거나 `HP_CHANGED` 사건을 만들면 안 된다(MUST NOT).
- `SUBSTITUTE_DAMAGED`와 인접한 `move`, `-damage`, 급소 또는 상성 사건을 결합해 피해량·공격력·방어력·난수·미공개 세트를 역산해서는 안 된다(MUST NOT).
- `PROTECTION_STARTED`와 뒤의 `BLOCKED`는 각각 독립된 공개 사실이다. 배열 인접성만으로 둘 사이의 원인 관계를 새 필드로 만들면 안 된다(MUST NOT).

## 4. Router 전달

- Router 다이제스트는 두 결과 종류와 익명 대상 별칭, `publicEffectId`를 구조화해 전달해야 한다(MUST).
- Router 시스템 명령은 `SUBSTITUTE_DAMAGED`가 실제 포켓몬 HP 손실을 뜻하지 않고, `PROTECTION_STARTED`가 후속 차단을 보장하지 않는다고 명시해야 한다(MUST).
- 원본 UUID, 추정 공격 사용자, 추정 원인 기술과 피해량을 Router에 추가해서는 안 된다(MUST NOT).
- 공개 효과 의미가 바뀌므로 프롬프트 버전과 결정 태그를 `brain-choice-v8`, `openrouter_brain_choice_v8`로 올려야 한다(MUST).

## 5. 폐기한 대안

- 모든 `-activate`를 공개 효과 사건으로 바꾸는 방식은 같은 메시지가 특성·도구·상태에도 쓰여 의미가 서로 달라지므로 폐기한다.
- 모든 `-singleturn`을 보호 성공으로 바꾸는 방식은 Endure, Follow Me, Roost 등 보호와 다른 임시 효과를 오분류하므로 폐기한다.
- 새 사건 종류나 새 DTO 필드를 추가하는 방식은 기존 `MOVE_OUTCOME.publicEffectId`가 같은 계약을 이미 표현하고 JVM 공개 표면만 넓히므로 폐기한다.
- Substitute 사건에 직전 기술이나 HP 차이를 강제로 붙이는 방식은 공개되지 않은 원인과 수치를 만든다는 이유로 폐기한다.

## 6. 검증 계약

- 기존 `BattleMoveOutcomeKind` 순서와 공개 DTO 생성자 호환성을 회귀 테스트해야 한다(MUST).
- 두 정확한 메시지 형식과 일반 `-activate`, 비보호 `-singleturn`의 무시를 단위 테스트해야 한다(MUST).
- Substitute 피해 사건 뒤에도 공개 포켓몬 HP와 `HP_CHANGED` 사건 수가 바뀌지 않음을 테스트해야 한다(MUST).
- Router 요청이 두 구조화 결과, 의미 제한 문구와 익명 별칭을 포함하고 원본 UUID를 포함하지 않음을 테스트해야 한다(MUST).
- 본체와 Better AI를 함께 빌드·검증하고 두 JAR을 같은 서버 재기동 단위로 배치해야 한다(MUST).

## 7. 계속 유예하는 범위

- 급소 무효화의 일반 사건 계약
- 임의 기술 콜백과 임의 `-activate`의 일반화
- Substitute 실제 내구도·피해량과 공격·방어 능력치 역산
- 보호 성공률, 연속 사용 확률과 자동 효용 점수
