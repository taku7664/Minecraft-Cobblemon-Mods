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

개발 환경에서 다음 화면 상태를 열 수 있다.

```text
/mbc-league-ui
/mbc-league-ui badges_0
/mbc-league-ui badges_2
/mbc-league-ui badges_3
/mbc-league-ui badges_5
/mbc-league-ui badges_8
/mbc-league-ui champion
```

기본 명령은 `badges_3` fixture를 연다. 이 경로는 Fabric 개발 환경에서만 등록된다.

## 아직 검증하지 않은 것

- 실제 Minecraft 클라이언트에서 화면이 열리는지
- `320×240`, `426×240`, `640×360` 캡처의 시각 품질과 글자 잘림
- 마우스, 키보드 포커스, ESC와 내레이션의 실제 동작
- 실제 트레이너 3D 모델 슬롯과 scissor
- owo 후보 백엔드와의 동일 fixture 비교
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

1. 개발 클라이언트를 실행해 코드 드로잉 홈의 여섯 fixture를 연다.
2. 세 논리 화면 크기와 한·영에서 같은 상태를 캡처한다.
3. 가장 큰 시각·입력 문제를 먼저 기록하고 코드 드로잉 기준선을 한 번만 정정한다.
4. 같은 `LeagueHomeFixture`와 `LeagueHomeContract`를 소비하는 owo 후보 화면을 추가한다.
5. 두 화면의 정보·행동 ID가 같은지 테스트하고 실제 캡처를 나란히 비교한다.
6. 구현 복잡도, 접근성, 클리핑, 3D 슬롯과 프레임 시간 근거로 하나를 선택한다.
7. 미선택 제품 코드를 제거한 뒤 체육관 상세 화면으로 확장한다.
