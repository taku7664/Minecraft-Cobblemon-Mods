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

## 2026-09-25 런타임 스파이크

Minecraft 1.21.1 개발 클라이언트에서 `badges_3` fixture의 코드 드로잉과 owo 후보를 각각 열고 캡처했다. `427×240`과 `320×240`에서 정보 구조와 반응형 배치가 일치했다. 캡처와 상세 관찰은 [UI_BACKEND_SPIKE_RESULT.md](UI_BACKEND_SPIKE_RESULT.md)에 있다.

클라이언트 기동 과정에서 두 의존성 문제도 확인해 정정했다.

- League의 MBC 프로젝트 의존성을 Loom `modImplementation`으로 선언했다.
- owo는 전이 Endec 의존성을 제공하는 Wisp Maven 좌표를 사용한다.

첫 코드 드로잉 캡처에서 배경 처리가 중복되어 사용자 정의 내용이 흐려지는 결함을 발견했고, 수정 후 다시 캡처했다. 이 항목들은 실제 개발 클라이언트 실행으로 확인했지만 배포 프로필과 서버에는 적용하지 않았다.

개발 환경에서 다음 화면 상태를 열 수 있다.

```text
/mbc-league-ui
/mbc-league-ui badges_0
/mbc-league-ui badges_2
/mbc-league-ui badges_3
/mbc-league-ui badges_5
/mbc-league-ui badges_8
/mbc-league-ui champion
/mbc-league-ui-owo
/mbc-league-ui-owo badges_3
```

기본 명령은 `badges_3` fixture를 연다. 두 경로 모두 Fabric 개발 환경에서만 등록된다. owo 경로는 백엔드 비교용이며 제품 API가 아니다.

## 아직 검증하지 않은 것

- `640×360` 캡처와 모든 fixture의 시각 품질
- 한국어와 긴 관장명·보상명의 글자 잘림
- 마우스, 키보드 포커스, ESC와 내레이션의 실제 동작
- 실제 트레이너 3D 모델 슬롯과 scissor
- 프레임 시간과 입력 지연

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

1. `640×360`, 한국어와 긴 문자열 fixture를 두 백엔드에서 캡처한다.
2. 마우스, 키보드 포커스, ESC, 내레이션과 비활성 버튼 설명을 확인한다.
3. 같은 3D 트레이너 슬롯을 두 백엔드에 넣고 scissor 경계를 비교한다.
4. 프레임 시간과 입력 지연을 같은 조건에서 측정한다.
5. 근거를 모아 백엔드와 첫 시각 방향을 결정한다.
6. owo 채택 시 구현을 MBC 비공개 렌더러로 이동하고, 기각 시 owo 스파이크를 제거한다.
7. 미선택 구현과 개발용 직접 의존성을 제거한 뒤 체육관 상세 화면으로 확장한다.
