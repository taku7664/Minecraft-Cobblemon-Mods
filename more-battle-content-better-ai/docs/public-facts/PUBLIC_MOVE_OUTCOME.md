# Better AI 공개 기술 결과 후속 결정

| 항목 | 값 |
|---|---|
| Status | `shared` |
| Effective | 2026-08-18 |
| Updates | `PUBLIC_ACTION_EVIDENCE.md` §§3·4·5, `../architecture/DESIGN.md` §5.4 |
| Scope | 일반 싱글·더블의 공개 기술 결과 메시지 보존과 Brain 전달 |
| 주 독자 | 빡대리님과 Better AI 구현·검증 담당자 |

## 1. 결정

- 본체 공개 관측 계층은 Showdown 프로토콜이 명시적으로 공개한 기술 결과를 구조화된 `MOVE_OUTCOME` 사건으로 보존해야 한다(MUST).
- 지원 결과는 `MISSED`, `FAILED`, `BLOCKED`, `NO_TARGET`, `CANNOT_ACT`, `CRITICAL_HIT`, `SUPER_EFFECTIVE`, `RESISTED`, `IMMUNE`, `HIT_COUNT`다.
- `MOVE_OUTCOME`은 공개 메시지에 실제로 존재한 사용자·대상·기술·효과·횟수만 포함해야 한다(MUST).
- 공개 메시지에 없는 사용자, 대상, 원인 기술, 특성, 도구 또는 피해 보정을 직전 사건에서 추측해 채워서는 안 된다(MUST NOT).
- `MOVE_OUTCOME`을 효용 점수, 후보 순위, 추천, Router 호출 생략 또는 합법 후보 삭제에 사용해서는 안 된다(MUST NOT).
- 로컬 Brain과 OpenRouter Brain은 동일한 `BattleDecisionContext.observedEvents`에서 결과 사건을 받을 수 있어야 한다(MUST).

## 2. 프로토콜 매핑

| 공개 메시지 | 사건 자료 |
|---|---|
| `move ... [miss]` | 사용자·표시 대상·공개 기술 ID가 있는 `MISSED` |
| `-miss SOURCE TARGET` | 명시된 사용자와 존재할 때의 대상이 있는 `MISSED` |
| `-fail POKEMON ACTION` | 프로토콜상 대상과 공개된 ACTION 기술 ID가 있는 `FAILED`; 사용자는 추정하지 않음 |
| `-block POKEMON EFFECT MOVE ATTACKER` | 명시된 대상, 차단 효과, 선택적 기술·공격자가 있는 `BLOCKED` |
| `-notarget POKEMON` | 명시된 사용자만 있는 `NO_TARGET` |
| `cant POKEMON REASON MOVE` | 명시된 사용자, 공개 사유와 선택적 기술이 있는 `CANNOT_ACT` |
| `-crit POKEMON` | 명시된 대상만 있는 `CRITICAL_HIT` |
| `-supereffective POKEMON` | 명시된 대상만 있는 `SUPER_EFFECTIVE` |
| `-resisted POKEMON` | 명시된 대상만 있는 `RESISTED` |
| `-immune POKEMON` | 명시된 대상만 있는 `IMMUNE` |
| `-hitcount POKEMON NUM` | 명시된 대상과 양의 공개 횟수가 있는 `HIT_COUNT` |

- 표의 인자 역할은 [Pokemon Showdown simulator protocol](https://github.com/smogon/pokemon-showdown/blob/master/sim/SIM-PROTOCOL.md)을 따라야 한다(MUST).
- `move [miss]`와 바로 뒤의 `-miss`가 같은 턴·사용자·대상을 가리키면 같은 공개 실패를 두 번 제공하지 않아야 한다(MUST). 기술 ID가 있는 `move` 자료를 유지해야 한다(MUST).
- 잘못된 `HIT_COUNT`, 해석할 수 없는 포켓몬 또는 빠진 선택 인자는 거짓 기본값으로 대체하지 않고 해당 부분만 생략해야 한다(MUST).

## 3. 인과관계 제한

- `CRITICAL_HIT`, `SUPER_EFFECTIVE`, `RESISTED`, `IMMUNE`, `HIT_COUNT`는 프로토콜이 명시한 대상만 기록하고 직전 행동 사용자를 자동 연결해서는 안 된다(MUST NOT).
- 사건 배열에서 기술 사용 직후에 결과가 나타났다는 사실만으로 원인 기술 또는 피해량을 확정해서는 안 된다(MUST NOT).
- `IMMUNE`만으로 미공개 특성·도구·타입을 확정해서는 안 된다(MUST NOT). 같은 메시지의 `[from]`이 특성이나 도구를 공개한 경우에만 기존 공개 자원 사건을 별도로 기록할 수 있다(MAY).
- `HIT_COUNT`만으로 Skill Link, Loaded Dice 또는 다른 미공개 입력을 추론해서는 안 된다(MUST NOT).
- 한 번의 `MISSED`를 기술의 최종 적중률이나 명중·회피 랭크의 증거로 수치화해서는 안 된다(MUST NOT).

## 4. Router 전달

- Router 다이제스트는 결과 종류, 공개 기술 ID, 공개 효과 ID와 명중 횟수를 구조화해 전달해야 한다(MUST).
- 포켓몬 식별자는 기존 익명 별칭으로 바꿔야 하며 원본 UUID를 전달해서는 안 된다(MUST NOT).
- 시스템 명령은 결과 사건이 공개 메시지 자체의 사실만 담으며 인접 사건이 인과관계를 증명하지 않는다고 명시해야 한다(MUST).
- 결과 사건 추가는 API 키가 유효한 복수 후보 요청을 Router가 직접 결정한다는 판단 소유권을 바꾸지 않는다(MUST NOT).

## 5. 유예 범위

다음은 별도 후속 결정 전까지 구현하지 않는다(MUST NOT).

- 공개 결과와 HP 감소를 결합한 공격·방어 능력치 또는 피해 난수 역산
- 공개 결과에서 실제 스피드, 아이템, 특성 또는 상대 세트 확률 생성
- `-activate`, Substitute 피해, 보호 성공, 급소 무효화와 기술별 콜백의 일반화
- 공개 결과 표본을 자동 승률·효용·후보 점수로 변환

## 6. 폐기한 대안

- 결과 사건마다 현재 열린 행동 창의 사용자·기술을 강제로 붙이는 방식은 지연 공격, 다중 대상과 부가 사건을 오분류하므로 폐기한다.
- 모든 결과를 자유 문자열 하나로 전달하는 방식은 종류별 필수 값과 금지 값을 검증할 수 없어 폐기한다.
- `move [miss]`와 `-miss`를 각각 별도 실패로 남기는 방식은 같은 실패의 중복 표본을 만들므로 폐기한다.

## 7. 검증 계약

- `HIT_COUNT`는 양의 횟수를 반드시 가져야 하고 다른 결과는 횟수를 가져서는 안 된다(MUST).
- 원인 기술을 명시할 수 없는 급소·상성·면역·횟수 결과는 `moveId`를 가질 수 없어야 한다(MUST).
- 지원 프로토콜 메시지의 인자 위치와 ID 정규화를 단위 테스트해야 한다(MUST).
- 두 miss 표현은 공개 사건 하나로 중복 제거되어야 한다(MUST).
- Router 요청은 구조화 결과와 불확실성 경고를 포함하고 원본 UUID를 포함하지 않아야 한다(MUST).
- 공개 DTO의 기존 JVM 생성자 호환성을 유지해야 한다(SHOULD).
