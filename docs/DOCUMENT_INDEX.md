# Cobblemon 애드온 저장소 내부 문서 인덱스

| 항목 | 값 |
|---|---|
| Status | `shared` |
| Effective | 2026-08-19 |
| Last reviewed | 2026-09-28 — Cobblemon UI DS 창 스타일 결정(87번) 추가 |
| 주 독자 | 빡대리님과 이후 설계·구현 담당자 |
| 목적 | 활성 계약, 분석 근거, 폐기 기록과 재개 지점의 라우팅 |

## 1. 압축 후 읽는 순서

1. `PROJECT_STATUS.md` — 실제 Git·코드·검증 상태와 바로 다음 재개 지점
1-1. `MORE_BATTLE_CONTENT_DEV_DEPLOYMENT.md` — MBC·Better AI 개발 배포 절차, 프로세스 확인 규칙, 버전 핀·중복 JAR·Loom 캐시 함정과 배틀 라운지 재생성 주의
2. `BETTER_BATTLE_PRESENTATION_CONTEXT.md` — 독립 연출 모드의 제품 경계, 구현 지도, 배치·검증 상태와 세션 복구 절차
3. `BETTER_BATTLE_PRESENTATION_IDENTITY_DECISION.md` — Better Battle Presentation 표시 이름·Mod ID·확장 경계와 첫 구현 상태
4. `DYNAMAX_ATMOSPHERE_AUDIENCE_AND_TRANSITION_DECISION.md` — 다이맥스 하늘의 참가자·등록 관전자 한정 수신 범위와 자연스러운 페이드 계약
5. `REPOSITORY_ADDON_HOSTING_AND_DYNAMAX_ATMOSPHERE_DECISION.md` — 두 JAR 저장소 상한 폐기, 독립 Mega Showdown 애드온과 하늘 셰이더 구현 원계약
7. [`../more-battle-content/docs/README.md`](../more-battle-content/docs/README.md) — More Battle Content 문서 인덱스. 현행 계약은 모듈 `docs/`의 분류 폴더에, 작업 기록·이슈는 모듈 `MEMORY.md`에 있다. 루트 `docs/`에는 개발 배포 절차(1-1번) 외의 MBC 문서를 두지 않는다. 2026-09-27 정리 전 원본은 비공개 `archive/2026-09-27-mbc-docs/`에 있다
8. [`../more-battle-content-better-ai/docs/README.md`](../more-battle-content-better-ai/docs/README.md) — Better AI 문서 인덱스. 현행 계약은 모듈 `docs/`의 분류 폴더에, 작업 기록·이슈는 모듈 `MEMORY.md`에 있다. 루트 `docs/`에는 Better AI 문서를 두지 않는다
37. `DYNAMAX_STORM_SKY_VISUAL_REVISION_DECISION.md` — 거의 밤인 흑적색 하늘, 정면에서도 보이는 찢어진 진홍 에너지 장막과 바닐라 구름 암전 후속 계약
55. `BETTER_COBBLEMON_MUSIC_ARCHITECTURE_AND_MIGRATION_DECISION.md` — Better Cobblemon Music의 새 정체성, 레거시 0.5.3 단계 이식과 데이터 기반 음악 아키텍처 계약
56. `BETTER_COBBLEMON_MUSIC_RCT_ROLE_MAPPING_CORRECTION.md` — 2026-09-22 폐기된 RCT 역할별 선곡의 과거 결정 기록
60. `BETTER_COBBLEMON_MUSIC_MBC_INTEGRATION_DECISION.md` — 삭제된 선택형 애드온과 구형 생성팩의 과거 결정 기록. 콘텐츠 ID만 현행 본체 연동에 남음
66. `../better-cobblemon-music/RELIABILITY_UPDATE_2026-08-22.md` — 카탈로그 구조 이전 재로드·생성팩 신뢰성의 과거 결정 기록
67. `../better-cobblemon-music/CONFIG_SCHEMA_V2_2026-08-23.md` — 카탈로그 구조 이전 `music.json` 스키마 2의 과거 결정과 마이그레이션 기준
68. `ROUNDING_BLOCK_ARCHITECTURE_DECISION.md` — Rounding-Block의 Fabric Renderer API 기반 외곽 엣지 라운딩, Sodium·Iris 그림자 호환 경계, 안전 폴백과 단계별 검증 계약 초안
72. `COBBLEMON_UI_DESIGN_SYSTEM_BOUNDARY_DECISION.md` — MbcUI는 MBC에 남기고, 독립 모드와는 의미 토큰·시각 문법·fixture·캡처 기준만 공유하며 별도 런타임 추출을 유예한 후속 결정
73. `COBBLEMON_UI_TOOLKIT_AND_RUNTIME_EVIDENCE_AMENDMENT.md` — 72번의 런타임 추출 유예를 갱신해 Cobblemon 전용 공용 위젯·테마 소스 모듈을 정하고, 내장 자산/Visual Pack 경계와 타이틀 fixture·실제 월드 증거 등급을 분리
74. `COBBLEMON_BATTLE_UI_PHASED_COMMAND_AND_LOG_DECISION.md` — Battle UI를 명령 선택, 연출 내레이션과 별도 전체 기록으로 나누고 구조화된 아군·상대·시스템 사건과 기존 상시 로그 종료 조건을 정의
75. `COBBLEMON_UI_SURFACE_STYLE_AMENDMENT.md` — 공용 위젯의 사각형·선택 모서리 챔퍼, 채움 없음·단색·세로 그라데이션, 테두리 없음·굵기와 배경 불투명도 계약을 추가하고 역할별 표면 차이를 확정
76. `COBBLEMON_UI_MAINLINE_THEME_PRESETS_AMENDMENT.md` — 아르세우스 계열을 제외하고 GBA·DS 도트 본가와 가라르·팔데아 문법을 분리한 여섯 내장 테마 후보, 코드·Visual Pack 경계와 같은 월드 비교 게이트를 확정
77. `COBBLEMON_UI_MAINLINE_THEME_RUNTIME_EVIDENCE.md` — 실제 개발 월드 한 세션에서 여섯 테마의 렌더·포커스·스크롤·닫기를 순회하고, 비교 캡처·25개 단위 테스트와 아직 검증하지 않은 소비자 경계를 기록
78. `COBBLEMON_UI_PIXEL_LEAGUE_THEME_CORRECTION.md` — 세대명 도트 프리셋을 팔레트 연구로 정정하고, 단일 `pixel_league`의 픽셀 프레임·하드 섀도·내장 커서/아이콘과 스냅샷 경고 개발 캡처 경계를 확정
79. `COBBLEMON_UI_TEXT_SHADOW_POLICY_AMENDMENT.md` — 공용 버튼 글자 그림자를 기본 해제하고, 테마 기본값과 `UiButtonSpec` 호출부 인자로 필요한 곳만 명시적으로 활성화하는 정책을 확정
80. `COBBLEMON_UI_PIXEL_SHADOW_OFFSET_AMENDMENT.md` — 공용 픽셀 프레임의 하드 섀도를 위젯에 가깝게 두는 기본 간격과 `pixel_league` 셸·카드 계층별 재정의를 확정
81. `COBBLEMON_UI_WIDGET_PRIMITIVES_AMENDMENT.md` — 원형·라운드·캡슐·마름모 표면, 아이콘 전용 버튼, 탭·배지·토글·리스트·진행 바와 공용 스크롤 뷰포트 계약을 확정
82. `COBBLEMON_UI_LAYOUT_OVERLAY_WIDGETS_AMENDMENT.md` — 스택·플로·그리드 배치, 툴팁·모달·토스트, 체크박스·라디오·콤보박스와 카드·통계 행 계약을 확정
83. `COBBLEMON_UI_CONTENT_PRIMITIVES_AMENDMENT.md` — 여러 줄 본문·패널·단계 트랙·순서형 선출·렌더 슬롯·지속 안내·앵커 배치와 스크롤 키보드/드래그 계약을 확정
84. `BETTER_COBBLEMON_MUSIC_RESOURCE_CATALOG_AND_MAPPING_DECISION.md` — 공식 ZIP의 OGG·사운드 이벤트·기본 카탈로그 소유권, 확장 카탈로그, 안정 ID, `settings.json`·`overrides.json`, 구형 생성팩 종료를 확정한 현행 계약
85. `../better-cobblemon-music/RCT_ROLE_SUPPORT_RETIREMENT_2026-09-22.md` — RCT 역할 조회와 `battle.roles`·`battle.gym`을 전부 제거하고 NPC 여부만 사용하는 현행 종료 계약
86. `../more-battle-content-league-challenge/docs/LIVE_UI_WIRING_V1.md` — 실제 터미널 홈과 서버 요청 연결, Better AI 진입점 보존, 임시 UI Kit 내장 배포와 검증 경계
87. `COBBLEMON_UI_DS_WINDOW_STYLE_AMENDMENT.md` — 테마를 스타일과 팔레트로 나누고, 4세대 배틀타워·배틀팩토리 팔레트 여섯 개와 DPPt 메뉴 창 스타일(창 테두리·커서형 선택·색 글자 그림자·규칙선 제목·틀 없는 목록 줄)과 작은 컨트롤 글자 배율 정정을 확정

