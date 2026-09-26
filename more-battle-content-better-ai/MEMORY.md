# Better AI MEMORY

작업 내역과 이슈를 `[YYYY-MM-DD HH:MM]` 형식으로 기록한다. 최신 항목을 위에 추가한다.
계약·합격 조건은 [`docs/`](docs/README.md)에 두고, 이 파일에는 경과·배포·검증·이슈만 쓴다.
2026-09-27 이전 항목은 당시 커밋 시각과 기존 문서에서 옮겨 온 것이다.

---

## [2026-09-27 05:02] 클라이언트 설치 — Better AI 1.2.21

- 셀렉터 수정·네이티브 기믹 제한·세션 유지·판단 저장을 포함한 1.2.21을 `cobblemon-dev/mods`에 설치했다. 본체(MBC 1.6.24)는 그대로다(요구 범위 `>=1.6.21 <2.0.0`).
- JDK 21 `jar --validate` 통과, 빌드·설치 SHA-256 `EF975E9AA4E7998C3E0424CE4A17845424242C15AD8A3D2EE157E586D28FBC54` 일치. 직전 1.2.20은 `codex-deploy-backups/20260927-selector-native-allowance`에 보관했다.
- 설치 시 클라이언트는 꺼져 있었다. 설치 확인일 뿐 실게임 검증은 아니다. 다음 `/mbc test ai-boss`에서 확인할 것: 0턴이 `native_showdown_initial`로 가는지, `excluded=` 사유, `logs/betterai-decisions/` 저장 파일, 테라 판단.

## [2026-09-27 05:00] 네이티브 기믹 제한, 폴백 뒤 재진입, 판단 저장·재생

- **0턴 매핑 실패 원인 확정**: Mega Showdown이 패치한 `side.js`의 `canDynamaxNow()`는 9세대에서 아직 다이맥스를 안 쓴 쪽이면 허용한다. 실제 전투는 MBC가 막지만 네이티브 샌드박스는 몰라서, 스피리텀에게 다이맥스 기술 4개를 더 만들었다(`unmatchedNative=4`). 상대에게도 가짜 다이맥스 분기가 생기고 있었다.
- **수정**: `NativeMechanicAllowance`가 AI 후보에 나온 기믹을 허용 목록으로 모은다. 탐색 트리·세션 조정의 네이티브 행동 생성(`NativeShowdownRequestActionFactory`)이 이 목록 밖의 기믹을 만들지 않는다. 목록은 세션에 저장되고 전투 동안 늘기만 한다. 목록이 없는 기존 세션은 제한하지 않는다.
- **진단**: 루트 매핑 실패 시 `unmatchedProductIds`·`unmatchedNativeIds`를 최대 6개 로그에 남긴다.
- **재진입**: 이후 턴에 세션 조정은 성공하고 탐색만 실패하면, 평가기가 조정된 세션을 돌려주고 Brain이 수제 탐색의 선택을 대기 행동으로 기록한다. 다음 턴에 네이티브를 다시 시도한다. 태그 `native_session_retained`. 0턴 첫 계획이 실패하면 여전히 돌아올 방법이 없다(전투 중간 상태에서 세계를 새로 만드는 기능은 별도 과제).
- **판단 저장·재생**: `/mbc test ai-*` 전투에서 판단 입력을 `logs/betterai-decisions/<전투ID>/turn-NNN-<요청>.json`으로 저장한다(`AiTestDecisionSnapshot`, 경로는 모드 초기화 때만 설정되어 단위 테스트는 파일을 쓰지 않는다). 재생: `./gradlew :more-battle-content-better-ai:replayDecisionSnapshot -Psnapshot=<파일>` (`-PreplayNative`로 네이티브 경로 포함). 네이티브 세션 상태는 저장하지 않으므로 네이티브를 이어서 쓴 턴은 정확히 재현되지 않는다.
- **검증**: 새 테스트 8개 포함 영향받은 클래스 50/50 통과. 전체 실행의 남은 실패는 HEAD 기준 기존 12개와 같다. 첫 전체 실행에서 실패 때 세션을 `sessionState`에 넣어 "세션은 성공일 때만" 불변식을 깨뜨린 것을 발견해 `retainedSessionState`로 분리했다. 커밋 `be55d887`(네이티브), 다음 커밋(저장·재생).
- **테라 관찰**: 수제 경로의 기믹 후보는 일괄 -25점만 받는다. 로그 2턴에서 테라+전기자석파가 일반 전기자석파보다 높았으므로 다른 곳에서 테라에 가산이 붙는다. 다음 테스트 플레이의 판단 저장 파일로 원인을 확인한다.

