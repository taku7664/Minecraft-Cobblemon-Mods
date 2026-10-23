# Better AI 설명 검수와 보스 관측 경로 분석

검수 기준: 2026-10-23, `main`/작업 브랜치 `5d8a42a6b`. AI 실행 소스는 직전 전체 기본 검사와 같다. 이 문서는 검수 결과이며, 아래의 설명·코드·테스트를 고쳤다는 뜻이 아니다.

## 요청과 범위

- 사용자 요청: 탐색 범위를 비롯한 설명을 검수하고, 전체 기본 검사에서 보스 관측 관련 7건이 실패한 원인을 파악한다.
- 첨부한 CPU 병목 표의 최적화는 **이후 작업 예고**다. JSON 옵션, 계산 근사, 캐시·해시 개선을 이번에 구현하지 않는다.
- `docs/betterai/`의 MD 29개와 `BETTERAI_OPTIMIZATION_REVIEW.md`를 대상으로 설명·계약·과거 기록을 검색하고, 현재 진입점·프롬프트·설정·계산 자료·선택·기록 경로와 대조했다. 모듈 README/CLAUDE/AGENTS도 확인했다.
- 인벤토리 30개/6,281줄과 SHA-256, 상대 Markdown 링크 검사, 직전 기본 검사의 해당 실패 7개는 `F:/AI/builds/cobblemon-mods-1b39-entry-healblock/explanation-audit-20261023/`에 보관한다. 상대 Markdown 링크의 없는 대상은 0개다. 코드 블록 안의 명령 경로가 맞다는 의미는 아니다.
- 원본 Showdown과 모든 기술·특성·도구를 다시 차등 실행한 검수가 아니다. 날짜가 붙은 당시 결과를 현재 결과로 바꾸지 않았고, Excel 구현표의 전 항목 완료 여부도 재승인하지 않았다.

## 현재 경로를 설명할 때 구분할 값

| 구분 | 현재 코드에서 확인한 내용 | 근거 |
|---|---|---|
| 게임의 로컬 Brain | `LocalTacticalBrain(searchless = true)`로 등록. 네이티브/레거시 재귀 행동 탐색을 하지 않는다. 대면 계산이나 특성 세계 재생까지 없다는 뜻은 아니다. | `MoreCobblemonContentsBetterAi.kt:94-95`, `LocalTacticalBrain.kt:536,710,1125` |
| 난이도 프로필의 기본 깊이 | 입문/표준/상급/보스 모두 `lookaheadPlies=2` | `internal/ai/BattleTacticalContracts.kt:106-155` |
| 탐색을 켠 더블 | `CURRENT.doublesSingleTurn=true`라 기본 1턴. 프로필 2와 다르다. | `evaluation/LocalDecisionTuning.kt:100`, `search/LocalRecursiveLookahead.kt:133`, `search/NativeInitialProductDecisionEvaluator.kt:252,408` |
| 네이티브 끝내기 확장 | 아군 잔존 수 2 이하이면 노드 예산을 늘리고 `nativePlies=3`. 실제 완결 깊이는 별도다. | `search/LocalLookaheadBudgetPolicy.kt:114-118` |
| 탐색 시간 | 로컬 탐색 안전 상한 40,000ms. 컨텍스트 마감과 작은 쪽을 사용한다. | `search/LocalLookaheadBudgetPolicy.kt:49,133-139`, `LocalTacticalBrain.kt:182-185` |
| 본체 판단 마감 | 요청 공용 상한 45,000ms | `internal/ai/BattleBrainContracts.kt:1352-1354`, `Cobblemon173BrainTrainerBattleActor.kt:681-683` |
| Router HTTP 설정 | 기본 10,000ms, 허용 1,000~20,000ms. 컨텍스트 잔여 시간/soft timeout도 함께 제한한다. | `router/BetterAiConfig.kt:29,56-57`, `brain/OpenRouterTacticalBrain.kt:123-127` |
| 보스 턴 행동 | 주입 선택기 대신 `LocalHighestRankedActionSelector` 사용. 확률 1.0, 선택 폭 1. 보스의 `ruleExclusions`는 빈 맵이다. 기믹 짝의 동점 처리 경로는 별도로 남아 있다. | `LocalTacticalBrain.kt:286,323,1074-1085,1211-1223`, `policy/LocalWeightedActionSelector.kt:672-677` |
| 보스 선봉 | 턴 행동과 다른 경로. `LocalLeadChoice`는 보스도 온도 0.3의 소프트맥스 추첨을 한다. | `policy/LocalLeadChoice.kt:149-163` |
| Router 판단 | 독립된 Brain. 현재 프롬프트는 보스 포함 상위 40% 안에서 가중 추첨하라고 지시한다. 로컬 보스의 1위 정책을 자동으로 공유하는 구현이 아니다. | `router/HumanlikePromptCodec.kt:39-40,479` |

