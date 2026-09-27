# Cobblemon: More Battle Content 구조 전환 설계

- Status: shared
- Effective: 2026-08-18
- Obsoletes: `BATTLE_TOWER_DESIGN.md`
- Updated by: [`CONTENT_SCOPE.md`](CONTENT_SCOPE.md), [`TERMINAL_AND_BP.md`](TERMINAL_AND_BP.md)
- Updates: `PROJECT_STATUS.md`의 기존 3모듈 구성
- Scope: 프로젝트 정체성, 모듈 경계, 승계·폐기 정책

## 1. 목적

`Cobblemon Battle Facilities`의 타워 중심 구조를 종료하고, 여러 종류의 코블몬 배틀 콘텐츠를 담을 수 있는 단일 본체 모드로 다시 만든다. AI는 본체에 포함하지 않고 선택형 Better AI 애드온으로 제공한다.

본체가 제공할 콘텐츠는 [`CONTENT_SCOPE.md`](CONTENT_SCOPE.md)에서 확정한다. 타워 중심 모듈 구조와 별도 Battle Block 모드는 종료하지만, 본체가 제공하는 홀로 배틀 터미널은 유지한다. 콘텐츠 구현 순서는 `배틀타워 → 배틀팩토리 → PvP → 보스 레이드`로 확정됐다. 진입은 코드 생성 홀로 터미널과 명령어를 함께 제공하며 두 경로는 같은 서버 서비스를 사용한다.

## 2. 종료 선언

- `Cobblemon Battle Facilities API`, `Cobblemon Battle Tower`, `Cobblemon Battle Intelligence`는 `v0.1.0-alpha.5` 이후 새 버전을 만들지 않는다.
- `cobblemon_battle_facilities_api`, `cobblemon_battle_tower`, `cobblemon_battle_intelligence` ID는 새 프로젝트에서 사용하지 않는다.
- 기존 릴리스와 저장소 기록은 보존한다.
- 기존 모드를 새 이름으로만 바꾸는 방식은 채택하지 않는다.

종료 대상은 옛 모드 ID와 모듈 경계, Battle Lounge, 전용 차원, 과거 터미널 자산·구현, 과거 BP 저장 스키마, 기존 PvP 접수 구현, 타워 전용 트레이너 데이터와 GUI다. 배틀타워 PvE, 배틀팩토리 PvE, 플레이어 1대1 PvP, 홀로 배틀 터미널과 BP 경제의 제품 기능은 승계하며 보스 레이드(협동 보스전)를 추가한다. 승계는 기존 파일을 이동하는 방식이 아니라 새 계약에 따른 재구현을 뜻한다.

## 3. 목표 산출물

| 산출물 | Mod ID | 환경 | 책임 |
|---|---|---|---|
| Cobblemon: More Battle Content | `cobblemon_more_battle_content` | 서버+클라이언트 | 배틀 콘텐츠, 데이터 규격, 네트워크·UI, 애드온 계약과 서버 권위 검증 |
| Cobblemon: More Battle Content - Better AI | `cobblemon_more_battle_content_better_ai` | 서버 | 로컬 전술 AI, 추론, OpenRouter와 장애 폴백 |

본체는 하나의 JAR로 배포한다. 공개 API는 본체 내부의 작은 패키지로 제공하며 별도 API JAR로 분리하지 않는다.

## 4. 의존 방향

```text
Cobblemon
    ↑
Cobblemon: More Battle Content
    ↑ optional server-side addon
Cobblemon: More Battle Content - Better AI
```

- 본체는 Better AI 없이 로드되고 플레이 가능해야 한다.
- Better AI는 본체 없이는 로드되지 않아야 한다.
- 본체가 특정 AI 구현, 외부 제공자, API 키 또는 모델명을 알아서는 안 된다.
- Better AI는 본체의 공개 계약만 사용하고 본체의 `internal` 패키지를 참조해서는 안 된다.
- 본체는 Better AI가 없을 때 Cobblemon 기본 AI로 판단을 위임하는 내부 기준선 어댑터를 제공한다. 이는 Better AI 기능이나 품질을 본체에 복제하는 것이 아니라 콘텐츠의 독립 실행을 보장하는 호환 경계다.

## 5. 공개 애드온 계약