## [2026-09-27 04:45] 셀렉터 1위 교체 제외 수정

- 원인: 현재 튜닝(`CURRENT`)은 예상 기술 가설(`lookaheadMoveHypotheses=true`)을 켜 둔다. 셀렉터의 1위 교체 거부 규칙(체력 유지율 0.50 미만)이 상대가 보여 준 적 없는 예상(EXPECTED) 기술의 최악 피해까지 그대로 셌다. 점수는 그 응답을 확신도 보정과 비관 가중치로 이미 반영했는데, 거부 규칙이 한 번 더 덮어썼다.
- 수정: 확정 응답(공개 기술, 난도 열람 기술, 교체, 미확인 응답)만으로 잰 `worstConfirmedResponseHpRetention`을 추가하고, 1위 교체 거부는 이 값만 본다. 후순위 교체의 탐색 안전 기준은 그대로다.
- 로그: 후보마다 `excluded=<사유> keepHp=… keepHpConfirmed=…`를 찍는다. 사유 코드는 `best_switch_confirmed_hp_retention_below_0.50`, `exploratory_switch_hp_retention_below_*`, `repeated_switch_pressure`, `setup_without_future`, `shortlist_count`, `regret_gap`, `score_ratio` 등이다.
- 검증: 새 테스트·보강 테스트 5개가 수정 전 실패, 수정 후 통과했다. 전체 1,152개 중 실패 13개. HEAD 베이스라인(12개 실패)과 비교해 차이 1개(`LocalDoublesSearchBudgetTest`)는 단독 재실행에서 통과한 시간 의존 흔들림이다.
- HEAD에서도 실패하는 기존 12개(최근 커밋이 동작을 바꾸고 테스트를 갱신하지 않은 것으로 보임): 강제 교체 2개(`LocalRecursiveLookaheadTest`), 재료값·확정 승리 가산 관련(`LocalMaterialOwnershipTest`, `LocalLookaheadEvaluationTest`, `LocalForesightOwnershipTest`), 네이티브 깊이 관련(`NativeDefensiveSetupProductBrainIntegrationTest`, `NativeSuckerPunchProductBrainIntegrationTest`), `LocalCooperativeCandidateCoverageTest`, `LocalTurnOrderPessimismTest`, `LocalForesightScenarioTest`, `LocalTacticalBrainSimulationTest`, 랭크업 폴백 1개. 별도 정리 과제.
- 문서 정정: 탐색 깊이 표를 코드 기준(입문 1·표준 1·상급 2·보스 2)으로 고쳤다. 폴백 정책 개정(§1.1)도 반영했다.

## [2026-09-27 03:40] 과제 — 셀렉터와 점수의 역할 분리

- 셀렉터(`LocalWeightedActionSelector`)가 전술 판단까지 하고 있다: 교체 후 남는 체력(`switchExclusion`), 앞날 없는 랭크업(`selfSetupHasFuture`), 행동 확률 하한. 8/25 탐색이 없던 시절 점수를 보완하려고 넣은 안전장치가 탐색 도입 뒤에도 남아, 점수와 선택이 같은 위험을 따로 판단한다.
- 방향(빡대리님 동의): 전술 판단은 점수로 옮기고, 셀렉터에는 의미 없는 행동 거르기(효과 없음·진입 즉사·기권·대기)와 성격 가중만 남긴다.
- 선행 조건: 실게임 판단 스냅샷 저장·재생 도구. 점수 변경 전후를 같은 입력으로 비교한 뒤 진행한다.
- 예: 0턴 루카리오 교체는 확정 기술(매지컬샤인) 기준으로도 체력이 절반 미만으로 남아 제외가 타당하다. 그런데 점수가 이 위험을 반영하지 못해 1위가 된다.