2턴은 요청/기본 프로필의 값이지 게임에서 실제로 2턴을 완주했다는 뜻이 아니다. 탐색 활성 여부, 포맷, 끝내기 확장, 완결 깊이를 함께 표시해야 한다.

## 확인된 설명 불일치와 설명 범위 누락

### 실제 요청·진단에 들어가는 설명

| ID | 설명과 위치 | 실제 구현과의 차이 / 영향 |
|---|---|---|
| P1 | `HumanlikePromptCodec.kt:417-433`: 입문·표준은 1턴, 입문은 2턴 사고 금지 | 같은 요청의 `trainer.difficulty.lookaheadPlies`는 둘 다 2다(`:184`). 외부 모델에 서로 다른 지시가 간다. `HumanlikeExternalBrainTest.kt:402-406`도 옛 문구를 기대하므로 프로필 숫자 assertion만 고쳐서는 정합성이 생기지 않는다. |
| P2 | `HumanlikePromptCodec.kt:480`: `standardDamage*`는 비급소 기본식이며 특성·도구·필드·기믹·기술별 동적 보정 제외 | 현재 공통 계산은 변신 후 타입/능력치, 확정 급소, 천진, 일부 공개 능력치/도구 보정, 다타와 만피 생존 등을 반영한다. `PublicBattleTacticalCalculator.kt:438-463,620-717`. 반영된 수치를 다시 빠진 보정으로 읽거나 의미를 잘못 판단할 수 있다. 모든 동적 효과가 완성됐다는 뜻도 아니다. |
| P3 | 프롬프트의 `typeChartMultiplier`와 행동 순서 자료의 의미 설명 부족 | 실제 배수는 알려진 부유 등 방어 특성과 특성 무시 조건도 읽는다(`PublicBattleTacticalCalculator.kt:735-757`). 입력 이름/`PUBLIC_TYPES` 근거만으로 순수 타입표 값이라고 읽으면 틀린다. 또 `facts.actsFirstProbability`는 상대 우선도를 0으로 둔 값(`:517-524`)인데 프롬프트는 이 가정을 명시하지 않는다. 어느 상대 기술보다도 먼저 움직일 확률로 읽어서는 안 된다. 숫자 오류가 확인됐다는 뜻은 아니다. |
| P4 | `HumanlikePromptCodec.kt:475`의 `brain-choice-v24` 대 응답 태그 `:87`의 `openrouter_brain_choice_v14` | 요청 버전과 결과 태그가 다르다. 옛 프롬프트가 실행됐다고 태그만으로 판정하면 틀린다. |
| P5 | `LocalTacticalBrain.kt:1143-1147`의 `mixed_top40`, `contextual_human_mix` 등 | 보스에서도 무조건 붙는다. 실제 보스 선택은 확률 1.0인데 태그가 추첨을 했다고 읽힌다. 확률 맵과 실제 선택 경로를 확인해야 한다. |
| P6 | `LocalTacticalBrain.kt:1119`의 추적 mode `legacy_lookahead` | `searchless=true`일 때도 실행된다. 로그 mode만으로 레거시 탐색이 돌았다고 볼 수 없다. `searchless` 태그와 실제 lookahead 결과를 함께 봐야 한다. |
| P7 | `AiTestDecisionTrace.kt:129`의 `next=legacy_lookahead` | 네이티브 실패 처리 호출 뒤 실제로는 `LocalTacticalBrain.kt:670-688`에서 `native_failed_policy_choice`를 반환한다. 실패 원인을 조사할 때 다음 경로를 잘못 알려 준다. `NOT_APPLICABLE`의 레거시 경로와 실패 경로는 다르다. |
| P8 | `LocalTacticalBrain.kt:134-144`, `LocalNonDamagingMoveEvaluator.kt:30`의 가중 추첨/엔진 상태기 평가 설명, `AiDecisionSnapshotReplay.kt:41`의 quick decider 설명 | 보스는 가중 추첨을 하지 않는다. 엔진 상태기 평가 결과 `statusPricing`은 현재 `null`로 고정(`LocalTacticalBrain.kt:694-696`)이고 `nativeStatus` 생성자 인수는 현재 클래스에서 판단 분기로 읽히지 않는다. `EmbeddedTeamBattle.kt:55,98`은 이 인수를 계속 전달하므로 이 설정을 바꿨다고 엔진 상태기 평가 A/B가 되지는 않는다. quick decider 설명도 현재 코드와 맞지 않는다. |

