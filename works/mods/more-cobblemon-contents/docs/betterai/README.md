# Better AI 문서 인덱스

| 항목 | 값 |
|---|---|
| 정리일 | 2026-09-27 |
| 주 독자 | 빡대리님과 Better AI 설계·구현·검증 담당자 |
| 작업 기록 | [`MEMORY.md`](MEMORY.md) — 날짜·시각별 작업 내역과 이슈(최신이 위) |
| 사용법·테스트 도구 | [`OVERVIEW.md`](OVERVIEW.md) — 빌드, 사용률 스냅샷, 기준선·쌍 비교·native 하네스, 실시간 판단 로그 |
| 미구현·버그 백로그 | [`engine/LOCAL_GAPS.md`](engine/LOCAL_GAPS.md) — 로컬 판단 계층에서 빠진 기술·특성·도구·상태·요청 처리 (2026-10-02 전수 조사). Better AI 작업 전에 먼저 본다 |

이 폴더에는 현재 효력이 있는 계약 문서만 둔다. 작업 경과·배포·검증 기록은 `MEMORY.md`에 쓰고, 계약 문서에는 합격 조건과 규칙만 남긴다.

## 읽는 순서

1. [`architecture/NATIVE_SHOWDOWN_SIMULATION.md`](architecture/NATIVE_SHOWDOWN_SIMULATION.md) — 현재 핵심 계약과 합격표(INF·CORE·AUD·LIVE)
2. [`architecture/BRAIN_OWNERSHIP.md`](architecture/BRAIN_OWNERSHIP.md) — 로컬·Router Brain의 판단 소유권과 폴백 순서
3. [`MEMORY.md`](MEMORY.md) — 최근 작업과 열린 이슈
4. [`engine/LOCAL_GAPS.md`](engine/LOCAL_GAPS.md) — 미구현·버그 백로그
5. 작업 대상에 해당하는 분류의 문서

## 우선순위

두 문서가 충돌하면 더 늦게 효력이 생긴 문서가 우선한다. 각 문서 머리표의 `Updates`·`Obsoletes`가 대체 관계를 나타낸다. [`architecture/DESIGN.md`](architecture/DESIGN.md)는 2026-08-18 원계약이므로, 아래 후속 결정이 다룬 부분은 후속 결정을 따른다.

## 분류

### architecture — 전체 구조

| 문서 | 효력일 | 내용 |
|---|---|---|
| [`DESIGN.md`](architecture/DESIGN.md) | 2026-08-18 | 품질·보안·폴백 원계약. 핵심 타입, 싱글·더블 Brain, 난도·전략 프로필, 외부 AI 설정 |
| [`BRAIN_OWNERSHIP.md`](architecture/BRAIN_OWNERSHIP.md) | 2026-08-18 | Router·로컬은 독립된 최종 판단 주체이고, 공통 계산기는 사실만 공급한다 |
| [`NATIVE_SHOWDOWN_SIMULATION.md`](architecture/NATIVE_SHOWDOWN_SIMULATION.md) | 2026-09-24 | 수제 상태 전이를 종료하고 별도 Showdown 샌드박스로 분기를 계산한다. 난도별 가설 상한과 전체 반례 합격표 |

### inference — 상대 가설과 추론

| 문서 | 효력일 | 내용 |
|---|---|---|
| [`MOVE_INFERENCE_BY_DIFFICULTY.md`](inference/MOVE_INFERENCE_BY_DIFFICULTY.md) | 2026-09-24 | `이름-그룹-추측/예상/확정` 기술 슬롯과 난도별 정보 예산 |
| [`NATIVE_ROSTER_HYPOTHESIS.md`](inference/NATIVE_ROSTER_HYPOTHESIS.md) | 2026-09-25 | 공개 팀 프리뷰만으로 싱글 6→3·더블 6→4 선출 가설을 만드는 입력 계약 |
| [`ACTION_ORDER_POSTERIOR.md`](inference/ACTION_ORDER_POSTERIOR.md) | 2026-09-25 | 공개 행동 순서로 속도·도구 세계를 좁히고 구애 고정을 네이티브 요청에 유지 |

### public-facts — 공개 관측과 계산 사실

