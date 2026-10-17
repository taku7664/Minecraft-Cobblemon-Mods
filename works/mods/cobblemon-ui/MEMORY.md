# cobblemon-ui 작업 기록

## [2026-10-18 07:10] 기술 커서 기억, 연출 중 핫바 숨김, VS 기울임 복구, Z 누름 틈 막기, 대화 스킵 2배 (컴파일·단위 테스트, 실게임 미확인)

- **빡대리님 피드백:** (1) 전 턴에 쓴 기술 칸을 기억 (2) 관장 연출 때 하단 핫바 아이템·수량이 보임 (3) VS 화면이 여전히 깨지고 기울임도 사라짐, 깨짐을 못 고치면 기울임이라도 (4) 대화에서 Z를 누른 채 넘기다 끝나면 전투 메뉴가 Z를 받아 넘어가던 문제가 고쳐졌는지 (5) 대화 스킵 2배.
- **기술 기억:** `LastMoveMemory`(포켓몬 UUID → 기술 id, 64개 상한). `BattleGUI.selectAction` HEAD에서 기록하되, Cobblemon이 무시하는 선택(전투 없음·이미 답한 요청)은 거른다. `BattleMoveSelection` 생성 직후 그 기술(지금 고를 수 있을 때만)에 `KeyboardTileFocus`를 두고, 내비 믹스인은 새 메뉴의 시작 인덱스를 그 포커스에서 가져온다(`takeOpeningFocus`). 마우스를 움직이면 평소대로 포커스가 풀린다. 타깃(더블) 기억은 하지 않았다.
- **핫바:** `CinematicGuiMixin`에서 레터박스가 있거나 VS 인트로(끝난 뒤 흰 화면 포함, `BattleVersusIntro.covering`)가 덮는 동안 `Gui.renderHotbarAndDecorations`를 건너뛴다(핫바·체력·경험치). 아이템이 왜 비쳤는지(깊이 순서인지 띠가 낮아서인지)는 화면으로 확인하지 않았다.
- **VS:** 10-13 수정(불투명·이탤릭 제거) 뒤에도 깨진다는 신고. 글꼴을 크게 키우면 글리프 가장자리가 반투명이라 8방향 테두리 복사가 쌓이는 것으로 보고(추정), 글자를 `VS_GLYPHS`(15×9 칸) 채우기로 바꿨다. 칸끼리 모서리를 공유해 어떤 배율·기울임에서도 틈이 없다. 기울임은 행렬 전단(`VS_SLANT` 0.25), 그림자→테두리→글자 순으로 모두 불투명.
- **Z 누름:** 기존 `BattleConfirmGate`는 전투 대화창 줄에서 누른 Z는 막았지만 두 틈이 있었다. 기술 메뉴가 떠 있을 때 나온 장면 대사(`SceneDialogue`)에서 누른 Z는 메뉴용으로 잡혔고(이제 장면 대사 중 누른 Z는 메뉴용이 아니고, 장면이 끝나면 `released()`), "싸운다"를 Z로 고른 뒤 계속 누르면 키 반복이 새 기술 메뉴에서 기술을 냈다(Codex 리뷰 지적, 원래부터 있던 틈). 이제 전투 메뉴 확인은 물리 입력 하나당 한 번(`consumeMenuPress`)만 받는다. NPC 대화 화면에서 누른 Z는 원래부터 메뉴용이 아니다.
- **스킵 속도:** 전투 대화창 길게 누르기 반복 `HoldRepeat(400ms, 70ms)` → `(200ms, 35ms)`. 장면 대사와 NPC 대화 화면은 OS 키 반복을 그대로 쓰므로 바꾸지 않았다.
- **테스트:** `LastMoveMemoryTest` 5개 추가. Codex가 돌린 1차 `:cobblemon-ui:unitTest`는 298개 중 297개 통과. 실패한 `SceneDialogueManualAdvanceTest`는 소스를 글자로 읽어 `
`을 찾는데 이 체크아웃(`core.autocrlf=true`)의 CRLF와 맞지 않아서였고, 줄바꿈을 맞춰 비교하게 고쳤다. 리뷰 반영(`efb36991`) 뒤 Codex 재실행 298/298 통과(로그 JUnit 요약 직접 확인), Codex 재리뷰 지적 없음.
- **미확인:** 실게임(기술 커서, 핫바, VS 모양, Z 누름). 빌드 JAR 배치는 하지 않았다.

## [2026-10-16 21:52] 장면 대사 수동 종료 유지 (컴파일·단위 확인, 실게임 미확인)

- `SceneDialogue`의 페이지 자동 진행과 마지막 페이지 시간 제한 종료를 없앴다. 장면 대사는 사용자의 확인 입력으로만 다음 페이지·종료로 진행하며, 기존 `expectedMillis` 공개 시그니처는 호환을 위해 유지하고 타이핑 예상 시간만 계산한다.
- `SceneDialogue.play()`는 새 장면을 넣기 전에 `stop()`으로 이전 장면의 완료 콜백을 동기 실행한다. MCC 소비자는 이전 장면을 먼저 멈춘 뒤 새 재생 토큰을 설치하고, 완료 콜백도 자기 토큰일 때만 현재 상태를 지우도록 보완했다.
- 기존 `SceneDialogueManualAdvanceTest` 1/1에 더해 MCC `BattleSceneLifecyclePolicyTest` 5/5가 성공했고, `compileKotlin`·`compileTestKotlin`도 성공했다. 첫 컴파일 시도에서 Gradle 의존성 때문에 cobblemon-ui 중간 JAR이 F 격리 빌드 경로에 생성되어 즉시 중단했으며, 최종 검증에서는 JAR 작업을 비활성화했다. JAR 배치·클라이언트 실행·실화면 확인·커밋·푸시는 하지 않았다.

## [2026-10-16 16:00] 1cb6bc1c 개발 클라·서버 배포 (빌드·해시 확인, 서버 기동·실게임 미확인)