Router의 상위 40% 지시와 로컬 보스의 1위 고정은 **서로 다른 판단 주체의 실제 차이**다. 문구 검수만으로 Router 정책을 로컬과 같게 바꾸지는 않았다. 보스 선봉 추첨도 턴 행동 선택과 따로 보고한다.

### 개발·계약 문서

| ID | 문서·위치 | 대조 결과 |
|---|---|---|
| D1 | `OVERVIEW.md:3-19` | 별도 선택 서버 모드처럼 설명하고 원격 클라이언트 설치가 불필요하다고 한다. 현재 모듈 README는 MCC 본체이며 양쪽 설치를 안내한다. 루트에서 `gradlew` 실행·옛 build 경로를 안내하는 명령도 현재 `works/` 구조와 F 쓰기 규칙에 맞지 않는다. |
| D2 | `OVERVIEW.md:25-28`, `MOVE_INFERENCE_BY_DIFFICULTY.md:150`, `DIFFICULTY_ACTIVATION.md:14` | 각각 `1/1/2/2`, `1/1/2/2`, `0/0/1/2`가 남아 있다. 현재 프로필은 `2/2/2/2`. 같은 inference 문서의 표 `:106-111`는 이미 2로 되어 있어 문서 내부도 충돌한다. |
| D3 | `OVERVIEW.md:30-61`, `NATIVE_SHOWDOWN_SIMULATION.md:102,123,129` | 사용률 기술표·빌드 주변분포·변화기 사용률 추정을 현재 경로처럼 설명한다. 10-12 후속 결정은 사용률 표를 판단에서 제거했으며 `NativeOpponentPreviewBuildWorldCompiler.kt:35-45`도 통계를 쓰지 않는다고 명시한다. inference §11은 철회 표시가 있으므로 그 안의 옛 규칙을 현행으로 재활성화하면 안 된다. |
| D4 | `OVERVIEW.md:70-101` | 네이티브 제품 연결/사후분포 연결이 아직 pending이며 세계별 공통 완결 깊이를 집계한다고 안내한다. 현재 코드에는 native 초기/연속 경로와 정보 집합 탐색이 있으며, 게임 등록에서는 그 재귀 탐색을 끈다. 원시 primitive 설명, 탐색 가능 구현, 실제 게임 활성 상태를 섞고 있다. |
| D5 | `ROUTER_DEADLINE.md:13-16,32`, `README.md:53` | 본체 공용 최대 20초라는 설명은 현재 45초 상한과 다르다. Router 설정의 최대 20초와 기본 10초는 여전히 맞다. 로컬 탐색 상한 40초까지 모두 같은 값으로 바꾸는 문제는 아니다. |
| D6 | `MATCHUP_SCORES.md:120-158`, `THREAT_AND_MECHANIC_VALUE.md:90-95`, `BLOCKERS.md`의 보스 선택 폭 항목 | 보스도 `setup_gate`, `status_wasted`, `switch_in_dies`, `dominated_knockout` 등 선택기 제외와 가중 추첨을 따른다는 설명이 남아 있다. 현재 보스 선택기는 제외 맵을 사용하지 않고 1위를 고른다. 상급 등 다른 난이도와 분리해 설명해야 한다. |
| D7 | `MATCHUP_SCORES.md:3,118` | 판단당 기초 대면표 한 번, 소비자가 전부 같은 표 사용, 캐시는 후속이라는 설명은 현재 호출 구조와 다르다. 기본·기믹·에이스·피격 후·회복 후·디버프 후 판을 계산하는 경로가 있고 기존 캐시도 있다. 직전 프로파일 근거는 `BETTERAI_OPTIMIZATION_REVIEW.md`의 호출자 표와 일치한다. |
| D8 | `MATCHUP_SCORES.md:163-175`, `NATIVE_SHOWDOWN_SIMULATION.md:97-104`, `ACTION_ORDER_POSTERIOR.md:34-37` | 기본 프로필/레거시 노드 상한/네이티브 노드 상한/전체 세계 수를 같은 예산처럼 읽기 쉽다. 레거시 기본 40만, 네이티브 2500/5000/10000/15000, 더블 기본 1턴이다. planner의 현재 `defaultWorldLimit`는 3/6/10/8이며 보스 8은 호출부에서 쓰는 제한 값이지 전체 세계 수 8이라는 뜻은 아니다. `maximumHypothesesPerPokemon=16`이 삭제됐다는 뜻도 아니다. |
| D9 | `STANDARD_DAMAGE_PROJECTION.md:21-35,57` | 기믹·광역기는 수치 없음, 급소·다타·동적 보정 제외라는 계약이 현재 계산과 맞지 않는다. 특히 `SHOWDOWN_GEN9_BASE_NON_CRITICAL` 모델 이름은 확정 급소와 고정 피해 경로에도 붙는다(`PublicBattleTacticalCalculator.kt:464-481,526,710`). 숫자뿐 아니라 모델 이름·불확실성 설명을 함께 정리할 대상이다. |
| D10 | `BRAIN_OWNERSHIP.md:35`, `STANDARD_DAMAGE_PROJECTION.md:43`, `DECLARATIVE_MOVE_EFFECTS.md:17` 등의 전체 합법 후보 전달 계약 | 현재 Router는 계산 후 `PublicMechanicalActionGate.removeNullifiedWhenPossible`로 공개 무효 후보를 제거하고, 남은 후보가 하나면 외부 호출을 생략한다(`OpenRouterTacticalBrain.kt:109-127`). 전술 점수로 상위 후보만 보내는 것과 다르지만, 원래 합법 후보를 언제나 전부 보낸다는 문서 그대로의 동작도 아니다. |

