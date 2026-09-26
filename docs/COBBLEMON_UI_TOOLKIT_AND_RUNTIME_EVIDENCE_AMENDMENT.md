# Cobblemon UI 툴킷과 런타임 증거 후속 결정

| 항목 | 값 |
| --- | --- |
| Status | `shared` |
| Effective | 2026-09-25 |
| Updates | `MORE_BATTLE_CONTENT_DECLARATIVE_UI_FRAMEWORK_DECISION.md`, `MORE_BATTLE_CONTENT_UI_RESOURCE_BOUNDARY_AMENDMENT.md`, `MORE_BATTLE_CONTENT_LEAGUE_UI_BOOTSTRAP_AMENDMENT.md`, `COBBLEMON_UI_DESIGN_SYSTEM_BOUNDARY_DECISION.md` |
| Does not obsolete | 타입 있는 화면 행동, 서버 권위 검증, 내장 기본 자산, 시각 전용 외부 팩 경계 |
| 적용 대상 | `more-battle-content`, `more-battle-content-league-challenge`, `cobblemon-battle-ui`와 후속 Cobblemon 클라이언트 애드온 |
| 주 독자 | UI 툴킷·화면·리소스팩을 구현하는 개발자 |

## 1. 수정 이유

기존 스파이크는 코드 드로잉과 owo 모두 임의 표면을 그릴 수 있음을 확인했지만, 폭·글꼴·상태·입력 계약을 가진 재사용 위젯 체계를 증명하지 않았다. 실제 구현도 넓이가 고정된 동일한 직사각형 행동 버튼과 같은 글자 크기를 반복한다. 따라서 “owo로도 커스텀 외형을 그릴 수 있다”는 결과만으로 UI 프레임워크 방향을 확정하면 안 된다(MUST NOT).

이 문서는 다음 요구를 현행 결정으로 추가한다.

1. Cobblemon 화면들이 공유할 수 있는 위젯·테마·입력 계층을 만든다.
2. League Challenge와 독립 설치되는 Battle UI가 같은 런타임 계약을 사용할 수 있게 한다.
3. 기본 화면은 코드와 JAR 내장 자산만으로 완전하게 작동하며, 외부 리소스팩은 시각만 바꾼다.
4. 타이틀 화면 fixture와 실제 월드 검증을 서로 다른 증거 등급으로 기록한다.

## 2. 제품 범위

새 계층은 임시 명칭 `Cobblemon UI Kit`인 **Cobblemon 전용 클라이언트 UI 툴킷**이다(MUST). MBC, League Challenge, Better Battle UI처럼 Cobblemon 정보와 상호작용을 표현하는 화면을 우선 대상으로 한다.

- 바닐라 인벤토리, 제작대, 채팅과 모든 Minecraft 화면을 교체하는 범용 UI 모드는 아니다(MUST NOT).
- 첨부된 인벤토리·포켓몬 메뉴·대화창 이미지는 패널 계층, 아이콘 탐색, 슬롯, 상태 막대와 좌우 대화 기록의 **시각·컴포넌트 언어 참고**일 뿐이다.
- 제공 이미지와 외부 Sketchfab 모델의 자산 또는 트레이드 드레스는 라이선스 확인 없이 복제하거나 배포하면 안 된다(MUST NOT).
- 에디터식 드래그앤드롭, 콤보박스와 목록은 가능한 위젯 범주의 예시다. 실제 사용처 없이 전부 선행 구현하지 않는다(SHOULD NOT).

### 2.1 현재 시각 참고