- 콘텐츠 테마 전환(피츄·PvP HUD·관전 버튼·대화 미리보기), 메시지 상자 공용 함수, PvP 초대·방출 확인 팝업, 원형 기믹 버튼이 들어간 `origin/main` `1cb6bc1c`를 `F:/AI/builds/deploy-1cb6bc1c`에서 빌드했다(EXIT 0, JAR 8개 `unzip -t` 정상, Kotlin in-process). 68e408b2 이후 커밋 13개는 모두 이 작업이다.
- MCC·NPC에 내장된 cobblemon-ui가 단독 JAR과 같다(SHA-256 앞 12자리 5E882E27C0B9).
- 개발 클라 8개(cobblemon-ui, NPC, MCC, 리그, 타워, 팩토리, PvP, jbro-policy), 개발 서버 7개(cobblemon-ui 제외)를 넣었고 SHA-256이 빌드본과 같다. 백업 `develop-product/deployment-backups/20261016-1cb6bc1c`(15개). 배포 때 게임·개발 서버는 꺼져 있었고 서버는 켜지지 않았다.
- PPakemon 프로필·운영 서버·VPS에는 반영하지 않았다.

## [2026-10-16 15:30] 기믹 버튼을 원형으로, 맨 아래 기술 높이부터 위로 (캡처·컴파일·단위 테스트 확인, 빌드 배치 안 함)

- **빡대리님 지시:** 기술 열 가운데부터가 아니라 아래에서 위로 쌓고, 원형 버튼으로.
- **배치:** `BattleScreenGeometry.gimmickTiles(w, h, count)` — 지름 26 원, 첫 버튼(Cobblemon 순서 첫째)이 맨 아래 기술 칸 바닥에 맞고 다음은 30px씩 위로. 기술 칸과 19px(`GIMMICK_MOVE_GAP`) 떨어진다: 포커스된 기술이 7px(조금 넘게) 튀어나오고 커서 화살표(폭 6)가 그 3px 왼쪽에 끝이 오기 때문이다. 427×240에서 x=242, y=190/160/130.
- **툴팁:** 기술 왼쪽(타입 아이콘 여백 18) 그대로 두고, 아래 끝을 기믹 버튼 맨 위보다 2px 위로 올린다(`MoveTooltipAnchor.position(columnLeft, gimmickTop, ...)`). 툴팁 폭에 따라 이름/아이콘 타일을 고르던 방식은 없앴다.
- **그리기:** 원 안에 아이콘만, 포커스·호버 때 커서 왼쪽에 이름표(높이 14, 둥근 판). 기믹에 포커스가 있으면 툴팁은 꺼져 있어 겹치지 않는다. 켜지면 선택 색.
- **키:** 기믹 열 목록이 아래부터라 ↑가 다음 인덱스다.
- **확인:** 커밋 `ee17e32f`. 개발 클라이언트 캡처(427×240·480×270·640×360)를 Claude가 직접 봤다: 버튼이 신속(맨 아래) 높이, 툴팁은 그 위, 켜면 노란 원, 포커스 때 "메가진화" 이름표. Codex가 F 복사본에서 컴파일과 `cobblemon-ui:unitTest` 292/292 통과(로그 Claude 확인), Codex 리뷰 지적 없음. 기믹 2개 이상 쌓인 모습은 이 구성에서 기믹이 1개뿐이라 캡처하지 못했다(단위 테스트로만 확인).

## [2026-10-16 14:50] 기믹 버튼 실게임 캡처 검증과 그 과정에서 고친 것 (캡처·컴파일 확인, 빌드 배치 안 함)

- **빡대리님 지시:** 캡처용 테스트 코드는 Codex가 쓰고, Claude가 실행해 캡처로 검증한다.
- **실행:** 리그 모듈 `runClient -PdevAllAddons`(Mega Showdown·MCC·cobblemon-ui가 함께 뜸)에 `BattleUiCaptureHarness`를 쓴다. 스크립트는 F 복사본의 `F:/AI/builds/gimmick-cap-90749ffe/capture.ps1`(저장소 밖). 반드시 `pwsh`로 돌린다. Windows PowerShell 5는 BOM 없는 스크립트의 한글 경로를 깨뜨린다. 캡처 월드는 `mcc-hub-capture` 복사본, 리소스팩은 프로필에서 복사(갈무리 포함).
- **하네스 추가:** `BATTLE_UI_CAPTURE_GIMMICKS=1`(메가스톤 루카리오 Lv10 리드 + Omni Ring), `BATTLE_UI_CAPTURE_SETUP`(전투 전 서버 명령, `{player}` 치환, 여기선 `mcc league progress set {player} all; mcc league cap sync {player}`), 단계 `window:WxH`/`scale:N`/`tooltipscale:N`. `key:`·`confirm`은 이제 `KeyboardHandler.keyPress`로 누르고 뗀다. 화면의 `keyPressed`를 직접 부르면 확인 키 게이트(`BattleConfirmGate`)를 건너뛰어 기믹이 켜지지 않는데도 캡처는 성공한 것처럼 보인다.
- **캡처로 잡은 문제:**
  1. Mega Showdown이 `BattleMoveSelection.mousePrimaryClicked`를 덮어써서, 그 안의 `isHovered`를 노린 `@Redirect`가 적용되지 못하고 클라이언트가 시작 단계에서 크래시했다. 단위 테스트에는 Mega Showdown이 없어 못 잡았다. 함수 앞 `@Inject`로 새 타일 클릭을 원래 버튼 가운데 클릭으로 바꿔 다시 부르게 고쳤다(`30887a0f`). 계약 테스트가 `@Redirect` 재도입을 막는다.
  2. 기본 툴팁 폭은 210이 아니라 247이다(글자 배율 0.85를 정수로 올림). 툴팁과 기믹 열 사이는 2px, 큰 툴팁 배율에서는 기믹 열을 툴팁 위에 그린다(`7883c8c9`).