### 그대로 오류라고 단정하지 않은 설명

- `DESIGN.md`의 10초·구형 호출 정책, `HUMANLIKE.md`의 호출 횟수 등은 후속 결정이 대체한다고 명시돼 있다. 옛 문장이 있다는 이유만으로 현재 런타임 오류로 세지 않는다. 다만 인덱스가 “현재 효력 있는 계약만”이라고 하는 것과 과거 기록의 혼재는 읽을 때 주의가 필요하다.
- `MECHANICS_CHECKLIST.md`는 기준 커밋 `6a715d557`, 일부 원본 미대조, 기록 재생 검증 없음이라고 직접 제한한다. “보스 추첨”, 반동·자기과신 등의 당시 평가를 최신 구현 완료/미완료 증거로 재사용하지 않는다. 이번 설명 검수는 그 모든 기계 규칙을 새로 검증한 것이 아니다.
- `HANDOFF_2026-10-12_STATUS_SETUP.md`, `REPLAY_AI_REVIEW.md`, 날짜별 `MEMORY.md`는 당시 기록이다. 옛 랭크업 200점 식이나 과거 판정기를 현재 코드라고 설명하지 않는다.
- Router의 공개 기술 catalog 설명은 실제 `Cobblemon173PublicActionCatalog`의 상대 `PUBLICLY_REVEALED` 구성과 맞는다. 로컬의 난이도별 숨은 세트 읽기를 Router에 그대로 준다고 단정하지 않는다.
- PP Max 추정과 Transform 복사 기술 5PP(1PP 기술 예외), 입문 Router의 예측 폐기·계획 CLEAR, 요약 로그 기본 false, 품질 모드의 effort/토큰 구분은 대응 코드에서 같은 구분을 유지한다.
- `LEAD_CHOICE.md`의 보스 선봉 소프트맥스 온도 0.3은 현재 코드와 맞는다. 보스 턴 행동의 추첨 제거와 혼동하지 않는다.
- `BETTERAI_OPTIMIZATION_REVIEW.md`의 7개 boolean은 제안이며 로더가 없다는 설명과 현재 상태가 맞는다. CPU 샘플 비중은 하위 호출이 겹치므로 합산하거나 예상 절감률로 쓰지 않는다. 첨부 이미지의 81.3/16.2/7.8/6.7/5.7/5.5/4.0%도 같은 제약을 따른다.

