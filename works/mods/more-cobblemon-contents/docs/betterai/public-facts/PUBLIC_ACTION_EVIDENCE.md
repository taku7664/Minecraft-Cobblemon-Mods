# Better AI 공개 행동 증거 후속 결정

| 항목 | 값 |
|---|---|
| Status | `shared` |
| Effective | 2026-08-18 |
| Updates | `../architecture/DESIGN.md` §5.4, `../architecture/BRAIN_OWNERSHIP.md` §§2·3·6, `STANDARD_DAMAGE_PROJECTION.md` §3 |
| Scope | 일반 싱글·더블의 공개 행동 순서와 HP 변화 증거 |
| 주 독자 | 빡대리님과 Better AI 구현·검증 담당자 |

## 1. 목적과 경계

- 본체의 공개 관측 계층은 화면에 드러난 행동 순서, 기술의 기본 우선도와 HP 변화를 판단 자료로 보존해야 한다(MUST).
- 관측 계층은 위 자료를 후보 점수, 효용, 추천, 교체 권고 또는 Router 호출 제한으로 변환해서는 안 된다(MUST NOT).
- 로컬 Brain과 OpenRouter Brain은 같은 `BattleDecisionContext`를 통해 같은 공개 증거를 받을 수 있어야 한다(MUST).
- 실제 스피드, 스피드 EV·성격, 미공개 도구·특성 또는 피해 원인은 이 문서의 관측 자료만으로 확정해서는 안 된다(MUST NOT).

## 2. 공개 행동 순서

- 공개 `move` 메시지를 관측하면 `ACTION_ORDER` 이벤트에 행동자, 공개 기술 ID와 기술 템플릿의 기본 우선도를 기록할 수 있다(MAY).
- 기본 우선도를 찾지 못하면 값을 임의로 0으로 채우지 않고 `null`로 남겨야 한다(MUST).
- 같은 턴에 상대와 NPC가 사용한 기술의 기본 우선도가 같으면 `BEFORE_AT_SAME_BASE_PRIORITY` 또는 `AFTER_AT_SAME_BASE_PRIORITY`라는 **관측 관계**를 만들 수 있다(MAY).
- 이 관계는 상대를 주체로 하고 비교한 NPC 포켓몬을 `relatedPokemonId`로 따로 식별해야 한다(MUST). UUID를 후보 문자열에 합쳐서는 안 된다(MUST NOT).
- 한 턴에 같은 행동자가 두 번 이상 등장하면 Instruct, Dancer 등 추가 행동 가능성을 배제할 수 없으므로 그 턴에서는 관계를 만들지 않아야 한다(MUST).
- 기본 우선도가 다르거나 어느 한쪽 우선도를 모르면 관계를 만들지 않아야 한다(MUST).
- 같은 기본 우선도에서 먼저 행동했다는 사실은 동률 난수, Trick Room, 상태·랭크, 공개 또는 미공개 특성·도구와 기타 순서 보정을 모두 제거한 실제 스피드 비교가 아니다(MUST NOT).

## 3. HP 변화와 직전 행동 창

- 공개 `-damage` 메시지에 `[from]` 효과가 있으면 그 공개 효과 ID를 `publicSourceEffectId`로 기록할 수 있다(MAY).
- `[from]` 효과가 있는 HP 변화는 직전 기술 행동과 연결해서는 안 된다(MUST NOT).
- `[from]` 효과가 없는 HP 감소가 같은 턴의 아직 닫히지 않은 행동 창 안에서 발생했고, 피해를 받은 포켓몬이 해당 공개 기술의 명시적 대상이면 직전 `ACTION_ORDER` 이벤트, 행동자와 기술 ID를 `precedingAction` 증거로 연결할 수 있다(MAY).
- `precedingAction`은 시간상 앞선 공개 행동 창을 뜻할 뿐 피해 원인을 확정하지 않는다(MUST NOT).
- 턴 전환, upkeep, 교체 또는 강제 교체를 관측하면 열린 행동 창을 닫아야 한다(MUST).
- 회복, HP 증가, 대상이 아닌 포켓몬의 HP 감소 또는 닫힌 행동 창 뒤의 변화에는 `precedingAction`을 붙여서는 안 된다(MUST NOT).