- **캡처 결과(직접 확인):** 427×240(1280×720@3)·480×270(960×540@2)은 아이콘 타일, 640×360(1280×720@2)은 "메가진화" 이름 타일. 키보드 기술 포커스 때 툴팁이 기믹 열과 겹치지 않는다. ←로 기믹, 확인 키로 켜면 선택 색(노랑·연두)으로 바뀌고 기술로 돌아가도 유지, →는 떠나온 기술로 돌아간다. 툴팁 글자 2배에서는 툴팁이 화면을 덮고 기믹 타일은 그 위에 보인다(기술 칸이 덮이는 건 원래 동작).
- **미확인:** 이 구성에서 기믹은 메가진화 1개만 나왔다(Codex 예상은 테라스탈까지 2개). 기믹 2개 이상의 세로 배치, 다이맥스(기술 칸이 바뀌는 기믹), 더블 배틀, 마우스 클릭으로 켜기는 캡처하지 않았다. 1920×1080 창은 이 PC의 커밋 메모리 부족으로 스크린샷 텍스처를 못 만들어, 같은 GUI 크기를 작은 창으로 찍었다.
- **메모리:** 이 PC는 커밋 한도가 낮다(페이지 파일 5.9GB). 캡처는 Gradle `-Xmx1g`, 클라이언트 `-Xmx2g -XX:TieredStopAtLevel=1`로 겨우 돈다. 다른 Gradle 데몬이 떠 있으면 실패한다(이번엔 빡대리님 허락으로 오전 3시 데몬 하나를 껐다).

## [2026-10-16 13:30] 기술 선택 기믹 버튼을 기술 왼쪽 큰 타일로, 키보드 이동 포함 (컴파일·단위 테스트 확인, 빌드 배치·실게임 미확인)

- **빡대리님 지적:** 기믹 버튼(메가진화·다이맥스 등)이 Cobblemon 원래 자리(왼쪽 아래)에 18×17로 작게 나오고 키보드로 갈 수 없다. 기술 4개 왼쪽에 크게, 아이콘은 Cobblemon 것을 키워서 우리 UI에 맞게.
- **작업 분담:** 빡대리님 지시로 구현은 Codex(gpt-5.6-sol)가 F 복사본에서 했고, Claude가 검토하며 아이콘·키보드 부분을 고쳤다.
- **배치:** `BattleScreenGeometry.gimmickTiles` — 90×26 타일을 기술 4칸 영역 세로 가운데에 4px 간격으로 쌓고, 기술 열과 8px(+포커스 돌출 7px) 띄운다. 427×240에서 3개면 x=189, y=103/133/163.
- **동작:** Cobblemon `BattleGimmickButton.render`는 취소하고, `BattleMoveSelection.mousePrimaryClicked` 안의 `BattleGimmickButton.isHovered`만 새 타일 위치로 리다이렉트했다. 다른 기믹 끄기, `toggle()`, `moveTiles`를 `getTiles()`/`baseTiles`로 바꾸는 일은 Cobblemon 원래 코드가 한다(javap로 원래 흐름 확인). 키보드 확인도 같은 `mousePrimaryClicked`를 부르고, `BattleConfirmGate.consumeMenuPress`로 누른 채 반복돼도 한 번만 토글한다.
- **아이콘:** Codex는 잘라낸 영역을 그대로 그려 버튼 바탕(회색·무지개 사각형)이 같이 나왔다. `BattleGimmickIcons.extract`가 꺼짐 반쪽에서 왼쪽 6열(문양이 없는 곳)의 색을 바탕색으로 보고 그 밖의 색을 문양으로 뽑는다(바탕색을 코드에 적지 않는다). 색은 꺼짐·켜짐 중 밝은 쪽 문양을 쓴다(울트라버스트만 켜짐 쪽 노란 문양). 런타임에 Cobblemon 텍스처를 읽어 `cobblemon_ui:gimmick_glyph/<id>` 동적 텍스처로 1:1(원래 0.5배의 2배) 그린다. 아이콘 칸 22px 고정이라 쌓인 타일의 이름이 같은 x에서 시작한다. 리소스팩을 바꿔도 처음 만든 문양을 계속 쓴다(다시 읽지 않음).
- **키보드(빡대리님 결정):** ←/→는 기믹 열과 기술 열을 오간다. 기믹 열에서 →는 떠나온 기술 칸으로 돌아간다(그 칸이 비활성이면 가장 가까운 칸). 원래 ←/→였던 기술 툴팁 켜기/끄기는 없앴고, 키보드 포커스가 기술에 있으면 툴팁이 항상 뜬다. 설정 `enableMoveTooltips`(기본 켜짐)가 꺼지면 툴팁 자체를 그리지 않는다. `MoveTooltipMode`와 그 테스트는 지웠다.
- **확인:** Codex가 F 복사본(`F:/AI/builds/gimmick-93b1fc2c`)에서 컴파일과 `cobblemon-ui:unitTest` 289/289 통과(로그는 Claude가 확인). 배치·겹침, 문양 추출(합성 텍스처), 확인 키 1회 소비, 소스 계약 테스트를 포함한다. 실제 Cobblemon 텍스처 추출 결과는 Python으로 같은 규칙을 돌려 눈으로 확인했다. 게임 안 화면과 키 동작, 더블 배틀은 확인하지 않았다.

## [2026-10-16 10:40] 메시지 상자 공용 함수, 남은 DS 테마 화면을 콘텐츠 테마로 (컴파일·단위 테스트 확인, 빌드 배치·실게임 미확인)

