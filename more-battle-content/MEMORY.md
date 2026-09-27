# More Battle Content MEMORY

작업 내역과 이슈를 `[YYYY-MM-DD HH:MM]` 형식으로 기록한다. 최신 항목을 위에 추가한다.
계약·합격 조건은 [`docs/`](docs/README.md)에 두고, 이 파일에는 경과·배포·검증·이슈만 쓴다.
2026-09-27 이전 항목은 커밋 시각과 옛 문서(`PROJECT_STATUS.md` §9, `MORE_BATTLE_CONTENT_SYSTEM_CONTENT_CONTEXT.md`, `MORE_BATTLE_CONTENT_GUI_UX_CONTEXT.md`)에서 옮겨 온 것이다. 옛 문서 원문은 비공개 `docs/archive/2026-09-27-mbc-docs/`에 있다.
구현, 빌드, JAR 배치, 서버 기동, 실게임 검증은 서로 다른 상태로 적는다. "실게임 미검증"은 빌드·배치까지만 확인했다는 뜻이다.

---

## [2026-09-27 03:50] 수정 — MBC 전투 포켓몬을 청크 저장에서 제외 (미배포)

- `EntityManagedBattlePersistenceMixin`: `Entity.saveAsPassenger` HEAD에서 MBC 관리 전투 포켓몬이면 `false`를 돌려준다. 청크 저장은 `EntityStorage.storeEntities` → `Entity.save` → `saveAsPassenger`의 반환값으로만 기록 여부를 정하므로(1.21.1 바이트코드 확인), ESC 일시정지 저장·자동 저장·청크 언로드 모두에 적용된다. `shouldBeSaved()`는 이 경로에서 쓰이지 않아 대상으로 삼지 않았다. PokemonEntity는 `save`·`saveAsPassenger`를 재정의하지 않는다.
- 판정(`ManagedBattleEntityPersistencePolicy`): 엔티티의 `battleId`가 MBC 규칙 레지스트리(`Cobblemon173BattleRuleHooks`)에 등록돼 있거나, 전투 종료 뒤 회수 대기 중인 포켓몬을 생명주기 레지스트리가 아직 소유하면(`ManagedBattleLifecycleRegistry.ownsTarget`, 동일 객체 비교) 저장하지 않는다. MBC 밖 전투와 엔티티는 기존 저장을 유지한다. 판정 중 예외가 나면 로그를 남기고 저장을 허용한다.
- 범위: 타워·팩토리·AI 테스트·애드온 PvE는 전투 중과 회수 대기 중 모두, PvP는 전투 중만 적용된다. PvP 복제본은 생명주기 레지스트리에 등록되지 않으므로 종료 직후 약 1.5초 회수 대기 중 저장과 강제 종료가 겹치는 경우는 남는다.
- 검증: `:more-battle-content:unitTest` 988개 통과(정책·배선 테스트 5개와 레지스트리 테스트 2개를 추가하고, 모듈 계약 테스트에 Mixin 등록 확인을 넣었다). 다른 세션의 Better AI 하네스(PID 35168)가 `build/libs/cobblemon-more-battle-content-1.6.24.jar`을 잠가 정식 `remapJar`는 실패했다. 임시 버전명으로 재매핑한 JAR에서 Mixin 대상이 `class_1297.method_5786`(saveAsPassenger)으로 바뀌고 `jar --validate`가 통과함을 확인한 뒤 임시 JAR은 지웠다. `build/libs`의 1.6.24 JAR은 수정 전 빌드다.
- 남은 일: 개발 월드에 이미 남은 4마리는 이 수정으로 사라지지 않는다. 실게임에서 "전투 중 ESC → 강제 종료 → 재접속"으로 재현 검증하지 않았다.

## [2026-09-27 03:33] 이슈 — 강제 종료 뒤 MBC 상대 포켓몬이 야생으로 남음 (원인 확정)

- **현상:** 개발 월드 `새로운 세계`의 `entities/r.-2.-1.mca`, 좌표 약 (-648, 95, -161)에 난천 팀 4마리(로즈레이드 2, 미라몽, 토게키스)가 레벨 50·`OriginalTrainerType=NPC`·`HeldItemVisible=0`인 채 떠돌고 있다. NBT에는 `BattleId`가 없고 `PokemonData`는 `["uncatchable"]`뿐이다. 로즈레이드가 둘이므로 최소 두 전투에서 샜다.
- **Cobblemon 1.8.1 결함(바이트코드 확인):** `safeCopyOf`가 복제본에 붙이는 `battleClone` 표식은 저장 때 `PokemonData`에 `"battleClone"`으로 기록되지만, 로드 때 `PokemonProperties.parse`가 키를 `toLowerCase(Locale.ROOT)`로 바꾼 뒤 등록 키 `{"battleClone"}`과 대조하므로 일치하지 않아 버려진다. 소문자 키인 `uncatchable`만 살아남는다.
- **연쇄:** 로드된 엔티티는 전투가 레지스트리에 없어 `BattleId`도 버리고, battle clone도 아니므로 Cobblemon tick의 안전장치(`!isBattling && beamMode==0 && isBattleClone → discard`)가 작동하지 않는다. 소유자 앵커도 메모리에만 있어 결과적으로 야생 포켓몬이 된다.
- **`eacf8a78`이 막지 못하는 경로:** 이 수정은 `SERVER_STOPPING`과 `DISCONNECT`에서 살아 있는 엔티티를 `discard()`한다. 그러나 싱글플레이는 ESC 일시정지마다 `Saving and pausing game...`으로 청크를 저장하고, 5분 주기 자동 저장도 있다. 전투 중 이런 저장이 한 번이라도 있은 뒤 프로세스가 강제 종료되면 종료 훅이 돌지 않으므로, 디스크의 복제본이 다음 로드 때 야생으로 되살아난다. 정상 종료는 마지막 저장 전에 엔티티를 지우므로 막힌다.
- **확인하지 못한 것:** 남은 4마리가 `eacf8a78` 이전 사고(2026-09-26 03:40)에서 생긴 것인지 이후 강제 종료에서 생긴 것인지는 보존 로그로 가릴 수 없다. `2026-09-26-7.log`가 전투 중에 끝난 것은 강제 종료가 아니라 자정 로그 회전이었다. 플레이어 쪽 복제본이 같은 방식으로 남는지는 확인하지 않았다.
- **수정 방향:** (1) MBC 관리 전투 엔티티가 청크 저장에 기록되지 않게 막는다 — 03:50 항목에서 구현. (2) 생성 때 `Pokemon.persistentData`에 대소문자 문제가 없는 MBC 표식을 넣고, 엔티티 로드 시 활성 MBC 전투에 속하지 않으면 제거한다. (2)는 이미 월드에 남은 개체도 정리한다 — 미구현.

