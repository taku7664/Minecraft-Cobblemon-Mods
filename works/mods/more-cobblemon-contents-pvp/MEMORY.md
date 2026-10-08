# MEMORY

## [2026-10-08 21:56] main 병합 후 빌드·개발 배포 재확인

- **병합:** 기믹 API 브랜치를 현재 main과 병합한 `5d4afd558bd3c1fa63e7d81d47c568ff5d564933`을 main에 푸시했다. 기존 BP 테스트 기대값 수정 `3c247482`·`10e14da4`도 포함한다. 앞선 브랜치 배포 기록은 이번 main 검증으로 보완하며 이전 실패 기록은 보존한다.
- **빌드·테스트:** 깨끗한 워크트리에서 현재 main 소스로 MCC 5개 모듈 빌드 성공(2분 19초). PvP 테스트 210개 통과.
- **배치:** main 빌드본의 SHA-256이 앞서 배치한 JAR과 같아 재복사하지 않았다. 실제 저장소 `develop-product/server/mods`·`develop-product/client/mods`의 5개 JAR 모두 main 빌드본과 해시 일치, 중복 Fabric ID·`.deploying` 잔여물 없음. JDK 21 `jar --validate`·ZIP CRC 재검사 통과. 백업·manifest는 실제 저장소 `build/mcc-gimmick-deployment/20261008-215627-main/`에 있다.
- **미확인:** 서버·게임이 꺼진 상태에서 확인했다. 서버 기동·실게임 검증은 하지 않았으며 `deploy-product` 릴리스도 아니다.

## [2026-10-08 21:48] PvP 배틀 기믹 비트플래그 API 개발 배포

- **구현:** `e8f428ea`에서 PvP 배틀 실행 요청을 공통 `BattleMechanicFlags`의 `mechanicFlags: Int`로 전환했다. 룸의 기존 기믹 선택 집합은 실행 경계에서 비트플래그로 변환한다. 코어와 함께 재빌드·배치했다.
- **테스트:** PvP 테스트 모음 통과. 이번 기록에서는 정확한 실행 개수를 확인하지 않았다.
- **빌드·무결성:** 0.1.0 JAR 생성, JDK 21 `jar --validate`와 ZIP CRC 검사 통과.
- **배치:** 실제 저장소 `C:/Users/박주형/Documents/GitHub/Cobblemon-Mods/develop-product/server/mods`와 `develop-product/client/mods`에 배치했다. 원본·서버·클라이언트 SHA-256 일치, 중복 Fabric ID와 `.deploying` 잔여물 없음 확인. 배치 전 서버·게임 프로세스 및 25565/25566 리스너가 없었다. 이전 JAR·manifest는 실제 저장소 `build/mcc-gimmick-deployment/20261008-214821/`에 보관했다.
- **미확인:** 서버 기동·실게임 검증은 하지 않았다. `deploy-product` 릴리스가 아니라 개발 배포다.

## [2026-10-08 01:05] 룸 오류 문장이 한 줄에서 잘리던 문제

- 원인: 룸 화면·목록의 오류 칸 높이가 10px(한 줄)로 고정돼, `MccHubKit.text`가 줄바꿈해도 둘째 줄부터 버려짐.
- 구현: PvP `PvpHubTab`에서 문장을 칸 너비로 나눈 줄 수만큼(최대 3줄) 높이를 잡음. MCC 본체(`MccHubKit`)는 손대지 않음.
- 빌드 성공, 테스트 통과. 서버·클라 모두 같은 JAR로 교체(이전 JAR은 `deployment-backups/20261008-pvp-feedback-wrap/`).
- 미확인: 실게임 화면 확인 안 함.

## [2026-10-08 00:50] 룸 시작 거절 메시지에 걸린 플레이어·사유 표시

- 사용자 요청: "진영 중 한쪽의 파티가 …" 메시지가 누구의 무슨 문제인지 알려 주지 않음 → 사람과 사유를 보여 주기로 함(긴 문장 줄바꿈은 이번에 안 함).
- 구현: `PvpRoomRejectedPayload`에 `messageArgs`(최대 4개 문자열) 추가. 서버는 왼쪽 자리부터 검사해 처음 걸린 플레이어 한 명과 사유 하나만 보냄(우선순위: 파티 수 → 같은 종족 → 같은 도구 → 그 밖). 옛 키 `room.error.team_invalid`는 `.team_size`/`.duplicate_species`/`.duplicate_held_item`/`.player`로 바꿈.
- 주의: 페이로드 형식이 바뀌어 서버·클라 PvP JAR 버전이 다르면 룸 거절 패킷을 읽다가 연결이 끊길 수 있음. 둘을 같이 배치할 것.
- 빌드: `:more-cobblemon-contents-pvp:build` 성공, 테스트 통과.
- 배치: 서버·클라 모두 교체(같은 JAR, 바이트 일치 확인). 이전 JAR은 `deployment-backups/20261008-pvp-team-invalid-reason/{server,client}/`.
- 미확인: 실게임 확인 안 함.

## [2026-10-07 23:55] 룸 입장 시 클라이언트 크래시 — 옛 UI kit 시그니처로 빌드된 JAR

- 증상: PvP 룸에 들어가면 클라이언트가 `NoSuchMethodError: CobblemonUiButton$Companion.create$default(..., UiWidgetState, Function0, int, Object)`로 튕김(`PvpHubTab.seat`, 크래시 `crash-2026-10-07_23.49.05-client.txt`).
- 원인: cobblemon-ui `CobblemonUiButton.create`에 `downSound: Boolean` 인자가 추가됐는데(6fc4fdb4), 22:39에 배치된 PvP JAR은 그 이전 시그니처로 컴파일돼 있었음. 소스는 이미 새 API 기준이라 코드 수정은 없음. 같은 시각 배치된 MCC·팩토리·타워·리그 JAR에는 옛 호출이 없음을 바이트코드로 확인.
- 조치: `:more-cobblemon-contents-pvp:clean build`로 다시 빌드(성공, 테스트 209개 통과) → 새 JAR에 새 시그니처 호출만 있음 확인 → `develop-product/client/mods`에 배치. 이전 JAR은 `develop-product/deployment-backups/20261007-2355-pvp-stale-ui-call`.
- 서버: 개발 서버를 끈 뒤 같은 JAR로 `develop-product/server/mods` 교체(이전 JAR은 백업 폴더의 `server/`). 서버는 다시 켜지 않음.
- 미확인: 실게임에서 룸 입장 재확인은 아직 안 함.
- 재발 방지: UI kit 시그니처를 바꾸면 이를 쓰는 애드온 JAR을 전부 다시 빌드해 함께 배치한다. 증분 빌드 결과를 믿지 말고 의심되면 `clean` 후 빌드.