- **빡대리님 지적:** 테마를 바꿨는데 피츄 도우미(`/피츄`)가 따라오지 않았다. 원인은 두 가지다. 피츄는 `CobblemonUiSharedTheme.snapshot()`(DS 창)을 불렀고, 메시지 상자를 NPC 대화창 코드에서 복사해 직접 그렸다. 10-15 대화창 전환은 공용 함수가 아니라 화면별로 바꿨기 때문에 복사본이 빠졌다.
- **구현:** `UiDialogueStyle.drawBox`(상자+양 끝 막대), `drawPlate`(이름표), `drawArrow`(넘김 화살표). 전투 메시지(`BattleDialogue`), 장면 대화 이름표(`SceneDialogue`), `NpcDialogueScreen`, `PichuScreen`, NPC 대화 초안 미리보기가 모두 이 함수로 그린다. 대화창 모양은 이 세 함수만 고친다. 화면에 상자 그리기 코드를 다시 복사하지 않는다.
- `CobblemonUiThemedButton.theme`(기본 `snapshot`): 위젯 테마를 설치하지 않는 화면에서 콘텐츠 테마 버튼이 필요하면 `CobblemonUiSharedTheme::contentSnapshot`을 넣는다. 생성자 시그니처는 그대로다. 이 속성을 쓰는 jbro-policy·PvP JAR은 이 버전 이상의 cobblemon-ui와 같이 배포해야 한다.
- **콘텐츠 테마로 옮긴 곳:** 피츄 버튼·상자, PvP 방 HUD의 측면 카드·버튼(창 외곽·제목 띠는 허브 크롬처럼 DS 유지), PvP 관전 종료 버튼.
- **일부러 남긴 곳(결정 있음):** 허브 헤더·왼쪽 탭 줄(DS, 10-15 결정), PvP 선발 화면(`BattleUiTheme`, PvP MEMORY 지시), 전투 HUD·모달(전투 테마), 레터박스·VS·진입 연출. 허브 툴팁과 PvP 관전자 카드는 순정 툴팁 모양이라 그대로 뒀다.
- **확인:** 커밋 `62ee783d`. Codex가 F 복사본에서 4개 모듈 컴파일과 `cobblemon-ui:unitTest` 282/282 통과(로그 `F:/AI/builds/theme-62ee783d/test.log`는 Claude가 직접 확인), Codex 리뷰 지적 없음. JAR 빌드·배치와 실게임 화면은 확인하지 않았다.
- **발견:** Kotlin 컴파일 데몬은 F 경로 설정과 상관없이 `C:\Users\박주형\AppData\Local\kotlin\daemon`에 표시 파일을 쓴다. 빌드 명령에 `-Pkotlin.compiler.execution.strategy=in-process`를 붙인다.

## [2026-10-16 03:50] 다이맥스 툴팁의 기본 위력 표시 수정 (빌드·282개 단위 테스트 확인, 설치 대기)

- 원인: 기술 타일의 표시용 Max Move 템플릿 위력(다수 10)을 그대로 읽고 원래 기술의 다이맥스 위력을 반영하지 않았다. `InBattleMove.id`는 원래 기술 ID를 유지하므로 이 ID의 MoveTemplate도 툴팁에 전달한다. UI 레이아웃·색·버튼은 변경하지 않는다.
- `MaxMovePowerPreview`가 Cobblemon 1.8.1의 Showdown 18 규칙으로 위력을 구한다. 격투/독의 별도 변환표, 명시적 maxMove.basePower 예외 50개, 거다이난타·화염구·저격의 고정 160, 다이월의 위력 없음 등을 처리한다. 원래 기술 정보가 없으면 정의값 10을 대신 보여 주지 않고 기존 표기 `--`를 사용한다. 일반 기술과 기존 동적 위력 표시는 유지한다.
- 실제 번들 Showdown `Dex.mod('cobblemon').moves.all()`에서 공격 기술 595개의 maxMove.basePower를 직접 추출한 비교 fixture와 회귀 검사를 추가했다. 단순 공식 복사본끼리 비교하는 검사가 아니다. 원래 registerMoveTile 호출 시그니처는 오버로드로 유지한다.
- 첫 Gradle 빌드는 C2 컴파일러의 native malloc 2MB 할당 실패로 JVM이 종료됐다. F 드라이브 hs_err 로그로 메모리 부족을 확인한 뒤 worker 1개, 힙 1GB, CPU 2개, 테스트 힙 384MB로 낮춰 재실행했다. 재실행 `F:/AI/builds/max-tooltip-c7fe/ui-build-verified.log`에서 53초 build 성공 및 282/282 테스트 통과를 확인했다. 공격 기술 595개의 위력이 실제 번들 Showdown 값과 일치했다. 모든 Gradle 출력·캐시·임시 경로는 F 드라이브다.
- 현재 개발 클라이언트가 실행 중이므로 설치 JAR은 교체하지 않았다. 소스 커밋 `b20decb6`을 main 및 작업 브랜치에 푸시하고 origin/main 일치를 확인했다. 실게임 툴팁 확인은 하지 않았다. 빌드 JAR은 `F:/AI/builds/galar-music-c7fe/out/cobblemon-ui/libs/cobblemon-ui-0.2.0.jar`, SHA-256 `04983bf92faf2e37ad6f64eeae25293a8d464187acc6d6233cb922dbb4177fac`이며 커밋된 변경 소스와 빌드 입력 3개 파일이 일치한다. 가라르 리그의 레벨 데이터는 사용자가 먼저 표를 검토하겠다고 했으므로 변경하지 않았다.

## [2026-10-16 01:30] 플레이 피드백 수정: 선택 우선·호버 색·전투 키·다이맥스 % (단위 테스트·허브 캡처 확인, 전투 실게임 미확인, 배포 안 함)

- **허브:** 선택 상태가 호버·포커스보다 우선(`CobblemonUiButton`, PvP 칩, 포켓몬 카드). 픽셀 테마 일반 버튼 호버는 선택 노랑을 35% 섞은 색(팔레트 원래 호버는 흰색에서 흰색이라 안 보였음). 리스트 행 +/-는 평소 투명, 호버만 노랑. `UiRenderSlotSpec.framed=false`로 모델만 표시. 상점 호버 3종·대시보드·PvP·타워·리그는 실캡처로 확인.
- **전투:** `BattleConfirmGate` — 확인 키 PRESS 시점에 메뉴가 떠 있었을 때만 메뉴가 받는다(대사 넘기며 누른 채 유지한 Z가 기술을 고르던 문제). 새 대사가 오면 초기화. 피해율은 패킷의 새 최대 HP 기준(다이맥스 시작 시 106.9% 같은 값). HUD 듀얼 타입 세로 배치, 전투 정보 창 올라오는 애니 제거. 전투는 실게임으로 확인하지 못했다.

