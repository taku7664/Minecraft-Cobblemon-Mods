# League Challenge GUI 프레임워크 및 리소스팩 계획

- 상태: `shared planning baseline`
- 적용 상위 결정: `../../docs/MORE_BATTLE_CONTENT_DECLARATIVE_UI_FRAMEWORK_DECISION.md`
- 구현 상태: 문서화만 완료, 코드·자산·리소스팩 미구현

## 1. 소유권

League Challenge는 MBC의 `MbcUI`를 처음 사용하는 애드온이다. 선언 문법, 공용 컴포넌트, Minecraft 렌더러와 웹 미리보기 도구는 MBC가 소유한다(MUST). League Challenge는 리그 화면, 리그 전용 컴포넌트, 테마와 자산만 소유한다(MUST).

League가 owo UI나 MBC 내부 레이아웃 클래스를 직접 참조하면 안 된다(MUST NOT). 실제 백엔드가 바뀌어도 League의 화면 문서와 상태 모델은 유지돼야 한다(MUST).

## 2. 첫 화면 묶음

GUI 우선 구현은 실제 서버 기능보다 먼저 fixture 상태로 다음 흐름을 완성한다.

1. **리그 홈/패스포트**: 현재 계급, 8개 뱃지, 다음 도전, 현재 레벨캡, 시설 잠금 상태
2. **체육관 상세**: 관장, 형식, 레벨캡, 파티 규칙, 최초·반복 보상, 입장 조건
3. **파티 확인**: 각 포켓몬의 규칙 적합 여부, 출전 순서와 도전 확인
4. **사천왕 로비**: 연속 도전과 팀 잠금 규칙, 시작 확인
5. **연속 도전 진행**: 현재 상대, 완료한 단계, 다음 상대와 중단 경고
6. **결과 및 해금**: 승패, 새 뱃지·계급·레벨캡, 챔피언 칭호, Tower·Factory 해금

첫 수직 단면은 `리그 홈 → 체육관 상세 → 파티 확인 → 결과` 네 화면이다. 이 흐름이 웹과 Minecraft 양쪽에서 승인되기 전에는 나머지 화면을 양산하지 않는다(SHOULD NOT).

## 3. League 전용 컴포넌트

- `LeagueRankEmblem`: 몬스터볼·수퍼볼·하이퍼볼·마스터볼·챔피언 계급
- `LeagueBadgeTrack`: 8개 뱃지의 미획득·획득·현재 목표 상태
- `LeagueChallengeCard`: 잠김·가능·완료·진행 중 도전 카드
- `LeagueTrainerPortrait`: 초상화, 리소스 스킨 3D 모델 또는 기본 실루엣
- `LeagueLevelCap`: 현재와 승리 뒤 레벨캡 변화
- `LeaguePartyRule`: 레벨·인원·중복·금지 규칙 결과
- `LeagueGauntletTrack`: 사천왕 4명과 챔피언의 연속 진행
- `LeagueUnlockSummary`: 새 계급, 기능과 보상의 결과 묶음

각 상태는 색뿐 아니라 아이콘, 형태와 번역 문자열로도 구분한다(MUST).

## 4. 테마와 자체 리소스팩

League 기본 테마는 애드온 JAR에 포함한다(MUST). 별도 설치가 없어도 제품 화면이 완전해야 한다. 권장 namespace와 경로는 다음과 같다.

```text
assets/cobblemon_more_battle_content_league_challenge/mbc_ui/screens/*.mbcui.xml
assets/cobblemon_more_battle_content_league_challenge/mbc_ui/themes/league_challenge.json
assets/cobblemon_more_battle_content_league_challenge/textures/gui/mbc_ui/panels/*.png
assets/cobblemon_more_battle_content_league_challenge/textures/gui/mbc_ui/icons/*.png
assets/cobblemon_more_battle_content_league_challenge/textures/gui/mbc_ui/backgrounds/*.png
assets/cobblemon_more_battle_content_league_challenge/textures/gui/portraits/*.png
```

선택형 `League Challenge Visual Pack`은 같은 리소스 ID를 덮어써서 다음만 바꿀 수 있다(MAY).

- 패널, 테두리, 배경과 장식
- 계급·뱃지·잠금·완료 아이콘
- 관장·사천왕·챔피언 초상화
- 허용된 범위의 짧은 UI 애니메이션 프레임

