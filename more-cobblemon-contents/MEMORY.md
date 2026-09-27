# More Cobblemon Contents MEMORY

작업 내역과 이슈를 `[YYYY-MM-DD HH:MM]` 형식으로 기록한다. 최신 항목을 위에 추가한다.
구현, 빌드, JAR 배치, 서버 기동, 실게임 검증은 서로 다른 상태로 적는다.

---

## [2026-09-27 22:30] 모듈 분리 — Core와 타워·팩토리·PvP 모드 (컴파일·JAR 확인, 테스트·실게임 미실행)

- **순서 변경(빡대리님):** 홈 리더보드와 Hub UI는 새 UI에서 버릴 구조라 등록 구조를 만들지 않고 모듈 분리부터 했다. 리더보드·상점 화면은 콘텐츠 타입 대신 기록 ID 문자열을 쓰는 임시 형태다.
- **Core 참조 끊기(`777de491`):** 공용 UI 타입을 `MccRect`·`MccPartyCardContentLayout`·`MccPlayerModelRenderer`·`MccPokemonPortraitRenderSpec`으로 Core에 두고, 타워 파티 슬롯 초상화는 타워 쪽 확장 함수로 옮겼다. Hub의 PvP 대체 경로를 지웠다. 진행도 명령은 공용 `BattleProgressCommands`와 `TowerProgressCommands`·`FactoryProgressCommands`로 나눴다. Cynthia AI 테스트는 타워 세트 파이프라인(`TowerPokemonSet`, 속성 팩토리, 레벨 캡)에 묶여 있어 타워 모드로 보냈다.
- **분리(`21d71b12` 팩토리, `028d1917` PvP, `5e46e5ef` 타워):**

  | 모듈 | 모드 ID | 엔트리포인트 | 내용 |
  |---|---|---|---|
  | `more-cobblemon-contents` | `more_cobblemon_contents` (표시 이름 More Cobblemon Contents Core) | `MoreCobblemonContents`, `MoreCobblemonContentsClient` | 관리 전투 엔진, Better AI, BP·상점, 기록, Hub, 연출, 터미널 |
  | `more-cobblemon-contents-battle-tower` | `more_cobblemon_contents_battle_tower` | `BattleTowerContent`, `BattleTowerContentClient` | 타워, 카탈로그(`mcc-battle-tower`), AI 테스트 |
  | `more-cobblemon-contents-battle-factory` | `more_cobblemon_contents_battle_factory` | `BattleFactoryContent`, `BattleFactoryContentClient` | 팩토리, 렌탈 카탈로그(`mcc-battle-factory`) |
  | `more-cobblemon-contents-pvp` | `more_cobblemon_contents_pvp` | `PvpContent`, `PvpContentClient` | PvP 룸·라운지·턴 타이머, `battle_lounge` 차원, 클라이언트 Mixin 3개(`jbro.cobblemon.mcc.pvp.mixin`, `more_cobblemon_contents_pvp.mixins.json`) |

  모두 0.1.0이며 콘텐츠 모드는 `more_cobblemon_contents >=0.1.0 <1.0.0`에 의존한다. 패키지 이름, 데이터팩 경로, 번역 키, 페이로드 ID, 콘텐츠 ID(`more_cobblemon_contents:battle_tower` 등)는 그대로다. 번역 파일만 각 모드 네임스페이스 폴더(`assets/<모드 ID>/lang`)로 옮겼다.
- **공개 범위:** Kotlin `internal`은 Gradle 모듈을 넘지 못하므로, 콘텐츠가 쓰는 Core 선언의 `internal`을 컴파일 오류를 따라 반복해서 제거했다(약 100개, `@PublishedApi` 1개 포함). API 설계가 아니라 현재 사용처 기준의 개방이다.
- **테스트 이동:** Core 테스트의 콘텐츠 검사는 각 모드의 `BattleFactoryContentContractTest`·`PvpClientContractTest`·`BattleTowerContentContractTest`로 옮겼다.
- **빌드(`a1df04ac`):** 도구 훅이 만드는 `.omc` 상태 폴더가 Core JAR 번역 폴더에 들어가던 것을 네 빌드 모두 `processResources`에서 제외했다. 네 JAR 모두 JDK 21 `jar --validate` 통과, 클래스가 모드별로 섞이지 않음(Core JAR의 tower·factory·pvp 클래스 0), PvP Mixin 재매핑 확인. 네 모듈 `remapJar`를 한 번에 돌리면 실패하고 하나씩은 성공한다(동시 재매핑 충돌로 추정, 원인 미확인).
- **미실행:** 테스트, 게임 안 확인, 배포. 개발 환경(MCC 전용 프로필 또는 `cobblemon-dev`의 MBC 교체)이 아직 정해지지 않았다.