## [2026-10-15 23:55] 대화창(전투·장면·NPC)을 콘텐츠 테마로 (단위 테스트·개발 클라 캡처 확인, 빌드·배치 안 함)

- **빡대리님 결정:** 대화창도 허브 콘텐츠 테마로. 위치·크기·레터박스 위 배치는 그대로, 테두리·색만 바꾼다.
- **구현:** `UiDialogueStyle`(`uikit/client`)에 대화창 규칙을 모았다. 상자는 콘텐츠 테마 카드(`panelAlt`, 픽셀 프레임), 이름표는 버튼 프레임에 카드 제목 띠 색(`accentCaution`), 양 끝 막대는 정보 띠 색, 글자 그림자 없음. `BattleDialogue.renderMessage`(전투 메시지·장면 대화가 함께 씀), `SceneDialogue` 이름표, `NpcDialogueScreen`이 이를 쓴다. 양 끝 막대는 둥근 모서리와 겹치지 않게 위아래 2px씩 안쪽에서 시작.
- **확인:** `/mcc scene test`, `/npc talk @s tower_guide` 실캡처. 전투 중 메시지 상자는 같은 함수지만 실전투로는 미확인.

## [2026-10-15 22:20] 콘텐츠 테마(pixel_frame) 분리, 공용 탭 바·색 띠 패널·글리프 기준 세로 정렬 (단위 테스트·개발 클라 캡처 확인, 빌드·배치 안 함)

- **빡대리님 결정:** 허브 콘텐츠는 1번째 레퍼런스(`pixel_frame.tower_lobby`) 모양, 허브 헤더·왼쪽 탭 행은 기존 공용 테마(`ds_window`) 그대로. 픽셀 프레임도 DS 창처럼 둥근 모서리(창 4, 버튼 3)와 안티에일리어싱을 쓴다. 선택 표시는 세모 커서 없이 배경색만. 카드·버튼 그림자 알파는 절반(0xA8→0x54, 셸 0xC8→0x64).
- **추가:** `CobblemonUiSharedTheme.contentSnapshot()/installContent()`(공용 팔레트 + PIXEL_FRAME), `CobblemonUiTabBar`(브라우저형 탭, 선택 탭이 페이지와 이어지고 나머지 탭도 페이지 윗선에 붙음, 잠긴 탭은 자물쇠), `UiPanelSpec.band`/`UiPanelBand`(제목 띠 색 지정), `UiPixelBox`(직접 그리는 작은 박스도 테마 모서리 모양·`UiSurfaceRenderer` 경로로), `UiHoverWiggle`(호버 시 아이콘 흔들림+확대), `UiSurfaceTokens.panelTextDim`(밝은 카드 위 흐린 글자).
- **렌더러:** `PixelFrame`도 부드러운 모서리 경로를 탄다(외곽선→하이라이트→채움 + 아래·오른쪽 음영, 그림자도 같은 경로). 둥근 패널의 제목 띠 윗모서리는 패널 안쪽 반경을 따른다. `PIXEL_CORNER`는 게터다. 저장 값으로 두면 프리셋 객체 초기화 중 null이 되어 클라이언트가 시작 시 크래시했다.
- **세로 정렬:** `(h - lineHeight)/2 + 1` 같은 보정값 대신 `UiTextRenderer.centeredY`가 실제 글리프 잉크 범위(`BakedGlyph.up/down`, access widener로 연 `Font.getFontSet`)로 가운데를 잡는다. 버튼·리스트 행·체크박스·드롭다운·토스트·패널 제목·탭에 적용.
- **확인:** `cobblemon-ui:unitTest` 278/278(픽셀 테마 선택 상태 테스트를 "커서 없음, 채움 색이 다름"으로 갱신). 개발 클라이언트 실캡처 427×240에서 모양 확인. 빌드 산출물 배치와 실서버 확인은 하지 않았다.
- **교훈:** 개발 실행 폴더(`run/`)는 리소스팩이 실제 프로필과 다르다. 갈무리 폰트·Whimscape 없이 찍으면 글자 정렬을 잘못 판단한다. 캡처 전에 프로필 `resourcePacks`와 맞춘다.

## [2026-10-13 20:40] 능력 단계를 서버(Showdown) 값으로 덮어쓴다 (단위 테스트 확인, 빌드·배치·실게임 미확인)

- **빡대리님 결정:** 능력 단계는 서버에 정확한 값이 있으니 그걸 쓴다. 클라이언트 계산을 고집할 이유가 없다.
- **구조:** UI는 클라이언트 전용이라 서버 코드가 없다. MCC 서버가 Showdown `pokemon.boosts`를 읽어 보내고, MCC 클라이언트가 `BattleStateTracker.applyServerStatStages`로 넘긴다. 받은 포켓몬은 메시지로 쌓은 값을 통째로 바꾼다(`StatTracker.replaceStats`). UI는 MCC를 모른다(의존 방향 MCC → UI).
- **메시지 계산은 남겼다:** 턴 연출 중에는 메시지로 바로 바뀌고, 연출이 끝나면 서버 값으로 맞춘다. MCC가 없는 서버에서는 메시지 계산만 쓴다.
- **테스트:** `BattleStateRegressionTest` 40/40.

## [2026-10-13 20:05] 하양허브 발동 후에도 능력 하락이 남던 문제 (단위 테스트 확인, 빌드·배치·실게임 미확인)