## [2026-09-27 03:10] 실게임 로그 분석 — battle `435db617` (02:25~, 18턴)

근거: `cobblemon-dev/logs/latest.log`의 `[BetterAI Trace]`. 코드 수정은 아직 없다.

- **Selector가 1위 교체를 뺌 (0턴 루카리오 106, 5턴 로즈레이드 135, 6턴 로즈레이드 195 → 모두 확률 0).** 세 건 모두 legacy 경로의 1위 `SWITCH`다. 1위가 적격이면 `bestScore`가 되어 확률이 0일 수 없으므로, 점수 차 필터가 아니라 `LocalWeightedActionSelector.canReceiveWeight`의 적격 판정에서 빠진 것이다. `execution=1.0`이므로 남는 경로는 `entryFaints` 또는 `switchIsSafeEnough`다. 가장 유력한 원인은 `switchIsSafeEnough`의 1위 교체 규칙이다: 최고점과 199점 이내에 피해를 주는 잔류 행동이 있으면 `worstResponseHpRetention >= 0.50`을 요구한다. 이 값은 `LocalRecursiveLookahead`가 모든 상대 응답과 모든 행동 순서에 대해 **평탄한 최솟값**으로 계산하므로, 같은 응답을 비관 가중치로 이미 반영한 점수를 다시 거부권으로 덮는다. 추적 대상 포켓몬이 투영 상태에서 조회되지 않으면 `?: 0.0`으로 HP 0이 되는 경로도 있다. 로그에 retention 값이 없어 둘 중 어느 쪽인지는 미확정이다.
- **네이티브는 0턴에 한 번 실패한 뒤 전투 끝까지 돌아오지 않았다.** 0턴 실패 뒤 `nativeProductState=null`이 되어 1~17턴은 모두 `NOT_APPLICABLE`, 전부 동결된 수제 투영기로 판단했다.
- **0턴 `ROOT_ACTION_MAPPING_INCOMPLETE unmatchedNative=4`.** 제품 후보 13개(기술 4×일반/테라 + 교체 5)는 모두 매핑됐고, 네이티브 요청에만 아군 행동 4개가 더 있었다. 기술 수와 같은 4개라서 세션에서 고르지 않은 기믹(다이맥스·메가 등)을 네이티브 규칙이 허용했을 가능성이 있으나, 로그에 개수만 있어 미확정이다. 매핑 검사는 `NativeRecursiveSearch.evaluateProduct`에서 세계 계획·루트 생성 뒤에 실행돼 실패까지 4.2초가 걸렸다. 아군 행동 집합은 상대 가설과 무관하므로 첫 루트에서 한 번만 검사하면 된다.
- **4턴 13.47초는 탐색 자체가 원인이 아닐 가능성이 높다.** 02:27:05에 `Can't keep up! ... 10120ms behind`가 찍혀 서버 전체가 약 10초 멈췄다. 같은 세션에서 02:22~02:28 사이 멈춤이 10회(최대 36초) 있었고, 네이티브 규칙 준비에 56초가 걸렸다. GC·메모리 압박 등 프로세스 전체 원인을 먼저 확인해야 한다.
- 로그의 `components=[]`는 점수 분해가 아니라 더블 복합 행동의 구성 ID(`componentActionIds`)라서 싱글에서는 항상 비어 있다.

## [2026-09-27 02:35] 이슈 — 네이티브 실패 시 레거시 탐색 폴백이 결정 문서와 충돌

