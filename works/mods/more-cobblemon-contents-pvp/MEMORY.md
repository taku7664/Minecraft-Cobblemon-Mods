# MEMORY

## [2026-10-08 00:50] 룸 시작 거절 메시지에 걸린 플레이어·사유 표시

- 사용자 요청: "진영 중 한쪽의 파티가 …" 메시지가 누구의 무슨 문제인지 알려 주지 않음 → 사람과 사유를 보여 주기로 함(긴 문장 줄바꿈은 이번에 안 함).
- 구현: `PvpRoomRejectedPayload`에 `messageArgs`(최대 4개 문자열) 추가. 서버는 왼쪽 자리부터 검사해 처음 걸린 플레이어 한 명과 사유 하나만 보냄(우선순위: 파티 수 → 같은 종족 → 같은 도구 → 그 밖). 옛 키 `room.error.team_invalid`는 `.team_size`/`.duplicate_species`/`.duplicate_held_item`/`.player`로 바꿈.
- 주의: 페이로드 형식이 바뀌어 서버·클라 PvP JAR 버전이 다르면 룸 거절 패킷을 읽다가 연결이 끊길 수 있음. 둘을 같이 배치할 것.
- 빌드: `:more-cobblemon-contents-pvp:build` 성공, 테스트 통과.
- 배치: 서버(`develop-product/server/mods`)만 교체. 클라이언트는 실행 중이라 아직 못 바꿈. 이전 서버 JAR은 `deployment-backups/20261008-pvp-team-invalid-reason/server/`.
- 미확인: 실게임 확인 안 함.

## [2026-10-07 23:55] 룸 입장 시 클라이언트 크래시 — 옛 UI kit 시그니처로 빌드된 JAR

- 증상: PvP 룸에 들어가면 클라이언트가 `NoSuchMethodError: CobblemonUiButton$Companion.create$default(..., UiWidgetState, Function0, int, Object)`로 튕김(`PvpHubTab.seat`, 크래시 `crash-2026-10-07_23.49.05-client.txt`).
- 원인: cobblemon-ui `CobblemonUiButton.create`에 `downSound: Boolean` 인자가 추가됐는데(6fc4fdb4), 22:39에 배치된 PvP JAR은 그 이전 시그니처로 컴파일돼 있었음. 소스는 이미 새 API 기준이라 코드 수정은 없음. 같은 시각 배치된 MCC·팩토리·타워·리그 JAR에는 옛 호출이 없음을 바이트코드로 확인.
- 조치: `:more-cobblemon-contents-pvp:clean build`로 다시 빌드(성공, 테스트 209개 통과) → 새 JAR에 새 시그니처 호출만 있음 확인 → `develop-product/client/mods`에 배치. 이전 JAR은 `develop-product/deployment-backups/20261007-2355-pvp-stale-ui-call`.
- 서버: 개발 서버를 끈 뒤 같은 JAR로 `develop-product/server/mods` 교체(이전 JAR은 백업 폴더의 `server/`). 서버는 다시 켜지 않음.
- 미확인: 실게임에서 룸 입장 재확인은 아직 안 함.
- 재발 방지: UI kit 시그니처를 바꾸면 이를 쓰는 애드온 JAR을 전부 다시 빌드해 함께 배치한다. 증분 빌드 결과를 믿지 말고 의심되면 `clean` 후 빌드.
