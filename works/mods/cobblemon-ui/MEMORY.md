# cobblemon-ui 작업 기록

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