- `36cb2641`부터 `LocalTacticalBrain`은 네이티브 판단이 `PLAN_FAILED`·`RECONCILIATION_FAILED`·`SEARCH_FAILED`이면 예외를 올리지 않고 경고 로그와 `native_fallback_*` 태그만 남긴 뒤 수제 탐색으로 판단한다.
- `b8fa5683`도 Showdown 워커가 최대 10초 안에 준비되지 않으면 로컬 탐색으로 넘어간다.
- [`NATIVE_SHOWDOWN_SIMULATION.md`](docs/architecture/NATIVE_SHOWDOWN_SIMULATION.md) §1.1은 같은 판단을 구형 수제 투영기로 조용히 다시 실행하는 것을 금지(MUST NOT)한다.
- 결정 필요: 정책 변경으로 문서를 개정할지, 임시 조치로 보고 되돌릴지 빡대리님이 정한다.
- **해결(2026-09-27):** 빡대리님이 경험 우선으로 폴백 유지를 결정했다. `NATIVE_SHOWDOWN_SIMULATION.md` §1.1을 폴백 필수·로그 필수로 개정했다. 한 번 폴백한 뒤 네이티브가 전투 끝까지 돌아오지 않는 문제는 별도 과제로 남는다.

## [2026-09-27 02:35] 이슈 — 실게임 검증 미완료

- 최근 기록은 모두 빌드·테스트 통과와 `cobblemon-dev` JAR 설치 확인이다.
- 새 JAR로 첫 판단이 `native_showdown_initial`에 도달하는지(LIVE-01, LIVE-08), 보스가 3깊이를 완주하는지(LIVE-05, LIVE-07)는 아직 확인하지 않았다.
- `2026-09-26 12:03`~`2026-09-27 00:47`의 코드 커밋은 합격표에 반영되지 않았다. 아래 해당 항목은 커밋 메시지만 근거로 적었다.

## [2026-09-27 02:35] 문서 정리

- 루트 `docs/BETTER_AI_*.md`와 모듈 문서를 `docs/{architecture,inference,public-facts,router,behavior}/`로 옮기고 파일명에서 `BETTER_AI_` 접두어와 `_DECISION` 접미어를 뺐다. 인덱스는 [`docs/README.md`](docs/README.md)다.
- 루트 `docs/`는 `.gitignore`의 `/docs/` 규칙으로 비공개였다. 빡대리님 결정에 따라 옮긴 문서는 공개 저장소에서 추적한다. 옮기기 전에 API 키·로컬 경로를 검사했고 `DESIGN.md`의 `SERVER_OPERATOR_SECRET` 예시 외에는 없었다.
- 삭제(git 미추적 파일이라 복구 불가): `BETTER_AI_WORK_CONTEXT.md`, `BETTER_AI_HUMANLIKE_REASONING_PLAN.md`, `BETTER_AI_HYBRID_REVIEW_AND_VALIDATION_PLAN.md`, `BETTER_AI_15_SECOND_ROUTER_DEADLINE_DECISION.md`.
- 병합: `BETTER_AI_ROUTER_DECISION_JOURNAL_DECISION.md` → `docs/router/ROUTER_DECISION_SUMMARY.md` §6. 15초 결정의 종료 조항 → `docs/router/ROUTER_DEADLINE.md` §2.
- `NATIVE_SHOWDOWN_SIMULATION.md` §7 뒤의 배포·검증 경과 문단은 아래 2026-09-26 항목으로 옮겼다.
- `docs/behavior/HUMANLIKE.md` 상단에 후속 결정이 대체한 조항 목록을 추가했다.
- 루트 `docs/PROJECT_STATUS.md`의 Better AI 과거 기록(2026-09-05~09-24)을 삭제하고 이 파일을 가리키게 했다.

## [2026-09-27 02:35] 구 문서에서 옮긴 유효 규칙과 함정

삭제한 `BETTER_AI_WORK_CONTEXT.md`, `BETTER_AI_HUMANLIKE_REASONING_PLAN.md`, `BETTER_AI_HYBRID_REVIEW_AND_VALIDATION_PLAN.md`에서 아직 유효한 내용만 옮겼다.

