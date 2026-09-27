# More Battle Content 문서 인덱스

| 항목 | 값 |
|---|---|
| 정리일 | 2026-09-27 |
| 주 독자 | 빡대리님과 MBC 본체 설계·구현·검증 담당자 |
| 작업 기록 | [`../MEMORY.md`](../MEMORY.md) — 날짜·시각별 작업 내역과 이슈(최신이 위) |
| 사용법·데이터팩 | [`../README.md`](../README.md) — 빌드, 데이터팩 폴더·스키마, 카탈로그 덮어쓰기 규칙 |
| 개발 배포 절차 | 루트 `docs/MORE_BATTLE_CONTENT_DEV_DEPLOYMENT.md` — 로컬 경로가 들어 있어 비공개 `docs/`에 남겼다 |
| Better AI | [`../../more-battle-content-better-ai/docs/README.md`](../../more-battle-content-better-ai/docs/README.md) |

이 폴더에는 현재 효력이 있는 계약 문서만 둔다. 작업 경과·배포·검증 기록은 `MEMORY.md`에 쓰고, 계약 문서에는 규칙과 합격 조건만 남긴다. 계약 문서 본문의 배포 해시·테스트 개수는 작성 당시 기록이며 현재 상태가 아니다.

## 읽는 순서

1. [`architecture/DESIGN.md`](architecture/DESIGN.md) — 모듈 경계, 의존 방향, 승계·폐기 정책
2. [`architecture/CONTENT_SCOPE.md`](architecture/CONTENT_SCOPE.md) — 네 콘텐츠의 핵심 규칙
3. 아래 [현재 코드와 다른 조항](#현재-코드와-다른-조항) — 문서를 그대로 믿으면 틀리는 곳
4. [`../MEMORY.md`](../MEMORY.md) — 최근 작업과 열린 이슈
5. 작업 대상에 해당하는 분류의 문서와 실제 코드

## 우선순위

두 문서가 충돌하면 더 늦게 효력이 생긴 문서가 우선한다. 각 문서 머리표의 `Updates`·`Obsoletes`가 대체 관계를 나타낸다. 문서와 코드가 다르면 코드·테스트·런타임을 먼저 확인하고, 차이를 `MEMORY.md`에 이슈로 남긴 뒤 어느 쪽을 고칠지 빡대리님이 정한다.

## 분류

### architecture — 전체 구조와 진입

| 문서 | 효력일 | 내용 |
|---|---|---|
| [`DESIGN.md`](architecture/DESIGN.md) | 2026-08-18 | 옛 Battle Facilities 종료, 본체·Better AI 두 모듈 경계, 공개 애드온 계약, 개발 순서, Battle Hub 셸 원칙 |
| [`CONTENT_SCOPE.md`](architecture/CONTENT_SCOPE.md) | 2026-08-18 | 배틀타워·배틀팩토리·보스 레이드·PvP의 확정 플레이 규칙. 타워 랭크 조항은 `tower/STREAK_PROGRESSION.md`가 대체 |
| [`TERMINAL_AND_BP.md`](architecture/TERMINAL_AND_BP.md) | 2026-08-18 | 코드 생성 홀로 터미널, 터미널·GUI·명령어의 같은 서버 서비스, BP 경제 존치 |
| [`ROOT_COMMAND_AND_VICTORY_BP.md`](architecture/ROOT_COMMAND_AND_VICTORY_BP.md) | 2026-08-19 | `/mbc` 단일 GUI 진입, 공개 하위 명령은 `bp`만, 전투 UUID 멱등 승리 BP 정산 |
| [`MANAGED_PVE_EXTENSION_API.md`](architecture/MANAGED_PVE_EXTENSION_API.md) | 2026-09-26 | 애드온용 관리형 PvE 전투·시설 접근 정책·BP 보상·리소스 스킨 실험 API v1 (League Challenge가 사용) |

### tower — 배틀타워 규칙

| 문서 | 효력일 | 내용 |
|---|---|---|
| [`MECHANIC_SESSION.md`](tower/MECHANIC_SESSION.md) | 2026-08-18 | 도전 전 `MEGA/DYNAMAX/TERA` 중 하나를 골라 세션 전체와 양측에 고정 |
| [`RUNTIME_INTEGRATION.md`](tower/RUNTIME_INTEGRATION.md) | 2026-08-18 | Mega Showdown 필수 의존, 명시적 포기만 패배, 승패 미확정 종료는 무효 |
| [`STREAK_PROGRESSION.md`](tower/STREAK_PROGRESSION.md) | 2026-08-22 | 11랭크 폐기. 싱글·더블 현재/최고 연승, 입문·실전·고급·프로 구간, 5승 단위 보스, 구간별 1~4 BP |
| [`LEGENDARY_PERMISSION.md`](tower/LEGENDARY_PERMISSION.md) | 2026-08-23 | 전설급 허용은 후보 제한 해제일 뿐 선출 강제가 아니며, 첫 전투 전에만 바꿀 수 있다 |

### catalog — 상대·렌탈 데이터

| 문서 | 효력일 | 내용 |
|---|---|---|
| [`TOWER_OPPONENT_DATA.md`](catalog/TOWER_OPPONENT_DATA.md) | 2026-08-18 | 타워 NPC 팀은 MBC 전용 신규 데이터. 본가 시설의 공개 패턴만 참고 |
| [`TOWER_OPPONENT_SCHEMA3.md`](catalog/TOWER_OPPONENT_SCHEMA3.md) | 2026-08-18 | 기믹별 `tera_type`·`dmax_level`·`gmax_factor`·메가스톤 선언 규칙. 18프로필·72세트 수치는 현재 데이터가 아님(아래 불일치 표) |
| [`FACTORY_RENTAL_SCHEMA4.md`](catalog/FACTORY_RENTAL_SCHEMA4.md) | 2026-08-22 | 501종·폼 × 4 = 2,004개 완성 렌탈 프리셋. 무작위성은 세트 선발에만 적용 |
| [`PROPERTY_ID.md`](catalog/PROPERTY_ID.md) | 2026-08-19 | 카탈로그는 네임스페이스 리소스 ID, `PokemonProperties`에는 특성·기술만 Showdown 이름으로 변환 |

### pvp — 플레이어 1대1

| 문서 | 효력일 | 내용 |
|---|---|---|
| [`ROOM_AND_LOUNGE.md`](pvp/ROOM_AND_LOUNGE.md) | 2026-08-19 | 공개·비공개 룸, 복수 기믹 진영당 1회, 팀 프리뷰, 배틀 라운지 차원, 순수 관전과 복귀. 8절 영속 저장은 승인 대기 |
| [`PRE_BATTLE_PLACEMENT.md`](pvp/PRE_BATTLE_PLACEMENT.md) | 2026-08-19 | 참가자를 actor 생성 전에 라운지로 배치. 세 콘텐츠의 플레이어 actor·임시 파티 수명주기 공통화 |
| [`CLICKABLE_INVITE.md`](pvp/CLICKABLE_INVITE.md) | 2026-08-19 | 채팅 `[입장] [거절]`이 명령어 문자열 없이 룸 패킷을 직접 보냄 |

### economy — BP·상점·기록

| 문서 | 효력일 | 내용 |
|---|---|---|
| [`BP_STORAGE.md`](economy/BP_STORAGE.md) | 2026-08-18 | 초기 0 BP, 플레이어·거래 UUID 멱등 원장, 전체 이력 보존, NBT 스키마 1 |
| [`BP_SHOP.md`](economy/BP_SHOP.md) | 2026-08-19 | JSON 기반 44품목, 실제 아이템 툴팁, 서버 권위 원자 구매, 부분 배포용 enum ordinal 보존 |
| [`RECORDS.md`](economy/RECORDS.md) | 2026-08-18 | 승패·현재/최고 연승·진행·최고 지표 집계만 저장. 경기 원장·세션 복구·PvP 레이팅 제외 |

### battle — MBC 관리 전투 공통 규칙과 연출

| 문서 | 효력일 | 내용 |
|---|---|---|
| [`REWARD_AND_HELD_ITEM.md`](battle/REWARD_AND_HELD_ITEM.md) | 2026-08-19 | 관리 전투만 처치 경험치 억제, 전투 복제본의 지닌 도구 외형 숨김 |
| [`TRAINER_LOOT_SUPPRESSION.md`](battle/TRAINER_LOOT_SUPPRESSION.md) | 2026-08-19 | 상대 포켓몬 사망 드롭 억제. 3절 합성 `PlayerPartyStore` 방식은 `MEMORY.md` 2026-08-19 05:03 항목에서 ArmorStand 소유 앵커로 교체됨 |
| [`MECHANIC_BUTTON_VISIBILITY.md`](battle/MECHANIC_BUTTON_VISIBILITY.md) | 2026-08-19 | 전투창 기믹 버튼은 타워 1개·팩토리 0개·PvP 선택분만. 서버 제출 검사가 최종 경계 |
| [`PLAIN_NPC_NAMES.md`](battle/PLAIN_NPC_NAMES.md) | 2026-08-19 | NPC 표시 이름은 한·영 일반 사람 이름 |
| [`SHADOW_NPC_NAME.md`](battle/SHADOW_NPC_NAME.md) | 2026-08-19 | Shadow는 도전자 스킨을 유지하고 이름표만 상대 액터 이름을 사용 |
| [`SHARED_ARENA_HOLOGRAM.md`](battle/SHARED_ARENA_HOLOGRAM.md) | 2026-08-19 | 몬스터볼 LED 지형 홀로그램을 Shadow와 분리한 전투 UUID 수명주기 |

### ui — 화면

| 문서 | 효력일 | 내용 |
|---|---|---|
| [`TOWER_PLAY_SCREEN.md`](ui/TOWER_PLAY_SCREEN.md) | 2026-08-18 | 첫 타워 화면은 Play만. 서버 승인 전 상태 확정 금지, request ID·revision |
| [`TOWER_PARTY_CARD.md`](ui/TOWER_PARTY_CARD.md) | 2026-08-18 | 클릭 순서가 실제 출전 순서, 카드에 초상·이름·배틀 레벨·도구 |
| [`TOWER_CUSTOM_GUI.md`](ui/TOWER_CUSTOM_GUI.md) | 2026-08-18 | 바닐라 버튼 표면 없이 코드로 그리는 셸·패널·카드 |
| [`TABBED_CONTENT_AND_PVP_ROOM.md`](ui/TABBED_CONTENT_AND_PVP_ROOM.md) | 2026-08-19 | 루트 `뒤로` 폐기, 고정 탭, PvP 대기실 배치, 정면 유휴 모델, PvP 기본 기믹 `MEGA` |
| [`FIXED_CHROME_AND_PVP_ROOM_OVERLAY.md`](ui/FIXED_CHROME_AND_PVP_ROOM_OVERLAY.md) | 2026-08-19 | 고정 상단바·탭 좌표, PvP 룸 전용 화면의 X/ESC → 룸 목록 |
| [`HOME_DASHBOARD.md`](ui/HOME_DASHBOARD.md) | 2026-08-19 | 첫 탭 `홈`, 캐릭터·리더보드·상점, 독립 세로 스크롤 |
| [`HOME_COMPACT_BALANCE.md`](ui/HOME_COMPACT_BALANCE.md) | 2026-08-19 | 상단 BP 배지 제거, BP는 상점 헤더, 제목 `상점`. 열 비율은 다음 문서가 대체 |
| [`HOME_THREE_COLUMN.md`](ui/HOME_THREE_COLUMN.md) | 2026-08-19 | 캐릭터 → 리더보드 → 상점의 동일 높이 3열 |
| [`HOME_MODEL_AND_LEADERBOARD.md`](ui/HOME_MODEL_AND_LEADERBOARD.md) | 2026-08-19 | 전신 모델 중심 좌표, 타워 2·팩토리 4·PvP 2의 8개 리더보드 |
| [`DECLARATIVE_UI_FRAMEWORK.md`](ui/DECLARATIVE_UI_FRAMEWORK.md) | 2026-09-25 | MbcUI 선언형 UI 원결정. 화면 문서·웹 미리보기 부분은 다음 문서가 수정 |
| [`UI_RESOURCE_BOUNDARY.md`](ui/UI_RESOURCE_BOUNDARY.md) | 2026-09-25 | 화면 구조는 Kotlin 타입, 외부 팩은 시각 자산만(Visual Pack), Minecraft 우선 |
| [`LEAGUE_UI_BOOTSTRAP.md`](ui/LEAGUE_UI_BOOTSTRAP.md) | 2026-09-25 | League 최소 모듈부터 착수, 실험 MbcUI, 안정 API 승격 게이트 |

MbcUI의 저장소 간 재사용 범위는 루트 `docs/COBBLEMON_UI_DESIGN_SYSTEM_BOUNDARY_DECISION.md`와 `docs/COBBLEMON_UI_TOOLKIT_AND_RUNTIME_EVIDENCE_AMENDMENT.md`가 다시 갱신했다. 공용 위젯 계약은 `cobblemon-ui-kit` 쪽 문서를 따른다.

### boss-raid — 보스 레이드(미구현)

| 문서 | 효력일 | 내용 |
|---|---|---|
| [`MECHANICS_AND_PARTY_SCALE.md`](boss-raid/MECHANICS_AND_PARTY_SCALE.md) | 2026-08-20 | 레퍼런스 Boss Mode 역분석, 1~4인 확장과 인원 비례 HP 배율 제안 |
| [`DESIGN_DRAFT.md`](boss-raid/DESIGN_DRAFT.md) | 2026-09-24 `draft` | 최대 2인 협력 더블배틀, 체력 구간·배리어·부하몹 기획 초안 |

두 문서의 인원·전투 구조가 다르다. 어느 쪽을 따를지는 `MEMORY.md`의 열린 이슈를 보라.

### reference — 외부 레퍼런스 분석

| 문서 | 내용 |
|---|---|
| [`BATTLE_TOWER_REFERENCE_ANALYSIS.md`](reference/BATTLE_TOWER_REFERENCE_ANALYSIS.md) | Cobblemon Battle Tower 1.10.22(ARR) 기능·JAR·런타임·GUI 상세 분석. 6.4절 공유 HP는 `boss-raid/MECHANICS_AND_PARTY_SCALE.md`가 정정 |
| [`BATTLE_TOWER_REFERENCE_MATRIX.md`](reference/BATTLE_TOWER_REFERENCE_MATRIX.md) | 위 분석의 채택·변형·폐기 요약표 |

레퍼런스는 동작과 UX만 참고한다. 코드·데이터·자산·번역은 복사하지 않는다.

## 현재 확정 결정

2026-09-27에 코드로 확인한 값이다. 확인 근거가 코드가 아닌 항목은 괄호로 표시했다.

| 영역 | 결정 |
|---|---|
| 모드 | `Cobblemon: More Battle Content` / `cobblemon_more_battle_content`, 서버+클라이언트. 버전 `1.6.24` (`gradle.properties`) |
| 필수 의존 | Cobblemon `>=1.8.1 <1.9.0`, Mega Showdown `1.2.0+1.8.1+1.21.1-release` 정확 일치 (`fabric.mod.json`). 호환 계층 패키지 이름은 아직 `internal/compat/cobblemon173` |
| Better AI | 선택형 서버 애드온. 본체 `>=1.6.21 <2.0.0`을 요구한다 (`more-battle-content-better-ai/src/main/resources/fabric.mod.json`). 2026-08-20까지의 정확 일치 핀은 `7ed25d48`에서 범위로 바뀌었다 |
| 진입 | 홀로 터미널과 `/mbc`가 같은 GUI를 연다. 일반 사용자 하위 명령은 `bp`뿐이다. 권한 레벨 2 운영 명령으로 `/mbc tower streak`·`/mbc factory floor`(진행 조회·설정·초기화)와 `/mbc test`(AI 테스트 전투)가 있다 |
| 탭 | `홈 → PvP → 배틀타워 → 배틀팩토리 → 보스 레이드`, 기본 `홈`. 내부 enum 이름은 `SHOP`이고 ordinal은 부분 배포 호환 때문에 고정 |
| 배틀타워 | 서로 다른 종족 6마리 등록, 싱글 3·더블 4 선출, 가방 금지, 지닌 도구 중복 금지, 세션 고정 단일 기믹, 전설급 허용 선택 |
| 타워 진행 | 싱글·더블별 현재/최고 연승. 1~5 입문, 6~10 실전, 11~20 고급, 21+ 프로. 다음 승리가 5의 배수면 보스전 |
| 타워 BP | 구간별 1·2·3·4 BP. 보스전 승리는 여기에 5 BP를 더한다 (`TOWER_BOSS_BP_BONUS`, 계약 문서 없음) |
| 배틀팩토리 | 레벨 50·오픈 레벨 100, 스키마 4 완성 렌탈, 7승 라운드, 기믹 없음, 승리 2 BP |
| PvP | 공개·비공개 1대1 룸, 새 룸 기본 기믹 `MEGA`, 복수 기믹 진영당 종류별 1회, 경기가 끝나면 룸은 로비로 돌아감 (`PvpRoomService.finishMatch`) |
| 보스 레이드 | 탭만 비활성으로 노출. 구현 없음 |
| 저장 | BP `cobblemon_more_battle_content_bp`, 기록 `cobblemon_more_battle_content_records`. 세션·PvP 라운지 복귀 지점은 저장하지 않음 |
| 관리 전투 | 경험치 억제, 도구 외형 숨김, NPC 드롭 억제, 허용 기믹 버튼만 표시 |
| AI 부재 | 본체의 Cobblemon 기준선 AI로 진행 |

## 현재 코드와 다른 조항

계약 문서의 조항을 고치지 않고 옮겼으므로, 아래 조항은 문서 본문과 현재 코드가 다르다. 해당 문서 제목 아래에도 `2026-09-27 정리 메모`를 붙였다. 확인 방법은 각 행의 근거 열에 적었다.

| 문서·조항 | 문서의 서술 | 현재 코드 | 근거 |
|---|---|---|---|
| 여러 문서의 Cobblemon·Mega Showdown 버전 (`tower/RUNTIME_INTEGRATION.md` 1절 등) | Cobblemon 1.7.3, Mega Showdown `1.9.3+1.7.3+1.21.1` 정확 일치 | Cobblemon 1.8.1, Mega Showdown `1.2.0+1.8.1+1.21.1-release` | `f4158376`(2026-09-20), `fabric.mod.json` |
| `/mbc open`과 `/mbc pvp`·`factory`·`status` 등 (`pvp/ROOM_AND_LOUNGE.md` 9절, `pvp/CLICKABLE_INVITE.md` 2절, `ui/` 여러 문서) | 하위 명령으로 화면을 연다 | `/mbc`만 GUI를 열고 공개 하위 명령은 `bp`뿐 | `architecture/ROOT_COMMAND_AND_VICTORY_BP.md` |
| 루트 공개 자식 (`architecture/ROOT_COMMAND_AND_VICTORY_BP.md` 1절) | `/mbc`의 자식은 `bp`만 | 권한 2 전용 `tower`·`factory`·`test`가 추가로 등록됨. `PvpCommands.kt`·`FactoryCommands.kt`·`RemoteSpectateCommands.kt`는 남아 있지만 등록되지 않음 | `c7af31e0`(2026-08-25), `c03987a5`(2026-09-26), `BattleContentCommands.kt` |
| 카탈로그 경로 (`catalog/*`, `economy/BP_SHOP.md` 1절) | `battle_tower/opponents/mbc_core.json`, `battle_factory/catalog/mbc_core.json`, `bp_shop/catalog/mbc_core.json` 단일 파일 | `mbc-battle-tower/{trainers,pools,encounters,pokemon-sets}`, `mbc-battle-factory/{trainers,rental-sets}`, `mbc-bp-shop/{rules,entries}`로 분리, 폴더별 독립 재로드 | `5be3cbfc`·`e95846d4`(2026-08-22), `../README.md` |
| 타워 상대 데이터 (`catalog/TOWER_OPPONENT_SCHEMA3.md`, `catalog/TOWER_OPPONENT_DATA.md` 3·4절) | 스키마 3, 랭크 ID가 있는 18프로필·72세트 | 트레이너 120명(`team_style`·`signature_species_ids`), 출전 풀 6, 대전 조건 24, 포켓몬 세트 스키마 4 파일 240개. 이 형식의 결정 문서는 없다 | `92a42394`·`e95846d4`, 리소스 폴더 |
| 타워 랭크 지표 (`economy/RECORDS.md` 2절, `ui/HOME_DASHBOARD.md` 2절, `ui/HOME_MODEL_AND_LEADERBOARD.md` 3절) | `current_rank`·`highest_rank` 기준 저장·정렬 | 리더보드는 최고 연승 → 누적 승리 → 적은 패배 | `tower/STREAK_PROGRESSION.md`, `HomeLeaderboard.kt` |
| 타워 승리 BP (`architecture/ROOT_COMMAND_AND_VICTORY_BP.md` 2절) | 타워·팩토리 승리 각 2 BP | 타워는 구간별 1~4 BP + 보스 승리 5 BP. 팩토리는 2 BP 유지 | `tower/STREAK_PROGRESSION.md` 3절, `TowerProgression.kt` |
| 타워 AI 난도 (Better AI `behavior/DIFFICULTY_ACTIVATION.md`, 옛 루트 인덱스) | 고급·프로 정규전은 `ADVANCED` | 프로 구간 정규전은 `BOSS` | `c7af31e0`(2026-08-25), `TowerBattleDifficultyPolicy.kt` |
| 상점 장바구니 (`economy/BP_SHOP.md` 3절) | 여러 품목 장바구니, `비우기` | 상품 하나만 선택하고 수량만 조절. 서버 요청은 한 줄 | `MEMORY.md` 2026-08-19 19:18 |
| PvP 경기장 연출 (`battle/SHARED_ARENA_HOLOGRAM.md` 2절) | 타워·팩토리와 같은 몬스터볼 지형 홀로그램 | PvP는 `ledFloorRadius`를 쓰는 별도 LED 바닥 효과(틴트 없음, 양 끝에서 중앙으로 직선 점등) | `MEMORY.md` 2026-08-20 01:58 |
| PvP 경기 종료 (`pvp/ROOM_AND_LOUNGE.md`) | 종료 뒤 룸 닫힘을 전제한 서술 | 룸이 좌석·회원·초대를 유지한 채 로비로 복귀 | `51166927`(2026-08-21) |
| 드롭 억제 방식 (`battle/TRAINER_LOOT_SUPPRESSION.md` 3절) | 합성 UUID의 `PlayerPartyStore` | ArmorStand 소유 앵커와 `Pokemon.getOwnerEntity()` 폴백 | `MEMORY.md` 2026-08-19 05:03 |

## 열린 결정

| 결정자 | 대상 | 막는 범위 |
|---|---|---|
| 빡대리님 | 보스 레이드 방향: 1~4인 공유 HP(`MECHANICS_AND_PARTY_SCALE.md`)인지 2인 더블(`DESIGN_DRAFT.md`)인지, 의존 모드·보상·이탈 정책 | 보스 레이드 수직 기능 |
| 빡대리님 | PvP 라운지 영속 저장 스키마(`pvp/ROOM_AND_LOUNGE.md` 8절) | 서버 재시작 뒤 복귀 보장 |
| 빡대리님 | 기존 BP·세션·진행도·설정의 마이그레이션 여부 | 변환기만 |
| 빡대리님 | 위 불일치 표의 각 행을 문서 개정으로 닫을지 코드로 되돌릴지 | 해당 계약 문서 |
| 빡대리님 | 저장소 보관과 새 GitHub·Modrinth 공개 시점 | 외부 공개 상태 변경 |

## 2026-09-27 정리에서 없앤 문서

원본은 비공개 `docs/archive/2026-09-27-mbc-docs/`에 그대로 복사해 두었다.

| 구 문서 | 처리 |
|---|---|
| `MORE_BATTLE_CONTENT_SYSTEM_CONTENT_CONTEXT.md` | 삭제. 불변식·함정은 `MEMORY.md`로 옮김 |
| `MORE_BATTLE_CONTENT_GUI_UX_CONTEXT.md` | 삭제. 미해결 GUI 항목과 함정은 `MEMORY.md`로 옮김 |
| `MORE_BATTLE_CONTENT_FIGMA_UX_HANDOFF.md` | 삭제. Figma Draft는 보조 자료이며 진행이 멈춘 상태. 파일 정보는 보관본 참고 |
| `MORE_BATTLE_CONTENT_CONTENT_PROPOSAL.md` | 삭제. `rejected` 상태의 Battle Contracts 대안 |
| `MORE_BATTLE_CONTENT_TOWER_PROGRESSION_DECISION.md` | 삭제. 11랭크 진행은 `tower/STREAK_PROGRESSION.md`가 폐기 |
| `MORE_BATTLE_CONTENT_FACTORY_CATALOG_DATA_WRITE_PLAN.md`, `..._SCHEMA2_DATA_WRITE_PLAN.md`, `..._SCHEMA3_RANDOMIZATION_DECISION.md` | 삭제. 스키마 4가 읽기 호환까지 종료. 경과는 `MEMORY.md` |
| `MORE_BATTLE_CONTENT_BP_REWARD_AND_SHOP_DATA_WRITE_PLAN.md` | 삭제. 상점은 `economy/BP_SHOP.md`, 타워 보상은 `tower/STREAK_PROGRESSION.md`가 대체 |
| `MORE_BATTLE_CONTENT_BP_SHOP_HORIZONTAL_SCROLL_DECISION.md` | 삭제. `ui/HOME_DASHBOARD.md`가 폐기 |
| `MORE_BATTLE_CONTENT_PVP_FIRST_ENTRY_DECISION.md` | 삭제. 탭 순서·기본 화면을 상점·홈 결정이 대체. 남은 조항(설명 허브 금지, ordinal 고정)은 `economy/BP_SHOP.md` 3절과 같음 |
| `REFACTOR_CODE_REVIEW_VALIDATION_2026-08-18.md` | 삭제. 08-18 작업 사본의 정적 검토 스냅샷. 항목 요약은 `MEMORY.md` |
