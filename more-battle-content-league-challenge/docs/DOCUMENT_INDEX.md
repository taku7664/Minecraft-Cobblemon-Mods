# League Challenge 문서 색인

- 상태: `implementation handoff + planning baseline`
- 대상: 기획자, MBC 본체 구현자, League Challenge 구현자, 데이터·리소스팩 제작자
- 최종 갱신: 2026-09-26 — 서버 시스템 인계와 UI Kit 콘텐츠 프리미티브 반영

최신 제품 UI 연결은 `LIVE_UI_WIRING_V1.md`, 서버 구현은 `SYSTEM_IMPLEMENTATION_V1.md`를
먼저 확인한다. 기존 기획 문서는 결정 이력으로 보존하며, 그 안의 과거 미구현 상태나
레벨캡 제공자 미정 표기를 현재 상태로 해석하지 않는다. 제공자는 CLC로 확정됐다.

## 문서 목록

| 문서 | 역할 |
| --- | --- |
| [LIVE_UI_WIRING_V1.md](LIVE_UI_WIRING_V1.md) | 실제 터미널 홈과 서버 요청 연결, Better AI 진입점 보존, 패키징과 검증 경계를 기록한다. |
| [SYSTEM_IMPLEMENTATION_V1.md](SYSTEM_IMPLEMENTATION_V1.md) | 구현된 서버 진행도·저장·데이터팩·외부 연동·MBC API 경계, 클라이언트 통신과 UI 인계, 미검증 범위를 기록한다. |
| [DECISIONS.md](DECISIONS.md) | 지금까지 채택·보류·기각된 결정을 한곳에서 확인한다. |
| [PRODUCT_SCOPE.md](PRODUCT_SCOPE.md) | 제품 목표, 비목표, 용어와 모듈 경계를 정의한다. |
| [PROGRESSION_AND_CONTENT.md](PROGRESSION_AND_CONTENT.md) | 뱃지, 볼 계급, 레벨캡, 체육관, 사천왕, 챔피언과 후반 해금을 정의한다. |
| [DATA_AND_ASSET_PACKS.md](DATA_AND_ASSET_PACKS.md) | 교체 가능한 리그 콘텐츠의 데이터팩·리소스팩 계약을 정의한다. |
| [TERMINAL_GUI_AND_BATTLE_FLOW.md](TERMINAL_GUI_AND_BATTLE_FLOW.md) | 별도 터미널, GUI, 관장전과 리그 연전의 사용자 흐름을 정의한다. |
| [GUI_FRAMEWORK_AND_RESOURCE_PACK.md](GUI_FRAMEWORK_AND_RESOURCE_PACK.md) | Cobblemon UI 툴킷의 불변 화면·행동 경계, 위젯·테마, 내장 자산과 시각 전용 Visual Pack 계획을 정의한다. |
| [GUI_IMPLEMENTATION_STATUS.md](GUI_IMPLEMENTATION_STATUS.md) | GUI 착수 커밋, 검증 증거, 미검증 경계와 정확한 재개 순서를 기록한다. |
| [UI_BACKEND_SPIKE_RESULT.md](UI_BACKEND_SPIKE_RESULT.md) | 코드 드로잉과 owo 후보의 타이틀 화면 개발 fixture 캡처, 관찰 결과와 미검증 월드 경계를 기록한다. |
| [EXTERNAL_INTEGRATIONS.md](EXTERNAL_INTEGRATIONS.md) | PokeBadges와 외부 레벨캡 제공자 연동 및 장애 처리를 정의한다. |
| [TRAINER_APPEARANCES.md](TRAINER_APPEARANCES.md) | 관장·사천왕·챔피언 스킨, 초상화와 홀로그램 렌더링을 정의한다. |
| [MBC_CORE_CHANGES.md](MBC_CORE_CHANGES.md) | MBC가 독립성을 유지하면서 애드온을 지원하기 위해 필요한 공개 API와 잠금 지점을 정의한다. |
| [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) | 선행 작업, 구현 단계, 검증 범위와 완료 조건을 정리한다. |
| [../../docs/MORE_BATTLE_CONTENT_LEAGUE_UI_BOOTSTRAP_AMENDMENT.md](../../docs/MORE_BATTLE_CONTENT_LEAGUE_UI_BOOTSTRAP_AMENDMENT.md) | 최소 모듈부터 시작하는 GUI 착수 순서와 실험 API 승격·롤백 경계를 정의한다. |
| [../../docs/COBBLEMON_UI_TOOLKIT_AND_RUNTIME_EVIDENCE_AMENDMENT.md](../../docs/COBBLEMON_UI_TOOLKIT_AND_RUNTIME_EVIDENCE_AMENDMENT.md) | MBC 내부 전용 경계를 갱신한 Cobblemon 공용 위젯·테마 소스 모듈과 런타임 증거 등급을 정의한다. |
| [../../docs/COBBLEMON_UI_CONTENT_PRIMITIVES_AMENDMENT.md](../../docs/COBBLEMON_UI_CONTENT_PRIMITIVES_AMENDMENT.md) | League 화면용 본문·패널·단계·순서형 선출·렌더 슬롯·지속 안내와 스크롤 입력 계약을 정의한다. |
| [../../docs/COBBLEMON_BATTLE_UI_PHASED_COMMAND_AND_LOG_DECISION.md](../../docs/COBBLEMON_BATTLE_UI_PHASED_COMMAND_AND_LOG_DECISION.md) | Battle UI의 선택·연출·전체 기록 분리와 공용 툴킷 소비 계약을 정의한다. |

## 문서 우선순위

문서가 서로 다르게 읽힐 때는 다음 순서를 적용한다.

1. 사용자의 최신 명시적 결정
2. `DECISIONS.md`의 `채택` 항목
3. 각 세부 문서의 MUST/MUST NOT 계약
4. `방향 채택` 또는 `제안` 항목
5. 예시 JSON과 설명용 수치

예시 값은 계약이 아니다. 특히 레벨캡 수치와 기본 상대 팀은 별도 확정 전까지 구현 상수로
박아서는 안 된다(MUST NOT).

## 규범 용어

- **MUST**: 진행도 무결성, 상호운용성 또는 우회 방지를 위해 반드시 지킨다.
- **MUST NOT**: 진행도·보상·호환성을 깨뜨리므로 금지한다.
- **SHOULD**: 특별한 근거가 없다면 지킨다.
- **SHOULD NOT**: 특별한 근거가 없다면 피한다.
- **MAY**: 팩 제작자나 서버 운영자가 선택할 수 있다.
