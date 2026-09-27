# More Battle Content PvP 전투 사전 배치 정정

| 항목 | 값 |
|---|---|
| Status | `shared` |
| Effective | 2026-08-19 |
| Updates | [`ROOM_AND_LOUNGE.md`](ROOM_AND_LOUNGE.md) 4절·5절·7절 |
| 주 독자 | PvP·Cobblemon 1.7.3 호환 계층·서버 런타임 담당자 |
| 구현 상태 | 코드·회귀 테스트·클린 빌드 완료, 배치·서버 기동·실제 PvP 검증 대기 |

## 1. 정정 이유

기존 구현은 타워·팩토리·PvP 플레이어 복제팀을 공통 임시 `PlayerPartyStore`에 넣었지만, PvP 룸 참가자를 배틀 라운지로 이동하기 전에 `PlayerBattleActor`와 Cobblemon 전투를 만들었다. `PlayerBattleActor`는 생성 시점의 월드 위치를 초기 위치로 보유하므로 전투 시작 뒤 차원을 바꾸면 초기 포켓몬 출전과 첫 행동 요청이 서로 다른 위치 계약을 볼 수 있다. 임시 파티 저장소만 공유한 상태는 공통 전투 수명주기 구현으로 간주하지 않는다.

## 2. 현행 계약

- 룸 PvP는 양쪽 참가자의 복귀 지점을 먼저 캡처하고 배틀 라운지의 LEFT·RIGHT 위치로 이동한 뒤에만 `PlayerBattleActor`를 만들고 `BattleRegistry.startBattle`을 호출해야 한다(MUST).
- 타워·팩토리·PvP의 MBC 플레이어 전투 복제본은 `임시 PlayerPartyStore 완성 → PlayerBattleActor 생성 → 성공한 battle의 battlePartyStores 연결` 순서를 한 공통 호환 경계로 사용해야 한다(MUST).
- 룸 phase는 사전 배치 동안 `TEAM_PREVIEW`를 유지해야 한다(MUST). Cobblemon 전투 ID가 생성된 뒤에만 관전자를 이동·관전 등록하고 룸을 `ACTIVE`로 바꿔야 한다(MUST).
- 팀 실체화, 사전 배치, 전투 시작 또는 활성화 중 하나라도 실패하면 시작된 전투를 종료하고 캡처한 좌표와 경기장 임대를 롤백해야 한다(MUST).
- 접속 JOIN·DISCONNECT 콜백 안에서는 차원 이동 복귀를 수행해서는 안 된다(MUST NOT). 온라인 상태가 서버 틱에 반영된 뒤, 활성 또는 준비 중 라운지에 묶이지 않은 복귀 대기자만 복귀시켜야 한다(MUST).
- Turn 0 진단은 PvE 전용이 아니며 타워·팩토리·PvP의 모든 MBC 관리 전투에 적용해야 한다(SHOULD).

## 3. 채택하지 않은 안

- `BattleRegistry.startBattle` 뒤 참가자를 라운지로 이동하는 안은 actor 초기 위치와 실제 플레이어 차원이 갈라져 폐기한다.
- `PlayerPartyStore`만 공유하고 actor 생성·저장소 연결 순서를 콘텐츠별로 두는 안은 같은 누락을 다시 만들 수 있어 폐기한다.
- JOIN 콜백에서 보류 좌표로 즉시 순간이동하는 안은 플레이어 추적 자료구조가 안정되기 전 차원 변경을 만들 수 있어 폐기한다.

## 4. 검증 경계

회귀 테스트는 사전 배치가 runtime보다 먼저 실행되는 순서, 활성화 실패·예외의 전투 종료와 롤백, 준비·활성 라운지 참가자의 틱 복귀 제외, 좌석 소유자 검증과 세 콘텐츠의 공통 참가자 생성 경계를 고정한다. 이 검증은 코드·빌드 증거이며 실제 기술·교체 UI 표시 성공을 뜻하지 않는다. 서버와 클라이언트 JAR 배치 및 실제 두 클라이언트 PvP 확인은 별도 단계다.