- **빡대리님 신고:** 상대 포푸니크가 인파이트를 쓰고 하양허브가 발동했는데 상대 정보 패널에 방어·특방 -1이 그대로 남았다.
- **원인(Showdown·Cobblemon 1.8.1 바이트코드로 확인):** Showdown 하양허브는 `-enditem|...|White Herb` 다음 `-clearnegativeboost|...|[silent]`를 보낸다. Cobblemon `ClearNegativeBoostInstruction`은 `silent`면 채팅 메시지를 보내지 않아 클라이언트 UI가 초기화를 받을 길이 없다. 또 `enditem.whiteherb`는 인자가 하나뿐인데 `ITEM_CONSUMED_KEYS`(인자 2개 필요)에 있어서 아이템 소모 표시도 안 됐다.
- **수정:** `enditem.whiteherb`를 `ITEM_SINGLE_ARG_EVENTS`로 옮겨 소모 처리하고, 같은 메시지에서 그 포켓몬의 하락 단계만 0으로 돌린다(`StatTracker.clearNegativeStatsByName`). 조용하지 않은 `clearallnegativeboost`(·`.zeffect`)도 같은 함수로 처리한다. 올라간 능력은 그대로 둔다.
- **테스트:** `BattleStateRegressionTest` 한 클래스(39개) 통과. 새 테스트 2개.
- **남은 것:** 하양허브를 상대에게 던지기(fling)한 경우는 대상 쪽 하양허브 메시지가 없어 여전히 놓친다. 같은 단일 인자 키(`mentalherb`·`powerherb`·`airballoon` 등)도 `ITEM_CONSUMED_KEYS`에 남아 소모 표시가 안 될 수 있는데, Cobblemon이 실제로 인자를 몇 개 보내는지 확인하지 않아 손대지 않았다.

## [2026-10-13 02:35] 리그 VS 인트로 `BattleVersusIntro` (하네스 캡처·실제 흐름 확인, 배포 안 함)

- **무엇:** 관장·사천왕·챔피언 배틀 전 VS 화면. 사선으로 갈라진 두 면이 좌우에서 들어오고 속도선, 두 트레이너 전신, 이름판(도전자 왼쪽 아래, 상대 오른쪽 위), "VS"가 한 번 꽂히며 섬광·흔들림. 사천왕은 교차 슬래시, 챔피언은 금색 면과 불꽃 입자. 흰 화면으로 끝나고 배틀이 열리면 걷힌다. `/battleentry versus <gym|elite_four|champion>`으로 미리 볼 수 있다.
- **사용자 지적으로 뺀 것:** 챔피언 도입부(암전·조명·"챔피언" 큰 글씨 각인), VS 글자 맥동(꿀렁임), VS 글자 흔들림(화면만 흔들리고 글자는 고정), VS 뒤 트레이너 진입 전환.
- **캡처:** PvP 하네스 `MCC_PVP_CAPTURE=versus_<tier>@<ms>`가 `frozenAt`으로 시점을 고정해 찍는다.

## [2026-10-11] 배틀 HUD 카드와 TAB 현황판에 타입 아이콘 (빌드·개발 클라이언트 배치 확인, 실게임 미확인)

- **빌드·배치 [2026-10-12 03:33]:** 빡대리님 지시로 개발 쪽만. `:cobblemon-ui:build -x test -x unitTest`(F 경로, `F:/AI/builds/cobblemon-mods-main` 설정) 성공. 다른 세션이 같은 소스로 02:39에 이미 컴파일해 둬서 결과가 그대로였고, JAR에 `BattlePokemonTypes`가 들어 있는 것을 확인했다. SHA-256 앞자리 858d2acd. 개발 클라이언트(꺼진 상태)의 `cobblemon-ui-0.2.0.jar`와 `cobblemon-npc` 내장 사본을 이 JAR로 바꿨다. MCC는 클라이언트·서버 모두 이미 같은 해시를 품고 있었다. 백업 `develop-product/deployment-backups/20261012-0333-ui-type-icons`.
- **보류:** 개발 서버가 켜져 있어 서버 `cobblemon-npc` 안의 UI 사본(a5dcef0c)은 그대로다. UI 코드는 클라이언트에서만 돌아서 서버 동작에는 영향이 없다고 보지만, 서버를 내린 뒤 맞춰 두면 된다.

- **빡대리님 지시:** 배틀 HUD와 현황판에서 포켓몬 타입을 볼 수 있게.
- **타입 판단:** `BattlePokemonTypes.current`로 한곳에 모았다. 테라스탈 타입 → `TypeTracker`의 배틀 중 변경(물붓기·불사르기·폼 체인지 등) → 폼 원래 타입 순서다. 기술 툴팁의 상대 타입 계산(`MoveTooltipRenderer.getOpponentEffectiveTypes`)도 이 함수를 쓴다. 바뀐 점은 중복 타입을 한 번만 센다는 것뿐이다.
- **HUD 카드:** Cobblemon `TypeIcon` 작은 아이콘(9px)을 카드 안쪽 끝 위에 놓는다. 상태이상 배지와 같은 열이다(아군 `x+WIDTH-10`부터, 상대는 `x+10`에서 끝남). 카드 폭(136)과 기존 배치는 바꾸지 않았다. 이 위치는 Claude 판단이며, 카드 안에는 이름·HP 줄에 아이콘 두 개 들어갈 자리가 없었다(아군 HP가 `123/456`이면 바와 숫자 사이가 4px).
- **현황판:** 한 마리일 때 큰 카드는 상태 배지 오른쪽에 아이콘과 타입 이름을 쓴다. 2~3마리일 때 중간·작은 카드는 이름 줄의 레벨 앞에 아이콘만 넣고 이름을 그만큼 줄인다. 파티 칩에는 넣지 않았다.
- **미확인:** 실게임. HUD 아이콘이 둥근·사선 끝과 겹쳐 보이는 모양, 더블 배틀의 actorName 줄과 겹치는지는 화면으로 봐야 한다.