## [2026-09-27 19:40] Core 분리 준비 1~3단계 — 정리, 엔진 통합, 서버 등록 구조 (컴파일만 확인)

- **검증 상태:** 각 커밋마다 `compileKotlin`·`compileJava`·`compileTestKotlin`만 통과시켰다. 빡대리님 지시로 테스트는 돌리지 않았고 게임 안에서도 확인하지 않았다. 소스 텍스트를 읽는 테스트(`ManagedBattleLifecycleWiringTest`, `ManagedServerCatalogCleanupRegistrationTest`)와 ID 리터럴을 쓰는 테스트는 새 구조에 맞춰 고쳐 두었다.
- **1단계 정리(`6d53e583`):** 호출처가 없던 `Cobblemon173TowerBattleTeamMaterializer` 삭제.
- **2단계 엔진 통합(`cf6a0690`):** `Cobblemon173ManagedAiBattleEngine`이 공용 PvE 흐름(접근 검사, 임시 파티, 가상 트레이너, Brain, 선봉 선택, 규칙·수명 주기 등록, 연출, 종료 정리)을 소유한다. 결과는 중립 타입 `PveLaunchResult`·`PveOutcome`과 `onEnded` 콜백으로 준다. 팩토리 전용이던 차이는 엔진 선택 항목이 됐다: `strategyBrief`, 선택형 `learningScopeId`, 관측 어댑터를 붙이는 `onBattleStarted` 훅. 타워·팩토리·`ManagedPveBattles`·AI 테스트는 얇은 어댑터다. 팩토리에도 선봉 선택이 불리지만 상대 미리보기가 없어 팀 순서를 그대로 돌려준다.
- **규칙 레지스트리(`d86384f3`):** `internal/tower/rules` → `internal/battle/rules`. `ManagedBattleRuleRegistry`, `ManagedBattleRuleRegistrationWindow`, `ManagedSubmittedMechanic`, `ManagedActionSubmission`, `ManagedActorMechanicState`, `ManagedRuleRejection`.
- **PvP 턴 가로채기(`366e4207`):** `BattleActorMixin`·`PokemonBattleMixin`·규칙 훅은 `ManagedTurnInterceptors`만 부른다(캡처→수락·거절·시간 초과 해결, 매 tick 관측, 종료 시 정리). PvP는 `PvpManagedTurnInterceptor`를 네트워킹 시작 때 등록한다. 응답 개수 검사는 `ManagedTurnResponseCardinality`로 Core에 옮겼다.
- **콘텐츠 ID 통일(`c02c1307`):** 기록·애플리케이션·BP가 네임스페이스 ID(`more_cobblemon_contents:battle_tower` 등) 하나를 쓴다. 기록 카테고리와 `BattleContentId`는 네임스페이스 형식만 허용한다. BP 출처는 콘텐츠 ID 그대로, 사유는 경로 부분(`battle_tower_3_streak_win`)이라 문자열은 전과 같다.
- **Hub 등록(`00607553`):** `BattleHubEntries`에 콘텐츠 ID·접근 콘텐츠 ID·여는 함수를 등록한다. 타워·팩토리·PvP·상점(`BattleHubIds.SHOP`)이 각자 등록한다. 열기·접근 페이로드는 enum 서수 대신 ID 문자열을 보낸다. 터미널 진입 정보는 공용 `TerminalInteractionResult.Verified`로 Hub가 보관하고, 그 Hub 세션에서 여는 항목에 넘긴다. 전에는 타워가 성공적으로 열리면 지웠는데, 지금은 Hub를 다시 열거나 연결이 끊길 때까지 유지한다. 클라이언트 탭은 4단계 전까지 `BattleHubContent` enum을 ID로 변환해 쓴다.
- **`/mcc` 하위 명령(`a9b1f331`):** BP는 루트에 두고, 타워 연승·팩토리 층·AI 테스트 명령은 `MccCommandContributors`로 각자 등록한다.
- **종료 정리와 이벤트 단계(`6c2581a7`):** 타워·팩토리 카탈로그 비우기는 각 리소스 등록 코드가 `ManagedServerEphemeralStateCleanup.register`로 추가한다. 엔티티 정리 backstop은 DISCONNECT·SERVER_STOPPING·SERVER_STOPPED의 `managed_battle_backstop` 단계(기본 단계 뒤)에서 돈다.
- **남은 결합:** 홈 리더보드(보드 8개 하드코딩, 상한 8)와 클라이언트 탭은 4단계 Hub UI 개편에서 다룬다. `MoreCobblemonContents`·`MoreCobblemonContentsClient`가 각 콘텐츠 초기화를 직접 부르는 것은 5단계 모듈 분리 때 각 모드 초기화로 옮긴다.

