# Cobblemon: More Battle Content - League Challenge

`League Challenge`는 `Cobblemon: More Battle Content`(MBC)에 체육관, 뱃지, 계급,
플레이어별 레벨캡, 사천왕 연전과 챔피언 진행을 추가하는 별도 애드온이다.

서버 진행도·저장, 데이터팩 콘텐츠, PokeBadges·Cobbled Level Control(CLC) 연동,
전용 터미널, MBC 전투·보상·시설 잠금과 서버·클라이언트 통신을 구현했다.
전용 터미널의 실제 League 홈은 UI Kit 위젯으로 서버 상태와 도전 요청에 연결했다.
개발 명령 `/mbc-league-ui`는 여전히 별도 fixture를 열며, 실제 진행도를 변경하지 않는다.
최신 연결 범위와 검증 경계는 [제품 UI 연결](docs/LIVE_UI_WIRING_V1.md), 서버 구현은
[시스템 구현 및 UI 인계](docs/SYSTEM_IMPLEMENTATION_V1.md)를 따른다.
[레벨캡·포획·야생 스폰 정책](docs/LEVEL_CAP_POLICY_V2.md)이 해당 문서의 이전 CLC 연동 설명을 갱신한다.

## 모듈 이름

| 구분 | 값 |
| --- | --- |
| 표시 이름 | `Cobblemon: More Battle Content - League Challenge` |
| 폴더·Gradle 프로젝트 | `more-battle-content-league-challenge` |
| Fabric 모드 ID | `cobblemon_more_battle_content_league_challenge` |
| JAR 기본 이름 | `cobblemon-more-battle-content-league-challenge` |
| Kotlin 패키지 | `jbro.cobblemon.morebattlecontent.leaguechallenge` |
| 메인 클래스 | `MoreBattleContentLeagueChallenge` |
| 버전 속성 | `more_battle_content_league_challenge_version` |

이 이름은 기존 `more-battle-content-better-ai`와 같은 규칙을 따른다.

## 핵심 관계

- MBC는 이 애드온 없이 지금처럼 독립적으로 작동해야 한다(MUST).
- League Challenge는 MBC, PokeBadges와 CLC를 필수 의존성으로 사용한다(MUST).
- MBC는 공통 PvE·보상·시설 접근·트레이너 외형 API를 제공하고, League Challenge는
  리그 진행도·데이터팩·터미널·서버 처리와 클라이언트 통신을 담당한다.
- League Challenge가 설치되어 작동하는 동안에는 챔피언이 되기 전까지 MBC 배틀타워와
  배틀팩토리를 잠가야 한다(MUST).
- 애드온이 없으면 MBC 배틀타워와 배틀팩토리는 기존처럼 바로 이용할 수 있어야 한다(MUST).
- 체육관 건물이나 상시 스폰 트레이너 NPC를 만들지 않는다(MUST NOT). 전용 리그 터미널을
  우클릭해 GUI에서 도전한다.

## 문서 읽는 순서

1. [문서 색인](docs/DOCUMENT_INDEX.md)
2. [시스템 구현 및 UI 인계](docs/SYSTEM_IMPLEMENTATION_V1.md)
3. [결정 기록](docs/DECISIONS.md)
4. [제품 범위와 용어](docs/PRODUCT_SCOPE.md)
5. [진행도와 콘텐츠](docs/PROGRESSION_AND_CONTENT.md)
6. [GUI 프레임워크 및 리소스팩 계획](docs/GUI_FRAMEWORK_AND_RESOURCE_PACK.md)
7. [MBC 본체 변경 계약](docs/MBC_CORE_CHANGES.md)
8. 나머지 세부 문서

## 현재 경계

`cobblemon-dev` 검증 프로필에 설치한 버전과 별도 테스트 월드 준비 방법은
[로컬 검증 기록](docs/evidence/CLIENT_TEST_PROFILE_2026-09-26.md)을 참조한다.

레벨캡 제공자는 사용자 확인을 받은 CLC다. 내장 레벨캡·상대 팀·BP 보상은 데이터팩으로
교체할 수 있는 초기 콘텐츠이며, 최종 밸런스가 아니다. CLC의 단계별 레벨을 리그 데이터와
맞춰야 한다. UI Toolkit과 Battle UI는 별도 담당 범위다. League는 공용 위젯을 소비하며
Battle UI를 교체하지 않는다. 정식 서버 배포와 전체 리그 완주 검증은 아직 완료하지 않았다.

기존 기획 문서는 결정 이력으로 보존한다. 현재 구현과 그 한계는 시스템 구현 문서를 먼저 확인한다.