이 보수적 연결은 [Pokemon Showdown simulator protocol](https://github.com/smogon/pokemon-showdown/blob/master/sim/SIM-PROTOCOL.md)의 `move` 뒤 minor action 배열과 `-damage`의 제한된 의미를 따른다. 프로토콜 순서만으로 인과관계를 추가 가정하지 않는다.

## 4. Brain 전달 계약

- Router 다이제스트는 포켓몬 UUID를 기존 익명 별칭으로 바꿔 `baseMovePriority`, `precedingAction`, `publicSourceEffectId`와 행동 순서 관계를 전달해야 한다(MUST).
- Router 시스템 명령은 같은 기본 우선도의 관측 순서가 실제 스피드 증명이 아니며 `precedingAction`이 피해 인과관계를 증명하지 않는다고 명시해야 한다(MUST).
- 위 자료는 모든 합법 후보와 함께 전달되어야 하며 후보를 삭제·정렬하거나 Router 대신 로컬 판단을 선택하는 데 사용해서는 안 된다(MUST NOT).
- 로컬 Brain은 같은 공개 DTO를 읽을 수 있지만 사용 여부와 가중치는 로컬 Brain 자체의 판단 구현에 속한다(MAY).

## 5. 유예한 추론

다음 추론은 필요한 공정 입력이 아직 완전하지 않으므로 구현하지 않는다(MUST NOT).

- 관측 순서에서 상대 실제 스피드 범위, 스피드 EV·성격 또는 Choice Scarf를 좁히는 추론
- HP 감소량에서 상대 공격·특공, 피해 대상 방어·특방, 미공개 도구·특성 또는 난수 굴림을 좁히는 추론
- 급소, 다타, Substitute, spread 감쇠, 상태·필드·날씨·특성·도구·기술 콜백을 완전히 식별하지 않은 피해 역산

위 추론은 필요한 공개 보정 입력과 반례 테스트를 명시한 별도 후속 결정이 이 문서를 갱신한 뒤에만 추가할 수 있다(MUST).

## 6. 폐기한 대안

- 같은 기본 우선도에서 먼저 움직인 포켓몬을 `faster`로 기록하는 방식은 동률과 동적 보정을 숨기므로 폐기한다.
- 직전 `move` 뒤의 모든 `-damage`를 그 기술의 피해로 기록하는 방식은 잔여 피해, 반동, 지연 공격과 프로토콜 부가 효과를 오분류하므로 폐기한다.
- 자료가 불완전하다는 이유로 공개 행동 순서와 명시적 `[from]` 효과까지 버리는 방식도 폐기한다. 사실은 보존하되 의미를 좁게 표시한다.

## 7. 검증 계약

- 완전하지 않은 `precedingAction` 필드 조합과 `[from]` 효과를 동시에 가진 연결은 DTO에서 거부해야 한다(MUST).
- 같은 기본 우선도의 상대·NPC 행동 한 쌍은 상대 기준의 관측 관계 하나를 만들어야 한다(MUST).
- 다른 우선도, 알 수 없는 우선도와 같은 행동자의 반복 행동은 관계를 만들지 않아야 한다(MUST).
- 직접 대상의 source-less HP 감소만 열린 행동 창에 연결되고, 공개 잔여 효과와 닫힌 창의 HP 감소는 연결되지 않아야 한다(MUST).
- Router 요청에는 원본 UUID가 없어야 하며 두 불확실성 경고가 포함돼야 한다(MUST).
- 기존 공개 DTO의 JVM 생성자 호환성을 검사해야 한다(SHOULD).