| 문서 | 효력일 | 내용 |
|---|---|---|
| [`PUBLIC_ACTION_EVIDENCE.md`](public-facts/PUBLIC_ACTION_EVIDENCE.md) | 2026-08-18 | 공개 행동 순서와 HP 변화 증거 |
| [`PUBLIC_MOVE_OUTCOME.md`](public-facts/PUBLIC_MOVE_OUTCOME.md) | 2026-08-18 | miss·실패·차단·급소·상성·면역·명중 횟수 공개 사건 |
| [`PUBLIC_EFFECT_OUTCOME.md`](public-facts/PUBLIC_EFFECT_OUTCOME.md) | 2026-08-19 | Substitute 대체 피해와 Protect 공개 시작 |
| [`STANDARD_DAMAGE_PROJECTION.md`](public-facts/STANDARD_DAMAGE_PROJECTION.md) | 2026-08-18 | 공개 능력치 범위와 Gen 9 표준 피해·16난수 KO 자료 |
| [`DECLARATIVE_MOVE_EFFECTS.md`](public-facts/DECLARATIVE_MOVE_EFFECTS.md) | 2026-08-18 | 내장 Showdown 기술의 선언형 효과와 불완전성 경계 |

### router — OpenRouter Brain

| 문서 | 효력일 | 내용 |
|---|---|---|
| [`ROUTER_ACTIVATION.md`](router/ROUTER_ACTIVATION.md) | 2026-08-19 | 실제 보스만 기본 Router로 선택하는 전투 단위 정책과 설정 스키마 3 |
| [`ROUTER_DEADLINE.md`](router/ROUTER_DEADLINE.md) | 2026-08-19 | 판단 절대 마감 최대 20초, 기본 10초 |
| [`ROUTER_DECISION_QUALITY_MODE.md`](router/ROUTER_DECISION_QUALITY_MODE.md) | 2026-08-18 | `QUALITY`·`BALANCED`·`ECONOMY` 추론 예산과 설정 스키마 2 |
| [`ROUTER_DECISION_SUMMARY.md`](router/ROUTER_DECISION_SUMMARY.md) | 2026-08-19 | 선택형 공개 근거 한 문장, 서버 로그와 JSONL 장기 기록 |

### behavior — 난도와 사람다운 판단

| 문서 | 효력일 | 내용 |
|---|---|---|
| [`DIFFICULTY_ACTIVATION.md`](behavior/DIFFICULTY_ACTIVATION.md) | 2026-08-19 | 타워·팩토리의 입문·일반·상급·보스 난도 매핑 |
| [`HUMANLIKE.md`](behavior/HUMANLIKE.md) | 2026-08-18 | 계획·예측·상황별 습관·혼합 전략. 호출 정책 등 일부 조항은 문서 상단의 대체 목록 참고 |
| [`THREAT_AND_MECHANIC_VALUE.md`](behavior/THREAT_AND_MECHANIC_VALUE.md) | 2026-09-27 | 상대별 위협도, 내 포켓몬 역할 가치, 기믹 보존 가치(-25점 폐기)와 테라 담당 보정, 수제 경로의 테라·메가 상태 유지, 상태이상 대상 궁합, 확실한 KO 우선 |
| [`LEAD_CHOICE.md`](behavior/LEAD_CHOICE.md) | 2026-09-27 | 팀 프리뷰를 보고 선봉 고르기. 본체 `BattleBrain.chooseLeads` 계약과 난이도별 대면 점수 |

## 2026-09-27 정리에서 없앤 문서

| 구 문서 | 처리 |
|---|---|
| `BETTER_AI_WORK_CONTEXT.md` | 삭제. 유효한 사실과 함정은 `MEMORY.md`로 옮김 |
| `BETTER_AI_HUMANLIKE_REASONING_PLAN.md` | 삭제. 2026-08-31까지의 수제 탐색 실험 기록. 기각 목록만 `MEMORY.md`로 옮김 |
| `BETTER_AI_HYBRID_REVIEW_AND_VALIDATION_PLAN.md` | 삭제. 비규범 초안이며 네이티브 결정으로 방향이 바뀜 |
| `BETTER_AI_15_SECOND_ROUTER_DEADLINE_DECISION.md` | 삭제. 20초 결정이 대체했고 종료 조항은 `ROUTER_DEADLINE.md`에 합침 |
| `BETTER_AI_ROUTER_DECISION_JOURNAL_DECISION.md` | `ROUTER_DECISION_SUMMARY.md` §6으로 병합 |
