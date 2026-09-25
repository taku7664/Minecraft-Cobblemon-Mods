# MBC UI 리소스 경계와 Minecraft 우선 구현 수정 결정

| 항목 | 값 |
| --- | --- |
| Status | `shared` |
| Effective | 2026-09-25 |
| Updates | `MORE_BATTLE_CONTENT_DECLARATIVE_UI_FRAMEWORK_DECISION.md`의 화면 문서, 리소스팩, 웹 미리보기와 구현 순서 |
| Does not obsolete | `MbcUI` 소유권, owo 내부 백엔드 후보, 점진 이행과 실제 게임 검증 원칙 |
| 첫 사용처 | `more-battle-content-league-challenge` |

## 1. 수정 이유

원결정은 화면 문서를 `assets/<namespace>/mbc_ui/screens/`에 두면서 외부 리소스팩은 시각 자산만 교체한다고 규정했다. Minecraft의 `assets`는 리소스팩 우선순위로 덮어쓸 수 있으므로 두 계약은 동시에 성립하지 않는다.

또한 웹과 Minecraft가 같은 화면 문서를 렌더링한다는 목표는 정보 구조 공유에는 유용하지만, Minecraft 글꼴·정수 좌표·포커스·내레이션·scissor·아이템 툴팁·포켓몬과 플레이어 3D 렌더링의 동등성을 보장하지 않는다. 따라서 화면 계약과 시각팩의 신뢰 경계를 분리하고 실제 Minecraft를 첫 승인 표면으로 바꾼다.

## 2. 수정된 MbcUI 공개 계약

MbcUI 첫 버전의 공개 계약은 타입이 있는 Kotlin 화면 모델과 컴포넌트 API다(MUST). 화면 구조, 컴포넌트 ID, 상태 바인딩, 행동 ID, 확인 단계와 포커스 순서는 컴파일된 코드가 소유한다(MUST).

- League Challenge는 MBC가 공개한 화면 모델·컴포넌트·행동 타입만 사용한다(MUST).
- owo, `GuiGraphics`, `MbcGuiStyle`, 내부 레이아웃 사각형은 공개 계약이 아니다(MUST NOT).
- v1은 외부에서 로드하는 범용 화면 XML·JSON 문법을 제공하지 않는다(MUST NOT).
- 직렬화된 컴포넌트 트리를 만들더라도 테스트·진단·웹 미리보기용 내보내기 산출물일 뿐 런타임 권위 입력이 아니다(MUST).
- 화면 계약은 Minecraft `ResourceManager`의 리소스팩 우선순위로 교체되지 않아야 한다(MUST).

향후 팩 제작자가 화면 구조까지 바꾸는 요구가 실제로 확인되면 별도 `UI Pack` 계약으로 설계한다(MAY). 이 경우 Visual Pack과 다른 신뢰·호환성·행동 검증 계약을 가져야 하며, 본 수정 결정을 조용히 확장해서는 안 된다(MUST NOT).

## 3. Visual Pack 허용 범위

MBC와 League Challenge의 기본 테마는 각 JAR에 포함한다(MUST). 공식적으로 지원하는 외부 `Visual Pack`은 다음 자산만 덮어쓸 수 있다(MAY).

- 패널, 프레임, 배경, 아이콘, 초상화와 짧은 애니메이션 텍스처
- 색, 투명도, 텍스처 ID와 같이 `cosmetic`으로 명시된 테마 토큰
- 레이아웃 상자를 바꾸지 않는 모서리, 테두리와 장식 토큰의 검증된 범위

다음은 Visual Pack 계약에 포함하지 않는다(MUST NOT).

- 화면·컴포넌트 트리
- 행동 ID와 클릭·키보드 바인딩
- 확인 단계, 포커스·내레이션 순서
- 서버 상태 필드와 표시 조건
- 필수 경고·잠금 이유·보상 의미를 바꾸는 문자열
- 패널 위치, 행동 영역과 입력 히트박스를 바꾸는 레이아웃 수치

일반 리소스팩은 Minecraft 특성상 다른 `assets` 파일도 로컬에서 덮어쓸 수 있다. 위 목록은 MBC가 호환성을 보장하는 Visual Pack 계약이지 악의적 로컬 팩을 막는 보안 경계가 아니다(MUST).

내장 기본 토큰은 코드의 안전한 기본값으로도 존재해야 한다(SHOULD). 외부 테마 문서가 누락되거나 검증에 실패하면 해당 외부 테마 전체를 버리고 코드 기본값과 내장 텍스처로 돌아간다(MUST).

권장 외부 경로는 다음과 같다.

```text
assets/<namespace>/mbc_ui/themes/*.json
assets/<namespace>/textures/gui/mbc_ui/panels/*.png
assets/<namespace>/textures/gui/mbc_ui/icons/*.png
assets/<namespace>/textures/gui/mbc_ui/backgrounds/*.png
assets/<namespace>/textures/gui/mbc_ui/portraits/*.png
```

`assets/<namespace>/mbc_ui/screens/`는 v1 계약에서 사용하지 않는다(MUST NOT).

