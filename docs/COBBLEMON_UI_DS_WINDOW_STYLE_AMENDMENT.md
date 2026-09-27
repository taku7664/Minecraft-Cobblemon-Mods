# Cobblemon UI DS 창 스타일과 스타일·팔레트 분리 후속 결정

| 항목 | 값 |
| --- | --- |
| Status | `shared` |
| Effective | 2026-09-28 |
| Updates | `COBBLEMON_UI_MAINLINE_THEME_PRESETS_AMENDMENT.md`, `COBBLEMON_UI_PIXEL_LEAGUE_THEME_CORRECTION.md`, `COBBLEMON_UI_TEXT_SHADOW_POLICY_AMENDMENT.md` |
| 적용 대상 | `cobblemon-ui-kit`의 테마·표면·목록 줄·패널 제목과, 이를 쓰는 MCC Hub |
| 결정 근거 | 빡대리님이 4세대 배틀타워·배틀팩토리 색과 DPPt 메뉴 창 모양(S1)을 골랐다(2026-09-28) |

## 1. 배경

MCC Hub는 `pixel_league` 하나만 썼다. 이 테마는 색과 모양이 한 덩어리여서, 색만 바꾸거나 모양만 바꿀 수 없었다. 빡대리님은 두 가지를 지적했다.

- 색이 포켓몬답지 않다. 4세대 배틀타워·배틀팩토리 색을 쓰기로 했다.
- 색보다 위젯 모양이 문제다. 모든 요소가 같은 직사각형 2px 틀이고, 선택은 배경만 칠하며, 아이콘이 거의 없다. 4세대 DPPt 메뉴 창 모양(둥근 두 겹 테두리, 아이콘이 붙은 항목, 칠하지 않는 커서, 옅은 글자 그림자)을 쓰기로 했다.

## 2. 스타일과 팔레트 분리

테마는 모양을 정하는 스타일(`UiThemeStyle`)과 색을 정하는 팔레트(`UiThemePalette`)의 조합으로 만들 수 있어야 한다(MUST). `CobblemonUiThemeComposer.compose(style, palette)`가 조합을 만들고, 같은 조합은 한 번만 만든다(MUST). 조합한 테마의 ID는 `스타일.팔레트`다(예: `ds_window.tower_lobby`).

기존 `UiThemePreset`은 그대로 둔다(MUST). `tower_*`, `factory_*` 프리셋은 `PIXEL_FRAME` 스타일에 같은 이름의 팔레트를 조합한 것이다.

### 2.1 팔레트

Bulbapedia의 4세대 게임 화면 이미지에서 픽셀 분포를 뽑아 정했다.

| 팔레트 | 근거 | 핵심 색 |
| --- | --- | --- |
| `tower_lobby` | DPPt·HGSS 배틀타워 로비 벽과 테두리 | 흰 패널 `#EEF1F2`, 붉은 테두리 `#C04038`, 청회색 타일 `#8098B0`, 금색 기둥 `#ECBC2C` |
| `tower_elevator` | 로비 양옆의 유리 엘리베이터 | 청록 유리 `#44C4DC`·`#2C9CCC`, 붉은 틀 `#C04038`, 어두운 틀 `#1D2838` |
| `tower_sunburst` | DP 로비 바닥의 선버스트 무늬 | 빨강 `#E05040`, 노랑 `#E8D020`, 주황 `#C07038`, 크림 `#F3EEDC` |
| `factory_showroom` | 배틀팩토리 유리 진열장 | 얼음 유리 `#C4E4EC`, 유리 반사 `#7CBCE4`, 남색 지붕 `#242444` |
| `factory_night` | 불 꺼진 공장과 컨베이어 | 야간 카드 `#2C3A5E`, 컨베이어 빛 `#6CB4DC` |
| `factory_terminal` | 렌탈 단말 화면 | 강철 `#6A7A86`, 단말 녹색 `#58C080` |

팔레트마다 DS 창 스타일이 쓰는 커서색(`cursor`), 글자 그림자색(`textShadow`), 패널 위 보조 글자색(`panelTextDim`)이 있다.

### 2.2 스타일