- 첨부 참고 1: 큰 프레임 안에 슬롯 격자, 장비, 제작, 스크롤 목록, 핫바와 통계 막대를 구획한 대시보드
- 첨부 참고 2: 아이콘과 색상 역할로 여러 기능을 묶은 Pokémon식 다열 메뉴와 하단 탐색
- 첨부 참고 3: 플레이어와 상대 패널을 분리하고 선택지를 행으로 쌓은 반투명 대화 화면
- [PIXELAR의 Gui Menu Pokémon](https://sketchfab.com/3d-models/gui-menu-pokemon-5b843b9bb5624932b131dd6e2ea6472e): 픽셀 프레임, 색상별 기능 묶음과 아이콘 탐색 참고

이 참고들은 “모든 버튼은 비직사각형이어야 한다”거나 “인벤토리 UI를 교체한다”는 요구가 아니다(MUST NOT). 재사용 가능한 위젯 역할, 정보 계층과 테마 일관성을 판단하는 참고다.

## 3. 모듈과 배포 경계

공유 런타임은 MBC 내부 전용 구현이 아니라 독립된 Gradle 클라이언트 소스 모듈 경계로 추출한다(MUST). 이 결정은 `COBBLEMON_UI_DESIGN_SYSTEM_BOUNDARY_DECISION.md`의 “현재는 런타임을 추출하지 않는다” 결론을 갱신한다.

다만 배포 형태는 아직 확정하지 않는다.

- 별도 필수 모드 JAR, 각 소비자 JAR에 포함하는 Jar-in-Jar, 또는 빌드 시 소스 공유 중 무엇을 쓸지는 두 실제 소비자가 같은 생명주기 코드를 사용한 뒤 결정한다(MUST).
- 패키징 결정 전에도 공개 API는 MBC, League Challenge, Battle UI의 도메인 타입과 패킷 타입을 참조하면 안 된다(MUST NOT).
- Battle UI는 MBC 없이 설치·실행되는 현재 경계를 유지해야 한다(MUST).
- owo를 채택해도 공개 타입에 owo 컴포넌트, XML 또는 어댑터 타입을 노출하면 안 된다(MUST NOT). owo는 교체 가능한 내부 렌더 백엔드일 뿐이다.

## 4. 툴킷 계층

```text
Cobblemon 화면 상태와 사용자 의도
                ↓
  도메인 컴포넌트와 화면 조립
                ↓
 공용 위젯 + 레이아웃 + 입력/포커스
                ↓
 테마 스냅샷 + 내장 시각 자산
                ↓
 Minecraft 렌더 백엔드(자체/owo 후보)
```

툴킷은 다음을 소유한다(MUST).

- 크기 측정, 배치, 클리핑, 스크롤과 반응형 레이아웃
- 마우스·키보드의 단일 포커스 모델, 활성화, 취소와 내레이션
- `normal`, `hover`, `focus`, `pressed`, `disabled`, `selected` 위젯 상태
- 위젯 수명주기와 화면별 상태 복원 규칙
- 테마 토큰 해석과 누락 자산 폴백

각 소비 모드는 화면 상태, 행동 ID, 서버 요청, 도메인 검증과 화면 조립을 소유한다(MUST).

## 5. 첫 위젯 집합

첫 안정 후보는 League 홈과 Battle UI에서 실제로 필요한 반복 요소만 포함한다(SHOULD).

| 범주 | 후보 |
| --- | --- |
| 행동 | `Button`, `IconButton`, `TextButton`, `ActionMenu` |
| 탐색 | `Tabs`, `SegmentedControl`, `Dropdown`/`ComboBox` |
| 표면 | `Panel`, `Card`, `SectionHeader`, `Dialog` |
| 컬렉션 | `ListView`, `ScrollPanel`, `Scrollbar`, `SlotGrid`, `SelectableCard` |
| 상태 | `ProgressBar`, `BadgeTrack`, `StatBar`, `Tooltip`, `Toast` |
| 조작 | 실제 사용처가 생긴 뒤의 `DragSource`, `DropTarget` |

Cobblemon 전용 조합 위젯은 공용 원시 위젯 위에 둔다(SHOULD). 첫 후보는 `TrainerPortrait`, `PokemonSlot`, `RankTrack`, `BattleActionMenu`, `BattleNarrationBar`, `BattleTranscript`다.

버튼은 모두 같은 고정 폭·글자 크기를 쓰면 안 된다(MUST NOT). 호출자는 임의 픽셀을 매번 넘기기보다 다음 의미 속성을 우선 사용해야 한다(SHOULD).

```text
variant = PRIMARY | SECONDARY | DANGER | GHOST | ICON
size = SMALL | MEDIUM | LARGE
width = CONTENT | FILL | FIXED
icon, title, supportingText
```

`FIXED` 폭과 세부 패딩·글자 크기 덮어쓰기는 특수 화면에서 허용할 수 있지만(MAY), 기본 경로는 테마의 의미 토큰을 사용해야 한다(MUST).

## 6. 테마 객체

툴킷은 전역 접근이 가능한 테마 레지스트리와 **불변 현재 테마 스냅샷**을 제공한다(MUST). 임의 코드가 전역 색·패딩 값을 직접 변경하는 가변 싱글톤은 제공하면 안 된다(MUST NOT).

테마는 다음 토큰을 묶는다.

- 색과 표면
- 글자 역할과 축척
- 간격, 패딩, 최소 터치 영역과 모서리
- 위젯 종류·상태별 sprite와 nine-slice 정의
- 아이콘, 소리와 제한된 전환 시간

테마 교체는 리소스 재로드 경계에서 검증된 새 스냅샷으로 원자적으로 바뀌어야 한다(MUST). 누락·잘못된 테마는 JAR 내장 기본값 전체로 폴백한다(MUST).

## 7. 코드와 리소스팩의 책임

프레임워크와 리소스팩은 서로 대체재가 아니며 둘 다 필요하다.

- 코드는 행동, 레이아웃, 포커스, 입력, 내레이션, 상태와 히트박스를 소유한다(MUST).
- 각 제품 JAR은 외부 팩 없이 완전한 기본 테마·아이콘·텍스처·필요 글꼴을 포함한다(MUST).
- 선택형 Visual Pack은 허용된 색, 글자 역할, 간격 범위, texture/icon/font/sound 리소스와 상태 sprite만 덮어쓸 수 있다(MAY).
- Visual Pack은 화면 흐름, 행동 ID, 서버 검증, 패킷, 진행도, 포커스 순서와 히트박스 의미를 바꾸면 안 된다(MUST NOT).
- 늘어나는 프레임과 버튼은 가능한 경우 nine-slice 또는 동등한 확장 가능한 자산 규칙을 사용한다(SHOULD).

## 8. 구현 게이트

1. **Component Gallery:** 실제 글꼴로 모든 버튼 크기·상태, 패널, 목록, 스크롤과 한·영 긴 문자열을 한 화면에서 비교한다.
2. **League 소비자:** 리그 홈의 고정 폭 행동 버튼을 의미 크기·내용 폭 위젯으로 교체한다.
3. **Battle 소비자:** 행동 메뉴, 연출 내레이션과 기록 화면을 같은 툴킷으로 구현한다.
4. **공용화 판정:** 두 소비자에서 반복된 API만 안정 계약으로 승격한다.
5. **패키징 판정:** 별도 JAR과 내장 배포의 업데이트·충돌·독립 설치 비용을 비교해 결정한다.

갤러리와 League fixture만으로 Battle UI의 실제 전투 생명주기를 증명했다고 보고하면 안 된다(MUST NOT).

## 9. 런타임 증거 등급 수정

기존 League 캡처는 Minecraft 1.21.1 클라이언트의 실제 렌더 경로와 `Screen.keyPressed` 주입을 사용했지만, 월드에 진입하지 않고 타이틀 파노라마 위에서 개발 fixture를 열었다. 따라서 증거 이름은 **타이틀 화면 개발 fixture 캡처**다.

이 증거가 확인한 것은 렌더 결과, 논리 화면 크기, 번역 폭, 주입된 TAB/ENTER, 내레이션 항목 생성과 닫기 경로뿐이다. 다음을 확인하지 않았다(MUST NOT).

- `client.level != null`, `client.player != null`인 실제 월드
- 터미널 블록 우클릭과 서버 패킷으로 화면이 열리는 경로
- 물리 마우스·키보드 및 화면 읽기 프로그램 출력
- 실제 Cobblemon 전투 중 Battle UI 상태 전환

“실제 월드 검증”은 위 조건을 충족한 수동·자동 증거에만 사용한다(MUST). 빌드, 단위 테스트, 타이틀 fixture와 정적 캡처는 각자의 증거로 따로 기록한다.

## 10. 기각하거나 보류한 대안

| 대안 | 상태 | 이유 |
| --- | --- | --- |
| 리소스팩만으로 UI 프레임워크 구현 | 기각 | 입력, 포커스, 레이아웃, 상태와 행동 계약을 소유할 수 없다. |
| owo 기본 위젯을 조합하면 프레임워크가 완성된다고 간주 | 기각 | 현재 스파이크는 동일한 고정 폭·동일 글자 문법을 되풀이했다. |
| 모든 Minecraft 화면을 교체하는 범용 모드 | 기각 | Cobblemon 화면의 실제 요구보다 범위와 호환성 비용이 지나치게 크다. |
| 모든 위젯을 매개변수 픽셀값으로 자유 설정 | 기각 | 화면마다 시각 규칙이 갈라져 일관된 테마 교체가 불가능해진다. |
| 지금 즉시 별도 필수 UI 모드 JAR 확정 | 보류 | 독립 소비자 둘의 실제 통합 전에는 배포 비용과 API 폭을 판단할 근거가 부족하다. |