**폐기한 방향 — 다시 검토하려면 `BRAIN_OWNERSHIP.md`를 명시적으로 Obsoletes하는 후속 결정이 필요하다.**
- "중요 결정 지점"만 Router를 호출하고 나머지는 로컬이 외부 계획을 집행하는 방식
- 로컬 점수 상위 후보만 Router에 보내는 방식, 비용 절감을 이유로 Router 호출 수나 컨텍스트를 줄이는 방식
- temperature나 무작위성을 사람다움의 주 수단으로 쓰는 방식
- 계산기가 `localRank`·`expectedUtility`·`recommendedAction` 같은 판단 필드를 제공하는 방식
- 늦은 Router 응답을 기억에만 반영하는 방식
- 원시 사건의 인접 순서를 인과관계로 단정하는 방식

**확인된 함정**
- `BattleObservedEventKind` 같은 공개 enum 중간에 값을 넣으면 JVM ordinal이 바뀐다. 새 값은 끝에 추가하고 순서를 테스트한다.
- Showdown은 miss 하나에 `move [miss]`와 `-miss`를 모두 보낼 수 있다. 같은 실패를 두 표본으로 세지 않는다.
- `-crit`·`-supereffective`·`-resisted`·`-immune`·`-hitcount`는 대상만 명시하므로 원인 사용자를 자동 확정할 수 없다.
- 숨은 세트만 바꾼 쌍둥이 관측에서 계산 자료와 Router 요청이 같아야 한다. 다르면 정보 치팅이다.
- 모델 메타데이터 조회 실패는 Router 호출 실패가 아니다. `reasoning`만 빼고 같은 요청을 보낸다.
- 설정은 `JsonObject`에서 명시적 생성자로 읽어 역직렬화 뒤에도 HTTPS·스키마·시간·요청 제한을 다시 검증한다. `BetterAiConfig.toString()`은 API 키를 `<redacted>`로 표시한다.
- 테스트 통과, JAR 생성, 서버 배치, 서버 기동, 실게임 검증, AI 품질 검증은 서로 다른 상태로 보고한다.
- `LocalWeightedActionSelector`의 seed는 `comparisonValue.toBits()`를 섞는다. 작은 점수 차이도 추첨을 바꾸므로 선택 변화만으로 순위 역전이나 실력 향상을 주장하지 않는다. 진단에는 고정 seed 재생(`replayChoiceSeed`)을 쓴다.
- 시간 예산으로 잘리는 탐색은 머신 속도에 따라 결과가 흔들린다. 자가대전 1~2%p 차이는 결론으로 쓰지 않는다.

**수제 탐색 단계(2026-08-27~09-07)의 기각·미채택 상태** — 네이티브 전환 뒤 재측정하지 않았다.
- 기각: 구조 역전 `searchAuthority=1.0`(42.1%), 리프 가중치 재조정(42.1~48.3%), 가지치기 강화(48.6%), 선공 KO 가산점, 메가를 변신 전 능력치로 근사, 더블 노드 예산 증액(3배 비용), 더블 확률 분기 축소(깊이 변화 0), 급소 확률 반영(모든 확정 생존이 사라짐).
- 기본 false로 남은 실험 옵션: `revalidateUnsearchedRootLeaders`, `revalidateRootChoicePool`, 팀 대응 범위(`leafTeamCoverageWeight=0`).
- 정정(2026-09-27 코드 확인): 구 문서의 "가설 기술 기본 false"는 낡은 기록이다. 현재 `LocalDecisionTuning.CURRENT`는 `lookaheadMoveHypotheses=true`, `hypotheticalMoveLimitPerSlot=4`, `hypotheticalPriorityReservation=CONDITION_GROUPS`다.
- 채택: 말단 피해를 대상 잔여 HP로 제한(`capLeafDamageToRemainingHp=true`).
- native 정책 40전 비교(CAP3 대 full 22:18, 팀 대응 24:16, 우선도 그룹 23:17, 피해 제한 22:18)는 모두 Hoeffding 구간상 우월성을 확정하지 못했다.

## [2026-09-27 00:47] Cobblemon Normal 폼 정규화 — `9dbf30fd`