본체는 다음 기능만 공개한다.

1. AI 제공자 등록과 고유 ID 충돌 검사
2. 직렬화 가능한 읽기 전용 전투 상태
3. 서버가 생성한 합법 행동 후보
4. 고유 요청 ID와 판단 마감 시각
5. 선택된 행동의 실행 직전 재검증
6. 전투 시작·종료 수명 주기

공개 계약은 Cobblemon의 내부 전투 클래스, 실제 상대 파티 객체와 서버 전용 비밀을 노출하지 않는다. Cobblemon 버전별 변환은 본체의 `internal.compat.cobblemon173` 계층에서 처리한다.

초기 제공자 capability는 `SINGLE`과 `DOUBLE` 두 개만 둔다. 메가진화·다이맥스·테라스탈 같은 주요 기믹은 별도 제공자 capability가 아니다. 본체의 규칙과 Cobblemon 호환 계층이 기믹 합법성을 검사하고 행동 후보에 기믹 ID, 대상과 사용 상태를 표현하며, Brain은 그 후보의 전략적 가치만 평가한다.

배틀타워와 배틀팩토리는 난도·성격·전략 프로필 외 콘텐츠 전용 AI 컨텍스트를 추가하지 않는다. 보스 레이드의 공유 HP, 단계와 참가자 상태는 일반 싱글·더블 판단만으로 부족할 가능성이 있으나, 해당 capability와 DTO는 보스 레이드 구현 단계에서 실제 계약을 먼저 정한 뒤 추가한다.

## 6. 승계 매트릭스

| 기존 영역 | 결정 | 이유 |
|---|---|---|
| Brain DTO·등록소·판단 검증 | 본체에 선별 재작성 | 애드온 연결에 필요한 최소 계약 |
| Cobblemon 턴 후보 변환·공개 정보 관측 | 본체 호환 계층에 선별 재작성 | 서버 권위와 버전 격리 필요 |
| 로컬 AI 평가·추론 | Better AI로 이동 | AI 기능을 선택형 애드온으로 분리 |
| OpenRouter 클라이언트·설정 | Better AI로 이동 | 네트워크와 비밀을 서버 전용 경계에 격리 |
| Cobblemon 기본 AI 어댑터 | 본체에 신규 작성 | Better AI 부재 시 콘텐츠가 동작하는 기준선 |
| 첫 합법 행동 선택기 | 비상 안전장치로만 유지 | 모든 정상 Brain과 기준선 위임이 실패한 경우 배틀 정지만 막으며 Better AI 품질로 간주하지 않음 |
| 배틀타워·배틀팩토리·1대1 PvP 콘텐츠 계약 | 새 본체에 재작성 | 구버전 문서에서 확정된 핵심 콘텐츠이며 새 본체의 제품 범위 |
| 보스 레이드 콘텐츠 계약 | 새 본체에 신규 작성 | 레퍼런스의 협동 레이드형 PvE를 독립 콘텐츠로 채택 |
| Battle Lounge·전용 차원·옛 접수와 GUI | 폐기 | 특정 월드 구조와 옛 모듈 경계를 강제함 |
| Holo Tower 블럭 | 본체 홀로 배틀 터미널로 독립 재작성 | 실제 블럭은 유지하되 전용 모델·텍스처 없이 코드 생성 홀로그램과 공통 서비스 사용 |
| BP 경제·상점 | 본체에 독립 재작성 | 통화·원장·보상 정산·상점은 유지하고 과거 저장 구현만 폐기 |
| 세션·엔트리·진행 저장 구현 | 자동 이식하지 않음 | 콘텐츠별 새 계약과 마이그레이션 결정이 필요함 |
| 소드·실드 트레이너·랭크 매핑 | 배틀타워 설계 참고자료 | 데이터는 새 스키마로 독립 작성하고 기존 하드코딩을 복사하지 않음 |

이식은 파일이나 패키지 단위가 아니라 행동 계약 단위로 한다. 기존 `BattleBrainModels`가 폐지 대상 세션의 `BattleFormat`을 참조하는 것처럼 옛 경계가 섞인 코드는 새 독립 타입으로 분해한다.

## 7. 네임스페이스