외부 팩은 계급 조건, 레벨캡, 도전 순서, 팀, 보상, 잠금과 서버 승인 결과를 바꾸면 안 된다(MUST NOT). 서버가 특정 외형을 요구하면 일반 서버 리소스팩 배포 방식으로 제공할 수 있지만, 팩이 없는 클라이언트에는 기본 자산과 실루엣 폴백이 남아야 한다(SHOULD).

## 5. 디자인 토큰

첫 테마는 개별 화면에 색과 좌표를 직접 박지 않고 최소 다음 토큰을 사용한다.

- 표면: `background`, `chrome`, `panel`, `panelRaised`, `panelSelected`
- 상태: `locked`, `available`, `cleared`, `active`, `warning`, `error`
- 문자: `textPrimary`, `textSecondary`, `textMuted`, `textOnAccent`
- 간격: `space1`부터 `space6`, 카드 간격, 화면 여백
- 형태: 패널·카드·버튼 모서리와 테두리 두께
- 크기: 상단바, 하단 행동바, 아이콘, 초상화와 뱃지 슬롯

계급별 색은 강조색으로만 사용하고 본문 대비와 상태 의미를 침범하지 않는다(SHOULD).

## 6. fixture와 웹 미리보기

실제 네트워크 구현 전 다음 fixture를 준비한다(MUST).

- 0개, 2개, 3개, 5개, 8개 뱃지와 챔피언 상태
- 잠김·가능·완료·진행 중 체육관
- 규칙 적합 파티와 레벨·도구·중복 위반 파티
- 긴 관장명·보상명과 누락 초상화
- 한국어·영어
- 사천왕 0~4승과 챔피언 직전·승리 결과

미리보기는 최소 `320×240`, `426×240`, `640×360`에서 비교한다. AI가 만든 웹 시안은 빠른 구조 탐색에 사용하지만, 최종 승인에는 동일 fixture의 실제 Minecraft 캡처가 필요하다(MUST).

## 7. 단계와 승인 게이트

| 단계 | 산출물 | 다음 단계 조건 |
| --- | --- | --- |
| LGUI-0 | League 홈 fixture, 화면 문서, 토큰 초안 | 필수 정보와 상태 누락이 없음 |
| LGUI-1 | 웹 수직 단면 4화면 | 세 폭·한영·오류 상태 검토 통과 |
| LGUI-2 | Minecraft 백엔드 수직 단면 | 웹과 정보 구조가 같고 입력·클리핑 정상 |
| LGUI-3 | 내장 League 테마와 임시 원본 자산 | 별도 팩 없이 완전한 화면 표시 |
| LGUI-4 | 선택형 Visual Pack 예제 | 같은 ID 덮어쓰기와 폴백·재로드 성공 |
| LGUI-5 | 서버 권위 상태 연결 | 오래된 상태·조작 요청을 서버가 거부 |
| LGUI-6 | 사천왕·챔피언 화면 확장 | 수직 단면의 공용 컴포넌트 재사용 확인 |

## 8. 시각·기능 검증

- 세 논리 화면 크기의 동일 상태를 나란히 비교한다.
- 웹과 게임 캡처를 같은 fixture로 비교하고 가장 큰 차이부터 수정한다.
- 한국어와 영어에서 제목, 잠금 이유, 보상명이 잘리지 않는지 확인한다.
- 마우스, 키보드 포커스, ESC, 스크롤, 툴팁과 내레이션을 실제 게임에서 확인한다.
- 리소스 재로드 중 잘못된 테마, 누락 아이콘과 누락 초상화를 각각 검증한다.
- 포켓몬·트레이너 3D 슬롯은 패널 밖으로 새거나 다른 스크롤 영역을 침범하지 않아야 한다.
- 빌드와 웹 스냅샷은 실제 Minecraft 화면의 시각·입력·성능 증거로 보고하지 않는다.

## 9. 구현 전에 남은 선택

1. League 테마의 구체적인 미술 방향과 색상 팔레트
2. 계급 엠블럼과 뱃지 아이콘을 새로 제작할지 PokeBadges 공개 자산을 참조할지
3. 기본 관장 초상화를 별도 제작할지 3D 스킨 슬롯만 사용할지
4. 외부 Visual Pack 예제를 릴리스 JAR과 별도 ZIP으로 함께 배포할지

이 선택은 프레임워크 스파이크와 첫 웹 화면을 본 뒤 빡대리님이 결정한다. 구현자는 임의 팔레트나 제3자 자산을 확정본으로 넣으면 안 된다(MUST NOT).