## 2. 현재 확정 결정

| 영역 | 결정 |
|---|---|
| 본체 | `Cobblemon: More Battle Content` / `cobblemon_more_battle_content` |
| AI 애드온 | `Cobblemon: More Battle Content - Better AI` / `cobblemon_more_battle_content_better_ai` |
| 저장소 범위 | 서로 독립적인 Cobblemon 애드온을 함께 둘 수 있으며 전체 JAR 수를 제한하지 않음 |
| MBC 산출물 | 본체 JAR 하나 + 선택형 서버 전용 Better AI JAR 하나 |
| 독립 연출 모드 | `Cobblemon: Better Battle Presentation` / `cobblemon_better_battle_presentation`; Mega Showdown만 기능 의존, MBC와 Better AI에는 비의존; 첫 기능은 참가자·등록 관전자 한정 다이맥스 하늘과 약 0.8초 페이드 |
| More Battle Content 세부 결정 | 타워·팩토리·PvP·보스 레이드·BP·기록·관리 전투 규칙은 [`../more-battle-content/docs/README.md`](../more-battle-content/docs/README.md)의 "현재 확정 결정"과 "현재 코드와 다른 조항" 표를 따른다 |
| 기믹 책임 | 본체가 합법 후보를 생성하고 Better AI는 전략적 선택만 담당 |
| AI 부재 | 본체가 Cobblemon 기본 AI 기준선 어댑터 사용 |
| AI capability | 초기에는 `SINGLE`, `DOUBLE`만 제공 |
| AI 난도 | 타워 입문은 입문 AI, 실전은 일반 AI, 고급·프로는 상급 AI, 5승 단위 보스전은 보스 AI. 로컬·Router 최종 선택 소유권은 별도 Better AI 계약을 유지. **주의:** 현재 코드는 프로 정규전도 보스 AI를 쓴다(`c7af31e0`, MBC `MEMORY.md` 2026-09-27 이슈) |
| Router 기본 선택 | 설정 스키마 3은 타워 승급·MAX 보스와 팩토리 싱글 21·49 헤드만 Router, 나머지는 로컬. 콘텐츠별 `LOCAL_ONLY/BOSS_ONLY/ALL/DIFFICULTY_TIERS` 제공 |
| Router 근거 로그 | `logDecisionSummary` 기본 `false`; 활성화 때만 공개 사실 한 문장을 서버 콘솔·`latest.log`에 기록 |
| Router 근거 누적 | 활성화 때 `logs/mbc-better-ai-router-decisions.jsonl`에 공개 근거를 즉시 append하며 저장 실패는 행동·폴백에 영향 없음 |
| Router 판단 마감 | 공용 절대 상한과 설정 허용 상한 20초, 새 설정 기본값은 10초 유지 |
| Cobblemon UI 툴킷 | MBC 내부 전용 `MbcUI` 경계를 갱신해 독립 Gradle 클라이언트 소스 모듈로 공용 위젯·테마·입력 계약을 추출. League와 독립 Battle UI가 실제로 소비한 뒤 별도 JAR/Jar-in-Jar 배포를 결정하며 owo는 비공개 백엔드 후보로만 유지 |
| Battle UI 표시 | 명령 선택 때 데이터 기반 행동 메뉴, 연출 때 하단 내레이션, 요청 때만 좌·우·중앙 전체 기록을 표시. 실제 월드 Cobblemon 전투에서 검증한 뒤 기존 상시 로그를 종료 |
| 외부 레퍼런스 | 동작 계약과 UX만 참고하며 ARR 코드·데이터·자산은 복사하지 않음 |

