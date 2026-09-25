# MBC 본체 변경 계약

## 1. 목적과 호환 원칙

League Challenge는 MBC 위에서 동작하지만 MBC는 League Challenge 없이도 지금처럼 동작해야 한다.

- 애드온이 없으면 Battle Tower와 Battle Factory는 기존 접근 규칙을 그대로 따른다.
- 애드온이 있으면 챔피언 승리 전까지 두 시설의 새 진입을 서버가 거부한다.
- MBC가 애드온 클래스에 의존하거나 클래스 이름을 탐색하면 안 된다.
- 필요한 확장은 일반 공개 API로 만들고 애드온이 이를 등록해 사용한다.

GUI 버튼만 비활성화해서는 안 된다. 패킷 재전송, 직접 세션 호출, 명령이나 다른 진입점으로 잠금을 우회할 수 없어야 한다.

## 2. 현재 구조에서 확인된 제약

| 현재 위치 | 확인된 제약 |
| --- | --- |
| `internal/hub/BattleHubPayloads.kt` | `BattleHubContent`가 Tower, Factory, PvP, Boss Raid, Shop으로 고정되어 있고 허용 여부·잠금 사유 계약이 없음 |
| `internal/hub/BattleHubNetworking.kt` | Tower·Factory 열기를 직접 분기하며 외부 접근 정책을 거치지 않음 |
| `client/MbcContentTabContract.kt` | 허용 상태가 일반 콘텐츠 계약으로 표현되지 않음 |
| `client/MbcGuiStyle.kt` | 현재 Boss Raid만 하드코딩해 비활성화하며 애드온이 안전하게 재사용할 공개 스타일 계약도 아님 |
| `internal/application/BattleContentApplicationService.kt` | 서비스와 콘텐츠 등록 타입이 내부 구현이라 애드온에서 사용할 수 없음 |
| `MoreBattleContent.kt` | 애플리케이션 서비스에 현재 Tower만 등록하며 일반 애드온 등록 수명주기가 없음 |
| `internal/tower/network/TowerPlayNetworking.kt` | Tower의 열기·세션·시작 경로에 외부 잠금 정책이 없음 |
| `internal/compat/fabric/FactoryCommandRuntime.kt` | Factory 시작 경로에 같은 잠금 정책과 공개 실행 계약이 없음 |
| `internal/shadow/ShadowTrainerProjection.kt` | 도전자의 프로필과 장비에 묶인 표현이라 관장 리소스 스킨을 지정할 수 없음 |
| `client/ShadowTrainerProjectionRenderer.kt` | 페이로드가 리소스 스킨 모델을 표현하지 않음 |
| `internal/bp/BattlePointSavedData.kt`의 `BattlePointService` | BP 기능이 내부 구현이라 애드온이 안정적으로 원자 지급할 수 없음 |

내부 타입을 무더기로 공개하는 방식은 피한다. League Challenge가 실제로 필요한 좁고 안정적인 계약을 새 `api` 표면에 둔다.

## 3. 콘텐츠 접근 정책 API

MBC는 다음 의미의 공개 계약을 제공해야 한다.

- 안정적인 콘텐츠 ID: 최소 `battle_tower`, `battle_factory`
- `BattleContentAccessPolicy`: 플레이어와 콘텐츠·행동을 받아 허용 또는 잠금 결정을 반환
- 잠금 결정: 번역 키와 안전한 인자, 필요하면 기계 판독용 사유 코드
- 정책 등록·해제 핸들: 서버 시작과 종료 수명주기에 맞춤

여러 정책이 등록되면 모두 허용해야 진입을 허용한다. 정책이 없으면 기존 호환성을 위해 허용한다. 등록된 정책이 예외를 던지면 해당 콘텐츠의 새 진입은 안전하게 거부하고 원인을 기록하되 MBC의 다른 콘텐츠는 계속 동작한다.

League Challenge 정책은 다음을 반환한다.

- 챔피언 미격파: Tower·Factory 잠금
- 챔피언 격파: 허용
- 진행 데이터 읽기 실패: 잠금과 명확한 오류 사유