- Cobblemon 표준 폼이 `spiritombnormal`처럼 오는 경우 공개 종 + `normal`과 정확히 일치할 때만 Showdown 종 ID(`spiritomb`)로 바꾼다. `rotomheat` 같은 이름 있는 폼은 유지한다.
- 적용 위치: `NativeInitialBattleDefinitionCompiler.nativeSpeciesId`, `NativeInitialProductWorldPlanner`.

## [2026-09-27 00:21] Showdown 디스크 스냅샷 회피, 워커 준비 대기 — `b8fa5683`

- `NativeShowdownRuntimeService`가 규칙 세대 준비 시간(`rules_ms`·`worker_ms`)을 로그에 남긴다.
- 서버 시작·데이터팩 재적용 직후 준비 중인 세대가 있으면 판단당 최대 10초 기다린다. 넘으면 로컬 탐색으로 넘어간다(위 폴백 이슈 참고).

## [2026-09-26 17:20] 보스 랭크업 수순 연장 — `3f499e5c`

- 커밋 메시지 기준: 공격 수순이 탐색 예산 안에 들어올 때만 보스의 랭크업 수순을 연장한다.

## [2026-09-26 17:14] 미래 교체 후보 순위 — `be8c2e5c`

- 커밋 메시지 기준: 제한된 미래 교체 후보를 공개 타입 대응 범위와 진입 피해로 순위화한다.

## [2026-09-26 17:10] 상대 응답 정렬 — `5b2cb085`

- 커밋 메시지 기준: 관측된 상대 경향으로 네이티브 응답 탐색 순서를 정하되 선택지는 버리지 않는다(`NativeOpponentResponseOrdering`).

## [2026-09-26 17:02] 턴 번호 정렬 — `e326e4a5`

- 네이티브 Showdown 턴을 0부터 세는 공개 전투 턴과 맞췄다. 조정기·재생기·루트 검증기·행동 순서 조건화에 반영했다.

## [2026-09-26 15:27] 탐색 범위 축소와 폴백 — `36cb2641`

- 네이티브 판단 실패 시 예외 대신 레거시 탐색으로 넘어가도록 바꿨다(위 이슈).
- 실패 진단에 `failedRunStatus`·`failedRunDetail`을 추가했다.
- 상급 난도는 미래의 아군 자발 교체를 탐색에서 뺀다(`excludeFutureAllyVoluntarySwitches`).
- 초기 네이티브 판단은 `NativeOpeningStateRules.acceptsObservations`를 통과한 턴 0~1 상태에만 적용한다.

## [2026-09-26 14:29] 테스트 로그에 선택 확률과 탐색 진단 출력 — `238fb166`

- `AiTestDecisionTrace`와 `watch-ai-log.ps1`에서 후보별 선택 확률과 탐색 진단을 보여 준다.

## [2026-09-26 14:11] 세계 후보 조립 제한 — `f9cf3815`

- 커밋 메시지 기준: 후보 조립량을 제한하면서 네이티브 세계의 선택지는 보존한다.

## [2026-09-26 13:48] 자발 교체 후보 제한 — `0dabd47c`

- 커밋 메시지 기준: 네이티브 후속 탐색에서 자발 교체 선택지 수를 제한한다.

## [2026-09-26 13:20] 난도별 상대 IV·EV 가정 — `d96f5ad3`

- `LocalOpponentStatAssumption`을 추가해 난도별 상대 개체값·노력치 가정을 적용한다.

## [2026-09-26 13:18] 확정 승리 우선과 랭크업 효과 보존 — `5de727d3`

- `LocalTerminalOutcomeValue`를 추가해 확정 승리 분기를 우선하고, 계산 가능한 랭크업 효과를 보존한다.

## [2026-09-26 12:57] 반동 가격 조정 — `f8fcf405`

- 반동을 준 피해의 절반 HP로 가격 매기되 자기 기절은 숨기지 않는다. `branch-engine.cjs`와 투영기에 반영했다.