## 5번: 보스 관측 실패의 정확한 원인

### 선택기와 관찰기를 같은 객체로 쓴 구조

1. 테스트/재생 도구가 `LocalActionSelector`를 주입한다.
2. 그 선택기의 `choose(ranked, seed, context)` 안에서 순위와 결과를 저장한다.
3. 10-21 정책 변경 뒤 보스는 `LocalTacticalBrain.kt:286`에서 그 주입 선택기 대신 `LocalHighestRankedActionSelector`를 쓴다.
4. 실제 보스 행동 선택은 진행되지만 주입 선택기의 저장 코드는 호출되지 않는다.
5. 테스트가 빈 기록을 읽어 실패한다. 이 지점 뒤의 최종 행동/수치 assertion은 실행되지 않았다.

이는 보스 1위 선택을 되돌려야 한다는 증거가 아니다. 선택 정책과 관측 책임을 결합한 테스트/도구 구조가 현재 정책과 맞지 않는 문제다.

| 실패 클래스 | 건수 | 비는 값과 실패 지점 | 도달하지 못한 검증 |
|---|---:|---|---|
| `AiDecisionSnapshotReplayTest` | 1 | `AiDecisionSnapshotReplay.kt:45-47`의 `captured`, `:72`의 error | JSON 왕복 뒤 같은 행동/순위 재생 비교 |
| `LocalSelfDropMoveTest` | 1 | `LocalTacticalScenarioBattle.kt:998`의 `lastIdeal`, `:1009`의 requireNotNull | 특공 -2 뒤 용성군 반복 대신 다른 공격 선택 확인 |
| `LocalTeamRoleDecisionTest` | 3 | `LocalTeamRoleDecisionTest.kt:88-98`의 `observer.latest` | 역할 가치·미래 반영 비중·강제 교체 뒤 팀 커버 비교. 그 앞의 깊이/비중단 assertion은 통과했다. |
| `NativeTogekissDecisionProductBrainIntegrationTest` | 2 | `:153,278`의 주입 lambda가 채우는 shortlist, `:187,291`의 nonempty assertion | 최종 제출 기술이 공격인지/날개쉬기가 아닌지 검증. 앞의 native 평가 검증까지 전부 실패했다고 확대하지 않는다. |