## 4. 반드시 검사할 진입점

잠금 검사는 한 화면에만 두지 않고 실제 상태 변경 직전에 공통 서비스로 수행한다.

- 허브 상태 페이로드 생성
- Tower 화면 열기
- Tower 세션 생성·수정·전투 시작
- Factory 화면 또는 명령 진입
- Factory 런 생성·다음 전투 시작
- 공개 API를 통한 직접 시작

일반 관리자용 진행 조회·설정·초기화 명령은 기본적으로 잠그지 않는다. 실제 플레이를 시작하는 관리자 명령이 있다면 권한 우회 여부를 명시적으로 정하고 테스트한다.

현행 `/mbc`는 허브를 여는 사용자 진입점이다. 코드에서 확인되는 `/mbc tower`와 `/mbc factory` 하위 명령은 진행 조회·설정·초기화용 관리자 명령이므로 이름만 보고 플레이 우회 경로로 단정하지 않는다. 다만 앞으로 실제 시작 명령이나 다른 공개 진입점이 추가되면 반드시 같은 정책 서비스를 거치게 한다.

설치·업데이트 순간 이미 시작된 전투는 종료까지 허용하고, 이후 새 세션과 다음 런 진입부터 정책을 적용하는 방향을 기본안으로 둔다. 진행 중 Tower·Factory 런을 어디까지 이어주어야 하는지는 구현 전 확정한다.

## 5. 허브 및 클라이언트 계약

허브 페이로드는 콘텐츠마다 다음 정보를 내려야 한다.

- 콘텐츠 ID
- 표시 상태: 사용 가능, 잠김, 준비 중
- 서버가 결정한 잠금 사유 번역 키와 인자
- 선택 가능한 경우 다음 해금 조건 요약

클라이언트는 이 상태로 버튼과 설명을 표현할 뿐 최종 권한을 갖지 않는다. 현재 특정 콘텐츠만 하드코딩해 비활성화하는 분기를 일반 계약으로 교체한다.

## 6. 관리형 PvE 배틀 API

League Challenge가 Tower나 Factory 내부 구현을 흉내 내지 않도록 MBC는 관리형 단일 PvE 배틀을 시작하는 공개 API를 제공해야 한다.

요청에 필요한 최소 정보:

- 플레이어와 안정적인 콘텐츠·도전 ID
- 배틀 형식과 규칙
- 잠긴 플레이어 팀 스냅샷 또는 검증된 참조
- 상대 전체 팀
- AI 난이도 또는 선택적 공개 브레인 ID
- 트레이너 표현 정보
- 결과를 한 번만 소비하기 위한 거래·세션 ID

응답은 `accepted(sessionId)` 또는 구체적인 `rejected(reason)`처럼 타입으로 구분한다. MBC가 전투 생성, 가상 트레이너, 정리, 홀로그램, 이탈 처리를 소유하고, 최종 승패를 애드온에 멱등적으로 전달한다.

애드온이 내부 애플리케이션 서비스나 Tower 전용 세션 타입을 직접 가져오게 해서는 안 된다.

## 7. 트레이너 투영 API

공개 표현 계약은 최소 다음을 지원한다.

- 기존 동작 호환용 `GameProfile`
- 검증된 텍스처 리소스와 `default`/`slim` 모델을 가진 `ResourceSkin`
- 향후 별도 렌더러를 위한 확장 가능한 타입

`ResourceSkin`은 도전자 장비를 복사하지 않는다. 서버 페이로드는 임의 URL이나 파일 경로를 받지 않고 리소스 ID만 운반한다. 기존 MBC 전투는 이전 `GameProfile` 동작을 유지한다.

## 8. BP 공개 API

League Challenge는 새 화폐를 만들지 않고 MBC BP를 사용한다. MBC는 다음 공개 계약을 제공해야 한다.

- 잔액 조회
- 원자적 적립·차감
- 거래 ID 기반 중복 방지
- 사유 코드와 감사 기록
- 성공, 잔액 부족, 중복, 저장소 오류의 타입화된 결과