## [2026-09-26 12:45] 반복 회복 감점 제거 — `0b1cdf6e`

- 회복 가치는 실제 HP 회복량만 따른다. `LocalRecursiveActionHistory`의 반복 회복 감점을 제거했다.

## [2026-09-26 12:03] 가중 선택의 확실한 1위 우대 — `ebddfe87`

## [2026-09-26 11:52] 면역 기술 완화, 유효한 랭크업 보상 — `19e10aa6`

- `LocalSetupMovePreference` 추가, `LocalPublicMechanicsKernel`·`LocalTacticalScorer` 수정.

## [2026-09-26 11:20] AUD-09 클라이언트 설치

- 반올림된 공개 HP의 모든 지원 내부 세계를 유지하는 Better AI 1.2.17 JAR을 `cobblemon-dev/mods`에 설치했다.
- JDK 21 `jar --validate`와 빌드·설치 파일 SHA-256 `5F5724E7124FFCD3D5368CE4AC9404962386694EC09C8A03725BCFD172C4E2DF` 일치를 확인했다.
- 직전 JAR(`962E6107…`)은 `codex-deploy-backups/20260926-rounded-hp-posterior`에 보존했다. 클라이언트는 설치 시 꺼져 있었다. 서버 JAR은 바꾸지 않았다.
- 설치 검증일 뿐이며 재실행·실게임 행동 검증은 아니다.

## [2026-09-26 11:19] AUD-09 확률 보존 — `a6e5f714`

- 공개 행동이 특정되지 않은 상대 후보 둘 중 하나만 관측과 맞는 세계 A와 둘 다 맞는 세계 B(각 사전확률 1/2)에서, 기존 조정은 A/B를 다시 1/2씩 나눴다. 관측 뒤 올바른 값은 1/3·2/3이다.
- 실패 회귀를 추가하고 탈락한 행동의 확률을 살아남은 행동에 다시 나누지 않도록 고쳤다. `Native` 묶음 268개 통과.
- 균등 행동 사전확률은 사용률 기반 상대 정책이 없는 현재의 임시 가정이다.

## [2026-09-26 07:07] LIVE-04 사건 회귀 갱신 — `a9574fba`

- 합성 HP 사건을 번들 Showdown의 실제 `move`·`-status`·`-heal`·`-damage` 로그 변환 사건으로 바꿨다. 첫 턴 맹독, 둘째 턴 먹다남은음식 회복과 맹독 피해가 별도 사건인 상태에서 세션 조정 2회 통과.
- 공개 HP는 정수 퍼센트다(내부 `0.940594…` → 공개 `0.95`). 실제 Cobblemon 관측기·6대6 실전 증거는 아니다. LIVE-04는 OPEN.

## [2026-09-26 06:51] LIVE-04 세션 후속 검증 — `fdebf20d`

- 상대의 실제 `toxic` 사용을 공개 사건으로 전달하고, `NativeProductSessionReconciler`가 다음 두 판단에서 맹독 상태와 HP가 일치하는 스냅샷을 보존하는 회귀를 추가했다. 첫 판단 입력에서는 상대 미공개 정보를 제거했다.
- 사건 목록은 테스트가 구성한 축약 사건이었다. 6대6 실제 세션과 독 단계가 높아진 다턴 조정은 미검증.

## [2026-09-26 06:40] LIVE-04 추가 검증 — `b7517a47`

- `NativeMiloticToxicRecoveryTest`: 번들 Showdown에서 상대 `toxic` 뒤 `mirrorcoat`·`recover`로 턴을 이어 맹독 카운터 증가를 확인했다. 늦은 단계에서 `NativeRecursiveSearch`와 `NativeInitialProductDecisionEvaluator`가 3깊이를 완료했고, `LocalTacticalBrain` 추첨 후보에서 HP회복이 빠졌다.
- 첫 실행은 테라·일반 HP회복을 `single`로 묶은 테스트 오류로 실패했다. 합성 2대1 상태의 회귀이며 원래 6대6 실전의 해결 증거가 아니다.

