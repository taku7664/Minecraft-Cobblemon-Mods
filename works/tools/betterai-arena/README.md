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

화면은 DS 디아루가·펄기아 전투 화면 구성을 따르고, 바깥은 위키의 `ds_window` 스타일과 Galmuri 폰트다.

| 자리 | 내용 |
|---|---|
| 윗화면 | 상대 HP 창(왼쪽 위), 상대 포켓몬(오른쪽 위), 내 포켓몬 뒷모습(왼쪽 아래), 내 HP 창(오른쪽, 실제 HP·랭크), 메시지 창(아래) |
| 아랫화면 | 몬스터볼 줄, 싸운다·가방·도망간다·포켓몬. 싸운다는 타입 색 기술 4개(PP)와 다이맥스 등 켜기, 포켓몬은 교체 목록. 가방·도망간다는 게임처럼 막혀 있다 |
| 결과 | 두 화면 아래. 직전 턴부터 지금까지의 일. 전체 로그는 접어 둔다 |
| 보스가 본 후보 | 보스의 지난 결정: 상위 후보, 기본 점수, 규칙별 가감, 추첨 확률, 제외 사유. 안 쓴 기술도 보인다 |

키보드: 방향키로 아랫화면 버튼 사이를 위치대로 옮기고, Z·Enter 고르기, X·Esc 취소, 1~4 기술, D 메가진화·다이맥스 켜기(누를 때마다 바뀜).
스프라이트는 Pokemon Showdown 서버에서 바로 불러온다(gen4, 없으면 gen5).

- 나는 p1, 보스는 p2다(게임과 같은 조건: 검색 없음, 팀 미리보기, 보스의 정확한 배분).
- **엔트리:** 페이지의 「엔트리」 패널에서 양쪽 팀을 정한다. 각 팀은 1~6마리다.

  | 고를 수 있는 것 | 내용 |
  |---|---|
  | 무작위 3마리 | Battle Factory 렌탈 세트(1964개)에서 종족이 겹치지 않게 뽑는다 |
  | 하드 신오 | 리그 하드 체육관·사천왕·챔피언 13명의 팀. 메가진화(메가스톤), 다이맥스 안 함 |
  | 하드 가라르 | 리그 하드 체육관·챔피언컵·챔피언 15명의 팀. 다이맥스(담당 포켓몬·거다이맥스 그대로) |
  | 직접 입력 | Showdown 형식 텍스트. 이름은 한국어·영어 둘 다 읽는다 |

  - 고른 팀은 텍스트로 펼쳐져서 기술, 도구, 레벨, 노력치를 바로 고칠 수 있다. 고친 내용은 그 기기 브라우저에 남는다.
  - 프리셋은 `arena.py play-page`가 이 체크아웃의 리그·Battle Factory 소스에서 만든다. 레벨은 리그 데이터 그대로다(하드 101~105).
  - 엔트리 텍스트의 추가 줄은 `거다이맥스: 예`, `다이맥스 우선: 1`, `다이맥스: 안 함`이다.
- **기믹:** 기술 화면 왼쪽 위 주황 버튼(키보드 D)으로 고른다. 그 포켓몬이 쓸 수 있는 것만 나오고, 누를 때마다 끔 → 메가진화 → 다이맥스로 바뀐다. 메가진화는 메가스톤을 든 포켓몬, 다이맥스는 `다이맥스: 안 함`이 아닌 팀에서 한 번이다. 테라스탈·Z기술은 대전 브리지가 내주지 않는다.
- 보스 세트는 판이 끝날 때까지 종족만 보낸다.
- 판마다 `F:/AI/betterai-arena/play/<시각>/battle/`에 `decisions.jsonl`(보스 쪽), `result.json`, `referee-input.json`이 남는다.
- **턴 결과:** 차례가 끝나면 그 사이 일어난 일이 윗화면 메시지 창에 한 줄씩 나오고, 장면(교체, HP, 쓰러짐)도 줄마다 바뀐다. 한 줄에 1.3초, 화면을 탭하거나 Z·Enter를 누르면 다음 줄, Esc·X를 누르면 끝까지 넘어간다. 재생 중에는 메뉴가 잠긴다.
- **지난 판:** 페이지 아래 「지난 판」에 저장된 모든 판(시각, 양쪽 팀, 승·패·중단·진행 중, 턴)이 나온다. 누르면 턴별 로그와 그 턴에 보스가 본 후보, 두 팀 세트를 보여 주고, 기록 파일 3개를 휴대폰으로 받을 수 있다. 중단된 판은 보스 결정 기록에 든 로그로 연다. 두는 중인 판은 끝나야 열린다(보스 세트를 가리려고). 서버 경로는 `api/records`, `api/record?id=`, `api/record-file?id=&name=`이다.
- 모드 JAR은 `arena.properties`의 `playJar`다. 실행부와 같은 `main`에서 빌드한 것이어야 한다. 개발 서버 JAR이 더 오래됐으면 `NoSuchMethodError`가 난다.
- 서버는 `play.html`에 폰트와 Showdown 기술 타입·스프라이트 이름을 넣은 `play/page.html`을 요청마다 읽는다. `play.html`을 고친 뒤 `python works/tools/betterai-arena/arena.py play-page`만 돌리면 서버를 다시 켤 필요가 없다. 서버 코드(`BetterAiPlayServer.kt`)를 고치면 실행부를 다시 만들고 다시 켠다.
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
