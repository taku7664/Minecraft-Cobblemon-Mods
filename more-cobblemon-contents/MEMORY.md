# More Cobblemon Contents MEMORY

작업 내역과 이슈를 `[YYYY-MM-DD HH:MM]` 형식으로 기록한다. 최신 항목을 위에 추가한다.
구현, 빌드, JAR 배치, 서버 기동, 실게임 검증은 서로 다른 상태로 적는다.

---

## [2026-09-27 16:25] 시작 — MBC를 복사해 MCC 0.1.0 모듈 생성

- **방향(빡대리님):** MBC는 그대로 두고, 코드를 복사해 이름만 바꾼 새 프로젝트 More Cobblemon Contents(MCC)에서 재구성을 시작한다. 버전은 0.1.0부터.
- **복사 기준:** MBC `more-battle-content`를 커밋 `f3304822` 시점의 git HEAD에서 복사했다. 같은 작업 트리에 있던 다른 세션의 미커밋 수정(`api/ai/BattleBrainContracts.kt`, `Cobblemon173TowerPveBattleRuntime.kt`)은 들어오지 않았다. MBC `docs/`, `MEMORY.md`, `README.md`는 복사하지 않았다. 옛 문서는 제약이 아니며, 필요한 맥락은 MBC `MEMORY.md`의 2026-09-27 15:10·15:30 항목에 있다.
- **이름 변경 규칙(경로와 파일 내용 모두):** `jbro.cobblemon.morebattlecontent` → `jbro.cobblemon.mcc`, `cobblemon_more_battle_content` → `more_cobblemon_contents`(모드 ID·리소스 네임스페이스·번역 키·페이로드 ID·SavedData 파일명), `cobblemon-more-battle-content` → `more-cobblemon-contents`, `MoreBattleContent` → `MoreCobblemonContents`, `Mbc`/`MBC`/`mbc` → `Mcc`/`MCC`/`mcc`(클래스명, `/mcc` 명령, `mcc-battle-tower` 등 데이터팩 폴더, Mixin `mcc$` 접두사), 표시 이름 More Cobblemon Contents. `BattleContent`가 들어간 도메인 타입명(`BattleContentAccess` 등)은 그대로 두었다.
- **Gradle:** `settings.gradle.kts`에 `more-cobblemon-contents` 추가, `gradle.properties`에 `more_cobblemon_contents_version=0.1.0`. 루트 `maven_group`(`jbro.cobblemon.morebattlecontent`)이 Kotlin 모듈명으로 클래스 파일 1195개에 박혀 있어, MCC `build.gradle.kts`에서 `group = "jbro.cobblemon.mcc"`로 덮어썼다.
- **테스트 수정:** `Cobblemon173ExactOwnTeamViewTest`는 `Pokemon()`이 무작위 종을 고르는데 종 레지스트리를 다른 테스트가 채워 둔 경우에만 통과하는 순서 의존 테스트였다. MCC에서는 테스트 클래스 순서가 달라 실패했으므로, 자체 종 fixture를 등록하고 끝나면 복원하게 했다(MBC 쪽 같은 테스트는 그대로다).
- **검증:** `:more-cobblemon-contents:unitTest` 995개 통과. `remapJar` 결과 `more-cobblemon-contents-0.1.0.jar`(SHA-256 `FAA69050B2EFF928856477B428386DDC587ED0056B9DD25CCD802F16FB611C8E`): JDK 21 `jar --validate` 통과, 경로와 클래스 내용에 옛 이름 0건, 저장 제외 Mixin 대상 `method_5786` 유지.
- **미배포:** `cobblemon-dev`에는 MBC·Better AI·League가 설치돼 있고 다른 세션이 AI를 테스트 중이다. MCC를 같이 넣으면 같은 Cobblemon 지점에 Mixin이 이중으로 걸리고 `/mbc`·`/mcc` 터미널이 둘이 된다. 또 Better AI는 MBC 레지스트리에만 등록되므로 MCC 전투는 기본 AI로 돈다. MCC 전용 개발 프로필이 정해질 때까지 배포하지 않는다.
- **아직 없는 것:** Better AI 합병(AI 테스트 종료 뒤), League 이전, Core·애드온 분리, `cobblemon-ui-kit` 기반 좌측 탭 Hub(트레이너 정보 → 상점 → 애드온 탭). 기존 월드 데이터는 보존하지 않는다.
