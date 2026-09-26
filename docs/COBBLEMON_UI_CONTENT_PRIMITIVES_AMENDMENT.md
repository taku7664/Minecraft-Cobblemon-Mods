# Cobblemon UI 콘텐츠 프리미티브 후속 계약

| 항목 | 값 |
| --- | --- |
| Status | `shared` |
| Effective | 2026-09-26 |
| Updates | `COBBLEMON_UI_LAYOUT_OVERLAY_WIDGETS_AMENDMENT.md` |
| 주 독자 | Cobblemon UI Kit 소비 모드 구현자 |

## 1. 목적

이 후속 계약은 League 화면을 만들 때 다시 구현하기 쉬운 본문·진행·선출·미리보기 요소와 긴 화면의 입력 규칙을 공용 UI Kit에 추가한다. 도메인 진행도와 서버 권위는 소비 모드가 소유하며 UI Kit은 표시와 로컬 상호작용만 소유한다(MUST).

## 2. 공개 콘텐츠 계약

- 여러 줄 본문은 `UiTextSpec`과 `CobblemonUiTextBlock`을 사용해야 한다(SHOULD). 정렬, 최대 줄 수, 줄 간격, 말줄임과 글자 그림자를 호출부에서 정할 수 있다(MUST).
- 섹션 배경은 `UiPanelSpec`과 `CobblemonUiPanel`을 사용할 수 있다(MAY). 패널은 배경과 제목만 그리며 자식의 배치·클리핑·입력을 소유한다고 가정해서는 안 된다(MUST NOT).
- 밝은 패널을 쓰는 테마는 `shellText`, `panelText`, `panelAltText` 전경 토큰으로 읽기 가능한 대비를 제공해야 한다(MUST). 토큰이 없으면 기존 기본 글자색으로 폴백한다(MUST).
- 진행 단계는 `UiStepTrackSpec`으로 순서와 `LOCKED`, `AVAILABLE`, `CLEARED`, `ACTIVE` 상태를 선언해야 한다(SHOULD). 상태는 색만으로 구분해서는 안 되며 각 노드 안의 표식도 함께 제공해야 한다(MUST).
- 지속 경고·안내는 `UiCalloutSpec`으로 본문 배치 흐름 안에 남길 수 있다(MAY). 일시 피드백인 toast와 서버 확인을 막는 dialog를 대체해서는 안 된다(MUST NOT).
- 순서가 의미 있는 파티 선출은 `UiOrderedSelectionState`를 사용할 수 있다(MAY). 선택 순서는 1부터 표시하고 방향키로 앞·뒤 이동할 수 있어야 한다(SHOULD). 최대 선택 수와 비활성 항목은 상태 객체가 거부한다(MUST).

## 3. 렌더 슬롯

`CobblemonUiRenderSlot`은 접근 가능한 이름, 내부 여백과 실패 폴백을 가져야 한다(MUST). 현행 콘텐츠 종류는 다음과 같다.

- `Texture`: `UiIcon` 텍스처
- `Item`: Minecraft `ItemStack`
- `PlayerSkin`: 지정한 스킨을 사용하는 wide/slim 플레이어 모델
- `Empty`: 지정 아이콘 또는 물음표 폴백

슬롯은 자기 경계에 scissor를 적용해야 한다(MUST). 현행 API를 일반 Cobblemon 포켓몬 모델 또는 Bedrock 모델 렌더러로 설명해서는 안 된다(MUST NOT). 그런 렌더러는 실제 두 번째 소비 요구가 생긴 뒤 별도 계약으로 추가한다(SHOULD).

## 4. 배치와 스크롤 입력

- `UiAnchorLayout`은 아홉 기준점과 논리 오프셋으로 고정 장식을 배치할 수 있다(MAY). 화면 크기·패딩·중복 키 검증은 기존 레이아웃 계약과 같은 방식으로 수행한다(MUST).
- 스크롤 뷰포트는 휠, Page Up/Down, Home/End와 스크롤바 클릭·드래그를 제공해야 한다(MUST).
- 포커스가 새 위젯으로 바뀌면 해당 위젯을 보이게 조정해야 한다(SHOULD). 같은 포커스를 매 프레임 다시 노출해 사용자의 수동 스크롤을 되돌려서는 안 된다(MUST NOT).
- 스크롤 적용 직후 자동 캡처는 같은 프레임 버퍼를 증거로 사용해서는 안 되며 최소 후속 렌더 프레임을 기다려야 한다(MUST).

## 5. 의도적 비지원

다음 항목은 이번 계약에 포함하지 않는다.

- 텍스트 입력과 숫자 입력
- 슬라이더
- 대형 데이터용 가상화 목록
- 전역 행동 디스패처
- 일반 Cobblemon 포켓몬·Bedrock 모델 렌더러
- 화면 경계 위로 뜨는 전역 combo popup portal
- 패널의 자식 소유·자동 배치·클리핑

소비 화면이 실제로 요구하기 전까지 추측성 공용 API를 추가하지 않는다(SHOULD NOT).

## 6. 검증 근거와 한계

- 순수 계약·테마·모듈 테스트 54개가 통과했다.
- Cobblemon 1.8.1 실제 싱글플레이 오버월드에서 Pixel League 갤러리를 열고 휠, Page Up, End, 모달 취소와 화면 닫기를 자동 검증했다.
- 하단 캡처에서 단계 트랙, 순서형 선택, player skin 슬롯, 여러 줄 본문과 지속 경고가 실제 렌더됐고 밝은 패널의 어두운 전경색을 확인했다.

자동 입력과 캡처는 물리 마우스·키보드, 화면 읽기 프로그램, 프레임 시간, 외부 Visual Pack 또는 실제 League 터미널 통합을 증명하지 않는다(MUST NOT).