## [2026-09-27 18:05] 애드온 경계 제거 — `b6812d31`, `0e4121a1` (빌드·테스트 미실행)

- **`api.ai` → `internal.ai`(`b6812d31`):** AI 계약(`BattleBrainRegistry`, 전투 상태 뷰, 판단 계약 등 13개 파일)과 테스트 6개를 `jbro.cobblemon.mcc.internal.ai`로 옮기고 참조 411개 파일을 바꿨다. 두 패키지 사이에 겹치는 최상위 선언은 없었다. 공개 API `ManagedPveBattles`의 시그니처에는 이 타입들이 드러나지 않는다(내부 구현에서만 사용). `docs/betterai/` 계약 문서의 패키지 표기도 함께 바꿨고, 과거 기록인 `docs/betterai/MEMORY.md`는 그대로 두었다.
- **초기화 통합(`0e4121a1`):** `MoreCobblemonContentsBetterAi`는 더 이상 `ModInitializer`가 아니다. `fabric.mod.json`의 두 번째 main 엔트리포인트를 지우고, `MoreCobblemonContents.onInitialize` 마지막에서 `MoreCobblemonContentsBetterAi.initialize()`를 부른다. 초기화 순서는 합병 전과 같고 `MOD_ID`는 코어 값을 재사용한다. 애드온 메타데이터(애드온 이름·코어 버전 범위 의존)를 검사하던 테스트는 삭제했다.
- **검증 상태:** 빡대리님 지시로 빌드와 테스트를 돌리지 않았다. 컴파일 여부는 확인되지 않았다.

## [2026-09-27 17:45] Better AI 합병 — `54b32a24`, `2afbd2cd`, `56984ff0`

- **선봉 선택 이식(`54b32a24`):** MCC 복사 뒤 MBC에 들어온 코드 변경은 `50e9075b`(`BattleBrain.chooseLeads`, `BattleLeadChoiceContext`, `Cobblemon173LeadChoice`, `startManaged` 호출) 하나였다. 같은 이름 규칙으로 패치를 옮겼고, MBC HEAD 전체를 이름만 바꾼 결과와 MCC를 파일 단위로 비교해 의도한 두 파일(빌드 group, 테스트 fixture) 외에는 같음을 확인했다.
- **합병(`2afbd2cd`):** Better AI를 커밋 `f238a0c2` 시점 HEAD에서 복사해 `jbro.cobblemon.mcc.betterai`로 넣었다. 모드 ID는 MCC 하나로 합쳤다. 제공자 ID는 `more_cobblemon_contents:local_tactical`·`:openrouter_humanlike`, 사용률 데이터는 `data/more_cobblemon_contents/opponent_{build,move}_usage/`, 설정 디렉터리는 `config/more-cobblemon-contents`다. `MoreCobblemonContentsBetterAi`는 `fabric.mod.json`의 두 번째 main 엔트리포인트로 기존 초기화를 그대로 돈다. AI 테스트의 Better AI 확인 ID도 새 값으로 바꿨다. 빌드에 gson·graal-sdk 의존성, 오프라인 평가 태스크(`captureBaseline` 등), `-Ptests`·`-Psweeps`·`-Poracle`, 그리고 `-Pscope=core|ai`(본체만 또는 AI만)를 추가했다.
- **문서(`56984ff0`):** Better AI `docs/`는 `docs/betterai/`로, README는 `docs/betterai/OVERVIEW.md`, 작업 기록은 `docs/betterai/MEMORY.md`로 옮기고 깨진 상대 링크를 고쳤다. 라이브 보스 테스트에서 나온 미해결 이슈(0턴 에이스 테라, 에이스 선봉, 네이티브 탐색 실패와 제안 수정 3가지)는 그 기록의 2026-09-27 항목에 있다.
- **검증:** Better AI는 기존 제작자가 검증을 마친 상태로 인계받았으므로 동작 테스트는 다시 돌리지 않았다(전체 실행은 도중에 중단). 확인한 것은 합병으로 바뀐 부분이다. Better AI 테스트 코드까지 컴파일 통과, `-Pscope=core` 997개 통과, JAR(SHA-256 `47FDDA2305CA9B7E498AE30C6EBFF9229F502AABA2F8D80A16C58FE3B017C184`)에 두 엔트리포인트 클래스·betterai 클래스 642개·사용률 JSON·`native-showdown/branch-engine.cjs`가 있고 새 제공자 ID가 박혀 있으며 옛 이름은 0건, JDK 21 `jar --validate` 통과.
- **미배포:** 개발 환경 결정 대기(MCC 전용 프로필 또는 `cobblemon-dev`의 MBC 교체).