## [2026-09-27 03:05] 이슈 — 계약 문서와 코드의 불일치

문서 정리 중 코드와 대조해 확인했다. 행별 상세는 [`docs/README.md`](docs/README.md#현재-코드와-다른-조항)의 표에 있다. 어느 쪽을 고칠지는 빡대리님이 정한다.

- **타워 AI 난도.** `c7af31e0`(2026-08-25)부터 `TowerBattleDifficultyPolicy`가 프로 구간(21연승 이후) 정규전을 `BOSS` 난도로 판단한다. Better AI `DIFFICULTY_ACTIVATION.md`와 옛 루트 인덱스는 고급·프로 정규전을 `ADVANCED`로 적고 있다. 커밋 메시지에 이 변경의 이유가 없다.
- **타워 보스 BP.** `ecac6670`(2026-08-23)부터 보스전 승리에 `TOWER_BOSS_BP_BONUS = 5`를 구간 BP(1~4)에 더한다. `STREAK_PROGRESSION.md` 3절에는 보너스가 없다.
- **타워 카탈로그 형식.** `92a42394`·`e95846d4`(2026-08-22)부터 트레이너 120명·출전 풀·대전 조건·포켓몬 세트 스키마 4로 분리됐다. 이 형식을 정의한 결정 문서가 없고, `catalog/TOWER_OPPONENT_SCHEMA3.md`와 모듈 `README.md`의 데이터팩 표만 남아 있다.
- **등록된 운영 명령.** 루트 명령 결정은 자식을 `bp`만 남긴다고 적었지만, 권한 2 전용 `tower`·`factory`(`c7af31e0`)와 `test`(`c03987a5`)가 추가됐다. 등록되지 않은 `PvpCommands.kt`·`FactoryCommands.kt`·`RemoteSpectateCommands.kt`는 소스에 남아 있다(정리 대상 후보).
- 그 밖에 Cobblemon 1.8.1 이전, 카탈로그 경로 분리, 상점 단일 품목 구매, PvP LED 바닥 분리, 룸 로비 복귀는 코드가 계약 문서보다 앞서 있다.

## [2026-09-27 03:05] 이슈 — 보스 레이드 방향이 두 문서로 갈림

- `boss-raid/MECHANICS_AND_PARTY_SCALE.md`(2026-08-20, `shared`)는 1~4인 지원, 인원별 정확한 파티 수, 공유 HP와 인원 비례 배율 제안을 담는다. `architecture/CONTENT_SCOPE.md` 4장도 이 문서를 따른다.
- `boss-raid/DESIGN_DRAFT.md`(2026-09-24, `draft`, `689177fc`)는 최대 2인 협력 더블배틀, 솔로 NPC 동료, 체력 구간·배리어·부하몹을 제안하고 3~4인 전용 엔진을 기각한다.
- 초안이 앞선 결정을 `Obsoletes`하지 않았으므로 현재 효력은 1~4인 문서에 있다. 초안을 채택하려면 후속 결정 문서가 필요하다. 구현은 양쪽 모두 없다.

## [2026-09-27 03:05] 이슈 — 실게임 미검증 목록

옛 컨텍스트 문서의 미검증 목록 가운데 이후 기록으로 닫히지 않은 항목이다. 2026-08-20 이후 커밋은 대부분 빌드·테스트만 기록하므로, 아래 항목의 현재 상태는 실제로 확인해야 한다.

- 타워: 최신 서버 JAR로 6마리 표시, 선출 순서, 다음 층 진행, 승패·연승·최고 기록 저장, 연승 구간 BP, 전설급 허용 선택 흐름
- 팩토리: 스키마 4 드래프트 변동, 선봉 순서, 교환, 레벨 50·100, NPC 무드롭·도구 비표시
- PvP: 두 클라이언트로 도전·수락·선출·시간 초과·연결 종료·승패 기록, 클릭형 초대, 11명 이상 관전자 배치, 진행 중 공개방 관전, BattleCam 순수 관전. 개발 서버가 `online-mode=true`라 한 사람으로는 두 계정을 붙일 수 없다(2026-08-20 01:42)
- 공통: 관리 전투 경험치 억제, 전투창 기믹 버튼 필터, 홈 8개 리더보드 전환, 상점 구매·인벤토리 부족·BP 부족, 권한 2 경계, 홀로 터미널 배치·드롭·우클릭
- GUI 접근성: 320×240 배치, 키보드 포커스, 내레이터. 서버 거절의 `fieldErrors`를 카드별로 표시하는 구조는 결정되지 않았다
- 레퍼런스 코드 리뷰(2026-08-18)의 남은 항목: ThreadLocal 배틀 규칙 등록 창의 예외 안전성, UUID→ServerPlayer 장기 캐시, 더블 후보의 카티전 곱. 이후 09-23 보강 커밋이 일부를 다뤘을 수 있으나 항목별로 대조하지 않았다

## [2026-09-27 03:05] 문서 정리

- 루트 `docs/`의 MBC 문서와 모듈 `docs/`의 두 문서를 `docs/{architecture,tower,catalog,pvp,economy,battle,ui,boss-raid,reference}/`로 옮기고 파일명에서 `MORE_BATTLE_CONTENT_` 접두어와 `_DECISION` 등 접미어를 뺐다. 인덱스는 [`docs/README.md`](docs/README.md)다.
- 루트 `docs/`는 `.gitignore`의 `/docs/` 규칙으로 비공개였다. Better AI 정리와 같은 방식으로 옮긴 문서는 공개 저장소에서 추적한다. 옮기기 전에 로컬 경로·API 키·토큰·계정 정보를 검사했고 `127.0.0.1` 포트 기록 외에는 없었다. 로컬 경로가 들어 있는 `MORE_BATTLE_CONTENT_DEV_DEPLOYMENT.md`는 루트에 남겼다.
- 삭제 12개(목록은 인덱스 끝). 삭제 전 루트 원본 전부와 `PROJECT_STATUS.md`·`DOCUMENT_INDEX.md`를 `docs/archive/2026-09-27-mbc-docs/`에 복사했다.
- 문서 간 참조는 스크립트로 새 상대 경로 링크로 바꿨다. 삭제한 문서를 가리키던 참조에는 `(2026-09-27 삭제, MEMORY.md 참고)`를 붙였다. League·`COBBLEMON_UI_*` 문서의 참조도 함께 고쳤다.
- 계약 문서의 조항은 고치지 않았다. 코드와 다른 조항은 인덱스의 표로 모으고, 해당 12개 문서의 제목 아래에 `2026-09-27 정리 메모` 한 줄만 붙였다. Better AI·음악·독립 연출 모드 기록은 건드리지 않았다.
- 루트 `DOCUMENT_INDEX.md`와 `PROJECT_STATUS.md`에서 MBC 기록을 걷어 내고 이 파일과 인덱스를 가리키게 했다.

## [2026-09-27 03:05] 구 문서에서 옮긴 불변식과 함정

**제품 불변식** (옛 시스템 컨텍스트 §3에서 현재도 유효한 것)
- 본체는 Better AI 없이 로드·플레이 가능해야 한다. Better AI는 본체의 공개 계약만 사용한다.
- 등록 팀은 잠금 시 복제해 메모리 세션에 보존한다. 이후 모험 파티를 바꿔도 진행 중 세션 팀은 바뀌지 않는다. 잠기지 않은 `SELECTING` 세션을 다시 열면 현재 파티로 갱신한다. 세션 NBT 복구는 만들지 않는다.
- MBC 전투창은 서버 허용 기믹과 Cobblemon 제공 기믹의 교집합만 표시한다. 버튼 비표시와 별개로 서버 제출 검사가 권위 경계다.
- 화면 문자열이나 버튼 상태를 서버 규칙의 원본으로 만들지 않는다. GUI·터미널이 명령어 문자열을 조립하지 않는다.
- 외부 모드는 공개 동작과 사용자 흐름만 참고한다. 코드·데이터·텍스처·스킨·음원·번역을 복사하지 않는다.

**전투 런타임 함정**
- 일반 `TrainerBattleActor`만으로 NPC를 만들면 Cobblemon 초기 `SwitchInstruction`의 위치 계약(`EntityBackedBattleActor`)을 충족하지 못해 Turn 0에서 멈춘다. 월드에 저장하지 않는 ArmorStand 앵커 기반 공통 actor를 유지한다.
- NPC 포켓몬을 합성 UUID의 `PlayerPartyStore`에 넣지 않는다. 드롭은 막지만 팩토리 Turn 0 정지를 일으켰다. NPC 복제본은 `OriginalTrainerType.NPC`와 앵커 소유 엔티티 폴백(`PokemonMixin`)으로 소유 상태를 만든다.
- 플레이어 복제팀(타워 스냅샷·팩토리 렌탈·PvP 스냅샷)은 모두 `Cobblemon173ManagedPlayerBattleParticipants`로 `실제 플레이어 UUID의 일회성 PlayerPartyStore 완성 → PlayerBattleActor 생성 → battlePartyStores 연결` 순서를 거친다. 콘텐츠별로 따로 구현하면 한쪽만 Turn 0에서 멈춘다(2026-08-19 13:25).
- PvP는 참가자를 라운지로 옮긴 뒤에 actor와 전투를 만든다. JOIN·DISCONNECT 콜백 안에서 차원 이동을 하지 않는다.
- 기준선·비상 행동의 요청 슬롯 수를 하드코딩하지 않는다. `ShowdownActionRequest.iterate`의 실제 슬롯 수를 쓴다.
- Cobblemon `MoveActionResponse.isValid()`는 연결된 기믹 기술만 보고 비활성 기본기까지 허용한다. 후보 어댑터가 선택 행동 자체의 사용 가능 상태를 먼저 검사해야 한다(2026-08-19 19:03).
- 부분 배포에서 기존 payload 형식을 바꾸면 구 서버·신 클라이언트가 디코드 실패로 끊긴다. 새 필드는 새 payload로 보내고, 네트워크 enum은 끝에만 추가한다(2026-08-19 03:44).

**렌더링 함정**
- Mixin 패키지 아래에 일반 클래스를 두면 `IllegalClassLoadError`가 난다. 셰이더 브리지는 `client.render`에 둔다.
- 전용 셰이더는 정확히 `DefaultVertexFormat.NEW_ENTITY`만 받는다. 이름표·글린트에 적용하면 `Missing elements in vertex: UV1, Normal`로 크래시한다.
- 1.21.1 `Screen.render()`는 내부에서 `renderBackground()`를 다시 부른다. MBC 화면은 기본 배경 렌더를 무동작으로 재정의해야 블러 잔상이 생기지 않는다.
- `InventoryScreen.renderEntityInInventory`에 넘기는 좌표는 전신의 화면 중심이다. 하단 좌표로 해석하면 하반신이 잘린다.
- 지형 홀로그램의 적용 범위를 셰이더 코드만 보고 좁히지 않는다. 2026-08-20 01:45에 PvP 바닥용으로 범위를 좁혔다가 타워·팩토리의 주변 지형 연출 전체가 사라지는 회귀가 났다.

**빌드·배포 함정** (상세 절차는 루트 `docs/MORE_BATTLE_CONTENT_DEV_DEPLOYMENT.md`)
- 테스트는 `:more-battle-content:unitTest`로 실행한다. `tasks.test`는 꺼져 있고 `--tests` 필터를 받지 않는다.
- `compileKotlin`이 `net.minecraft.class_1799` 같은 intermediary 이름을 못 찾으면 소스가 아니라 stale Loom 캐시다. `--refresh-dependencies`로 푼다.
- 2026-08-20까지 Better AI는 본체 버전을 정확히 핀해, 본체만 새 버전으로 배포하면 `HARD_DEP_NO_CANDIDATE`로 서버가 부팅 중 크래시했다. `7ed25d48`(2026-08-21)부터는 범위 핀이다(현재 `>=1.6.21 <2.0.0`). 루트 배포 문서의 "정확 일치" 서술은 옛 상태다.
- 버전을 올리면 옛 JAR을 지워야 한다. 같은 mod ID가 둘이면 Fabric이 크래시한다.
- 실행 중인 클라이언트·서버의 JAR을 덮어쓰면 Windows가 막지 않는 경우가 있다. 배포 전에 Java 프로세스를 확인한다. 클라이언트는 직접 기동하지 않고, 실행 중이면 먼저 보고한다.
- 서버는 보이는 콘솔에서 `dev-server/run.bat`으로 띄운다. 숨김 실행은 규칙 위반이다. RCON이 꺼져 있어 콘솔 `stop`은 `AttachConsole` 후 `CreateFileW("CONIN$")`로 넣는다.
- `jcmd -l`은 다른 Minecraft 클라이언트의 인증 인자까지 출력한다. Java 프로세스 전체 목록에 쓰지 않는다.
- 병행 `clean build`가 공유 `build/classes`를 지우면 테스트가 `NoClassDefFoundError`로 대량 실패한다. 병행 작업이 끝난 뒤 다시 실행해 판정한다.
- PID만으로 `cobblemon-dev`인지 판정하지 않는다. 같은 PC에 `Fabric 1.21.1` 프로필 클라이언트가 따로 있다. `gameDir`로 확인한다.
- 배틀 라운지 바이옴은 청크에 기록된다. 바이옴을 바꾸면 라운지 차원 폴더를 지워 재생성해야 하고, 그 전에 라운지 안에 로그아웃한 플레이어가 없는지 확인한다.

**Figma**
- 2026-08-18에 만든 `More Battle Content UX Structure` Draft는 정보 구조 보조 자료이고 Decision Gates·Compact·State Variants가 미완성인 채 멈췄다. 빡대리님 요청 없이 새 Draft를 만들거나 기존 파일을 수정하지 않는다. 파일 정보는 보관본의 `MORE_BATTLE_CONTENT_FIGMA_UX_HANDOFF.md`에 있다.

---

## [2026-09-26 23:16] League 런타임 API 정렬, 1.6.24 — `d8854513`

- League Challenge가 현재 MBC 릴리스의 공개 API와 맞도록 조정하면서 본체 버전을 1.6.24로 올렸다.

## [2026-09-26 21:22] 1.6.23 — `4f5c61e9`

- Better AI 보스 2깊이 프로필 클라이언트 테스트용 릴리스. 본체는 버전 번호만 바뀌었다.

## [2026-09-26 15:45] AI 테스트 진입점 보존 병합 — `13d4e03a`

- main의 관리형 AI 테스트와 `unboundedBrainDecision` 배선을 유지하면서, 애드온 PvE를 중앙 수명주기에 맞췄다. `MANAGED_PVE_EXTENSION_API.md`의 Integration update 문단이 이 경계를 적는다.

## [2026-09-26 14:22] 관리형 PvE 공개 API, 1.6.22 — `4b6ff60c`

- 애드온용 `api.battle.ManagedPveBattles`, `api.access.BattleContentAccess`, `api.rewards.BattlePointRewards`, `api.presentation.TrainerResourceSkin`을 추가했다. 계약은 [`docs/architecture/MANAGED_PVE_EXTENSION_API.md`](docs/architecture/MANAGED_PVE_EXTENSION_API.md).
- 타워 PvE 런타임의 공통 부분을 `ManagedPveBattleRuntime`으로 분리했다. 허브는 `hub_access_v1` payload로 잠금 사유를 받는다. Shadow 표시는 리소스 스킨용 `shadow_trainer_show_v2`를 쓴다.
- 요구 배경은 League Challenge의 `MBC_CORE_CHANGES.md`(챔피언 격파 전 타워·팩토리 잠금, 관장전 시작, BP 지급)다.

## [2026-09-26 04:01] 임시 전투 엔티티 수명주기 — `eacf8a78`

- `ManagedBattleLifecycleRegistry`가 타워·팩토리 전투의 임시 엔티티 등록과 정리를 한곳에서 소유한다.

## [2026-09-26 00:17] 신오 챔피언 AI 테스트 전투 — `c03987a5`

- 권한 2 전용 `/mbc test` 하위 명령과 `Cobblemon173AiTestBattleRuntime`을 추가했다. Better AI 판단 품질을 실제 전투로 확인하는 용도다.

## [2026-09-25 15:10] 실험 MbcUI 계약 — `ca767a3d`

- `api/ui/experimental/`에 타입 있는 화면 계약과 검증기를 추가했다. `ui/LEAGUE_UI_BOOTSTRAP.md`의 BOOT-4 단계다. 이후 공용 위젯은 `cobblemon-ui-kit` 모듈로 옮겨 가는 중이다(루트 `COBBLEMON_UI_*` 문서).

## [2026-09-25 05:42~09:38] AI에 공개 로스터·종 정체성 공급 — `5bef6bec`, `230c8c0f`

- 타워 등록 팀의 공개 로스터를 Brain 요청에 넣고, Showdown 종 ID를 공개 정체성으로 노출했다. Better AI 네이티브 시뮬레이션의 입력이다. AI 쪽 경과는 [`../more-battle-content-better-ai/MEMORY.md`](../more-battle-content-better-ai/MEMORY.md).

## [2026-09-24 12:25] 보스 레이드 기획 초안 — `689177fc`

- `docs/boss-raid/DESIGN_DRAFT.md`(당시 `docs/BOSS_RAID_DESIGN.md`)를 추가했다. 위 보스 레이드 방향 이슈 참고.

## [2026-09-24 03:17] Brain 작업자의 서버 CPU 예약 — `dc906f98`

- `BattleBrainExecutors`의 작업자 수를 `프로세서 수 - 2`로 계산하고 1~4개로 제한했다(이전에는 프로세서 수를 2~8개로 제한).

## [2026-09-23 01:45~18:39] 수명주기·실패 격리 보강 — `55b7a3f7`~`da7d83d4` (약 115커밋)

커밋 본문이 없어 제목으로만 분류했다. 테스트와 함께 작은 단위로 들어갔다.

- **PvE 정산:** 전투 해체 전 패배 정산, 실패한 정산 재시도, 재진입 정산 재시도, 시작 경합 중 종료 재생, 서버 재시작 사이 PvE 상태 정리, 오프라인 완료 소유자 해제, 팩토리 라운드를 정산 전에 준비
- **전투 시작 실패:** 실패한 시작 정리, 팩토리 시작 롤백과 재시도 드래프트 재사용, 팀 실체화 실패 격리, 타워 포기 경합 복구
- **PvP:** 단조 시계로 제한 시간 강제, 제출 경합, 턴 마감, 수락·초대·프리뷰 전송 실패 롤백, 룸 변경 직렬화, 선택·기믹 스냅샷, 전투 세대에 묶인 정리, 종료 중 정산 flush, 관전자 게임 모드 복구
- **Brain:** 턴 설정·완료 실패 복구, 복구 불가 턴 종료, 전투 종료 시 작업 취소, 타임아웃 폴백 보존, 공개 스냅샷 방어, 진단 크기 제한, 중복 폴백 작업 취소
- **BP·상점·기록:** 전달 중 BP 잠금 해제, 치명적 전달 롤백, 롤백 실패 노출, 저장소 불가 시 기록 쓰기 실패
- **호환·렌더:** 호환 폴백 중앙화, 카탈로그 재로드·자원 닫기 격리, 선택적 렌더·투영 전송 실패 격리, 렌더 타깃 원자 게시

## [2026-09-22 16:32] 룸·동적 피해 경계 — `16356056`

- Better AI 커밋이지만 본체 공개 기술 효과(`BattleDeclarativeMoveEffects`)와 그 테스트도 함께 바뀌었다.

## [2026-09-20 23:35] Cobblemon 1.8.1 이전, 1.6.21 — `f4158376`

- 소유 모드 전체를 Cobblemon 1.8.1로 옮겼다. Mega Showdown 의존은 `1.2.0+1.8.1+1.21.1-release`가 됐다. 호환 계층 패키지 이름 `cobblemon173`은 그대로다.
- 계약 문서의 1.7.3 서술은 갱신하지 않았다(위 불일치 이슈).

## [2026-09-06~09-07] AI 공개 사실 공급 — `d489b138`~`df2bb4b2`

- 본체 호환 계층이 공개 타입 변경, 기술 효과 공유, 대타출동 상태, PP 추정(프레셔·원한·레포열매·변신), 공개 learnset 후보를 Brain에 공급하도록 확장했다. AI 경과는 Better AI MEMORY에 있다.

## [2026-08-28 09:52~15:47] Iris 호환 홀로그램과 전투 해체 안정화, 1.6.17~1.6.20 — `5e0fd3a3`~`444f0853`

- 외부 셰이더팩을 켠 채로 경기장·트레이너 홀로그램을 포켓몬·도구 렌더에서 분리하고, Iris 최종 합성 뒤에 지형 홀로그램을 그리며 월드 공간에 고정했다.
- 관리 시설 전투를 서버 스레드에서 끝내고 임시 세션을 항상 정산한다. 서버 접속·월드 경계마다 클라이언트 상태를 초기화한다. 유휴 프레임버퍼를 해제한다.

## [2026-08-27 16:33] 더 새로운 Mega Showdown 허용 — `8ae08242`

## [2026-08-25 20:07] Better AI 로컬 Brain 재구성 스냅샷 — `c7af31e0`

- Better AI 커밋이지만 본체의 타워 난도 매핑(프로 → `BOSS`)과 `/mbc tower`·`/mbc factory` 운영 명령이 함께 들어갔다(위 불일치 이슈).

## [2026-08-23 03:12~03:35] 설정 가능한 모드 묶음, 1.6.2 — `ecac6670`, `44496167`, `a3a68b45`, `ea1b4712`

- 타워 연승 진행(`STREAK_PROGRESSION.md`), 전설급 허용(`LEGENDARY_PERMISSION.md`), 보스 BP 보너스 5, 타워 트레이너 `team_style`·`signature_species_ids`가 이 묶음에 들어갔다.
- PvP 룸 HUD를 반투명하게 바꿔 게임 화면이 보이게 했다.

## [2026-08-22 01:45~12:10] 시설 상대 다양화와 데이터팩 분리, 1.3.0~1.6.1

- `92a42394`: 타워·팩토리 상대 확장, 최근 선택 회피(`RecentSelectionHistory`).
- `059c0ed6`, `35dd14b4`: 팩토리 렌탈 순환 확대, 스키마 4 완성 프리셋 2,004개(`catalog/FACTORY_RENTAL_SCHEMA4.md`). 스키마 1~3 읽기 호환 종료.
- `5be3cbfc`, `e95846d4`: 타워·팩토리 데이터팩을 폴더별로 독립 로드하고 카탈로그 파일을 트레이너·풀·세트 단위로 나눴다. 경로는 모듈 `README.md`.
- `30659967`: 시설에서 대체 폼 보존.
- `2206ef40`, `ceb95978`, `7a31d809`: PvP 관전자 수명주기, 복귀 재시도 전 룸 해제, 관전 이탈·조작 신뢰성.

## [2026-08-21 23:31] 모듈 버전 분리, 1.2.2 — `7ed25d48`

- 단일 `mod_version`을 모듈별 버전 속성(`more_battle_content_version` 등)으로 나눴다. Better AI와 음악 MBC 연동 애드온의 본체 의존을 정확 일치 `${version}`에서 범위 `>=1.2.1 <2.0.0`으로 바꿨다.

## [2026-08-21 00:20] 라운지 보강과 엔트리 선택 재구성 — `51166927`

- 경기가 끝나면 룸을 닫지 않고 좌석·회원·초대를 유지한 채 로비로 되돌린다(`PvpRoomService.finishMatch`).
- 경기장 치수를 `PvpArenaGeometry`로 모아 빌더와 클라이언트 바닥 효과가 함께 읽는다. PvP 엔트리 카드(`PvpEntryCardButton`)를 다시 만들었다.

## [2026-08-20 03:15] 디테일 보강 5건 완료, 1.2.1

- 임시 `/mbc arenatest` 명령과 디버그 상태를 제거했다. 임시 작업 문서 `WIP-detail-polish.md`를 지우고 운영 지식은 루트 `docs/MORE_BATTLE_CONTENT_DEV_DEPLOYMENT.md`로 옮겼다. `.gitignore`에 `WIP-*.md` 규칙을 추가했다.
- 03:11 빡대리님이 다섯 항목과 라운지 바이옴·중앙 개구리불·자동 구조를 실게임에서 확인해 1.2.0으로 올렸다.

## [2026-08-20 03:06] 라운지 갇힘 자동 구조

- `PvpLoungeRescuePolicy`: 라운지 차원에 있는데 복귀 지점 기록이 없으면 접속 시 오버월드 스폰으로 옮기고 사유를 알린다. 게임 모드는 바꾸지 않는다.

## [2026-08-20 03:01] 라운지 전용 바이옴·중앙 개구리불

- 라운지가 `공허`로 표시되던 문제를 전용 바이옴 `cobblemon_more_battle_content:battle_lounge`로 고쳤다. 라운지 차원을 재생성했다.
- 경기장 바닥 정중앙을 진주빛 개구리불로 바꿨다. 이 블럭이 건설 완료 표식을 겸하므로 바꾸면 옛 경기장이 다시 지어진다.

## [2026-08-20 02:40] PvP LED 합류 연출

- 두 직선이 중앙에서 만날 때 끊기던 문제를 이동 2.4초·소멸 0.85초·정지 2.0초 주기로 고쳤다.
- 같은 대화에서 중앙선 두께 지적에 추측으로 낸 배치 수정 두 번은 모두 틀렸고 되돌렸다. 빡대리님이 배치가 정상이라고 정정했다.

## [2026-08-20 01:58] PvP 경기장 LED 바닥을 별도 효과로 재구현

- 01:42의 합성 시점 변경(`AFTER_TRANSLUCENT`)과 범위 축소가 타워·팩토리 지형 연출 전체를 지우는 회귀를 내 즉시 되돌렸다(01:45).
- 빡대리님 결정: PvP 경기장은 지형 홀로그램과 분리하고, 틴트 없이 유리 아래에서 비치는 LED 순차 점등만 쓰며, 반경은 깊이로 판정하고, 양 끝에서 출발한 두 직선이 중앙에서 만난다. `BattleArenaHologramProjection.ledFloorRadius`를 PvP만 양수로 보낸다.

## [2026-08-20 01:21] PvP 엔트리 초상화와 상대 공개 범위

- 포켓몬 챔피언스·TPCi 오픈 팀시트를 조사해, 팀 프리뷰에는 폼·리전폼을 포함한 종만 공개하고 특성·기술·노력치·도구는 숨기기로 했다. 지닌 도구 공개 주장은 단일 출처라 채택하지 않았다.

## [2026-08-20 01:01] 라운지 경기장 파괴 방지

- `PvpArenaProtectionPolicy`: 라운지 차원의 모든 월드 편집을 권한 2 미만에게 거부한다. 폭발·몹에 의한 변경은 범위 밖이다. 01:10 빡대리님이 실게임 확인.
- 이 배포는 서버·클라이언트가 켜진 상태에서 JAR을 덮어쓴 사고였다. 프로세스 확인을 생략하고 앞선 확인이 유효하다고 가정한 것이 원인이다.

## [2026-08-20 00:30] Better AI 버전 핀 크래시

- 파일명을 빌드 산출물 이름으로 바꾸면서 본체만 `1.0.0`으로 배포해 `HARD_DEP_NO_CANDIDATE`로 서버가 부팅 중 크래시했다. 처음에는 명명 관행 문제로 잘못 진단했다. Better AI를 같은 버전으로 다시 빌드해 함께 배포했다.

## [2026-08-20 00:31] 타워 종료 후 GUI 복귀, PvP 재대결 경로

- 타워 전투 종료 뒤 `TowerPlayStatePayload`를 다시 보내지 않아 빈 화면이 남던 문제를 팩토리 패턴대로 고쳤다. 00:52 빡대리님이 실게임 확인(기권 시 미복귀 같은 경계는 개별 재현하지 않음).

## [2026-08-19 19:18] 상점 단일 품목 구매

- 장바구니 요약과 `비우기`를 없애고 상품 하나만 선택해 수량을 조절한다. 서버 구매 요청은 한 줄만 보낸다. 계약 문서 `economy/BP_SHOP.md`는 갱신하지 않았다.
- 병행 서버 AI 변경이 섞인 전체 빌드를 그대로 배치하지 않고, 검증된 클라이언트 상점 클래스만 바꾼 배치본을 만들어 클라이언트에만 넣었다.

## [2026-08-19 19:03] 비활성 기술 제출로 인한 전투 정지

- Showdown이 disabled 상태의 신속을 거부하고 Cobblemon이 `error` 행동을 해석하지 못해 전투가 멈췄다. 후보 어댑터가 선택 행동 자체의 사용 가능 상태를 먼저 검사하도록 고쳤다. 기본·메가·테라는 원래 기술, 다이맥스는 선택된 Max Move 상태를 따른다.

## [2026-08-19 18:40~19:00] PvP 룸 목록 새로고침

- 목록에 `새로고침` 버튼을 추가했다. 방 회원에게 무반응이던 원인은 서버 `Refresh`가 회원에게 목록 대신 룸 상태를 돌려준 것이었다. 회원 여부와 무관하게 항상 목록을 돌려주도록 고쳤다.

## [2026-08-19 18:35] 공통 경기장 홀로그램 분리

- 몬스터볼 LED 지형 홀로그램을 Shadow 패킷에서 분리해 전투 UUID·월드 중앙·상대 방향 payload로 다시 만들었다(`battle/SHARED_ARENA_HOLOGRAM.md`). 전체 테스트 중 무관한 `Cobblemon173ShowdownMoveEffectsTest` 1건이 실패한 상태였다.

## [2026-08-19 17:49] 카탈로그 특성·기술 ID 정정

- 팩토리 번치코 특성이 번역 키 원문으로 보인 것을 계기로 54세트의 밑줄 특성 경로를 고치고, `PokemonProperties`에 특성·기술만 네임스페이스 없이 넣도록 했다(`catalog/PROPERTY_ID.md`).

## [2026-08-19 17:30~17:31] 팩토리 카드 초상, PvP Turn 0

- 팩토리 카드가 3D 초상을 그리지 않던 GUI 누락을 공통 `MbcPokemonPortraitRenderer`로 고쳤다.
- 첫 실제 PvP에서 기술·교체 UI가 나오지 않은 원인은 전투 생성 뒤 참가자를 라운지로 옮긴 순서였다(`pvp/PRE_BATTLE_PLACEMENT.md`). 17:04 서버가 플레이어 제거 중 `DistanceManager.removePlayer` NPE로 종료된 기록이 있다.

## [2026-08-19 16:34~16:42] 승리 BP 연결과 `/mbc` 단일 진입

- 저장 월드에 BP 파일이 없던 원인은 타워·팩토리 완료 경로가 정산 서비스를 호출하지 않은 것이었다. 전투 UUID를 거래 UUID로 쓰는 2 BP 정산을 연결했다(`architecture/ROOT_COMMAND_AND_VICTORY_BP.md`). 타워 BP는 08-22에 연승 구간 방식으로 바뀌었다.
- 16:35 외부 접속을 위해 `dev-server/server.properties`의 `server-ip`를 비웠다.

## [2026-08-19 15:35] 팩토리 스키마 3 랜덤화(폐기됨)

- 기술 슬롯·도구 후보·전체 성격 풀을 드래프트 때 한 번만 실체화하는 스키마 3을 적용했다. 2026-08-22 스키마 4가 이 방식을 폐기했다.

## [2026-08-19 13:25~15:16] 공통 플레이어 로스터, 홈 대시보드

- 타워 Turn 0 정지 원인은 팩토리에만 있던 플레이어 임시 파티 경로가 타워·PvP에 없던 콘텐츠별 편차였다. `Cobblemon173ManagedPlayerBattleRoster` 하나로 통일했다.
- 첫 탭을 `홈`으로 바꾸고 캐릭터·리더보드·상점 3열, 전신 모델 중심 좌표, 8개 리더보드로 차례로 고쳤다(`ui/HOME_*`). 각 단계는 빡대리님 캡처로 판정했다.

## [2026-08-19 05:03] NPC 소유 앵커로 교체

- 04:30의 "NPC 합성 `PlayerPartyStore` 유지 + 역할 분리" 진단이 실게임에서 틀렸음을 확인했다. RCT의 공개 런타임 계약을 참고해, NPC 복제본 UUID를 비저장 ArmorStand 앵커에 등록하고 원래 소유자가 없을 때만 `PokemonMixin`이 그 앵커를 돌려주도록 바꿨다.

## [2026-08-19 03:36~04:48] 드롭 억제 정정, 기믹 버튼 필터, 클릭형 초대, 상점

- 이전의 "상대 사망 드롭을 고쳤다"는 보고가 틀렸다. 일반 `PartyStore`는 `ownerUUID`가 `null`이라 `doDeathDrops()`가 계속 돌았다(`battle/TRAINER_LOOT_SUPPRESSION.md`).
- 전투창 기믹 버튼 필터, 채팅 `[입장] [거절]` 초대, 44품목 상점과 가로 스크롤(13:36 홈 대시보드가 폐기)을 구현했다.
- 03:44 허브 payload에 BP 필드를 붙였다가 구 서버·신 클라이언트가 디코드 실패로 끊겼다. BP는 별도 `BattleHubHeaderStatePayload`로 보낸다.

## [2026-08-19 01:33~03:38] 관리 전투 경험치 억제, PvP 룸·라운지, 고정 탭

- 관리 전투 경험치 억제와 도구 외형 숨김을 구현했다.
- PvP 룸·라운지·공통 허브를 구현했다. 02:39 루트 `뒤로 → 허브`를 넣었다가 03:05 빡대리님 결정으로 고정 탭으로 바꾸고, 03:38 상단바·탭 좌표를 공통 프레임으로 고정했다.
- 03:09 팩토리 스키마 2(112세트)로 반복 드래프트와 미진화체 문제를 고쳤다(이후 스키마 4가 대체).

## [2026-08-19 00:12~00:52] 타워 커스텀 GUI

- 23:44 캡처에서 타워 화면이 바닐라 버튼 표면에 의존함을 확인했고, 코드 드로잉 셸·패널·카드로 교체했다(`ui/TOWER_CUSTOM_GUI.md`).
- 초상 미표시는 `drawProfilePokemon`의 프로필 변환을 끈 실수, 팀 확정 무반응은 정상적인 `duplicate_species` 거절을 버튼 비활성으로 숨긴 UX였다. 확정 버튼은 필요 마릿수·기믹을 모두 고른 뒤에만 활성화하고 서버 오류를 구체적으로 표시한다.
- 00:26 서버를 숨김 창으로 재기동한 규칙 위반을 빡대리님이 지적해 보이는 콘솔로 다시 띄웠다.

## [2026-08-18 22:59~23:36] 몬스터볼 경기장·Shadow 색

- 중앙 별도 원형 바닥을 없애고 외곽 청록 → 중앙 백색 보간, 확대 LED와 3.6초 반복 점등을 넣었다. 23:17 플레이어 → Shadow 방향을 고정한 적색·백색 반구 몬스터볼 시안, 23:24 검은 사각 격자 보정(원본 지형 텍스처 보존율 39% → 8%), 23:36 Shadow를 흑황색으로 바꾸고 무늬를 월드 좌표에 고정했다.
- 그 이전의 Shadow 셰이더·지형 RT 합성 반복 조정(청록 → 은회색 → 흑황색, 스캔선 간격·가우시안 선 등)의 상세 수치는 보관본 `PROJECT_STATUS.md` §9에 있다.

## [2026-08-18 16:11] 외부 코드 리뷰 정적 재검증

- 당시 작업 사본의 리뷰를 코드와 대조해 P0(랭크 4 이상 상대 프로필 공백, Better AI 설정 검증 우회), P1(ThreadLocal 등록 창 예외 안전성, UUID→ServerPlayer 장기 캐시, OpenRouter 풀 공유, 타임아웃 뒤 작업 지속, 콘텐츠 서비스 타워 전용, 화면·명령 포기 경로 차이, 관측성, 더블 후보 카티전 곱), P2 항목을 정리했다.
- 설정 검증 우회와 API 키 `toString()` 노출은 곧바로 고쳤다. 랭크 프로필 공백은 08-22 연승 진행 전환으로 의미가 없어졌다. 나머지는 항목별로 닫혔는지 확인하지 않았다.

## [2026-08-18] 첫 팩토리 실게임에서 NPC 미출전

- 선수만 나오고 NPC가 나오지 않는 정지를 재현했다. 0슬롯 `wait`에 패스 1개를 내던 하드코딩은 직접 원인이 아니었고, 평범한 `TrainerBattleActor`가 초기 `SwitchInstruction`의 위치 계약을 충족하지 못한 것이 원인이었다. ArmorStand 앵커 actor로 고쳤다.

## [2026-08-17~08-19] 새 구조 착수

- `Cobblemon Battle Facilities`의 API·Tower·Intelligence 세 모드를 종료하고 `refactor` 브랜치에서 본체와 Better AI 두 모듈로 다시 만들었다(`architecture/DESIGN.md`). 옛 문서 원본은 `private-docs` 브랜치에 있다.
- Cobblemon Battle Tower 1.10.22를 레퍼런스로 분석했다(`reference/`). 콘텐츠는 타워 → 팩토리 → PvP → 보스 레이드 순으로 확정했다.
- 2026-08-19 21:41 `544d6456`으로 현재 공개 저장소의 기준선을 올렸다. 그 이전 기록은 커밋이 아니라 옛 문서에만 있다.
