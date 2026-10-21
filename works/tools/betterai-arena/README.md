# Better AI 아레나

실제 모드 JAR로 보스 대 보스 자가 대전을 N판 돌리고, 판마다 기록 파일을 남기고, 휴대폰에서 볼 수 있는 뷰어를 만든다.
게임 조건(검색 없음, 상대 미리보기 켬)으로 돌고, 판 수만큼 프리셋 팀 짝을 시드로 뽑는다.

## 한 번 돌리기

```bat
works\tools\betterai-arena\run-arena.bat 50
works\tools\betterai-arena\run-arena.bat 10 --threads 3 --seed 1234 --jar F:\some\more-cobblemon-contents-0.1.0.jar --commit 94bbcf321 --note "교체 점수 수정 후"
```

| 인자 | 기본값 | 뜻 |
|---|---|---|
| 판 수 | 필수 | 돌릴 판 수(프리셋 팀 짝 수) |
| `--threads` | `arena.properties`의 `threads`(3) | 동시에 돌릴 판 수 |
| `--seed` | 무작위 | 팀 짝을 뽑는 시드. 같은 시드면 같은 짝이 나온다 |
| `--jar` | `modJar`(개발 서버의 MCC JAR) | 시험할 모드 JAR |
| `--commit` | 없음 | 그 JAR을 빌드한 커밋. 알 때만 적는다 |
| `--note` | 없음 | 실행 목록에 보일 메모 |

3스레드로 6판에 약 20초 걸렸다(2026-10-21).

## 남는 파일

`F:/AI/betterai-arena/runs/<시각>/`에 실행마다 폴더가 생긴다. 이 스크립트는 기록을 지우지 않는다.

| 파일 | 내용 |
|---|---|
| `run.json` | 실행 요약: 시각, JAR 경로·SHA-256, JAR 커밋(알 때), 저장소 HEAD, 시드, 판 수, 스레드, 판마다 승자·턴·팀 |
| `arena.log` | 실행부 출력 |
| `battle-<i>/decisions.jsonl` | 판단마다 입력·보드·후보 점수 단계·추첨 확률 (판 하나에 수 MB) |
| `battle-<i>/result.json` | 결과와 전체 공개 로그 |
| `battle-<i>/referee-input.json` | 두 팀 구성과 대전 시드 |
| `viewer-data.json`, `viewer.html` | 뷰어용 요약과 그 실행만 담은 독립 HTML |

`runs/index.json`이 전체 실행 목록이다. `F:/AI/betterai-arena/site/`에는 Artifact로 올릴 `index.html`(실행 목록 포함)과 `data/<시각>.json`이 생긴다.
엔진 작업 사본(`engine/`)과 JVM 임시 폴더는 기록이 아니라서 실행이 끝나면 지운다.

## 뷰어

실행 목록 → 판 목록(승자, 턴, 팀) → 판 하나를 턴별로 본다. 판단마다 지난 턴의 공개 로그, 그 진영이 본 두 보드, 상위 후보 6개(기본 점수, 규칙별 가감, 최종 점수, 추첨 제외 사유), 추첨 확률과 고른 행동이 나온다.
포켓몬·기술·특성·도구 이름은 Cobblemon JAR의 `ko_kr.json`에서 가져온다.