## [2026-09-27 16:25] 시작 — MBC를 복사해 MCC 0.1.0 모듈 생성

- **방향(빡대리님):** MBC는 그대로 두고, 코드를 복사해 이름만 바꾼 새 프로젝트 More Cobblemon Contents(MCC)에서 재구성을 시작한다. 버전은 0.1.0부터.
- **복사 기준:** MBC `more-battle-content`를 커밋 `f3304822` 시점의 git HEAD에서 복사했다. 같은 작업 트리에 있던 다른 세션의 미커밋 수정(`api/ai/BattleBrainContracts.kt`, `Cobblemon173TowerPveBattleRuntime.kt`)은 들어오지 않았다. MBC `docs/`, `MEMORY.md`, `README.md`는 복사하지 않았다. 옛 문서는 제약이 아니며, 필요한 맥락은 MBC `MEMORY.md`의 2026-09-27 15:10·15:30 항목에 있다.
- **이름 변경 규칙(경로와 파일 내용 모두):** `jbro.cobblemon.morebattlecontent` → `jbro.cobblemon.mcc`, `cobblemon_more_battle_content` → `more_cobblemon_contents`(모드 ID·리소스 네임스페이스·번역 키·페이로드 ID·SavedData 파일명), `cobblemon-more-battle-content` → `more-cobblemon-contents`, `MoreBattleContent` → `MoreCobblemonContents`, `Mbc`/`MBC`/`mbc` → `Mcc`/`MCC`/`mcc`(클래스명, `/mcc` 명령, `mcc-battle-tower` 등 데이터팩 폴더, Mixin `mcc$` 접두사), 표시 이름 More Cobblemon Contents. `BattleContent`가 들어간 도메인 타입명(`BattleContentAccess` 등)은 그대로 두었다.
- **Gradle:** `settings.gradle.kts`에 `more-cobblemon-contents` 추가, `gradle.properties`에 `more_cobblemon_contents_version=0.1.0`. 루트 `maven_group`(`jbro.cobblemon.morebattlecontent`)이 Kotlin 모듈명으로 클래스 파일 1195개에 박혀 있어, MCC `build.gradle.kts`에서 `group = "jbro.cobblemon.mcc"`로 덮어썼다.
- **테스트 수정:** `Cobblemon173ExactOwnTeamViewTest`는 `Pokemon()`이 무작위 종을 고르는데 종 레지스트리를 다른 테스트가 채워 둔 경우에만 통과하는 순서 의존 테스트였다. MCC에서는 테스트 클래스 순서가 달라 실패했으므로, 자체 종 fixture를 등록하고 끝나면 복원하게 했다(MBC 쪽 같은 테스트는 그대로다).
- **검증:** `:more-cobblemon-contents:unitTest` 995개 통과. `remapJar` 결과 `more-cobblemon-contents-0.1.0.jar`(SHA-256 `FAA69050B2EFF928856477B428386DDC587ED0056B9DD25CCD802F16FB611C8E`): JDK 21 `jar --validate` 통과, 경로와 클래스 내용에 옛 이름 0건, 저장 제외 Mixin 대상 `method_5786` 유지.
- **미배포:** `cobblemon-dev`에는 MBC·Better AI·League가 설치돼 있고 다른 세션이 AI를 테스트 중이다. MCC를 같이 넣으면 같은 Cobblemon 지점에 Mixin이 이중으로 걸리고 `/mbc`·`/mcc` 터미널이 둘이 된다. 또 Better AI는 MBC 레지스트리에만 등록되므로 MCC 전투는 기본 AI로 돈다. MCC 전용 개발 프로필이 정해질 때까지 배포하지 않는다.
- **아직 없는 것:** Better AI 합병(AI 테스트 종료 뒤), League 이전, Core·애드온 분리, `cobblemon-ui-kit` 기반 좌측 탭 Hub(트레이너 정보 → 상점 → 애드온 탭). 기존 월드 데이터는 보존하지 않는다.
