# Battle UI 가독성·아이콘 간격 수정

Updates: [표면 테마 적용 기록](SURFACE_THEME_2026-09-27.md)

독자: 빡대리님과 다음 Battle UI 구현자. 사용자 실전 캡처에서 확인된 글자 그림자, 툴팁의 속성 아이콘 가림, 제목판 위치, 축소 시 한글 획 손실을 수정한다. 기존 입력 소유권·전투 상태·정보 공개 규칙은 변경하지 않는다(MUST).

## 변경

- 이 모드가 그리는 명령·기술·대사 안내·정보창·툴팁·로그의 글자 그림자를 제거한다. Cobblemon 자체 HP 패널 등의 글자까지 바꾸는 변경은 아니다.
- 기술 툴팁 오른쪽과 버튼 왼쪽 사이에 논리 좌표 18의 공간을 둔다. 타입 아이콘의 왼쪽 돌출 9, 포커스 확대 1.06배의 돌출, 추가 간격을 포함한다. 기존 클릭 영역과 세로 메뉴 위치는 유지한다.
- 제목판 높이는 40이며, 위쪽 좌표는 `FRAME_TOP - TITLE_HEIGHT / 2`로 계산한다. 따라서 패널 상단선이 제목판의 세로 중앙을 지난다. 제목판이 화면 위로 잘리지 않도록 전체 원점을 보정한다.
- TAB 글자 크기는 패널 배율과 GUI 배율을 합친 실제 화면 크기를 기준으로 결정한다. 최소 배율 1.5, 그 이상의 정수 배율과 픽셀 정렬을 사용한다. 가운데·오른쪽 정렬 및 이름 잘라내기도 실제 글자 크기로 측정한다.
- 글자 확대에 맞춰 단일 카드의 능력치 행 간격을 늘렸다. 두 개 이상 카드에서는 이름·상태·HP 영역을 압축하고 HP 백분율을 막대 오른쪽에 놓아, 더블 카드의 기존 7개 능력치·각 6칸 표시를 유지한다. 트리플 카드에는 기존처럼 작은 카드의 이름·상태·HP를 표시한다.

## 원인과 채택하지 않은 방법

`galmuri11-8px.zip`의 기본 폰트는 TTF 크기 8, oversample 1.5다. 854×480 / GUI 배율 2에서 기존 960폭 패널을 줄이면 글자별 배율까지 곱해져 작은 한글 획이 사라지는 현상을 재현했다.

픽셀 원점 정렬과 최소 화면 배율 1만 적용한 첫 수정은 충분하지 않았다. 실제 캡처에 획 손실이 남았으므로 채택하지 않았다. 최소 1.5로 올린 뒤 행간과 축소 카드 내부 배치를 함께 보정했다. 폰트팩 제거·교체나 사용자 GUI 배율 변경은 하지 않았다. 모든 글꼴과 모든 극소 해상도에서 같은 결과를 보장하는 것은 아니다.

## 재현 및 검증 경계

분리된 개발 클라이언트에 사용자 프로필의 Galmuri ZIP을 복사하여 활성화했다. 원본 팩과 사용자 `options.txt`는 수정하지 않았다. Minecraft 1.21.1 / Fabric 0.19.5 / Cobblemon 1.8.1, 854×480, GUI 배율 2, 한·영 조건으로 새 테스트 월드에 진입한 뒤 실제 렌더러를 캡처한다.

하네스는 명령·기술, TAB 단일·더블·트리플, 오른쪽 세로 기술 목록과 실제 기술 툴팁의 다섯 페이지를 제공한다. 입력·서버 전투는 시뮬레이션하지 않으며 샘플 데이터로 화면을 검증한다. 비중립 능력치·효과 목록·장시간 전투 검증은 이번 캡처에 포함되지 않는다.

