# MEMORY

## [2026-10-07 23:55] 룸 입장 시 클라이언트 크래시 — 옛 UI kit 시그니처로 빌드된 JAR

- 증상: PvP 룸에 들어가면 클라이언트가 `NoSuchMethodError: CobblemonUiButton$Companion.create$default(..., UiWidgetState, Function0, int, Object)`로 튕김(`PvpHubTab.seat`, 크래시 `crash-2026-10-07_23.49.05-client.txt`).
- 원인: cobblemon-ui `CobblemonUiButton.create`에 `downSound: Boolean` 인자가 추가됐는데(6fc4fdb4), 22:39에 배치된 PvP JAR은 그 이전 시그니처로 컴파일돼 있었음. 소스는 이미 새 API 기준이라 코드 수정은 없음. 같은 시각 배치된 MCC·팩토리·타워·리그 JAR에는 옛 호출이 없음을 바이트코드로 확인.
- 조치: `:more-cobblemon-contents-pvp:clean build`로 다시 빌드(성공, 테스트 209개 통과) → 새 JAR에 새 시그니처 호출만 있음 확인 → `develop-product/client/mods`에 배치. 이전 JAR은 `develop-product/deployment-backups/20261007-2355-pvp-stale-ui-call`.
- 서버: 개발 서버를 끈 뒤 같은 JAR로 `develop-product/server/mods` 교체(이전 JAR은 백업 폴더의 `server/`). 서버는 다시 켜지 않음.
- 미확인: 실게임에서 룸 입장 재확인은 아직 안 함.
- 재발 방지: UI kit 시그니처를 바꾸면 이를 쓰는 애드온 JAR을 전부 다시 빌드해 함께 배치한다. 증분 빌드 결과를 믿지 말고 의심되면 `clean` 후 빌드.
