# Better AI 공정 스탯·표준 피해 투영 후속 결정

| 항목 | 값 |
|---|---|
| Status | `shared` |
| Effective | 2026-08-18 |
| Updates | `../architecture/BRAIN_OWNERSHIP.md` §§2, 5, 6과 `../architecture/DESIGN.md` §§5.4, 12.2, 25.1 |
| Scope | 일반 싱글·더블의 공개 능력치 지식, 표준 피해 범위와 난수 KO 자료 |
| 주 독자 | 빡대리님과 Better AI 구현·검증 담당자 |

## 1. 결정

- 공통 계산기는 Brain이 전략 판단에 사용할 기계적 사실만 제공해야 한다(MUST). 후보의 효용·순위·추천·교체 권고는 계속 제공해서는 안 된다(MUST NOT).
- NPC 측 포켓몬의 HP·공격·방어·특공·특방·스피드는 자기 팀의 정확한 실수치 범위로 제공할 수 있다(MAY).
- 상대 측 포켓몬의 능력치는 화면에 공개된 종족·폼·레벨의 종족값과 합법적인 IV·EV·성격 상하한으로만 계산한 범위여야 한다(MUST).
- 상대의 실제 IV·EV·성격·특성·도구나 비활성 포켓몬 내부 객체는 범위 계산 입력으로 읽어서는 안 된다(MUST NOT).
- Illusion 등으로 다른 모습이 공개된 동안에는 실제 종족이 아니라 공개된 모습의 종족값 범위를 사용해야 한다(MUST). AI도 플레이어와 같은 거짓 정보에 속을 수 있어야 한다.

## 2. 표준 피해 모델

- `SHOWDOWN_GEN9_BASE_NON_CRITICAL`은 Gen 9의 기본 비급소 단일 대상 피해식을 사용한 표준 투영을 뜻한다(MUST).
- 이 모델은 공개 레벨·기술 위력·물리/특수 분류·공개 능력치 범위·공개 랭크·자속·타입 상성과 85~100의 16개 난수 굴림을 반영해야 한다(MUST).
- `standardDamageFractionRange`는 가능한 표준 피해의 대상 최대 HP 대비 범위다(MUST).
- `standardDamageRollKoProbabilityRange`는 **기술이 적중했다는 조건에서** 16개 피해 난수만 본 KO 확률 범위다(MUST). 기본 명중률과 곱한 최종 KO 확률로 표현해서는 안 된다(MUST NOT).
- `standardKnockoutAssessment`는 모든 허용 가설과 난수에서 KO면 `GUARANTEED`, 어느 가설에서도 KO가 아니면 `IMPOSSIBLE`, 나머지는 `POSSIBLE`이어야 한다(MUST).
- 표준 모델이라는 이름 없이 이 수치를 실제 최종 피해나 확정된 미래 결과로 표현해서는 안 된다(MUST NOT).

기본 식과 정수 처리 순서는 [Pokemon Showdown `battle-actions.ts`](https://github.com/smogon/pokemon-showdown/blob/master/sim/battle-actions.ts), 16개 난수와 고정소수점 보정은 [Pokemon Showdown `battle.ts`](https://github.com/smogon/pokemon-showdown/blob/master/sim/battle.ts)를 기준으로 독립 구현한다.

## 3. 계산하지 않는 범위

- 특성, 지닌 도구, 날씨, 필드, 상태, 보호, 급소, 기술별 콜백, 다타, 광역 감쇠와 주요 기믹의 동적 보정은 현재 표준 투영에 포함하지 않는다(MUST NOT).
- 위 입력이 실제 결과를 바꿀 수 있음을 `DYNAMIC_DAMAGE_MODIFIERS` 불확실성으로 함께 전달해야 한다(MUST).
- 주요 기믹을 사용하는 후보, 광역기, 위력이 정수로 확정되지 않은 기술, 상태기, 대상·타입·능력치 범위가 없는 후보는 표준 피해 숫자를 꾸며 만들지 말고 `null`과 해당 `UNKNOWN` 사유를 유지해야 한다(MUST).
- 최종 명중률, 행동 순서, 회복·반동·진입 피해는 각각의 공정 입력과 호환 계산이 완성되기 전까지 이 결정의 완료 범위가 아니다(MUST NOT).

## 4. Brain과 Router 경계

- 로컬 Brain과 OpenRouter Brain은 동일한 `BattleCandidateFactsView`를 받아야 한다(MUST).
- Router 프롬프트는 `standardDamage*`가 동적 보정을 제외한 범위이며 추천이 아니라고 명시해야 한다(MUST).
- 계산기는 표준 피해 범위가 크거나 KO 가능성이 높다는 이유로 후보를 삭제·정렬하거나 Router 호출을 생략해서는 안 된다(MUST NOT).
- API 키가 유효하면 합법 후보가 둘 이상인 모든 판단을 Router가 직접 선택한다는 `../architecture/BRAIN_OWNERSHIP.md` 계약은 바뀌지 않는다(MUST).

## 5. 폐기한 대안

- Cobblemon `StrongBattleAI.calculateDamage` 직접 호출은 실제 전투 객체와 Tracker 상태에 결합돼 상대의 숨은 능력치·특성에 접근할 위험이 있고, 완전한 Showdown 결과도 아니므로 사용하지 않는다.
- 실행 중인 Graal Showdown 배틀 컨텍스트를 복제 없이 조회하거나 임시 변경해 계산하는 방식은 동시성·부작용·상태 복원 위험 때문에 사용하지 않는다.
- 상대 능력치를 임의의 표준 세트 한 개로 고정하는 방식은 불확실성을 정확한 값처럼 위장하므로 사용하지 않는다.
- 모든 동적 보정을 아직 계산하지 못했다는 이유로 공개 정보로 확정 가능한 기본 피해 범위까지 숨기는 방식도 폐기한다. 대신 모델 이름과 불확실성을 함께 제공한다.

## 6. 검증 계약

- 공식 Showdown 정수 절삭·STAB 보정·타입 배수·16개 난수 예제를 단위 테스트해야 한다(MUST).
- 상대의 숨은 세트를 바꾸고 공개 종족·폼·레벨을 유지하면 공개 능력치 범위와 Router 요청이 같아야 한다(MUST).
- 공개 랭크 변화는 표준 피해 범위를 바꿔야 한다(MUST).
- 면역은 최소 1 피해로 보정하지 않고 0으로 남아야 한다(MUST).
- 기믹 후보와 광역기는 현재 표준 모델 값을 갖지 않아야 한다(MUST).
- 공개 계약에는 실제 IV·EV·성격 원시값 필드를 추가해서는 안 된다(MUST NOT).