| 종류 | 본체 | Better AI |
|---|---|---|
| 패키지 | `jbro.cobblemon.morebattlecontent` | `jbro.cobblemon.morebattlecontent.betterai` |
| 리소스 | `cobblemon_more_battle_content` | `cobblemon_more_battle_content_better_ai` |
| JAR | `cobblemon-more-battle-content` | `cobblemon-more-battle-content-better-ai` |
| 설정 디렉터리 | `cobblemon-more-battle-content` | `cobblemon-more-battle-content-better-ai` |

기존 ID를 새 데이터에 별칭으로 남기지 않는다. 저장 데이터 변환이 필요하다고 확정될 경우에만 읽기 전용 일회성 마이그레이터가 과거 ID를 인식한다.

## 8. 데이터와 마이그레이션

- 기존 월드의 BP, 엔트리, 세션과 타워 진행 데이터는 자동 삭제하지 않는다.
- 새 본체가 해당 데이터를 자동으로 읽거나 변환하지도 않는다.
- 빡대리님이 보존 대상을 정하면 원본 백업, 스키마 검증, 변환, 결과 검증과 롤백 순서의 별도 계획을 작성한다.
- 기존 AI 설정의 API 키는 자동 복사하지 않는다. 새 설정 형식과 값은 쓰기 전에 사용자에게 보여 주고 확인받는다.

## 9. 개발 순서

0. **공통 기반:** 새 Gradle 모듈과 Mod ID 테스트, 본체 단독 로딩, Cobblemon 1.7.3 호환 계층, 본체 기준선 AI 어댑터, 좁은 Better AI 등록 계약, 공통 애플리케이션 서비스와 BP 멱등 거래 원장을 만든다.
1. **배틀타워:** 코드 생성 홀로 터미널·명령어의 동등 진입, 본가형 팀 등록·선출·복구, 랭크 진행, BP 보상 정산, 가방 금지와 단일 주요 기믹 규칙을 한 수직 기능으로 검증하고 BP 상점을 원자 거래로 연결한다.
2. **배틀팩토리:** 렌탈 후보, 선출, 7연전과 승리 후 교환을 구현한다.
3. **PvP:** 플레이어 1대1 신청·합의·팀 프리뷰·비공개 선출과 서버 판정을 구현한다.
4. **보스 레이드:** 1~3인 공유 HP, 단계 전환, 참가자 상태와 복구를 구현한다. 이 단계에서만 보스 전용 AI 컨텍스트 필요성을 확정한다.
5. **출시 검증:** 옛 ID·패키지·리소스 잔재 검사, 빌드, 전용 서버, 클라이언트와 실제 게임 검증을 수행한다.

Better AI의 로컬 전술 평가와 OpenRouter 연동은 공통 제공자 계약이 검증된 뒤 별도 애드온 작업으로 진행할 수 있다. 다만 Better AI의 진행 상태가 위 콘텐츠 순서를 바꾸거나 본체 수직 기능을 막아서는 안 된다.

## 10. 출시와 롤백

- 새 프로젝트 버전은 `0.1.0-alpha.1`부터 시작한다.
- 기존 모드와 새 모드를 같은 런타임에 함께 설치하는 구성은 지원하지 않는다.
- 첫 공개 전 기존 저장소와 릴리스에는 후속 프로젝트 링크를 추가하되 기존 자산은 삭제하지 않는다.
- 문제가 생기면 새 JAR을 제거하고 기존 세 JAR과 백업한 월드를 함께 복구한다. 서로 다른 세대의 JAR을 섞어 롤백하지 않는다.

## 11. 결정 대기 항목

1. **결정자:** 빡대리님  
   **결정:** 첫 배틀타워 MVP에서 Challenges, Records, Guide와 BP 상점 패널을 어느 순서까지 노출할지  
   **열린 이유:** 코드 생성 홀로 터미널과 명령어 동시 진입은 확정됐지만 첫 실제 Screen 범위는 정해지지 않았다.

2. **결정자:** 빡대리님  
   **결정:** 기존 BP·진행도·세션 데이터의 보존 및 변환 범위  
   **열린 이유:** 새 BP 경제 존치와 과거 데이터 자동 변환은 별개이며 중복 지급을 피하려면 원본 데이터 확인이 필요하다.