`python works/tools/betterai-arena/arena.py viewer`로 모든 실행의 뷰어를 다시 만든다.
Artifact 페이지(https://claude.ai/artifact/RR13L7EUx4cKCfKz9mA9zJ)는 `site/`를 Claude 세션의 스크래치패드로 복사한 뒤 같은 URL로 다시 올린다. 새 실행은 `data/<시각>.json`만 더하면 된다.

## 휴대폰으로 보스와 직접 붙기

마인크래프트를 켜지 않고 휴대폰 브라우저에서 보스 AI와 3대3 싱글을 둔다. 대전은 이 PC에서 돌고, cloudflared 터널이 공인 https 주소를 붙인다.

```bat
works\tools\betterai-arena\play-server.bat
```

창을 띄워 두는 동안 서버가 돈다. 시작하면 PC 주소와 휴대폰 주소를 출력하고 `F:/AI/betterai-arena/play/server.json`에도 적는다. 켤 때마다 터널 주소와 비밀 토큰이 새로 나오고, 토큰이 틀린 주소는 404다.

| 화면 | 내용 |
|---|---|
| 필드 | 보스 쪽 포켓몬(HP%, 상태, 랭크, 드러난 특성·도구·기술)과 내 포켓몬(실제 HP), 양쪽 남은 팀 |
| 버튼 | 기술(PP), 다이맥스 등 특수 행동, 교체. 쓰러진 뒤에는 교체만 |
| 로그 | 직전 턴부터 지금까지. 전체 로그는 접어 둔다 |
| 보스가 본 후보 | 보스의 지난 결정: 상위 후보, 기본 점수, 규칙별 가감, 추첨 확률, 제외 사유. 안 쓴 기술도 보인다 |

- 두 팀은 프리셋에서 무작위로 뽑고, 나는 p1, 보스는 p2다(게임과 같은 조건: 검색 없음, 팀 미리보기, 보스의 정확한 배분).
- 보스 세트는 판이 끝날 때까지 종족만 보낸다.
- 판마다 `F:/AI/betterai-arena/play/<시각>/battle/`에 `decisions.jsonl`(보스 쪽), `result.json`, `referee-input.json`이 남는다.
- 모드 JAR은 `arena.properties`의 `playJar`다. 실행부와 같은 `main`에서 빌드한 것이어야 한다. 개발 서버 JAR이 더 오래됐으면 `NoSuchMethodError`가 난다.
- 페이지(`play.html`)는 요청마다 디스크에서 읽으므로 고쳐도 서버를 다시 켤 필요가 없다. 서버 코드(`BetterAiPlayServer.kt`)를 고치면 실행부를 다시 만들고 다시 켠다.
- `cloudflared.exe`는 Cloudflare 공식 GitHub 릴리스에서 받아 `F:/AI/betterai-arena/tools/`에 둔다(2026-10-21 받은 2026.10.2, Cloudflare 서명 확인).

## 실행부 다시 만들기

실행부(`betterai-arena-runner.jar` + `lib/`)는 테스트 쪽 대전 코드(`EmbeddedTeamBattle`, `EmbeddedPresetAudit`, `BetterAiArena.kt`, `oracle/team-battle-bridge.cjs` 등)만 담는다. AI 본체는 고른 모드 JAR에서 로드되고, 클래스패스 맨 앞에 그 JAR이 온다.
테스트 쪽 대전 코드를 바꿨을 때, 그리고 그 코드가 부르는 AI 본체의 생성자·함수 시그니처가 바뀌었을 때 다시 만든다. 2026-10-21 `LocalTacticalBrain`의 `quickDecider` 인자를 지운 뒤 옛 실행부로 돌린 50판이 전부 `NoSuchMethodError`로 실패했다.

```bat
works\tools\betterai-arena\build-runner.bat
```

Gradle 쓰기는 모두 `F:/AI/builds/cobblemon-mods-arena`로 가고, 끝나면 그 폴더를 지운다. 설정 단계에서 Minecraft 리맵을 다시 하므로 몇 분 걸린다.
`lib/`에는 Kotlin 표준 라이브러리, gson, slf4j-api만 들어간다. 렌탈 세트는 Battle Factory 애드온 JAR, `showdown.zip`과 한국어 이름은 Cobblemon JAR(`arena.properties`의 `dataJars`)에서 읽는다.

## 경로 설정

이 PC의 경로는 `arena.properties`에 있다. 모드 JAR, Showdown 폴더, 데이터 JAR, 실행부 폴더, 기록 폴더, 저장소, `java`, 스레드 수, 힙 크기, 그리고 직접 대전용 `playJar`·`play`·`playPort`·`cloudflared`다.

## 알아 둘 것

- 개발 서버를 켜지 않는다. 개발 서버 폴더에서 JAR과 Showdown 파일을 읽기만 한다.
- `decisions.jsonl`이 판 하나에 약 3MB라서 50판이면 실행 하나가 150MB 안팎이 된다.
- JAR을 빌드한 커밋은 JAR 안에 없다. `--commit`을 주지 않으면 `run.json`의 `jarCommit`은 비어 있고, `repoHead`는 실행할 때 저장소의 HEAD일 뿐이다.
