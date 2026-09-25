# Cobblemon: More Battle Content - League Challenge

`League Challenge`는 `Cobblemon: More Battle Content`(MBC)에 체육관, 뱃지, 계급,
플레이어별 레벨캡, 사천왕 연전과 챔피언 진행을 추가하는 별도 애드온이다.

현재 Gradle 모듈과 서버·클라이언트 진입점이 있으며, 개발 환경에서만 `/mbc-league-ui`로
코드 드로잉 League 홈을 열 수 있다. 기본값은 `badges_3`이고 `badges_0`, `badges_2`,
`badges_3`, `badges_5`, `badges_8`, `champion` 하위 명령으로 경계 상태를 비교할 수 있다.
진행도, PokeBadges, 레벨캡, 터미널, 전투와 시설 잠금은 아직 연결하지 않았으므로 배포 가능한
제품 상태가 아니다.

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
- League Challenge는 MBC와 PokeBadges를 필수 의존성으로 사용한다(MUST).
- League Challenge가 설치되어 작동하는 동안에는 챔피언이 되기 전까지 MBC 배틀타워와
  배틀팩토리를 잠가야 한다(MUST).
- 애드온이 없으면 MBC 배틀타워와 배틀팩토리는 기존처럼 바로 이용할 수 있어야 한다(MUST).
- 체육관 건물이나 상시 스폰 트레이너 NPC를 만들지 않는다(MUST NOT). 전용 리그 터미널을
  우클릭해 GUI에서 도전한다.

## 문서 읽는 순서

1. [문서 색인](docs/DOCUMENT_INDEX.md)
2. [결정 기록](docs/DECISIONS.md)
3. [제품 범위와 용어](docs/PRODUCT_SCOPE.md)
4. [진행도와 콘텐츠](docs/PROGRESSION_AND_CONTENT.md)
5. [GUI 프레임워크 및 리소스팩 계획](docs/GUI_FRAMEWORK_AND_RESOURCE_PACK.md)
6. [MBC 본체 변경 계약](docs/MBC_CORE_CHANGES.md)
7. 나머지 세부 문서

## 현재 경계

이 문서들은 구현 방향을 고정하는 기획 기준선이다. 아직 확정되지 않은 레벨캡 수치,
레벨캡 외부 모드 선택, 기본 상대 팀과 세부 보상은 구현 전에 별도로 결정해야 한다.