## [2026-09-26 06:20] 첫 턴 HP 이득 평가 포함 JAR 재설치 — `dd6bcfb4`

- Better AI 1.2.17 JAR을 `cobblemon-dev/mods`에 다시 설치했다. SHA-256 `962E610799DEE9D9AD983D8E3F61E230324A11F32469D89010CC6B025288FB49` 일치, 직전 JAR은 `codex-deploy-backups/20260926-native-recovery-tempo`.
- 설치 검증일 뿐이다.

## [2026-09-26 06:19] LIVE-02 후속 회귀 — `97f626a3`

- 테스트의 난천 토게키스가 실제 `/mbc test ai-boss` 세트와 달랐다(방어 EV 252·비행 테라 누락). HP/스피드 252·비행 테라로 고치자 90%대 HP의 날개쉬기가 추첨 후보에 들어오는 실패가 재현됐다.
- 원인: 3깊이 탐색이 마지막 판세만 봐서 즉시 공격과 회복 후 공격의 이득 실현 시점을 구별하지 못했다. 중간 턴 판세와 루트 첫 턴의 실제 HP 변화를 반영하되 KO 생존 보너스는 중복 계산하지 않도록 고쳤다(`37be997f`).
- 네이티브 묶음 257개 통과. 카푸브루루 숨은 기술 두 칸, 실제 6대6, 새 JAR 게임 내 행동은 미재현. LIVE-02는 OPEN.

## [2026-09-26 05:42] LIVE-02 점수 분해 — `ea38838b`

- 보존 로그 `2026-09-26-7.log.gz`: 5턴 날개쉬기 `112.383`과 공격 3개의 `89.298` 차이 `23.085` = 기본점 차이 15 + 탐색 차이 `8.085`. 10턴도 기본점 15 + 탐색 `8.199`.
- 공격 3개가 같은 기본점·탐색점을 받은 것은 요청 표시 이름으로 기술 상세를 조회해 공백 있는 기술을 놓친 결함(LIVE-06)과 일치한다.
- 같은 로그의 HP회복은 3턴 `140.506`이지만 열탕이 `188.291`로 더 높았고 4턴에는 `12.035`였다. HP회복에 상시 고정 보너스가 있다는 근거는 아니다. 과거 레거시 경로 분석이다.

## [2026-09-26 05:35] 네이티브 탐색 가지치기·캐시 상한 포함 JAR 설치 — `08af326d`

- Better AI 1.2.17 JAR을 `cobblemon-dev/mods`에 설치했다. SHA-256 `D59F73B52020ED83650ED0B422351A8C768601979DE08CEC028DD1DFFC60740E` 일치, 이전 JAR은 `codex-deploy-backups/20260926-native-search-bound`.
- 설치 검증일 뿐이다.

## [2026-09-24 20:19] 네이티브 Showdown 전환 착수 — `8f7bc71c`

- 격리 Showdown 분기 엔진을 추가하며 수제 상태 전이에서 네이티브 시뮬레이션으로 전환을 시작했다. 계약은 [`NATIVE_SHOWDOWN_SIMULATION.md`](docs/architecture/NATIVE_SHOWDOWN_SIMULATION.md).
- `PublicSingleTurnProjector`·`LocalSwitchStateProjector`는 이날부터 새 규칙을 추가하지 않는 동결 코드다.

## [2026-09-24 07:39] 싱글·더블별 기술 보유율 표 — `82039afd`

- Pokemon Showdown 2025-12 Regulation J 1500 자료를 형식별 고정 스냅샷으로 JAR에 넣었다. 싱글 BSS 26,139전·407종·8,673기술, 더블 VGC 200,077전·436종·16,799기술. 런타임 다운로드는 없다.
- 네 기술의 결합 분포가 아니므로 응답 선택 확률에 곱하지 않고, 미공개 분기를 슬롯당 최대 3개까지 고르는 데만 쓴다.
- 같은 규정 전월 자료와 상위 3개 비교: 공유 종 중 2개 이상 유지가 싱글 69.4%, 더블 85.4%.