| 스타일 | 모양 |
| --- | --- |
| `PIXEL_FRAME` | `pixel_league`의 사각 픽셀 틀, 칠하는 선택, 스프라이트 커서, 띠 제목 |
| `DS_WINDOW` | 둥근 창과 창 테두리, 틀 없는 목록 줄, 커서형 선택, 규칙선 제목, 옅은 글자 그림자 |

두 스타일 모두 `pixel_league`의 컨트롤 크기(작음 20, 보통 26, 큼 36)를 쓴다(MUST). 한 스타일에 맞춘 화면 배치가 다른 스타일에서도 그대로 맞아야 하기 때문이다.

## 3. DS 창 스타일의 공용 계약

- **창 테두리:** `UiBorder.WindowFrame(outerColor, bandColor, bandWidth, innerColor?)`는 1픽셀 외곽선, 색 띠, 선택적인 1픽셀 안쪽 선을 표면 모양을 따라 그린다. `thickness`는 한쪽 두께다.
- **커서형 선택:** `UiSelectionIndicator.Outline(color, width)`는 위젯 전체를 모양대로 두르는 커서 틀이다. DS 창 스타일의 선택 상태는 배경을 바꾸지 않고 이 커서로만 표시한다(MUST). 포커스는 1픽셀, 선택은 2픽셀이다.
- **선택 그리기 책임:** 컨트롤 표면을 직접 그리는 화면은 스타일의 `selectionIndicator`를 `UiSurfaceRenderer.drawSelection`으로 함께 그려야 한다(MUST). 그리지 않으면 DS 창 스타일에서 선택이 보이지 않는다. `CobblemonUiButton`, `CobblemonUiListItem`, `CobblemonUiListRows`는 이미 그린다.
- **색 글자 그림자:** `UiButtonStyle.textShadowColor`가 있으면 그 색으로 1픽셀 그림자를 그린다. Minecraft 검은 그림자(`textShadow`)와는 별개이며, 글자 그림자 정책(79번)의 기본 해제 원칙은 검은 그림자에만 적용한다. `UiTextRenderer`가 두 방식을 모두 그린다.
- **패널 제목:** `UiSurfaceTokens.panelTitle`은 `TEXT`(기존, 여백 위치의 글자), `BAND`(틀 안쪽 15픽셀 띠), `RULE`(글자와 그 아래 규칙선) 중 하나다. `UiPanelSpec.featured`인 패널은 주의색(`accentCaution`), 나머지는 밝은 테두리색(`borderBright`)을 쓴다. 띠 위 글자색은 띠 밝기에 따라 고른다. `CobblemonUiPanel.create`는 제목 앞에 그릴 렌더 슬롯 아이콘을 받는다.
- **선택 유지:** 선택된 컨트롤이나 목록 줄 위에 마우스를 올리거나 포커스를 줘도 선택 커서는 남아야 한다(MUST). `UiSurfaceRenderer.indicatorFor`가 이를 고른다.
- **흐린 글자와 비활성 글자:** DS 창 스타일은 흐린 글자(`textDim`)와 비활성 컨트롤 글자에 팔레트의 `panelTextDim`을 쓴다. 이 글자는 대부분 밝은 창 위에 놓이기 때문이다.
- **목록 줄 스타일:** `UiThemeSnapshot.listRowStyle(state)`는 테마가 따로 정한 목록 줄 스타일을 준다. 정하지 않은 테마는 보조 버튼 스타일을 쓴다. DS 창 스타일의 목록 줄은 틀과 배경이 없고, 올리면 밝아지고, 고르면 커서가 둘러진다.
- **공용 목록 줄:** `CobblemonUiListRows.draw`는 아이콘, 제목, 보조 줄, 끝 글자, 작은 동작 버튼이 있는 목록 줄을 그린다. 위젯과 입력은 화면이 가지며, `actionBounds`로 클릭한 동작을 찾는다.

## 4. 작은 컨트롤 글자 배율 정정

`pixel_league`의 작은 크기는 글자를 0.75배로 줄였다. 이렇게 줄이면 도트 한글이 깨져서, "공개" 같은 두 글자 버튼도 읽을 수 없었다. 작은 크기는 높이만 20으로 줄이고 글자는 원래 크기로 둔다(MUST). 바닐라 20픽셀 버튼과 같은 방식이다. 새 스타일도 이 크기표를 쓴다.

## 5. MCC Hub에 적용한 것

