# Cobblemon 애드온 저장소 내부 문서 인덱스

| 항목 | 값 |
|---|---|
| Status | `shared` |
| Effective | 2026-08-19 |
| Last reviewed | 2026-09-29 — Cobblemon UI 반응형 배치 트리와 owo 비의존 결정(88번) 추가 |
| 주 독자 | 빡대리님과 이후 설계·구현 담당자 |
| 목적 | 활성 계약, 분석 근거, 폐기 기록과 재개 지점의 라우팅 |

## 1. 압축 후 읽는 순서

1. `PROJECT_STATUS.md` — 활성 모듈과 모듈별 상태·기록을 확인하는 위치
1-1. `MORE_COBBLEMON_CONTENTS_DEV_DEPLOYMENT.md` — MCC 개발 배포 절차, 프로세스 확인 규칙, 버전 범위·중복 JAR·Loom 캐시 함정과 배틀 라운지 재생성 주의
1-1a. [`../jbro-policy/docs/SERVER_OPEN_CHECKLIST.md`](../jbro-policy/docs/SERVER_OPEN_CHECKLIST.md) — 새 운영 서버를 열 때 맞춰야 하는 서버 설정값(PokemonToItem 권한, 닉네임 형식, 안내 목록, 레벨캡 전제)
1-2. [`../more-cobblemon-contents/README.md`](../more-cobblemon-contents/README.md) — MCC 모듈 구성. 작업 기록·결정·이슈는 [`../more-cobblemon-contents/MEMORY.md`](../more-cobblemon-contents/MEMORY.md), AI 계약은 `../more-cobblemon-contents/docs/betterai/`에 있다
2. `BETTER_BATTLE_PRESENTATION_CONTEXT.md` — 독립 연출 모드의 제품 경계, 구현 지도, 배치·검증 상태와 세션 복구 절차
3. `BETTER_BATTLE_PRESENTATION_IDENTITY_DECISION.md` — Better Battle Presentation 표시 이름·Mod ID·확장 경계와 첫 구현 상태
4. `DYNAMAX_ATMOSPHERE_AUDIENCE_AND_TRANSITION_DECISION.md` — 다이맥스 하늘의 참가자·등록 관전자 한정 수신 범위와 자연스러운 페이드 계약
5. `REPOSITORY_ADDON_HOSTING_AND_DYNAMAX_ATMOSPHERE_DECISION.md` — 두 JAR 저장소 상한 폐기, 독립 Mega Showdown 애드온과 하늘 셰이더 구현 원계약
37. `DYNAMAX_STORM_SKY_VISUAL_REVISION_DECISION.md` — 거의 밤인 흑적색 하늘, 정면에서도 보이는 찢어진 진홍 에너지 장막과 바닐라 구름 암전 후속 계약
55. `BETTER_COBBLEMON_MUSIC_ARCHITECTURE_AND_MIGRATION_DECISION.md` — Better Cobblemon Music의 새 정체성, 레거시 0.5.3 단계 이식과 데이터 기반 음악 아키텍처 계약
56. `BETTER_COBBLEMON_MUSIC_RCT_ROLE_MAPPING_CORRECTION.md` — 2026-09-22 폐기된 RCT 역할별 선곡의 과거 결정 기록
66. `../better-cobblemon-music/RELIABILITY_UPDATE_2026-08-22.md` — 카탈로그 구조 이전 재로드·생성팩 신뢰성의 과거 결정 기록
67. `../better-cobblemon-music/CONFIG_SCHEMA_V2_2026-08-23.md` — 카탈로그 구조 이전 `music.json` 스키마 2의 과거 결정과 마이그레이션 기준
68. `ROUNDING_BLOCK_ARCHITECTURE_DECISION.md` — Rounding-Block의 Fabric Renderer API 기반 외곽 엣지 라운딩, Sodium·Iris 그림자 호환 경계, 안전 폴백과 단계별 검증 계약 초안
73. `COBBLEMON_UI_TOOLKIT_AND_RUNTIME_EVIDENCE_AMENDMENT.md` — Cobblemon 전용 공용 위젯·테마 소스 모듈을 정하고, 내장 자산/Visual Pack 경계와 타이틀 fixture·실제 월드 증거 등급을 분리
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
87. `COBBLEMON_UI_DS_WINDOW_STYLE_AMENDMENT.md` — 테마를 스타일과 팔레트로 나누고, 4세대 배틀타워·배틀팩토리 팔레트 여섯 개와 DPPt 메뉴 창 스타일(창 테두리·커서형 선택·색 글자 그림자·규칙선 제목·틀 없는 목록 줄)과 작은 컨트롤 글자 배율 정정을 확정
88. `COBBLEMON_UI_RESPONSIVE_LAYOUT_AMENDMENT.md` — ui-kit에 반응형 배치 트리(크기 규칙·크기 구간 분기·열 정렬 격자)를 두고 Hub와 모든 탭의 손 계산을 옮기며, 우리 모드는 owo를 쓰지 않고 Mega Showdown의 요구로만 개발 실행 환경에 둔다

