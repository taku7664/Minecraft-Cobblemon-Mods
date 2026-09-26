# Cobblemon UI 디자인 계약과 런타임 경계 후속 결정

| 항목 | 값 |
| --- | --- |
| Status | `shared` |
| Effective | 2026-09-25 |
| Updates | `MORE_BATTLE_CONTENT_DECLARATIVE_UI_FRAMEWORK_DECISION.md`, `MORE_BATTLE_CONTENT_UI_RESOURCE_BOUNDARY_AMENDMENT.md`의 저장소 간 재사용 범위 |
| Does not obsolete | MBC가 `MbcUI`를 소유하고 League Challenge가 그 계약을 사용하는 구조 |
| 적용 대상 | `more-battle-content`, `more-battle-content-league-challenge`, `cobblemon-battle-ui` |
| 주 독자 | 각 모드의 GUI를 구현하는 개발자 |

## 1. 결정

저장소 공통 UI는 하나의 런타임 프레임워크가 아니라 **디자인 계약**으로 공유한다(MUST). 디자인 계약은 의미 토큰, 시각 문법, 고정 fixture와 캡처 합격 기준을 소유한다. 위젯 생명주기, 입력 라우팅, 렌더 백엔드와 패킷은 각 모드가 소유한다(MUST).

- MBC와 League Challenge는 MBC 내부의 `MbcUI`와 `MbcGuiStyle`을 계속 사용한다(MUST).
- `cobblemon-battle-ui`처럼 MBC와 무관하게 설치되는 모드는 UI 때문에 MBC를 의존하면 안 된다(MUST NOT).
- 독립 모드는 이 문서의 의미 토큰을 자체 패키지에 로컬 구현하며, 실제로 쓰는 역할만 두어도 된다(SHOULD).
- 토큰 이름과 색 값이 같다는 이유만으로 공용 JAR, Jar-in-Jar 또는 MBC API 의존성을 추가하지 않는다(MUST NOT).

## 2. 기본 의미 토큰

값은 ARGB 형식이다. 각 모드는 해당 역할을 쓰는 경우 아래 기본값을 제공해야 한다(SHOULD). 테마가 값을 바꾸더라도 역할의 의미는 바꾸면 안 된다(MUST NOT).

| 역할 | 기본값 | 의미 |
| --- | --- | --- |
| `BACKDROP` | `FF080C16` | 전체 화면 배경 |
| `SCRIM` | `6A030612` | 모달 뒤 게임 화면 암전 |
| `SHELL` | `FF080E1D` | 최상위 화면 프레임 |
| `HEADER` | `FF0C1528` | 제목과 고정 헤더 |
| `PANEL` | `FF101A2D` | 기본 콘텐츠 패널 |
| `PANEL_ALT` | `FF0C1525` | 패널 안의 카드와 행 |
| `BORDER` | `FF274562` | 기본 경계선 |
| `BORDER_BRIGHT` | `FF3F7896` | 강조된 경계선 |
| `ACCENT_PRIMARY` | `FF39E4E4` | 주 행동, 아군, 현재 상태 |
| `ACCENT_SECONDARY` | `FF9868FF` | 보조 행동과 브랜드 강조 |
| `ACCENT_CAUTION` | `FFFFC84A` | 주의가 필요한 상태 |
| `ACCENT_DANGER` | `FFFF667A` | 적군, 위험, 파괴적 행동 |
| `ACCENT_GOOD` | `FF62E39B` | 성공, 회복, 양의 변화 |
| `TEXT_PRIMARY` | `FFEAF7FF` | 제목과 핵심 값 |
| `TEXT_SECONDARY` | `FFB9CAD8` | 레이블과 보조 정보 |
| `TEXT_DIM` | `FF71859A` | 비활성·부가 정보 |

색상은 정보를 보조하지만 단독으로 상태를 전달하면 안 된다(MUST NOT). 아군·적군, 성공·위험은 문구, 아이콘, 위치 중 최소 하나로도 구분해야 한다(MUST).

