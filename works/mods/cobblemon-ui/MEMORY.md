# cobblemon-ui 작업 기록

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
