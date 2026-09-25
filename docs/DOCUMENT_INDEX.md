# Cobblemon 애드온 저장소 내부 문서 인덱스

| 항목 | 값 |
|---|---|
| Status | `shared` |
| Effective | 2026-08-19 |
| Last reviewed | 2026-09-25 — League Challenge GUI 착수 순서와 실험 API 승격 게이트 연결 |
| 주 독자 | 빡대리님과 이후 설계·구현 담당자 |
| 목적 | 활성 계약, 분석 근거, 폐기 기록과 재개 지점의 라우팅 |

## 1. 압축 후 읽는 순서

1. `PROJECT_STATUS.md` — 실제 Git·코드·검증 상태와 바로 다음 재개 지점
1-1. `MORE_BATTLE_CONTENT_DEV_DEPLOYMENT.md` — MBC·Better AI 개발 배포 절차, 프로세스 확인 규칙, 버전 핀·중복 JAR·Loom 캐시 함정과 배틀 라운지 재생성 주의
2. `BETTER_BATTLE_PRESENTATION_CONTEXT.md` — 독립 연출 모드의 제품 경계, 구현 지도, 배치·검증 상태와 세션 복구 절차
3. `BETTER_BATTLE_PRESENTATION_IDENTITY_DECISION.md` — Better Battle Presentation 표시 이름·Mod ID·확장 경계와 첫 구현 상태
4. `DYNAMAX_ATMOSPHERE_AUDIENCE_AND_TRANSITION_DECISION.md` — 다이맥스 하늘의 참가자·등록 관전자 한정 수신 범위와 자연스러운 페이드 계약
5. `REPOSITORY_ADDON_HOSTING_AND_DYNAMAX_ATMOSPHERE_DECISION.md` — 두 JAR 저장소 상한 폐기, 독립 Mega Showdown 애드온과 하늘 셰이더 구현 원계약
6. `MORE_BATTLE_CONTENT_SYSTEM_CONTENT_CONTEXT.md` — 코사원의 본체 시스템·콘텐츠 담당 범위, 불변식, 구현 지도, 통합 경계와 세션 재개 지점
7. `MORE_BATTLE_CONTENT_GUI_UX_CONTEXT.md` — GUI/UX 전담 범위, 코드 기반 분석, 사용자 결정, 캡처 목록과 세션 재개 지점
8. `BETTER_AI_WORK_CONTEXT.md` — Better AI 전담 범위, 판단·계산 경계, 현재 구현, 검증 한계와 세션 재개 지점
8-1. [BETTER_AI_HYBRID_REVIEW_AND_VALIDATION_PLAN.md](BETTER_AI_HYBRID_REVIEW_AND_VALIDATION_PLAN.md) — **비규범 초안.** 독자 AI와 외부 원리 하이브리딩 분석, poke-engine 비교, 잠정 설계 평가, 독립 검증 제안과 미결 사항. 알고리즘 채택·구현 승인을 뜻하지 않음
8-2. `BETTER_AI_HUMANLIKE_REASONING_PLAN.md` — 2026-08-31까지의 판단 구조 개편 계획·실험 기록. §0의 날짜별 정정과 하네스 한계를 먼저 확인하며, 옛 미완료 목록을 현재 지시로 사용하지 않음
9. `MORE_BATTLE_CONTENT_FIGMA_UX_HANDOFF.md` — Figma Draft 링크, 실제 완료·미완료 상태, 최신 Play-only 계약과 정확한 재개 절차
10. `MORE_BATTLE_CONTENT_TOWER_MECHANIC_SESSION_DECISION.md` — 첫 전투 전 유저가 선택하고 세션 전체에 고정하는 주요 기믹과 기믹별 상대 풀
11. `MORE_BATTLE_CONTENT_TOWER_RUNTIME_INTEGRATION_DECISION.md` — Mega Showdown 필수 버전과 명시적 포기 패배·강제 종료 무효 경계
12. `MORE_BATTLE_CONTENT_TOWER_PLAY_SCREEN_DECISION.md` — 첫 Play 전용 Screen 범위와 서버 승인 상태 계약
13. `MORE_BATTLE_CONTENT_TOWER_PARTY_CARD_DECISION.md` — 클릭 순서 출전, 카드 즉시 정보와 Cobblemon GUI 초상 후속 계약
14. `MORE_BATTLE_CONTENT_TOWER_CUSTOM_GUI_DECISION.md` — 바닐라 버튼 표면과 분리된 Play-only 전용 셸·패널·카드·버튼 계약
15. `MORE_BATTLE_CONTENT_TOWER_OPPONENT_DATA_DECISION.md` — MBC 전용 NPC 팀 원칙과 본가 배틀 시설 참고 경계
16. `MORE_BATTLE_CONTENT_TOWER_SCHEMA3_DATA_WRITE_PLAN.md` — 적용된 스키마 3 형식과 18프로필·72세트 전체 값
17. `MORE_BATTLE_CONTENT_BP_REWARD_AND_SHOP_DATA_WRITE_PLAN.md` — 승인 전 소드·실드형 BP 보상 수치와 후속 결정 전의 44품목 상점 원안
18. `MORE_BATTLE_CONTENT_FACTORY_COMPLETE_RENTAL_SCHEMA4_DECISION.md` — 스키마 3 랜덤 조합을 폐기하고 501종·폼·2,004개 완성 렌탈 프리셋과 독립 트레이너 선발을 적용한 현행 계약
18-1. `MORE_BATTLE_CONTENT_FACTORY_CATALOG_SCHEMA3_RANDOMIZATION_DECISION.md` — 2026-08-22 스키마 4가 폐기한 과거 슬롯 랜덤화 결정 기록
18-2. `MORE_BATTLE_CONTENT_FACTORY_CATALOG_SCHEMA2_DATA_WRITE_PLAN.md` — 스키마 3 이전의 경쟁형 카탈로그 스키마 2 이력
18-3. `MORE_BATTLE_CONTENT_FACTORY_CATALOG_DATA_WRITE_PLAN.md` — 팩토리 첫 카탈로그 원안 이력. 이후 스키마 2·3·4가 순차 대체
19. `MORE_BATTLE_CONTENT_TOWER_PROGRESSION_DECISION.md` — 소드·실드형 랭크 게이지와 승급·MAX 보스전 처리
19-1. `MORE_BATTLE_CONTENT_TOWER_STREAK_PROGRESSION_DECISION.md` — 19번의 플레이어 랭크를 폐기하고 5승 단위 연승·1~4 BP·전설급 허용·싱글 성능 가중치를 확정한 현행 후속 계약
19-2. `MORE_BATTLE_CONTENT_TOWER_LEGENDARY_PERMISSION_AMENDMENT.md` — 전설급 허용을 강제 선출이 아닌 후보 제한 해제로 수정하고 시작 전 선택·도전 중 고정을 명시한 후속 계약
20. `MORE_BATTLE_CONTENT_RECORDS_DECISION.md` — 기록·리더보드용 누적 집계와 비저장 범위
21. `MORE_BATTLE_CONTENT_TERMINAL_AND_BP_DECISION.md` — 홀로 터미널, 명령어 동등 경로와 BP 존치 후속 결정
22. `MORE_BATTLE_CONTENT_BP_STORAGE_DECISION.md` — BP 초기값, 멱등 원장과 NBT 스키마 1
23. `MORE_BATTLE_CONTENT_CONTENT_SCOPE.md` — 네 콘텐츠의 확정 플레이 규칙
24. `MORE_BATTLE_CONTENT_DESIGN.md` — More Battle Content 제품군 모듈 경계, 의존 방향과 구현 순서
25. `BETTER_AI_PUBLIC_MOVE_OUTCOME_DECISION.md` — 원인·숨은 세트로 과장하지 않는 miss·실패·차단·급소·상성·면역·명중 횟수 공개 사건 계약
26. `BETTER_AI_PUBLIC_EFFECT_OUTCOME_DECISION.md` — Substitute 대체 피해와 Protect 공개 시작만 좁게 보존하는 후속 공개 효과 계약
27. `BETTER_AI_PUBLIC_ACTION_EVIDENCE_DECISION.md` — 실제 스피드·피해 원인으로 과장하지 않는 공개 행동 순서와 HP 변화 증거 계약
28. `BETTER_AI_DECISION_QUALITY_MODE_DECISION.md` — Router 호출 횟수를 건드리지 않는 품질·균형·절약 추론 예산과 설정 스키마 2
29. `BETTER_AI_DECLARATIVE_MOVE_EFFECTS_DECISION.md` — Cobblemon 내장 Showdown 기술의 선언형 효과와 불완전성·데이터팩 덮어쓰기 경계
30. `BETTER_AI_STANDARD_DAMAGE_PROJECTION_DECISION.md` — 숨은 세트를 읽지 않는 공개 능력치 범위와 표준 피해·난수 KO 자료의 최신 후속 결정
31. `BETTER_AI_BRAIN_OWNERSHIP_DECISION.md` — Router·로컬을 독립 Brain으로 두고 계산과 판단을 분리하는 후속 결정
32. `BETTER_AI_HUMANLIKE_DECISION.md` — 계획·예측·상황별 습관·혼합 전략 후속 결정. 호출 소유권과 횟수는 31번 문서가 대체함
32-1. `BETTER_AI_NATIVE_SHOWDOWN_SIMULATION_DECISION.md` — 수제 상태 전이를 종료하고 공개 정보로 구성한 별도 Showdown 샌드박스·난도별 완결 세트 가설·전체 반례 합격표로 교체하는 후속 계약
33. `BETTER_AI_DESIGN.md` — Better AI 품질·보안·폴백 원계약
34. `COBBLEMON_BATTLE_TOWER_REFERENCE_MATRIX.md` — 레퍼런스 채택·변형·폐기 요약
35. `COBBLEMON_BATTLE_TOWER_REFERENCE_ANALYSIS.md` — 위 요약의 상세 증거와 한계
36. `REFACTOR_CODE_REVIEW_VALIDATION_2026-08-18.md` — 16:11 작업 사본에서 외부 코드 리뷰 주장을 코드·결정 문서와 대조한 비규범 검증 및 조치 우선순위
37. `DYNAMAX_STORM_SKY_VISUAL_REVISION_DECISION.md` — 거의 밤인 흑적색 하늘, 정면에서도 보이는 찢어진 진홍 에너지 장막과 바닐라 구름 암전 후속 계약
38. `MORE_BATTLE_CONTENT_BATTLE_REWARD_AND_HELD_ITEM_PRESENTATION_DECISION.md` — MBC 관리 전투만 일반 경험치를 억제하고 양쪽 전투 복제본의 지닌 도구 외형을 숨기는 계약
39. `MORE_BATTLE_CONTENT_PVP_ROOM_AND_LOUNGE_DECISION.md` — 공개·비공개 1대1 룸, 다중 기믹, 팀 프리뷰, 진행 중 공개방 관전과 새 배틀 라운지 후속 계약
40. `BETTER_AI_15_SECOND_ROUTER_DEADLINE_DECISION.md` — 당시 10초 상한을 15초로 바꾼 이력. 현행 상한은 48번 문서가 대체함
41. `MORE_BATTLE_CONTENT_TABBED_CONTENT_AND_PVP_ROOM_LAYOUT_DECISION.md` — 루트 뒤로가기를 폐기한 고정 콘텐츠 탭, PvP 대기실 재배치, 정면 유휴 모델, 닉네임 포함 관전자와 메가 단독 기본값 후속 계약
42. `MORE_BATTLE_CONTENT_FIXED_CHROME_AND_PVP_ROOM_OVERLAY_DECISION.md` — 모드명·실제 BP·X가 있는 고정 상단바, 고정 탭 좌표, 타워 패널 내 행동과 PvP 룸 전용 화면의 X/ESC→룸 목록 계약
43. `MORE_BATTLE_CONTENT_PVP_FIRST_ENTRY_DECISION.md` — `/mbc open`의 설명 허브를 폐기하고 PvP 룸 목록을 기본 선택하는 PvP→타워→팩토리→보스 탭 순서
43. `MORE_BATTLE_CONTENT_MANAGED_TRAINER_LOOT_SUPPRESSION_CORRECTION.md` — 일반 `PartyStore`로는 막히지 않던 배틀타워·배틀팩토리 상대의 종족·지닌 도구 드롭을 가상 트레이너 소유 상태로 억제하는 정정 계약
44. `MORE_BATTLE_CONTENT_MANAGED_MECHANIC_BUTTON_VISIBILITY_DECISION.md` — MBC 관리 전투에서 타워 선택 1개·팩토리 0개·PvP 체크 항목만 Cobblemon 기믹 버튼으로 표시하는 계약
45. `MORE_BATTLE_CONTENT_CLICKABLE_PVP_ROOM_INVITE_DECISION.md` — 채팅 `[입장] [거절]`이 명령어 문자열 없이 룸 참가·초대 거절 패킷을 직접 보내는 계약
46. `BETTER_AI_DIFFICULTY_ACTIVATION_DECISION.md` — 타워 랭크·챔피언과 팩토리 헤드에 연결한 입문·일반·상급·보스 난도 계약
47. `BETTER_AI_ROUTER_DECISION_SUMMARY_DECISION.md` — 기본 비활성 JSON 토글로만 요청·기록하는 공개 판단 근거 한 문장 계약
48. `BETTER_AI_20_SECOND_ROUTER_DEADLINE_DECISION.md` — 15초 상한을 종료하고 본체·Router를 함께 20초로 늘리는 후속 계약
49. `BETTER_AI_ROUTER_DECISION_JOURNAL_DECISION.md` — 활성 Router 공개 판단 근거를 서버 전용 JSONL에 누적하는 후속 계약
49. `MORE_BATTLE_CONTENT_BP_SHOP_CATALOG_DECISION.md` — 상점을 첫 탭·기본 화면으로 두고 JSON 기반 임시 44품목, 실제 아이템 툴팁과 서버 권위 구매를 확정한 후속 계약
50. `MORE_BATTLE_CONTENT_BP_SHOP_HORIZONTAL_SCROLL_DECISION.md` — 홈 대시보드가 폐기한 전체 화면 가로 상점의 과거 구현 기록
51. `MORE_BATTLE_CONTENT_HOME_DASHBOARD_DECISION.md` — 홈의 좌측 캐릭터·타워 리더보드, 우측 세로 상점과 순위·부분 배포 계약
52. `MORE_BATTLE_CONTENT_HOME_COMPACT_BALANCE_REVISION.md` — 홈 좌측 열을 36%로 축소하고 BP를 범용 `상점` 헤더로 옮긴 시각 정정 계약
53. `MORE_BATTLE_CONTENT_HOME_THREE_COLUMN_REVISION.md` — 캐릭터 2/3폭·리더보드 기존폭·상점 나머지의 동일 높이 3열 후속 계약
54. `MORE_BATTLE_CONTENT_HOME_MODEL_AND_LEADERBOARD_CORRECTION.md` — 전신 모델 중심·점유율과 타워·팩토리·PvP 8보드 후속 정정 계약
55. `BETTER_COBBLEMON_MUSIC_ARCHITECTURE_AND_MIGRATION_DECISION.md` — Better Cobblemon Music의 새 정체성, 레거시 0.5.3 단계 이식과 데이터 기반 음악 아키텍처 계약
56. `BETTER_COBBLEMON_MUSIC_RCT_ROLE_MAPPING_CORRECTION.md` — 잘못 승계한 RAD Gyms 판별을 종료하고 선택형 RCT 역할·관전자 진영·야생 특수곡 우선순위를 정정한 후속 계약
57. `MORE_BATTLE_CONTENT_PLAIN_NPC_NAMES_DECISION.md` — 배틀타워 18프로필과 배틀팩토리 28컨셉의 호칭형 이름을 한·영 일반 사람 이름으로 정정한 후속 계약
58. `BETTER_AI_ROUTER_BOSS_ACTIVATION_DECISION.md` — 실제 승급 보스·MAX 챔피언·팩토리 헤드만 기본 Router로 선택하고 스키마 3에서 콘텐츠별 모드를 제공하는 후속 계약
59. `MORE_BATTLE_CONTENT_SHADOW_NPC_NAME_DECISION.md` — 플레이어 Shadow 외형은 유지하고 이름표만 동일 전투의 일반 NPC 이름으로 바꾸는 후속 계약
60. `BETTER_COBBLEMON_MUSIC_MBC_INTEGRATION_DECISION.md` — MBC 타워·팩토리·PvP의 명시적 콘텐츠 ID, 늦은 관전자 동기화, 콘텐츠별 JSON 선곡과 단일 공유 생성 리소스팩 계약
61. `MORE_BATTLE_CONTENT_ROOT_COMMAND_AND_BP_VICTORY_REWARD_DECISION.md` — `/mbc` 직접 GUI 진입, 구형 콘텐츠 하위 명령 폐기, 권한 레벨 2 BP 관리와 타워·팩토리 승리당 2 BP 멱등 정산 후속 계약
62. `MORE_BATTLE_CONTENT_PVP_PRE_BATTLE_PLACEMENT_CORRECTION.md` — 룸 참가자를 actor 생성 전에 배치하고 타워·팩토리·PvP의 플레이어 actor·임시 파티 수명주기를 공통화하는 정정 계약
63. `MORE_BATTLE_CONTENT_CATALOG_PROPERTY_ID_CORRECTION.md` — 팩토리 54세트의 특성 경로와 타워·팩토리 공통 `PokemonProperties` 특성·기술 이름 변환을 정정한 후속 계약
64. `MORE_BATTLE_CONTENT_SHARED_ARENA_HOLOGRAM_DECISION.md` — 타워·팩토리·PvP에 같은 몬스터볼 LED 지형 홀로그램을 표시하고 Shadow 모델과 연출 수명주기를 분리한 후속 계약
65. `MORE_BATTLE_CONTENT_BOSS_RAID_MECHANICS_AND_PARTY_SCALE_DECISION.md` — 레퍼런스 Boss Mode의 병렬 1대1 구조, 불사 보스와 커스텀 `-bossdamage` 공유 HP 실체, 1~4인 확장과 인원 비례 HP 배율 제안
66. `../better-cobblemon-music/RELIABILITY_UPDATE_2026-08-22.md` — 설정·생성팩·Minecraft 리소스 재로드의 전체 성공/원복, Ogg/Vorbis·경로 검증, 폴링 하한과 선택형 제공자 격리를 확정한 후속 계약
67. `../better-cobblemon-music/CONFIG_SCHEMA_V2_2026-08-23.md` — 정확한 바이옴 객체와 순서형 태그·경로 규칙, 같은 바이옴 복수곡 재생, 스키마 1 무수정 호환을 확정한 설정 스키마 2 계약
68. `ROUNDING_BLOCK_ARCHITECTURE_DECISION.md` — Rounding-Block의 Fabric Renderer API 기반 외곽 엣지 라운딩, Sodium·Iris 그림자 호환 경계, 안전 폴백과 단계별 검증 계약 초안
69. `MORE_BATTLE_CONTENT_DECLARATIVE_UI_FRAMEWORK_DECISION.md` — MBC 공용 선언형 UI 계약, 웹·Minecraft 이중 렌더러, 내장 테마와 선택형 외부 리소스팩, GUI 우선 점진 이행 후속 결정
70. `MORE_BATTLE_CONTENT_UI_RESOURCE_BOUNDARY_AMENDMENT.md` — 69번의 교체 가능한 화면 문서와 웹 우선 단계를 수정한 현행 계약. 불변 타입 화면·행동, 시각 전용 Visual Pack과 Minecraft 우선 수직 스파이크를 확정
71. `MORE_BATTLE_CONTENT_LEAGUE_UI_BOOTSTRAP_AMENDMENT.md` — League 모듈이 없던 착수 순서를 수정하고 최소 모듈·개발 진입점·실험 MbcUI·두 Minecraft 스파이크·안정 API 승격 게이트를 확정