## 3. 시각 문법

- 최상위 셀은 `SHELL`, 콘텐츠는 `PANEL`, 반복 행과 카드는 `PANEL_ALT`로 깊이를 나눈다(SHOULD).
- 패널은 얇은 `BORDER`와 한 가지 의미 액센트를 사용한다(SHOULD). 여러 액센트를 장식으로 동시에 남발하지 않는다(SHOULD NOT).
- 레이아웃, 스크롤, 포커스, 내레이션, 툴팁과 입력 히트박스는 테마가 바꾸지 않는다(MUST).
- 마인크래프트 기본 글꼴을 기준으로 한국어와 영어의 자름·줄바꿈을 각각 확인한다(MUST).

## 4. fixture와 캡처 계약

한 화면의 시각 확정 전에 다음 상태를 고정 fixture로 두어야 한다(MUST).

1. 빈 상태
2. 표준 데이터 상태
3. 최대 밀도와 명시적 overflow 상태
4. 비활성·위험·성공을 포함한 경계 상태
5. 한국어와 영어의 긴 문구 상태

실제 Minecraft 확정 캡처는 동일한 fixture와 GUI 배율에서 변경 전·후를 나란히 비교해야 한다(MUST). 포커스, 호버, 스크롤, 툴팁, 내레이션과 실제 글꼴 폭은 실행 화면에서 확인한다(MUST). HTML 목업, 정적 이미지, 단위 테스트와 빌드는 이 런타임 증거를 대체하지 못한다(MUST NOT).

## 5. 공용 런타임 추출 게이트

별도 UI 런타임 모드나 라이브러리는 다음 모두가 확인된 뒤에만 제안한다(MUST).

1. 독립적으로 배포되는 둘 이상의 모드에서 동일한 렌더·입력 생명주기 코드가 반복된다.
2. 공용화할 인터페이스가 토큰 목록보다 좁고, 각 모드의 패킷·믹스인 경계를 누출하지 않는다.
3. 독립 모드의 서버 없이 작동하는 클라이언트 계약을 보존한다.
4. 공용 모듈 제거나 비활성화가 게임 진행도와 저장 데이터를 훼손하지 않는다.

현재는 토큰 공유만으로 충분하므로 위 게이트를 충족하지 않는다.

## 6. 첫 적용과 롤백

`cobblemon-battle-ui`의 `ChampionsBattleInfoOverlay`를 첫 독립 적용 화면으로 삼는다. 이 단계는 색·패널·텍스트 계층만 바꾼다(MUST). 모달 Z 순서, 입력 차단, 여섯 칸 랭크 트랙, overflow 행, 상태 수집과 레이아웃 좌표는 바꾸면 안 된다(MUST NOT).

롤백은 Battle UI의 로컬 토큰 파일과 해당 오버레이 스타일 변경만 되돌리면 끝나야 한다(MUST). MBC와 League Challenge의 런타임은 롤백 영향을 받지 않아야 한다(MUST).

## 7. 기각한 대안

| 대안 | 상태 | 이유 |
| --- | --- | --- |
| Battle UI가 MBC의 `MbcUI`를 직접 의존 | 기각 | 독립 모드의 설치 경계와 패킷 기반 렌더러를 억지로 결합한다. |
| 즉시 별도 UI 프레임워크 모드를 생성 | 보류 | 공유될 런타임 행동보다 토큰만 확인됐다. 새 필수 의존성의 비용을 정당화하지 못한다. |
| 색 값만 문서 없이 복사 | 기각 | 후속 변경에서 역할과 값이 드리프트하고 캡처 합격 기준이 남지 않는다. |
| Battle UI 전체를 한 번에 리테마 | 기각 | 레이아웃·입력 회귀 범위가 커지고 화면별 비교가 불가능해진다. |