근거는 직전 기본 검사 `full-default-20261023-0940/test-outcomes.json`과 해당 `results/*/events.jsonl`이다. 이번에 테스트를 다시 실행하거나 assertion을 지우지 않았다.

### 영향은 테스트에만 한정되지 않는다

- CLI `replayDecisionSnapshot`도 같은 `AiDecisionSnapshotReplay.replay`를 부른다. 보스 정상 결정이 주입 선택기를 건너뛰면, 결정을 계산한 뒤에도 `captured == null`로 결과 반환에 실패할 수 있다. 기본 searchless 모드도 `:51`에서 같은 선택기를 주입하므로 이 구조를 공유한다.
- 보스 외에도 단일 후보/네이티브 실패 등의 조기 반환은 선택기를 거치지 않는다(`LocalTacticalBrain.kt:644-688`). 따라서 “선택기 주입을 호출하면 모든 결정을 관측한다”는 가정 자체가 성립하지 않는다.
- **게임의 단계 기록은 별도 경로다.** 일반 루트의 `LocalDecisionStages`는 실제 `ranked`와 `selection`으로 만들어 `stageTrace` 및 `writeStages`에 보낸다(`LocalTacticalBrain.kt:1088-1117`). 이 경로가 주입 선택기 내부 저장을 필요로 하지는 않는다. native/조기 반환까지 전부 같은 stages 형식을 쓴다는 뜻은 아니다.
- 기존 아레나 `20261023-011215-profile-review/battle-0/decisions.jsonl`의 p1 1턴을 직접 확인했다. 최종 행동 `move 3 dynamax`, refined 후보 10개, 내부 값 154개, 확률 맵 `{ "move 3 dynamax": 1.0 }`, 제외 맵 `{}`이며 실제 선택과 stages 선택이 같다. `mixed_top40` 태그도 같이 남아 있어 태그 불일치를 확인한다. 이 한 건으로 모든 조기 반환 경로의 기록 완전성을 보장하지 않는다.

### 수정한다면 지켜야 할 경계 (아직 구현하지 않음)

- 선택 정책을 바꾸지 않는 별도 관측 지점에서 **실제 순위, 실제 선택 결과, 기믹 짝 처리 뒤 최종 행동**을 전달하는 방향이 맞다.
- searchless뿐 아니라 native 성공/실패, 단일 후보 등 반환 경로를 구분해 관측해야 한다. `stageTrace` 하나로 native 테스트까지 바로 해결된다고 쓰면 안 된다.
- 재생 도구는 보스에게 가중 선택기를 대신 실행해서 기록을 만들면 안 된다. 실제로 쓰지 않은 확률·제외 규칙을 재생 결과로 꾸미게 된다.
- 토게키스 테스트는 현재 보스에게 존재하지 않는 추첨 shortlist를 기대할지부터 정리해야 한다. 최종 행동 검증은 유지해야 하며, 기록만 채워 7개가 전부 통과한다고 미리 보장하지 않는다.

## 수행 결과와 남은 상태

- 완료: 설명 검색·현재 경로 대조, 기존 실패 7건 재확인, 아레나 기록 한 건의 별도 저장 경로 확인, 검수 기록 작성.
- 미수정: 위 프롬프트/진단/문서 불일치, 관측 구조와 테스트/재생 도구, 기존 KO 특성 혼합 오류 및 보류 고정 상수.
- 미수행: 새 전투·테스트·컴파일·게임 JAR 생성·설치·개발/운영 서버 및 클라이언트 조작·실게임 확인.
- 최적화 7항목은 다음 작업 맥락으로만 남긴다. 이번 검수 문서가 옵션 구현 승인이나 구현 완료를 뜻하지 않는다.
