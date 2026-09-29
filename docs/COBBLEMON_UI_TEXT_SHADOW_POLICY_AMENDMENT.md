# Cobblemon UI 글자 그림자 정책 후속 결정

| 항목 | 값 |
| --- | --- |
| Status | `shared` |
| Effective | 2026-09-26 |
| Updates | `COBBLEMON_UI_PIXEL_LEAGUE_THEME_CORRECTION.md` |
| 적용 대상 | `cobblemon-ui-kit`의 버튼 글자 렌더링과 후속 공용 텍스트 위젯 |

## 1. 기본 정책

버튼 글자 그림자는 기본적으로 꺼야 한다(MUST). 불투명 패널과 버튼 위에서 Minecraft 기본 그림자를 일괄 적용하면 글자 오른쪽 아래에 검은 중복 픽셀이 생겨 작은 글자와 도트 테마의 획을 읽기 어려워진다.

그림자 기능 자체를 삭제하지는 않는다(MUST NOT). 배경 이미지나 밝기 변화가 큰 표면처럼 실제 대비 확보에 필요한 호출부만 명시적으로 켤 수 있다(MAY).

## 2. API 계약

- `UiButtonStyle.textShadow`는 테마·상태별 기본값을 제공한다(MAY). 기본값은 `false`다(MUST).
- `UiButtonSpec.textShadow`는 `Boolean?` 호출부 재정의다(MUST).
- `null`은 테마 값을 따르고, `true`와 `false`는 각각 테마 값을 덮어쓴다(MUST).
- 가운데 정렬과 글자 축척은 그림자 설정과 무관하게 동일해야 한다(MUST).
- 화면별 코드가 Minecraft의 그림자 강제 가운데 정렬 함수를 직접 호출하면 안 된다(MUST NOT).

권장 호출은 다음과 같다.

```kotlin
UiButtonSpec(
    title = label,
    textShadow = true // 실제로 필요한 드문 표면만 명시
)
```

## 3. 검증

- 기본 테마가 그림자를 끄는지 검증
- 테마가 켠 그림자를 호출부 `false`가 끌 수 있는지 검증
- 테마가 끈 그림자를 호출부 `true`가 켤 수 있는지 검증
- 전체 30개 단위 테스트와 빌드 통과
- `ui-kit-clean` 실제 월드에서 `pixel_league` 상단·스크롤 캡처, 포커스·닫기와 정상 종료 확인

```text
cobblemon-ui-kit/run/screenshots/ui-kit-world-pixel_league-top-427x240.png
cobblemon-ui-kit/run/screenshots/ui-kit-world-pixel_league-scrolled-427x240.png
```
