# 배틀타워 첫 Play 화면 범위 결정

| 항목 | 값 |
|---|---|
| Status | `shared` |
| Effective | 2026-08-18 |
| Updates | [`DESIGN.md`](../architecture/DESIGN.md) § 13, [`BATTLE_TOWER_REFERENCE_ANALYSIS.md`](../reference/BATTLE_TOWER_REFERENCE_ANALYSIS.md) § 11 |
| 주 독자 | 빡대리님과 배틀타워 화면 구현 담당자 |

## 1. 결정

- 첫 배틀타워 MVP Screen은 Play 기능만 노출해야 한다(MUST).
- 화면은 파티 미리보기, 싱글·더블 선택, 3마리·4마리 선출, 규칙 오류, 현재 랭크·진행도·BP, 팀 확정, 시작·재개·팀 변경 의도를 포함해야 한다(MUST).
- Challenges, Records, Guide와 BP 상점 패널은 첫 Screen에 노출하지 않아야 한다(MUST). 기록 저장과 향후 리더보드 계약은 폐기하지 않는다.
- 클라이언트는 서버 승인 전에 선출, 팀 확정과 활성 세션을 확정하지 않아야 한다(MUST).
- 모든 변경 요청은 `entryContextId`, `requestId`, 예상 revision을 사용해야 하며(MUST), 서버는 중복 요청과 이전 화면의 지연 요청을 안전하게 거절해야 한다(MUST).
- 실제 Cobblemon 배틀 생성기가 연결되기 전에는 Start가 `ACTIVE`를 만들어서는 안 되며(MUST), 사용 불가를 명시적으로 알려야 한다(SHOULD).

## 2. 채택하지 않은 대안

- 첫 화면에 모든 탭과 상점을 빈 패널로 먼저 노출하는 방식은 채택하지 않았다. 사용할 수 없는 탭이 첫 수직 기능의 검증 범위를 흐리고 실제 계약이 없는 UI를 고정하기 때문이다.
- 화면 클릭 직후 선출을 낙관적으로 표시하는 방식은 채택하지 않았다. 파티 변경·중복 요청·낡은 revision에서 서버 상태와 불일치할 수 있기 때문이다.

## 3. 다음 확장 게이트

Records·Challenges·Guide·BP 상점을 추가하려면 각 패널의 실제 서버 계약과 우선순위를 빡대리님이 확정한 후 이 문서를 Updates하는 후속 결정을 추가해야 한다(MUST).