## 4. 웹 미리보기의 지위

웹 미리보기는 제품 런타임과 동등한 두 번째 렌더러가 아니라 비권위 디자인 보조 도구다(MUST). 실제 Minecraft 화면을 먼저 만들고 승인한 뒤, 반복 속도를 높일 가치가 확인될 때 추가한다(SHOULD).

웹 도구는 다음만 공유할 수 있다(MAY).

- 같은 fixture 상태 값
- 컴포넌트 ID, 상태 이름과 행동 ID의 내보낸 manifest
- 색·텍스처·상태 토큰
- 논리 뷰포트 크기와 콘텐츠 우선순위

웹 CSS 배치와 Minecraft 픽셀 배치가 같다고 가정하면 안 된다(MUST NOT). 두 구현 사이의 자동 검증도 픽셀 동일성이 아니라 컴포넌트 존재, 상태·행동 ID, 필수 문구와 정보 우선순위를 대상으로 한다(MUST).

## 5. Minecraft 우선 구현 순서

### UI-A: 타입 계약

- League 홈에 필요한 상태, 행동과 컴포넌트 트리를 Kotlin 타입으로 정의한다.
- 잘못된 상태 바인딩, 중복 컴포넌트 ID와 등록되지 않은 행동은 테스트에서 거부한다.
- 화면 구조는 리소스팩 없이 조립한다.

### UI-B: 단일 Minecraft 스파이크

- 같은 League 홈 fixture를 현재 MBC 코드 드로잉 기반과 owo 후보 기반으로 각각 최소 구현한다.
- 320×240, 426×240, 640×360에서 정보 누락, 코드량, 예외 처리와 실제 시각 차이를 비교한다.
- 3D 호스트 슬롯, 툴팁, scissor, 키보드 포커스와 내레이션을 포함한다.

### UI-C: 백엔드와 시각 방향 승인

- 실제 Minecraft 캡처를 같은 크기·상태로 나란히 비교한다.
- 빡대리님의 시각 승인과 접근성·입력·성능 검증 뒤 백엔드를 확정한다.
- 스파이크를 위해 만든 버려지는 구현은 제품 API로 굳히지 않는다.

### UI-D: 테마와 Visual Pack

- 코드 기본 토큰과 JAR 내장 League 테마를 만든다.
- 허용 목록 기반 외부 테마 파서와 전체 테마 폴백을 구현한다.
- 화면·행동 파일을 외부 팩에 제공하지 않는다.

### UI-E: League 수직 단면

- 승인된 Minecraft 백엔드로 홈 → 체육관 상세 → 파티 확인 → 결과를 구현한다.
- 공용 컴포넌트가 실제 네 화면에서 반복 사용되는지 확인한 뒤에만 API를 넓힌다.

### UI-F: 선택형 웹 보조 도구

- 실제 Minecraft 컴포넌트 표면이 안정된 뒤 fixture·manifest 기반 웹 미리보기를 평가한다.
- 웹 도구의 유지 비용이 반복 속도 이득보다 크면 구현하지 않을 수 있다(MAY).

### UI-G: 서버 상태 연결과 점진 이행

- fixture를 서버 권위 상태로 교체한다.
- 기존 MBC 화면은 한 화면씩 이행하고 실제 검증 전까지 구 화면을 폴백으로 유지한다.

## 6. 기각·보류한 대안

| 대안 | 상태 | 이유 |
| --- | --- | --- |
| 화면 XML을 일반 `assets`에서 로드 | 기각 | Visual Pack이 구조와 행동까지 덮어쓸 수 있어 계약과 충돌한다. |
| 웹과 Minecraft의 픽셀 동등성을 목표로 한 이중 렌더러 | 기각 | 글꼴·입력·3D·툴팁·클리핑 차이가 크고 두 레이아웃 엔진의 드리프트를 만든다. |
| 범용 XML 파서부터 구현 | 기각 | League 실제 화면보다 UI 언어와 검증기의 범위가 먼저 커진다. |
| fixture·manifest 기반 비권위 웹 보조 도구 | 보류 | Minecraft 수직 단면 뒤 반복 비용 절감 효과를 보고 채택한다. |
| 화면 구조까지 바꾸는 UI Pack | 보류 | 실제 수요와 별도 신뢰 계약이 생길 때 후속 결정으로 다룬다. |

## 7. 완료 기준 수정

- 외부 Visual Pack을 제거하거나 깨뜨려도 화면 구조, 행동과 필수 의미가 변하지 않는다.
- 리소스팩으로 화면·행동 계약을 교체할 수 없다.
- 기본 JAR만으로 완전한 화면이 보인다.
- 첫 League 홈은 실제 Minecraft에서 세 화면 폭, 3D 슬롯, 툴팁, 입력과 내레이션 검증을 통과한다.
- 웹 도구를 만들 경우 fixture와 manifest의 의미 일치만 보장하며 픽셀 동등성을 주장하지 않는다.
- MbcUI 공개 API는 최소 두 개의 실제 League 화면에서 반복되는 요구가 확인된 뒤 확장한다.