## [2026-10-10 18:25] TAB 현황판 기절 칩 막이 안 보이던 문제 (`8c7bd3b6`, 빌드·배치 확인, 실게임 미확인)

- **빡대리님 신고:** TAB 현황판에 기절한 포켓몬 표시가 없다. 검은 반투명 막이나 초상화 처리로 구분해 달라.
- **원인(코드로 확인):** `ChampionsBattleInfoOverlay.drawTeam`은 원래 기절 칩에 `palette.koVeil`(다크 `0xA0101624`)을 덮고 있었다. 그런데 칩 초상화는 `drawPosablePortrait`로 그리는 3D 모델이라(`scale(s, s, -s)`) 보는 쪽으로 튀어나오고, 막은 칩과 같은 깊이에 그려져 깊이 테스트에서 모델 뒤로 숨었다.
- **수정:** 막을 `PORTRAIT_FRONT_Z`(100)만큼 앞으로 올려 그린다. 색은 테마 팔레트 그대로다(라이트 테마는 흰 막). 상태이상 점(비기절 칩)도 같은 이유로 가려질 수 있지만 이번엔 손대지 않았다.
- **빌드:** 빡대리님 지시로 main(`bc4e93eb`) 그대로 `:cobblemon-ui:build -x test -x unitTest`. `GRADLE_USER_HOME=F:/AI/caches/gradle`, `--project-cache-dir`·TEMP·`java.io.tmpdir`는 `F:/AI/builds/cobblemon-mods-main/`, 빌드 출력은 init 스크립트(`f-build-dirs.init.gradle`, 같은 폴더)로 `.../out/<모듈>`에 보냈다. 저장소 빌드 스크립트에는 출력 경로를 F로 돌리는 설정이 없다. JAR SHA-256 앞자리 a5dcef0c.
- **배치:** 게임·서버가 꺼진 상태에서 클라이언트 `cobblemon-ui-0.2.0.jar` 교체. MCC·cobblemon-npc가 같은 버전 사본을 `META-INF/jars/`에 품고 있어서, 두 JAR(클라이언트·서버)은 다시 빌드하지 않고 내장 사본만 새 JAR로 바꿔 끼웠다. MCC 작업 트리에 다른 세션의 미커밋 Better AI 변경이 있어 다시 빌드하면 섞이기 때문이다. 백업 `develop-product/deployment-backups/20261010-tab-ko-veil`.
- **미확인:** 실게임. 100이 큰 모델(예: 거대한 종)까지 덮는지는 화면으로 봐야 한다.

## [2026-10-06 12:55] 대화창을 아래 띠 위쪽으로, 레터박스 슬라이드 강화 (개발 클라이언트 연속 캡처 확인)

- **배포 [2026-10-06 13:00]:** 배틀캠·cobblemon-ui(클라이언트), MCC·cobblemon-npc(클라이언트·서버, 내장 UI 사본 포함). 해시·무결성 확인, 백업 `develop-product/deployment-backups/2026-10-06_1300-letterbox-slide-above`. 서버는 꺼진 그대로.

- **빡대리님 지적:** "레터박스 위에 안 뜬다." 12:42 항목에서 박스를 띠보다 *앞 레이어*에만 그리고 위치는 화면 아래 그대로 둬서 띠를 덮었다. 뜻은 화면상 위치로 **아래 띠의 위쪽**에 놓으라는 것이었다.
- **위치:** `BattleDialogue.renderMessage`의 박스 top을 `CinematicLetterbox.bottomInset`만큼 올린다. 연출 대화와 연출 중 전투 메시지 모두 아래 띠 바로 위에 놓이고, 띠가 들어오는 동안 박스도 같이 올라온다. 레터박스가 없으면 0이라 전과 같다.
- **애니메이션:** "슈루룩" 들어와야 한다는 지시로 슬라이드를 0.25초 smoothstep에서 **0.45초 ease-out(1-(1-t)^3)**으로 바꿨다. 연속 캡처(2틱 간격)로 띠가 두꺼워지며 박스가 올라오는 것을 확인했다.

## [2026-10-06 12:42] 레터박스 위에 기존 대화창 박스를 띄운다 (자막 방식 철회, 캡처 확인)

- **빡대리님 지적:** "레터박스 위에 대화창 UI가 떠야 한다." 10:55 항목에서 대화창을 아래 띠 안의 자막으로 바꾼 것은 Claude가 지시를 잘못 읽은 것이다. "레터박스에 맞게 재구성"은 박스의 위치를 맞추라는 뜻이지 형태를 바꾸라는 뜻이 아니다.
- **지금:** `SceneDialogue`는 전투 메시지 박스(`BattleDialogue.renderMessage`)와 이름표를 그대로 그리되, 띠(깊이 400) 위 깊이 450(`CinematicLetterbox.OVER_BARS_Z`)에 그린다. 아래 띠는 위 띠와 같은 높이로 고정(화면 11%, 20~48px)이고, 박스는 화면 아래쪽에서 띠를 덮으며 떠 있다. `renderCaption`, 자막 줄 수로 띠를 늘리던 처리는 지웠다. 띠 그리기는 `renderBars(context, z)`로 깊이를 받는다(`CinematicScreen`은 0에 그리고 그 위에 자기 박스를 그린다).
- **배포 [2026-10-06 12:44]:** cobblemon-ui(클라이언트), 내장 사본을 맞춘 MCC·cobblemon-npc(클라이언트·서버). 해시·무결성 확인, 백업 `develop-product/deployment-backups/2026-10-06_1244-dialogue-box-over-letterbox`. 서버는 꺼진 그대로.
- **그대로 둔 것:** HUD 카드를 위 띠 아래로 내리기, 연출 중 채팅·액션바·토스트·이름표 숨기기.

## [2026-10-06 10:55] 연출용 상하 레터박스 `CinematicLetterbox` (빌드·단위 테스트 275개·개발 클라이언트 캡처 확인)