`MORE_BATTLE_CONTENT_CONTENT_PROPOSAL.md`는 `rejected` 상태의 과거 대안이다. 현재 제품 범위나 구현 우선순위를 결정할 때 사용하지 않는다.

## 2. 현재 확정 결정

| 영역 | 결정 |
|---|---|
| 본체 | `Cobblemon: More Battle Content` / `cobblemon_more_battle_content` |
| AI 애드온 | `Cobblemon: More Battle Content - Better AI` / `cobblemon_more_battle_content_better_ai` |
| 저장소 범위 | 서로 독립적인 Cobblemon 애드온을 함께 둘 수 있으며 전체 JAR 수를 제한하지 않음 |
| MBC 산출물 | 본체 JAR 하나 + 선택형 서버 전용 Better AI JAR 하나 |
| 독립 연출 모드 | `Cobblemon: Better Battle Presentation` / `cobblemon_better_battle_presentation`; Mega Showdown만 기능 의존, MBC와 Better AI에는 비의존; 첫 기능은 참가자·등록 관전자 한정 다이맥스 하늘과 약 0.8초 페이드 |
| MBC 전투 보상·도구 외형 | 관리 전투만 일반 처치 경험치를 억제하고 플레이어·상대 전투 복제본의 지닌 도구 외형을 숨김; 배틀타워·배틀팩토리 상대는 가상 트레이너 소유로 만들어 종족·지닌 도구 사망 드롭도 억제; BP·도구 효과·일반 전투는 유지 |
| MBC 전투 기믹 버튼 | 타워는 세션 선택 1개, 팩토리는 0개, PvP는 방에서 체크한 종류만 표시; MBC 밖 전투는 기존 Cobblemon·Mega Showdown 표시 유지 |
| MBC PvE NPC 이름 | 배틀타워 18프로필과 배틀팩토리 28컨셉은 종·기믹·전술 호칭 대신 언어별로 중복 없는 일반 사람 이름을 표시; Shadow는 플레이어 스킨을 유지하되 같은 상대 액터 이름을 이름표로 사용 |
| 구현 순서 | 배틀타워 → 배틀팩토리 → PvP → 보스 레이드 |
| 배틀타워 팀 | 서로 다른 종족 6마리 등록, 싱글 3마리·더블 4마리 선출 |
| 배틀타워 진행 | 싱글·더블별 현재/최고 연승. 1~5 입문, 6~10 실전, 11~20 고급, 21+ 프로. 다음 승리가 5의 배수면 보스전 |
| 배틀타워 전설급 | 기본 비허용. 시작 전에 비허용/허용을 선택하고 첫 전투 뒤 고정. 허용은 양쪽 후보 제한만 풀며 전설급 선출을 강제하지 않음 |
| 배틀타워 도구 | 가방 사용 금지, 지닌 도구 허용, 같은 지닌 도구 중복 금지 |
| 배틀타워 기믹 | 도전 전 `MEGA/DYNAMAX/TERA` 중 하나를 선택해 세션 전체와 양측에 고정, 각 진영 전투당 최대 1회 |
| 기믹 제공자 | Mega Showdown `1.9.3+1.7.3+1.21.1` 필수 의존 |
| 활성 명시적 포기 | Cobblemon 포기 경로를 사용해 패배 1회 기록 후 세션 폐기. 승패 미확정 강제 종료는 무효 |
| 기믹 책임 | 본체가 합법 후보를 생성하고 Better AI는 전략적 선택만 담당 |
| AI 부재 | 본체가 Cobblemon 기본 AI 기준선 어댑터 사용 |
| AI capability | 초기에는 `SINGLE`, `DOUBLE`만 제공 |
| AI 난도 | 타워 입문은 입문 AI, 실전은 일반 AI, 고급·프로는 상급 AI, 5승 단위 보스전은 보스 AI. 로컬·Router 최종 선택 소유권은 별도 Better AI 계약을 유지 |
| Router 기본 선택 | 설정 스키마 3은 타워 승급·MAX 보스와 팩토리 싱글 21·49 헤드만 Router, 나머지는 로컬. 콘텐츠별 `LOCAL_ONLY/BOSS_ONLY/ALL/DIFFICULTY_TIERS` 제공 |
| Router 근거 로그 | `logDecisionSummary` 기본 `false`; 활성화 때만 공개 사실 한 문장을 서버 콘솔·`latest.log`에 기록 |
| Router 근거 누적 | 활성화 때 `logs/mbc-better-ai-router-decisions.jsonl`에 공개 근거를 즉시 append하며 저장 실패는 행동·폴백에 영향 없음 |
| Router 판단 마감 | 공용 절대 상한과 설정 허용 상한 20초, 새 설정 기본값은 10초 유지 |
| 보스 레이드 인원 | 1·2·3·4인 지원. 인원은 매칭 키에 포함하며 인원별 파티 수는 최소·최대가 같은 정확한 값 |
| 보스 레이드 HP | 공유 HP는 서버 권위 단일 값이며 배율 규칙은 데이터 선언. 4인 파티 수와 인원 비례 보정 수치는 승인 대기 |
| 보스 AI 확장 | 보스 레이드 구현 단계까지 공유 HP·단계 컨텍스트 설계 유예 |
| 터미널 | 본체가 실제 블럭을 제공하되 전용 모델·텍스처 없이 코드 생성 홀로그램으로 표시 |
| 기능 진입 | 터미널과 일반 사용자 `/mbc`가 공통 GUI를 열며 `/mbc open`, `status/start/resume/abandon/spectate/pvp/factory` 구형 공개 하위 명령은 폐기 |
| BP | 본체 공통 통화·거래 원장·멱등 정산은 유지. 타워는 입문 1·실전 2·고급 3·프로 4 BP/승, 팩토리는 기존 고정 보상 유지 |
| 홈·BP 상점 | 첫 탭·기본 화면은 홈. 좌측 캐릭터·배틀타워 싱글/더블 리더보드, 우측 세로 스크롤 JSON 기반 44품목 상점과 실제 아이템 툴팁·서버 권위 원자 구매 |
| BP 명령 | `/mbc bp add|remove|set`, 별도 `admin` 하위 명령 없음, 권한 레벨 2 이상 |
| BP 저장 | 초기 0 BP, 플레이어·거래 UUID 멱등 키, 전체 거래 이력 보존, `cobblemon_more_battle_content_bp` 스키마 1 |
| 기록·리더보드 | 플레이어·콘텐츠·형식별 승패·현재/최고 연승·진행·최고 지표만 저장. 경기 원장·세션 복구·초기 PvP 시즌/레이팅은 제외 |
| 첫 타워 Screen | Play만 노출. 파티·형식·선출·진행·BP·시작/재개/변경 의도를 포함하고 나머지 패널은 유예 |
| 타워 파티 카드 | 클릭 순서가 실제 출전 순서이며 `(1)~(3/4)` 표시. GUI 초상·이름·배틀 레벨·지닌 도구를 카드에 즉시 노출 |
| 타워 커스텀 GUI | 바닐라 버튼 텍스처 없이 코드 드로잉 셸·상단 BP·좌측 파티 rail·중앙 진행/설정·우측 상태·하단 행동을 제공. 320×240은 파티 rail과 세로 접힌 본문을 유지 |
| MBC UI 프레임워크 | `MbcUI`의 화면 구조·행동은 타입이 있는 불변 계약으로 두고 owo는 내부 백엔드 후보로만 시험. 기본 테마는 각 JAR에 내장하며 외부 Visual Pack은 허용된 시각 토큰·텍스처만 덮어씀. 실제 Minecraft League 홈을 첫 스파이크로 구현 |
| 타워 NPC 팀 | MBC 전용 신규 데이터로 작성. 소드·실드 실제 팀과 외부 모드 데이터는 복제하지 않고 여러 본가 배틀 시설의 공개 패턴만 참고 |
| 상대 스키마 3 | TERA는 표준 `tera_type`, DYNAMAX는 `dmax_level: 10`과 Boolean `gmax_factor`, MEGA는 정확한 메가스톤을 사용. 승인된 18프로필·72세트를 내장 데이터에 적용 |
| 팩토리 스키마 4 | 트레이너와 렌탈 풀을 분리하고 501종·폼에 네 개씩 2,004개 완성 프리셋을 제공한다. 기술 4개·특성·도구·성격·EV는 세트에 고정하며 무작위성은 세트 선발에만 적용 |
| PvP 룸 | 공개·초대 전용 비공개 1대1 룸, 좌·우 진영, 수동·자동 방장 양도와 진행 중 공개방 관전 |
| PvP 기믹 | 새 룸은 `MEGA`만 기본 활성화하며, 방장이 `MEGA/DYNAMAX/TERA/Z_MOVE`를 복수 선택하고 각 진영은 선택된 각 종류를 전투당 1회 사용 |
| PvP 라운지 | 새 전용 차원의 2048블록 간격 원기둥 경기장 풀, 참가자·관전자 공통 몬스터볼 LED 지형 홀로그램, 순수 관전과 종료 복귀. 최고치·복귀 영속 스키마는 승인 대기 |
| PvP 전투 시작 순서 | 룸 참가자·복귀 좌표를 actor 생성 전에 배치하고, 성공한 battle ID 뒤 관전자·ACTIVE phase를 연결. JOIN·DISCONNECT 콜백 안 차원 복귀 금지 |
| 외부 레퍼런스 | 동작 계약과 UX만 참고하며 ARR 코드·데이터·자산은 복사하지 않음 |