## 3. 아직 열린 결정

MBC 본체의 열린 결정(보스 레이드 방향, PvP 라운지 영속 저장, 기존 데이터 마이그레이션, 문서·코드 불일치 처리)은 [`../more-battle-content/docs/README.md`](../more-battle-content/docs/README.md)의 "열린 결정" 표에서 관리한다.

1. **결정자:** 빡대리님
   **대상:** 기존 저장소 보관과 새 GitHub·Modrinth 프로젝트 공개 시점
   **시점:** 외부 공개 상태 변경 전

## 4. 재개 지점

- 작업 브랜치는 `main`이다. 2026-08-19 `544d6456` 이후 코드는 커밋돼 있으므로 옛 `refactor` 작업 사본 서술은 쓰지 않는다.
- MBC 본체는 [`../more-battle-content/MEMORY.md`](../more-battle-content/MEMORY.md)의 열린 이슈부터 확인한다.
- Better AI는 [`../more-battle-content-better-ai/MEMORY.md`](../more-battle-content-better-ai/MEMORY.md)의 열린 이슈부터 확인한다.
- Better AI는 로컬 Brain과 OpenRouter Brain을 독립된 최종 판단 주체로 등록한다. 유효한 API 설정이 있으면 합법 후보가 둘 이상인 모든 요청을 Router가 판단하고, 없으면 로컬 Brain이 판단한다. 외부 실패만 로컬→본체 기준선→비상 행동으로 폴백한다. 공통 계산기는 공개 기술 사실과 숨은 세트를 읽지 않는 능력치 범위로 Gen 9 기본 비급소 피해·16난수 KO 범위를 제공하되, 동적 보정은 명시적 `UNKNOWN`으로 남긴다. 효용·순위·추천은 만들지 않는다. 로컬 Brain 10,000회 생성 판단과 Router 매 요청·실패 전파 계약을 단위 검증했다.
