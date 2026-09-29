# Cobblemon UI 공용 위젯 기초 계약 후속 결정

| 항목 | 값 |
| --- | --- |
| Status | `shared` |
| Effective | 2026-09-26 |
| Updates | `COBBLEMON_UI_SURFACE_STYLE_AMENDMENT.md`, `COBBLEMON_UI_PIXEL_SHADOW_OFFSET_AMENDMENT.md` |
| 적용 대상 | `cobblemon-ui-kit`을 사용하는 League Challenge와 독립 GUI 모드 |

## 1. 표면 형태

공용 표면 렌더러는 다음 형태를 제공해야 한다(MUST).

- `Rectangle`: 직사각형
- `Chamfer`: 선택한 모서리를 픽셀 단위 대각선으로 자른 형태
- `RoundedRectangle`: 지정 반지름의 둥근 사각형
- `Capsule`: 높이를 기준으로 양 끝을 둥글게 만든 형태
- `Circle`: 원형 표면
- `Diamond`: 마름모 표면

`Circle` 아이콘 버튼은 정사각형 크기를 강제해야 한다(MUST). 일반 표면에서 가로와 세로가 다른 `Circle`을 사용하면 타원처럼 래스터되므로 소비자는 원형 의미가 필요한 경우 같은 너비와 높이를 제공해야 한다(SHOULD).

## 2. 아이콘 전용 버튼

`UiButtonSpec.iconOnly`는 `SQUARE`, `CIRCLE`, `DIAMOND` 프리셋을 제공해야 한다(MUST). 화면에 글자를 그리지 않더라도 접근성 제목은 유지해야 하며(MUST), 아이콘과 `ICON` 변형 없이 생성할 수 없어야 한다(MUST NOT).

아이콘 전용 버튼도 기존 버튼과 같은 테마 상태, 포커스, 선택, 눌림, 테두리·채움·불투명도 재정의를 사용해야 한다(MUST).

## 3. 공용 위젯

다음 위젯은 각각 독립된 데이터 계약과 클라이언트 구현을 제공해야 한다(MUST).

- 탭: 선택 상태와 아이콘·너비 정책
- 배지: 중립·정보·성공·주의·위험 의미 색상
- 토글: 현재 불리언 값, 키보드·마우스 버튼 동작과 변경 콜백
- 리스트 항목: 제목, 보조문, 아이콘, 우측 상태문과 선택 상태
- 진행 바: 원본 값·최댓값, 0~1 표시 비율, 선택적 제목과 수치
- 스크롤 뷰포트: 자식 위치 갱신, 가시성, 가위 영역, 휠 소비와 스크롤바

표시 문자열은 한·영 번들에서 같은 키 집합을 유지해야 한다(MUST). 공용 위젯은 owo 타입을 공개 API에 노출하면 안 된다(MUST NOT).

## 4. 현재 제외 범위

콤보박스·드롭다운·체크박스·라디오 그룹·슬라이더·텍스트 입력·툴팁·드래그앤드롭은 이번 계약에 포함하지 않는다. League Challenge 또는 다른 소비자 화면에서 실제 사용 사례가 정해질 때 해당 상호작용 계약과 함께 별도 후속 문서로 추가한다.

## 5. 검증

- 형태 대칭·유효성, 아이콘 전용 버튼 크기·필수값, 진행도·스크롤 계산과 위젯 label 계약을 포함한 37개 단위 테스트 통과
- `:cobblemon-ui-kit:check`와 `:cobblemon-ui-kit:build` 통과
- 개발 월드 `ui-kit-clean`에서 `pixel_league` 갤러리 진입, 포커스, 콘텐츠 끝까지 스크롤, 닫기와 정상 종료 확인
- 상단 캡처에서 기존 버튼 회귀가 없고, 하단 캡처에서 탭·배지·토글·리스트 항목·진행 바 렌더링을 확인

```text
cobblemon-ui-kit/run/screenshots/ui-kit-world-pixel_league-top-427x240.png
cobblemon-ui-kit/run/screenshots/ui-kit-world-pixel_league-scrolled-427x240.png
```
