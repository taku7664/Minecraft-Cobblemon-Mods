# League Challenge GUI 착수 순서 수정 결정

| 항목 | 값 |
| --- | --- |
| Status | `shared` |
| Effective | 2026-09-25 |
| Updates | `MORE_BATTLE_CONTENT_UI_RESOURCE_BOUNDARY_AMENDMENT.md`의 UI-A~UI-C 착수 순서 |
| Does not obsolete | Minecraft 우선 비교, Visual Pack 경계, 최소 두 화면 뒤 API 승격 원칙 |
| 적용 모듈 | `more-battle-content`, `more-battle-content-league-challenge` |

## 1. 수정 이유

원계획은 League Challenge 모듈이 아직 Gradle 빌드에 존재하지 않는데도 League 홈 계약과 두 Minecraft 백엔드 스파이크를 먼저 구현하도록 적었다. 이 순서로는 첫 사용처의 fixture, 개발 진입점과 렌더러를 컴파일하거나 실행할 위치가 없다.

또한 첫 화면 하나에서 추정한 요구를 즉시 MBC의 안정 공개 API로 고정하면, 네 화면 수직 단면에서 드러날 실제 반복 요구보다 스파이크 구조가 공용 계약을 지배하게 된다. 따라서 최소 실행 기반과 실험 계약을 먼저 만들고, 실제 반복이 확인된 뒤 안정 API로 승격한다.

## 2. 수정된 착수 순서

### BOOT-0: 문서와 격리 기준점

- 이 후속 결정을 구현 기준으로 사용한다(MUST).
- 기존 작업 사본과 분리한 Git worktree 또는 동등한 격리 브랜치에서 작업한다(MUST).
- 같은 저장소의 미완료 작업과 League 변경을 한 커밋에 섞지 않는다(MUST NOT).

### BOOT-1: 의존성 게이트

- Minecraft `1.21.1`, Fabric Loader, Fabric API, Fabric Language Kotlin, Cobblemon과 MBC 버전을 현재 저장소 값으로 고정한다(MUST).
- PokeBadges의 실제 대상 버전, 모드 ID와 API를 확인하기 전에는 프로덕션 필수 의존성을 추측해 선언하지 않는다(MUST NOT).
- PokeBadges 미연결 상태의 빌드는 GUI 개발용일 뿐 배포 가능한 League Challenge로 표시하지 않는다(MUST).

### BOOT-2: 최소 League 모듈

- `more-battle-content-league-challenge`를 Gradle 빌드에 포함하고 서버·클라이언트 진입점, 모드 메타데이터와 한·영 번역을 제공한다(MUST).
- 이 단계는 진행 저장, 뱃지 지급, 레벨캡, 터미널과 MBC 시설 잠금을 구현하지 않는다(MUST NOT).
- 사용자 설정을 추가하는 시점에는 `modmenu` 설정 화면을 함께 제공한다(MUST).

### BOOT-3: 개발 전용 GUI 진입점

- 개발 환경에서만 League 홈 fixture 화면을 여는 명시적 진입 경로를 제공한다(MUST).
- 배포 환경에서는 이 경로가 등록되지 않아야 한다(MUST NOT).
- 실제 제품 진입은 후속 단계의 League 터미널 우클릭으로 교체한다(MUST).

### BOOT-4: 실험적 MbcUI 계약

- MBC는 League 홈 스파이크에 필요한 최소 타입과 검증기만 `experimental` 계약으로 제공한다(MUST).
- 계약은 owo, `GuiGraphics`와 MBC 내부 레이아웃 타입을 노출하지 않는다(MUST NOT).
- 중복 컴포넌트 ID, 등록되지 않은 행동과 잘못된 상태 참조를 단위 테스트로 거부한다(MUST).
- 실험 계약은 호환성 보장을 받는 안정 API가 아니며 네 화면 수직 단면 전에는 안정 패키지로 승격하지 않는다(MUST NOT).

### BOOT-5: fixture와 두 Minecraft 스파이크

- 같은 League 홈 fixture를 코드 드로잉과 owo 후보 백엔드에 입력한다(MUST).
- 두 스파이크는 정보, 행동 ID와 상태가 같아야 하며 렌더링 구현만 달라야 한다(MUST).
- `320×240`, `426×240`, `640×360`, 한·영, 키보드·내레이션, 클리핑과 3D 호스트 슬롯을 실제 Minecraft에서 비교한다(MUST).

### BOOT-6: 선택과 폐기

- 백엔드는 실제 캡처, 입력·접근성, 구현 복잡도와 프레임 시간 증거로 선택한다(MUST).
- 선택되지 않은 스파이크의 제품 코드는 제거하고 비교 기록만 남긴다(MUST).
- 두 백엔드를 장기간 동시에 유지하지 않는다(MUST NOT).

## 3. 안정 API 승격 게이트

다음 조건을 모두 만족하기 전에는 MbcUI 실험 타입을 안정 공개 API로 승격하지 않는다(MUST NOT).

1. League 홈과 체육관 상세를 포함해 최소 두 화면에서 같은 개념이 반복된다.
2. 코드 드로잉과 owo 후보 중 하나가 실제 Minecraft 증거로 선택됐다.
3. League가 백엔드 구현 타입을 직접 참조하지 않는 테스트가 통과한다.
4. 외부 Visual Pack이 화면 구조와 행동을 바꾸지 못하는 경계 테스트가 통과한다.
5. 기존 MBC 화면과 MBC 단독 로딩이 회귀하지 않는다.

## 4. 롤백 경계

BOOT-2~BOOT-5는 저장 데이터와 서버 진행도를 만들지 않는다(MUST). 해당 단계의 롤백은 새 모듈의 빌드 포함 제거, 실험 API 제거와 개발 진입점 제거로 끝나야 한다(MUST). PokeBadges, 레벨캡, 터미널, 전투와 시설 잠금은 별도 후속 커밋에서만 추가한다(MUST).