내부 `BattlePointService`를 그대로 공개하기보다 위 계약을 감싸는 안정적인 API를 둔다.

## 9. MbcUI 공개 계약과 콘텐츠 식별

MBC는 [UI 리소스 경계 수정 결정](../../docs/MORE_BATTLE_CONTENT_UI_RESOURCE_BOUNDARY_AMENDMENT.md)에 따라 클라이언트 전용 `MbcUI` 계약을 소유해야 한다. 공개 범위는 타입 있는 화면 모델, 디자인 토큰, 공용 컴포넌트, 상태 바인딩, 사용자 의도와 Minecraft 전용 호스트 슬롯이다(MUST).

League Challenge는 owo API, `GuiGraphics`, `MbcGuiStyle`, `MbcContentFrameLayout`과 화면별 내부 좌표 타입을 참조하면 안 된다(MUST NOT). owo는 실제 게임 백엔드 후보일 뿐이며, 스파이크에서 채택하지 않아도 League 상태·행동 계약이 유지돼야 한다.

화면 구조, 행동 ID, 확인 단계와 포커스 순서는 타입 있는 코드가 소유하며 `ResourceManager`에서 읽지 않는다(MUST). MBC 기본 테마는 코드 기본값과 MBC JAR에, League 테마는 애드온 JAR에 내장한다. 선택형 외부 Visual Pack은 허용된 cosmetic 토큰과 안정적 ID의 시각 자산만 덮어쓸 수 있다. 잘못된 외부 테마는 전체를 버리고 코드 기본값과 내장 텍스처로 돌아가며 서버 진행은 계속돼야 한다(MUST).

새 프레임워크는 기존 화면을 일괄 교체하지 않는다. League의 fixture 수직 단면과 개발 전용 진입점에서 먼저 검증하고, 실제 게임 승인을 통과한 화면만 한 개씩 이행한다.

배틀에는 안정적인 콘텐츠 ID를 전달해 Better AI·Better Music 같은 선택 애드온이 `gym`, `elite_four`, `champion`을 구분할 수 있게 한다. 특정 선택 애드온의 타입을 MBC 코어 요청에 직접 넣지 않는다.

## 10. 테스트 계약

MBC 변경에는 최소 다음 자동 테스트가 필요하다.

- 정책 미등록 시 Tower·Factory가 기존처럼 허용됨
- 챔피언 미격파 정책이 두 시설을 거부함
- 챔피언 격파 정책이 두 시설을 허용함
- 잠긴 버튼 상태와 서버 페이로드의 사유가 일치함
- 조작한 열기·시작 패킷이 서버에서 거부됨
- 화면을 건너뛴 직접 API 호출도 거부됨
- 정책 예외가 새 진입을 허용하지 않으며 다른 콘텐츠를 망가뜨리지 않음
- 정책 등록 중복, 해제, 서버 재시작 시 잔존 참조가 없음
- 이미 진행 중인 전투의 채택된 전환 규칙이 지켜짐
- `ResourceSkin`이 도전자 프로필과 방어구를 복사하지 않음
- 기존 `GameProfile` 투영이 회귀하지 않음
- BP 거래가 재전송돼도 한 번만 반영됨
- 리소스팩이 화면 구조·행동 ID·확인 단계를 교체할 수 없음
- 잘못된 외부 테마가 코드 기본값과 서버 진행을 보존함
- 선택형 웹 도구의 fixture·manifest가 Minecraft 화면의 필수 컴포넌트와 상태·행동 ID를 보존함
- League Challenge가 없어도 MBC 기본 테마와 기존 화면이 로드됨
- League Challenge가 없는 MBC 단독 환경이 로드되고 기존 테스트가 통과함

## 11. 변경 단위

MBC 공개 API, 각 내부 진입점 적용, 허브 표시, 투영 확장, BP 공개 계약은 구현 시 논리적으로 분리해 커밋한다. MBC API 버전을 명시하고 애드온이 지원 범위를 선언한다. 빌드 성공은 실제 멀티플레이 접속, GUI, 가상 트레이너 렌더링, 우회 방지의 런타임 증거를 대신하지 않는다.