```powershell
$env:COBBLEMON_BATTLE_UI_CAPTURE = '1'
$env:COBBLEMON_BATTLE_UI_CAPTURE_LANGUAGE = 'ko_kr' # 또는 en_us
$env:COBBLEMON_BATTLE_UI_CAPTURE_WIDTH = '854'
$env:COBBLEMON_BATTLE_UI_CAPTURE_HEIGHT = '480'
$env:COBBLEMON_BATTLE_UI_CAPTURE_GUI_SCALE = '2'
$env:COBBLEMON_BATTLE_UI_CAPTURE_FONT_PACK = 'galmuri11-8px.zip'
$env:COBBLEMON_BATTLE_UI_CAPTURE_LABEL = 'final-readability'
.\gradlew.bat --no-daemon --configure-on-demand :cobblemon-battle-ui:unitTest :cobblemon-battle-ui:build :cobblemon-battle-ui:runClient
```

폰트 ZIP은 사전에 모듈 `run/resourcepacks`에 있어야 한다. 실행 뒤 위 `COBBLEMON_BATTLE_UI_CAPTURE*` 환경 변수는 같은 터미널에서 해제한다. 하네스는 개발 환경에서 명시적으로 켰을 때만 동작하며 사용자 프로필에는 자동 실행되지 않는다.

툴팁 간격과 중첩 배율·최종 화면 좌표 정렬의 회귀 테스트를 추가했다. 수정 전 red 단계에서 실패를 확인했고 수정 후 119개 테스트가 통과했다.

## 캡처 결과

같은 창 크기·GUI 배율·Galmuri 폰트에서 수정 전후를 비교했다. 제목판 중심이 상단선과 일치하고, 툴팁 옆 타입 아이콘 전체가 드러나며, 단일 카드 능력치 행을 포함한 한글의 가독성이 개선됐다. 한·영 각각 단일·더블·트리플 카드도 확인했다. 글꼴의 가는 획 자체는 Galmuri의 형태를 유지한다.

| 화면 | 수정 전 | 수정 후 |
|---|---|---|
| 한국어 버튼 | [그림자 있음](docs/captures/2026-09-28/battle-ui-before-readability-controls-ko_kr.png) | [그림자 없음](docs/captures/2026-09-28/battle-ui-final-readability-controls-ko_kr.png) |
| 한국어 TAB | [획 손실·제목판 위치](docs/captures/2026-09-28/battle-ui-before-readability-info-ko_kr.png) | [크기·행간·제목판 보정](docs/captures/2026-09-28/battle-ui-final-readability-info-ko_kr.png) |
| 한국어 기술 툴팁 | [아이콘 가림](docs/captures/2026-09-28/battle-ui-before-readability-tooltip-ko_kr.png) | [아이콘 여백 확보](docs/captures/2026-09-28/battle-ui-final-readability-tooltip-ko_kr.png) |

추가 확인: [한국어 더블](docs/captures/2026-09-28/battle-ui-final-readability-info-double-ko_kr.png), [한국어 트리플](docs/captures/2026-09-28/battle-ui-final-readability-info-triple-ko_kr.png), [영어 단일](docs/captures/2026-09-28/battle-ui-final-readability-info-en_us.png), [영어 더블](docs/captures/2026-09-28/battle-ui-final-readability-info-double-en_us.png), [영어 트리플](docs/captures/2026-09-28/battle-ui-final-readability-info-triple-en_us.png).

## 배포

최종 `unitTest` 119개 통과, `build` 성공, 한·영 개발 실행 정상 종료를 확인했다. `cobblemon-dev`가 실행 중이지 않은 상태에서 `mods/cobblemon-battle-ui-0.1.8.jar`를 교체했다. 빌드·배포 파일 SHA-256은 모두 `091F1FA0013C18F646E7834C2CCF00E20E6FA43B4D27F48475B6FDE9F6BFC566`이다. 기존 Galmuri와 나머지 리소스팩·설정은 유지했으며 서버에는 배포하지 않았다. 배포 후 전체 모드팩에서 실제 전투 입력은 다시 확인하지 않았다.
