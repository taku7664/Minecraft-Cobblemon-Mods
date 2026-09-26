# Cobblemon UI 픽셀 프레임 그림자 간격 후속 결정

| 항목 | 값 |
| --- | --- |
| Status | `shared` |
| Effective | 2026-09-26 |
| Updates | `COBBLEMON_UI_PIXEL_LEAGUE_THEME_CORRECTION.md` |
| 적용 대상 | `cobblemon-ui-kit`의 `UiBorder.PixelFrame`과 이를 사용하는 테마 |

## 1. 기본 정책

공용 `UiBorder.PixelFrame`의 하드 섀도 기본 간격은 논리 픽셀 `1`이어야 한다(MUST). 카드·버튼 같은 위젯의 그림자가 본체와 과도하게 떨어져 별도 상자처럼 보이지 않게 하기 위함이다.

테마와 호출부는 `shadowOffset`을 `0` 이상의 값으로 재정의할 수 있다(MAY). 그림자를 없애야 하는 표면은 간격만 바꾸는 대신 테두리 없음 또는 투명한 그림자 색을 사용해야 한다(SHOULD).

## 2. `pixel_league` 계층

- 화면 전체 외곽 셸은 논리 픽셀 `2`를 사용해야 한다(MUST).
- 패널·카드·버튼 등 내부 위젯은 논리 픽셀 `1`을 사용해야 한다(MUST).
- 셸의 한 단계 큰 간격은 최상위 화면 계층을 구분하기 위한 예외이며 공용 기본값으로 승격하지 않는다(MUST NOT).

## 3. 검증

- 기본 `PixelFrame.shadowOffset == 1` 단위 테스트 통과
- `pixel_league` 셸 `2`, 내부 패널 `1` 단위 테스트 통과
- 전체 31개 단위 테스트와 빌드 통과
- 개발 월드 `ui-kit-clean`에서 `pixel_league` 상단·스크롤 렌더, 포커스, 닫기와 클라이언트 정상 종료 확인

```text
cobblemon-ui-kit/run/screenshots/ui-kit-world-pixel_league-top-427x240.png
cobblemon-ui-kit/run/screenshots/ui-kit-world-pixel_league-scrolled-427x240.png
```