## 3. 아직 열린 결정

1. **결정자:** 빡대리님
   **대상:** 기존 BP·세션·타워 진행도·설정의 마이그레이션 여부
   **시점:** 변환기 구현 전. 새 BP 경제 구현은 이 결정에 막히지 않음

2. **결정자:** 빡대리님
   **대상:** `MORE_BATTLE_CONTENT_BP_REWARD_AND_SHOP_DATA_WRITE_PLAN.md`의 승리 2 BP와 원작 승급 보너스
   **시점:** 배틀타워 보상 JSON과 실제 정산을 구현하기 전. 임시 44품목 상점은 2026-08-19 승인·구현됨

3. **결정자:** 빡대리님
   **대상:** 보스 레이드 의존 모드, 보상·진행, 연결 종료·포기 정책
   **시점:** 네 번째 콘텐츠 구현 전

4. **결정자:** 빡대리님
   **대상:** 기존 저장소 보관과 새 GitHub·Modrinth 프로젝트 공개 시점
   **시점:** 외부 공개 상태 변경 전

## 4. 재개 지점

- 현재 브랜치는 `refactor`다.
- 새 본체와 Better AI 모듈의 로딩 골격, 새 Mod ID, AI 공개 계약·공정 상태 DTO, 공유 Brain registry, Cobblemon 1.7.3 기준선 AI factory·합법 행동 후보 변환·공개 관측기·판단 폴백 actor, 기록 집계 SavedData, 공통 콘텐츠 애플리케이션 서비스, `/mbc` 진입 명령, BP 잔액·멱등 원장·보상 정산·운영 명령과 배틀타워 팀·랭크 진행·기록 투영·메모리 실행 조정이 커밋되지 않은 작업 사본에 구현돼 있다.
- 배틀팩토리는 완성 프리셋만 읽는 strict 스키마 4 카탈로그, 501종·폼·2,004세트, 직전 드래프트 종족 중복 억제, 원작형 라운드 풀·교환 상승, 첫 6마리 렌탈 선출과 7승 라운드 재선출을 실제 Cobblemon PvE 런타임까지 연결했다. 트레이너 신원은 렌탈 팀과 분리하며 NPC도 플레이어와 같은 라운드 풀에서 종족·도구 중복 없는 완성 세트를 뽑는다. 전용 Screen은 형식·레벨 선택, 싱글 3마리·더블 4마리 카드 선출, 구성 툴팁, 전투 시작, 승리 후 유지·교환과 완료 흐름을 서버 승인 패킷으로 제공한다.
- PvP는 공개·비공개 룸, 좌·우 진영, 관전자와 방장 양도, 복수 기믹별 진영당 1회, 명시적 준비·준비 취소, 진행 중 공개방 관전, 코드 생성 라운지 경기장 풀과 원위치 복귀를 메모리 런타임에 연결했다. 룸·팀 프리뷰는 MBC 전용 GUI를 사용한다. 라운지 최고치와 오프라인 복귀 지점 영속 저장은 제안 스키마 승인 전이라 연결하지 않았고 실제 차원 로드·게임 플레이도 미검증이다.
- 빡대리님 지시에 따라 보스 레이드는 현재 PvE 완료 기준에서 제외하고, 배틀타워와 배틀팩토리를 완성한 뒤 PvP 추가 작업을 재개한다. 레벨 50·오픈 레벨 100 제공은 확정됐다. 제품 카탈로그는 Cobblemon 1.7.3 정적 대조와 10,000회 선택 시뮬레이션을 통과했다. 격리된 `dev-server`에서 본체·Better AI와 두 내장 카탈로그의 실제 Fabric 전용 서버 로딩을 검증했다.
- 등록한 6마리의 배틀 복제본은 메모리 세션에 유지되므로 팀 확정 뒤 실제 모험 파티를 바꾸더라도 해당 세션의 다음 층과 팀 재선출은 최초 등록 파티를 사용한다. 연결 종료·명시적 세션 종료 시에는 폐기하며 NBT 복구는 하지 않는다.
- 프로덕션 PvE 런처는 선수·상대 팀을 모두 생성한 뒤에만 Cobblemon 배틀을 시작한다. Mixin 규칙 경계가 가방과 선택 외 기믹, 한 진영의 기믹 재사용을 거부하며 명시적 승패만 기록한다. 무승부·강제 종료는 기록을 바꾸지 않고 준비 상태로 복귀한다.
- Better AI는 로컬 Brain과 OpenRouter Brain을 독립된 최종 판단 주체로 등록한다. 유효한 API 설정이 있으면 합법 후보가 둘 이상인 모든 요청을 Router가 판단하고, 없으면 로컬 Brain이 판단한다. 외부 실패만 로컬→본체 기준선→비상 행동으로 폴백한다. 공통 계산기는 공개 기술 사실과 숨은 세트를 읽지 않는 능력치 범위로 Gen 9 기본 비급소 피해·16난수 KO 범위를 제공하되, 동적 보정은 명시적 `UNKNOWN`으로 남긴다. 효용·순위·추천은 만들지 않는다. 로컬 Brain 10,000회 생성 판단과 Router 매 요청·실패 전파 계약을 단위 검증했다.
- 승인된 스키마 3 내장 상대 카탈로그는 기믹·형식별 18프로필과 72개 고유 세트를 제공한다. 프로덕션 런처는 선택한 기믹과 정확히 일치하는 프로필만 조회한다.
- 배틀타워 18프로필과 배틀팩토리 28컨셉의 한·영 표시 이름은 일반 사람 이름으로 교체됐다. 종료 상태의 `cobblemon-dev`에 클라이언트 JAR을 배치했으며 서버 교체 없이 실제 전투창 확인만 남았다.
- Fabric 전용 서버에서 본체·Better AI 동시 로딩은 검증했다. 본체 단독 폴백 로딩, 클라이언트 접속과 게임 내 배틀은 아직 검증하지 않았다.
- 진입은 실제 블럭·최소 UUID 블럭 엔티티와 코드 생성 홀로그램으로 구현한 터미널과 명령어를 함께 제공한다. 첫 실제 Screen은 Play 전용이며 터미널과 명령어가 같은 서버 Screen 서비스를 사용한다. 실제 월드 렌더링·상호작용은 아직 게임 안에서 검증하지 않았다.
