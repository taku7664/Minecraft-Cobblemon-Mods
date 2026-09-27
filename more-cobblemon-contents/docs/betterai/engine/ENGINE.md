# AI 전투 엔진

Better AI가 수를 읽을 때 쓰는 자체 전투 엔진이다. 실시간으로 Showdown을 호출하지 않고, Showdown의 전투 코드를 Kotlin으로 그대로 옮겨서 JVM 안에서 돌린다. Showdown은 테스트에서 결과를 비교하는 심판으로만 쓴다.

## 왜 옮겨 왔는가

- 게임에 번들된 Showdown(GraalJS)은 인터프리터로만 돌아서 분기 하나에 약 49ms가 걸린다. 실게임에서는 네이티브 탐색이 한 번도 판단을 끝내지 못했다.
- 수제 계산기는 빠르지만 규칙을 더할수록 틀려도 알아챌 방법이 없어서 동결되어 있었다.
- 옮긴 엔진은 JVM에서 빠르게 돌고, 같은 시드를 주면 Showdown과 로그가 한 줄도 다르지 않아야 한다는 기준으로 검증한다. 규칙을 더해도 틀리면 테스트가 바로 잡는다.

## 구조

`src/main/kotlin/jbro/cobblemon/mcc/betterai/engine/`

| 경로 | 내용 |
|---|---|
| `sim/Battle.kt` | 이벤트 시스템(`runEvent`, `singleEvent`, 핸들러 정렬), 턴 진행, 피해·회복·능력 변화 |
| `sim/BattleActions.kt` | 기술 사용 흐름: 명중, 면역, 급소, 피해 계산, 부가효과, 교체, 메가진화, 테라스탈, 다이맥스 |
| `sim/Pokemon.kt`, `Side.kt`, `Field.kt`, `Queue.kt` | 포켓몬, 진영(선택 입력 포함), 필드, 행동 큐 |
| `sim/Prng.kt` | Showdown과 비트 단위로 같은 난수 생성기 |
| `dex/` | Showdown 데이터(기술, 특성, 도구, 상태 조건, 도감, 상성표) |
| `hooks/EngineHooks.kt` | 훅 등록 구조. 핸들러는 `condition:par`, `move:protect/condition`, `ability:intimidate`, `item:leftovers` 같은 키로 찾는다 |
| `effects/` | 옮긴 핸들러. 기본 상태 조건은 `BaseConditions.kt`, 나머지는 사용률 순으로 나눠 옮긴다 |

옮긴 기준은 개발 서버가 실제로 돌리는 Showdown이다. Cobblemon과 Mega Showdown이 고친 부분(알파 보정, uuid가 들어간 로그, 메가진화 해제, 9세대 다이맥스, 테라스탈 자속 보정)도 그대로 따른다. 9세대 규칙만 옮기며, Z기술은 제외한다.

## 데이터

`tools/ai-engine/export-dex.sh`가 개발 서버의 Showdown을 읽고, Mega Showdown이 실행 중에 등록하는 도구와 특성(메가스톤 88개 등)까지 반영해서 `src/main/resources/ai-engine/dex.json.gz`를 만든다. 코드로 된 필드(핸들러)는 데이터에서 빠지고 항목별 `hooks` 목록으로 남는다. 엔진은 이 목록으로 "Showdown에는 있는데 아직 옮기지 않은 핸들러"를 알아낸다.

```bash
bash more-cobblemon-contents/tools/ai-engine/export-dex.sh
```

옮기지 않은 핸들러에 도달하면 엔진은 그 핸들러를 조용히 건너뛰지 않고 `battle.missingHooks`에 기록한다.

## 심판 테스트

`tools/ai-engine/referee.cjs`가 같은 팀, 같은 시드, 같은 선택으로 Showdown에서 전투를 돌리고, 테스트가 엔진에서 같은 전투를 돌려 프로토콜 로그를 한 줄씩 비교한다.

- `EngineCoreParityTest`: 피해·급소·교체·기절·상태이상·동속·테라스탈·메가진화·다이맥스·더블배틀처럼 엔진 핵심을 다양한 시드로 비교한다.
- `EngineSweepTest`: 구현 현황 표의 모든 항목을 사용률 순으로 일반 전투에 넣어 비교한다. 선언한 핸들러를 모두 옮겼고 로그가 모두 같으면 통과이며, 통과한 항목은 커버리지 파일에 기록되어 표에서 노란색이 된다. `sweep-baseline.txt`에 있는 항목이 실패하면 테스트가 깨진다(후퇴 방지).

워크트리에는 개발 서버가 없으므로 경로를 직접 넘긴다.

```bash
./gradlew :more-cobblemon-contents:unitTest -Pscope=engine -PshowdownRoot=<저장소>/dev-server/showdown
```

한 묶음만 빠르게 확인할 때는 `-PsweepOnly=<id 목록 파일>`을 붙인다. 결과는 `build/reports/ai-engine-sweep.md`(실패 항목과 첫 차이), `ai-engine-sweep-pass.txt`(통과 목록)에 남는다.

주의할 점: 일반 전투에서 효과가 한 번도 발동하지 않을 수도 있다. 그래서 통과는 "옮긴 코드가 있고, 넣어 본 전투에서 Showdown과 달라지지 않았다"는 뜻이다. 조건이 까다로운 효과는 전용 시나리오를 추가해서 확인한다.

## 핸들러 옮기는 법

1. `node tools/ai-engine/show.cjs <showdown 경로> moves protect` 로 원본 핸들러를 본다.
2. `effects/` 아래 파일에 같은 동작을 적는다. 인자는 Showdown과 같은 위치 순서로 `relay`, `target`, `source`, `effect`에 들어온다. JS의 `undefined` 반환은 `Unit`, `null`은 `null`이다.
3. 우선도(`onXPriority`, `onXOrder`)와 상수 핸들러는 데이터에서 자동으로 읽으므로 함수 본문만 옮긴다.
4. 로그 문자열과 난수 호출 순서를 원본과 똑같이 유지한다. 비교 테스트는 이 두 가지로 차이를 잡는다.
5. 전수 검사를 돌려 통과를 확인하고, 통과 목록을 `sweep-baseline.txt`에 반영한다.

## 작업 순서

`tools/ai-engine/priority.cjs`가 표와 같은 기준으로 항목을 고르고 Smogon 사용률(BSS·VGC 2025-12) 순으로 정렬해 `src/test/resources/ai-engine/priority.json`을 만든다. 핸들러는 이 순서대로 옮긴다.