## 2. 현재 확정 결정

| 영역 | 결정 |
|---|---|
| 저장소 범위 | 서로 독립적인 Cobblemon 애드온을 함께 둘 수 있으며 전체 JAR 수를 제한하지 않음 |
| 콘텐츠 모드 | More Cobblemon Contents(MCC): 본체 `more_cobblemon_contents`와 콘텐츠 모드 배틀타워·배틀팩토리·PvP·리그 챌린지. 상대 AI는 본체에 들어 있다. 세부 결정은 MCC `MEMORY.md`를 따른다 |
| 독립 연출 모드 | `Cobblemon: Better Battle Presentation` / `cobblemon_better_battle_presentation`; Mega Showdown만 기능 의존, MCC에는 비의존; 첫 기능은 참가자·등록 관전자 한정 다이맥스 하늘과 약 0.8초 페이드 |
| Cobblemon UI 툴킷 | 독립 Gradle 클라이언트 소스 모듈 `cobblemon-ui-kit`이 공용 위젯·테마·입력 계약을 가진다. 테마는 스타일과 팔레트의 조합이다(87번). 배치는 ui-kit의 반응형 배치 트리로 하며 owo를 쓰지 않는다(88번) |
| Battle UI 표시 | 명령 선택 때 데이터 기반 행동 메뉴, 연출 때 하단 내레이션, 요청 때만 좌·우·중앙 전체 기록을 표시. 실제 월드 Cobblemon 전투에서 검증한 뒤 기존 상시 로그를 종료 |
| 음악 연동 | Better Cobblemon Music은 MCC가 설치돼 있으면 MCC의 전투 콘텐츠 ID(`more_cobblemon_contents:battle_tower` 등)로 콘텐츠별 전투 음악을 고른다 |
| 외부 레퍼런스 | 동작 계약과 UX만 참고하며 ARR 코드·데이터·자산은 복사하지 않음 |

## 3. 아직 열린 결정

MCC의 열린 결정과 이슈는 MCC `MEMORY.md`에서 관리한다.

1. **결정자:** 빡대리님
   **대상:** 기존 저장소 보관과 새 GitHub·Modrinth 프로젝트 공개 시점
   **시점:** 외부 공개 상태 변경 전

## 4. 재개 지점

- 작업 브랜치는 `main`이다.
- MCC는 [`../more-cobblemon-contents/MEMORY.md`](../more-cobblemon-contents/MEMORY.md)의 최근 항목과 "확인하지 않은 것"부터 확인하고 실제 코드와 대조한다.
- 독립 연출 모드는 `BETTER_BATTLE_PRESENTATION_CONTEXT.md`, 음악 모드는 55·84·85번, UI 툴킷은 73~83·87·88번 문서에서 시작한다.