- `MccHubTheme`이 스타일과 팔레트를 가진다. 기본값은 `DS_WINDOW` + `tower_lobby`다. Hub와 그 확인 대화상자가 이 테마를 설치한다.
- 카드 제목은 MCC가 직접 칠하지 않고 `CobblemonUiPanel`이 테마의 제목 스타일로 그린다.
- 목록 줄(페이지 목록, 스크롤 목록, 왼쪽 탭)은 모두 `CobblemonUiListRows`로 그린다.
- 왼쪽 탭 목록은 창 패널 안의 목록 줄이며, 탭마다 아이템 아이콘이 있다(`MccHubTab.icon`, `MccHubTabs.itemIcon`). 대시보드는 도감, 상점은 고대의 동전, League는 몬스터볼, 배틀타워는 검은띠, 배틀팩토리는 PC, PvP는 연결의끈이다.
- 머리줄 아래 선은 팔레트의 테두리색(`accentSecondary`, 로비 팔레트는 붉은 테두리)이다.
- 포켓몬 초상 카드는 표면을 직접 그리므로 선택 커서도 직접 그린다.
- 캡처 하네스의 `MCC_HUB_CAPTURE_THEME`은 `스타일.팔레트` 목록을 받아 한 번 실행으로 모든 테마를 캡처한다.

## 6. 초기화 순서 함정

조합 캐시에 `ConcurrentHashMap.computeIfAbsent`를 쓰면 안 된다(MUST NOT). 게임에서는 Hub가 DS 창 테마를 가장 먼저 만든다. 그러면 크기표를 읽는 순간 프리셋 객체가 초기화되고, 그 초기화가 자기 `tower_*`·`factory_*` 프리셋을 만들려고 같은 캐시를 다시 부른다. 이 중첩 호출은 `Recursive update`로 실패하거나 멈춘다. 그래서 크기표는 프리셋과 떨어진 `UiPixelMetrics`에 두고, 캐시는 먼저 읽고 없으면 만든 뒤 넣는다.

단위 테스트 JVM에서는 다른 테스트가 프리셋 객체를 먼저 초기화해서 이 순서가 재현되지 않았다. `CobblemonUiThemeComposerTest`는 새 클래스로더에서 DS 창 테마를 먼저 만드는 방식으로 이 순서를 재현한다.

## 7. 검증

- 단위 테스트: ui-kit 65개(조합·DS 창 계약·새 클래스로더 초기화 순서 8개 포함), Core 516개, League 64개, PvP 196개, 배틀타워 170개, 배틀팩토리 103개 통과. ui-kit 테스트 작업은 `:cobblemon-ui-kit:unitTest`다(`test`는 꺼져 있다).
- 개발 클라이언트 캡처(427×240, 한국어): 상점·League·PvP·배틀타워·배틀팩토리 탭을 `ds_window.tower_lobby`와 `ds_window.factory_night`로, 배틀팩토리 탭을 여섯 팔레트 모두로 캡처했다. 둥근 창, 아이콘이 붙은 왼쪽 탭, 선택 커서(호버 중 유지), 규칙선 제목, 틀 없는 목록 줄, 읽히는 비활성·안내 글자를 확인했다.

```text
more-cobblemon-contents/run/screenshots/mcc-hub-open-shop-step3-ko_kr-427x240-ds_window.tower_lobby.png
more-cobblemon-contents-league-challenge/run/screenshots/mcc-hub-open-league_challenge-ko_kr-427x240-ds_window.tower_lobby.png
more-cobblemon-contents-pvp/run/screenshots/mcc-hub-open-pvp-step1-ko_kr-427x240-ds_window.tower_lobby.png
more-cobblemon-contents-battle-tower/run/screenshots/mcc-hub-open-battle_tower-ko_kr-427x240-ds_window.tower_lobby.png
more-cobblemon-contents-battle-factory/run/screenshots/mcc-hub-open-battle_factory-ko_kr-427x240-ds_window.<팔레트>.png
```

- 확인하지 않은 것: 모든 모듈을 함께 설치한 클라이언트, 실서버, 1080p의 실제 GUI 배율, ui-kit 갤러리 화면의 DS 창 스타일, 여러 팔레트를 고르는 사용자 설정(아직 없다).
