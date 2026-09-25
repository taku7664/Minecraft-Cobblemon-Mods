# League Challenge GUI 구현 상태

- 상태: `non-normative progress record`
- 기준 브랜치: `feature/league-ui-foundation`
- 최종 갱신: 2026-09-25
- 규범 계약: [GUI_FRAMEWORK_AND_RESOURCE_PACK.md](GUI_FRAMEWORK_AND_RESOURCE_PACK.md), [GUI 착수 순서 수정 결정](../../docs/MORE_BATTLE_CONTENT_LEAGUE_UI_BOOTSTRAP_AMENDMENT.md)

이 문서는 완료 증거와 재개 지점만 기록한다. 제품 계약을 새로 만들거나 기존 결정을 수정하지 않는다.

## 완료

| 커밋 | 완료 내용 | 검증 |
| --- | --- | --- |
| `578da55` | 실행 가능한 GUI 착수 순서, 실험 API 승격과 롤백 경계 | 문서 교차참조, staged diff 검사 |
| `3a2a2be` | League Gradle 모듈, 서버·클라이언트 진입점, 개발 환경 전용 `/mbc-league-ui`, 한·영 번역 | 모듈 `build`, 계약 테스트 4개, JAR 내용 검사 |
| `ca767a3` | MBC의 백엔드 비종속 experimental 화면·행동·상태 계약과 검증기 | MBC `unitTest` 950개 |
| `ac86714` | 계급 경계와 여섯 fixture, 세 폭 레이아웃, 코드 드로잉 League 홈, fixture별 개발 하위 명령 | League `build`, 테스트 15개 |

## 2026-09-25 타이틀 화면 개발 fixture 스파이크

Minecraft 1.21.1 개발 클라이언트의 타이틀 파노라마 위에서 `badges_3` fixture의 코드 드로잉과 owo 후보를 각각 열고 캡처했다. 실제 월드에는 들어가지 않았다. `320×240`, `427×240`에서 정보 구조와 반응형 배치가 일치했고, `640×360`에서는 영어와 한국어를 각각 확인했다. 640×360 네 화면은 이 fixture 기준 글자 잘림이 없었다. 캡처와 상세 관찰은 [UI_BACKEND_SPIKE_RESULT.md](UI_BACKEND_SPIKE_RESULT.md)에 있다.

클라이언트 기동 과정에서 두 의존성 문제도 확인해 정정했다.

- League의 MBC 프로젝트 의존성을 Loom `modImplementation`으로 선언했다.
- owo는 전이 Endec 의존성을 제공하는 Wisp Maven 좌표를 사용한다.

첫 코드 드로잉 캡처에서 배경 처리가 중복되어 사용자 정의 내용이 흐려지는 결함을 발견했고, 수정 후 다시 캡처했다. 이 항목들은 실제 클라이언트 렌더 경로로 확인했지만 실제 월드, 배포 프로필과 서버에는 적용하지 않았다.

하네스가 `Screen.keyPressed`에 주입한 TAB 포커스, ENTER 행동 전달, 내레이션 항목과 닫기 경로도 영어·한국어 양쪽 백엔드에서 통과했다. 물리 입력 검증은 아니다. owo 기본 어댑터가 내부 버튼 내레이션을 바닐라 화면으로 전달하지 않는 결함을 발견해 스파이크에 전달 어댑터를 추가했다. 이 어댑터는 owo 채택 시 League가 아니라 Cobblemon UI 툴킷의 비공개 렌더러가 소유해야 한다.

`long_disabled` fixture로 긴 관장명·보상명, 잠긴 행동과 3D 초상화 슬롯을 `427×240` 영어·한국어 양쪽 백엔드에서 재검증했다. 줄 수 기반 배치로 글자 겹침을 없앴고, Owo 모델을 초상화 컴포넌트의 surface 단계에서 렌더해 실제 캡처에 표시했다. 잠긴 버튼은 포커스와 설명을 유지하되 행동은 전달하지 않는다. 바닐라 Steve는 렌더·scissor 검증용 fixture이며 실제 관장 스킨 증거가 아니다.

언어 계약 테스트는 MBC의 영어·한국어 574개 키와 League의 33개 키가 각각 동일하고 값·포맷 자리표시자가 유효함을 확인한다. MBC는 이미 두 언어가 완비되어 번역값을 바꾸지 않았다.

두 스파이크는 같은 고정 폭 행동 버튼, 같은 글자 크기와 유사한 직사각형 표면을 사용했다. 따라서 owo가 커스텀 표면을 허용한다는 점만 확인했으며, 내용 폭·의미 variant·아이콘·보조 문구, 목록·탭·콤보박스와 테마 스냅샷을 갖춘 공용 위젯 프레임워크는 아직 구현하지 않았다.

개발 환경에서 다음 화면 상태를 열 수 있다.

```text
/mbc-league-ui
/mbc-league-ui badges_0
/mbc-league-ui badges_2
/mbc-league-ui badges_3
/mbc-league-ui badges_5
/mbc-league-ui badges_8
/mbc-league-ui champion
/mbc-league-ui long_disabled
/mbc-league-ui-owo
/mbc-league-ui-owo badges_3
/mbc-league-ui-owo long_disabled
```

기본 명령은 `badges_3` fixture를 연다. 두 경로 모두 Fabric 개발 환경에서만 등록된다. owo 경로는 백엔드 비교용이며 제품 API가 아니다.

## 아직 검증하지 않은 것

- `client.level`과 `client.player`가 존재하는 실제 월드 화면
- 터미널 블록 우클릭 → 서버 패킷 → 화면 열기
- 모든 fixture의 시각 품질
- 물리 마우스 입력과 화면 읽기 프로그램 음성 출력
- 리소스팩 사용자 정의 관장 스킨과 `default`/`slim` 모델
- 프레임 시간과 입력 지연
- 공용 위젯 계열과 테마 교체의 일관성

현재 통과한 빌드와 단위 테스트는 위 런타임·시각 증거를 대신하지 않는다.

## 의도적으로 연결하지 않은 것

- PokeBadges 필수 의존성: 대상 버전, 모드 ID와 API 확인 전
- 레벨캡 숫자와 제공자
- 진행 저장과 서버 권위 상태
- League 터미널, 전투, 보상과 MBC 시설 잠금
- ModMenu 설정 화면: 아직 사용자 설정이 존재하지 않음
- 외부 Visual Pack과 제품 리소스 자산

따라서 현재 JAR은 GUI 개발 기준선이며 배포 가능한 League Challenge 제품이 아니다.

## 정확한 재개 순서

1. 독립된 Cobblemon UI 툴킷 소스 모듈과 불변 테마 스냅샷의 최소 계약을 만든다.
2. Component Gallery에 버튼 크기·variant·상태, 패널, 목록, 스크롤과 한·영 긴 문자열을 구현한다.
3. League 홈을 내용 폭·의미 위젯으로 교체하고 실제 월드의 터미널·서버 패킷으로 연다.
4. 리소스팩 사용자 정의 관장 스킨과 `default`/`slim` 모델을 같은 슬롯에서 검증한다.
5. 물리 마우스·키보드와 화면 읽기 프로그램 음성 출력을 수동 확인하고 프레임 시간·입력 지연을 측정한다.
6. 근거를 모아 백엔드와 첫 시각 방향을 결정한다.
7. owo 채택 시 구현과 내레이션 전달 어댑터를 툴킷 비공개 렌더러로 이동하고, 기각 시 owo 스파이크를 제거한다.
8. 미선택 구현과 개발용 직접 의존성을 제거한 뒤 체육관 상세 화면으로 확장한다.
