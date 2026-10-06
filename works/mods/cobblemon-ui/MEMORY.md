# cobblemon-ui 작업 기록

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