3. **결정자:** 빡대리님  
   **결정:** 새 GitHub·Modrinth 프로젝트 생성 및 기존 저장소 보관 시점  
   **열린 이유:** 문서와 로컬 구조가 먼저 검증돼야 공개 상태 변경 범위를 정확히 정할 수 있다.

## 12. Cobblemon Battle Tower 레퍼런스 반영

분석 기준은 Fabric 1.10.22의 공식 Modrinth 배포물이다. 로컬 JAR과 Modrinth 파일의 SHA-512가 일치했고 Java 21 검증을 통과했다. 상세 근거는 다음 내부 문서에 분리했다.

- [`BATTLE_TOWER_REFERENCE_ANALYSIS.md`](../reference/BATTLE_TOWER_REFERENCE_ANALYSIS.md): 공개 기능, JAR 구조, 런타임, 저장, 네트워크, GUI와 위험 상세 분석
- [`BATTLE_TOWER_REFERENCE_MATRIX.md`](../reference/BATTLE_TOWER_REFERENCE_MATRIX.md): 채택·변형·폐기와 GUI 계약 요약표

원본은 ARR이므로 기능 아이디어와 외부 동작만 참고한다. 디컴파일 구현, 팀·보스 데이터, 번역, GUI 장식, 텍스처, 스킨, 음악과 스크립트는 승계 대상이 아니다.

레퍼런스의 Boss Mode는 참가자별 전투를 공유 HP로 묶는 협동 레이드형 PvE다. MBC는 이를 `보스 레이드` 콘텐츠로 채택하되 원본의 고정 보스 편성, 전용 음악, 커스텀 기술과 Mega Showdown 결합 구현은 복사하지 않는다. 상세 계약과 아직 열린 정책은 [`CONTENT_SCOPE.md`](CONTENT_SCOPE.md)를 따른다.

레퍼런스의 일반 타워는 유동 파티 크기, 진행에 따른 여러 주요 기믹의 동시 허용과 제한적 가방 사용을 제공한다. MBC는 이를 그대로 채택하지 않는다. 본가형 6마리 등록·3/4마리 선출과 가방 금지를 유지하고, `NONE/MEGA/DYNAMAX/TERA` 중 한 규칙만 양측에 적용하는 방식으로 기믹을 변형 채택한다.

## 13. 공통 Battle Hub 셸

진입은 코드 생성 홀로 터미널과 명령어를 함께 제공한다. 두 경로가 공유하는 다음 Battle Hub 계약을 설계 기준으로 둔다.

- 화면은 서버 발급 `entryContextId`로 열려야 한다. 터미널 진입 문맥은 서버가 검증한 블럭 위치를 포함할 수 있지만 명령어 진입은 물리적 블럭을 요구하지 않는다.
- 공통 정보 구조는 파티, 콘텐츠·진행, 상세·보상, 하단 행동 영역이다.
- Play, Challenges, Records, Guide는 콘텐츠 capability에 따라 노출한다.
- BP 잔액은 공통 화면에서 확인 가능해야 하고 BP 상점 패널은 해당 콘텐츠 화면 범위에 맞춰 노출한다. 랭킹은 별도 기능 범위로 둔다.
- 클라이언트는 서버 승인 전에 팀 잠금, 세션 활성, 구매 또는 완료 상태를 확정해서는 안 된다.
- 변경 요청은 request ID와 상태 revision을 포함하고 서버는 구조화된 성공·실패 결과를 반환해야 한다.
- 구매와 보상 수령은 서버에서 검증한 단일 트랜잭션으로 처리해야 한다.
- 파괴적 포기와 취소는 예상 손실을 보여 주고 확인받아야 한다.
- 화면은 작은 해상도와 긴 번역 문자열에서 3열을 2열 또는 단일 열로 재배치해야 한다.
- 마우스, 키보드 포커스와 명시적 Back/Escape 흐름을 지원해야 한다.
- Better AI는 GUI를 소유하지 않으며, 본체가 선택적으로 읽기 전용 제공자 상태만 표시할 수 있다.

터미널 등록, 명령어 계약과 공통 DTO·세션은 첫 화면 탭 범위 결정과 분리해 검증할 수 있다. 실제 Screen에서는 빡대리님이 확정한 MVP 탭만 노출한다.