- **빡대리님 지시:** 연출을 위한 상하 레터박스를 넣고 UI 레이아웃을 그에 맞게 다시 짠다. 대화창은 필수, 나머지 UI는 권고.
- **동작:** `CinematicLetterbox.show/hide(owner)`. 소유자가 하나라도 있으면 위아래 검은 띠가 0.25초 동안 미끄러져 들어오고, 다 빠지면 나간다. 위 띠는 화면 높이의 11%(20~48 GUI 픽셀), 아래 띠는 위 띠와 같거나 자막 줄 수만큼 더 높다. `SceneDialogue`(전투 연출)와 cobblemon-npc 대화 화면이 소유자다. 띠는 화면이 없으면 HUD에서, 화면이 있으면 `afterRender`에서 화면 위에 그린다(`CinematicScreen` 표시가 있는 화면은 직접 그린다).
- **대화창 재구성(필수):** 레터박스가 있을 때는 대화창 박스를 화면 위에 띄우지 않고, **아래 띠 안에 자막처럼** 읽는다(`renderCaption`). 왼쪽에 화자 이름표(테마 기본 버튼 모양, 얼굴 선택), 그 오른쪽에 대사, 줄이 다 써지면 오른쪽 끝에 확인 키와 화살표. 줄이 길면 아래 띠가 늘어난다(최대 4줄). 박스 대신 자막으로 바꾼 것은 Claude 판단이다(검은 띠 안에 박스 테두리를 또 그리면 띠가 너무 두꺼워진다: 박스 높이 58~66px는 240px 화면의 25% 이상).
- **다른 UI(권고):** 전투 HUD 카드는 위 띠 높이만큼 내려간다. 채팅·액션바(`Gui.renderChat/renderOverlayMessage`), 토스트(`ToastComponent.render`, 큐는 유지), 엔티티 이름표(`EntityRenderer.renderNameTag`)는 레터박스 동안 숨는다. 전투 명령 메뉴는 아래 띠가 덮는다. 다른 모드의 HUD(예: Cobbled Level Control의 레벨 캡 표시)는 손대지 않았다.
- **이름표:** 처음에 `shouldShowName`을 막았는데 `LivingEntityRenderer`가 오버라이드해서 NPC 이름표가 남았다. 오버라이드되지 않는 `renderNameTag`를 막는다.
- **검증:** 리그 하네스(전투 세 장면)와 NPC 하네스로 캡처했다. 띠가 들어오고 자막·이름표·얼굴이 띠 안에 놓이며, HUD 카드는 띠 아래로 내려가고 이름표·토스트·채팅은 연출 동안만 사라진다.

## [2026-10-06 09:05] 연출 대화창 `SceneDialogue` (빌드·단위 테스트 275개 확인, 실게임 미확인)

- **빡대리님 지시:** 전투 승패 연출에서 트레이너 대사를 띄울 대화창. 호출은 MCC 코어 `BattleSceneClient`가 한다.
- **구현:** `SceneDialogue.play(speaker, lines, onFinish)`. 전투 메시지 상자(`BattleDialogue.renderMessage`)를 그대로 쓰고, 화자 이름 탭을 상자 왼쪽 위에 붙인다. `renderMessage`는 보여 줄 글자 수를 받고 상자 위치(`UiRect`)를 돌려주게 바꿨다(기존 호출은 기본값이라 그대로).
- **입력:** 확인 키(`selectActionKey`)로 줄을 마저 쓰고 넘긴다. 화면이 없으면 `consumeClick`, 화면이 열려 있으면(끝을 붙잡아 둔 배틀 화면) Fabric `allowKeyPress`로 받는다. 다 쓴 줄은 1.6초 + 글자당 35ms(최대 6초) 뒤 저절로 넘어가서 가만히 있어도 막히지 않는다.
- **그리기:** 화면이 없으면 HUD, 화면이 열려 있으면 `ScreenEvents.afterRender`로 화면 위에 그린다.

## [2026-10-05 22:25] 배틀 표면 그리기를 드로우 콜 하나로 묶음

- 배경: 사용자가 "UI 라운딩이 렉을 많이 먹는다, 코블몬 기본 UI는 안 그런데"라고 함. 코블몬은 PNG 스프라이트를 `blit` 한 번으로 그리고, 우리는 둥근 모서리를 줄마다 `fill`, 모서리 픽셀마다 `fill`로 매 프레임 직접 그린다.
- 원인: 1.21.1의 `GuiGraphics.fill`은 managed 상태가 아니면 호출마다 flush해서 드로우 콜이 하나씩 나간다. `UiSurfaceRenderer`(허브·대화창)는 이미 `drawManaged`로 묶었지만, `BattleSurfaceRenderer.draw`/`capsule`(호출처 68곳)은 묶지 않아 버튼 하나에 100번 넘게 그렸다(호출 수로 낸 추정, 프로파일링 안 함).
- 구현: `draw`와 `capsule` 본문을 `drawManaged` 안에서 실행. 같은 렌더 타입의 fill만 같은 순서로 나가므로 픽셀은 같다. 함수가 끝나기 전에 flush하므로 호출 쪽 셰이더 색·시저 상태도 그대로 적용된다.
- 다음 단계(사용자와 합의, 미착수): 그래도 렉이 남으면 스타일·크기별로 표면을 텍스처로 한 번 구워 두고 `blit`으로 그리는 방식으로 바꾼다. `UiSurfaceRenderer`도 매 프레임 사각형 수백 개를 CPU에서 만드는 비용은 그대로 남아 있다.
- 빌드: 본 코드 빌드 성공, JAR 무결성 확인. 테스트는 `GalleryHarnessModeTest` 때문에 테스트 컴파일이 깨져 있어 돌리지 않음(기존 문제).
- 배포: 클라이언트 `develop-product/client/mods`에 배치(백업 `develop-product/deployment-backups/*-ui-batched-surfaces`). 서버에는 UI 모드가 없다.
- 실게임 검증: 안 함. 배틀 화면 렉이 줄었는지, 모양이 그대로인지 확인 필요.
