# League Challenge GUI 프레임워크 및 리소스팩 계획

- 상태: `shared planning baseline`
- 적용 상위 결정: `../../docs/MORE_BATTLE_CONTENT_UI_RESOURCE_BOUNDARY_AMENDMENT.md`, `../../docs/MORE_BATTLE_CONTENT_LEAGUE_UI_BOOTSTRAP_AMENDMENT.md`
- 구현 상태: GUI 착수용 모듈·실험 계약 구현 단계

## 1. 소유권과 신뢰 경계

League Challenge는 MBC의 `MbcUI`를 처음 사용하는 애드온이다. 타입 있는 화면 모델, 공용 컴포넌트, 행동 계약과 Minecraft 렌더러는 MBC가 소유한다(MUST). League Challenge는 리그 상태 모델, 리그 화면 조립, 전용 컴포넌트, 테마와 자산만 소유한다(MUST).

League가 owo UI나 MBC 내부 레이아웃 클래스를 직접 참조하면 안 된다(MUST NOT). 실제 백엔드가 바뀌어도 League의 상태·행동 계약은 유지돼야 한다(MUST). 화면 구조, 행동 ID, 확인 단계와 포커스 순서는 컴파일된 코드가 소유하며 리소스팩에서 읽지 않는다(MUST).

## 2. 첫 화면 묶음

GUI 우선 구현은 실제 서버 기능보다 먼저 fixture 상태로 다음 흐름을 완성한다.

1. **리그 홈/패스포트**: 현재 계급, 8개 뱃지, 다음 도전, 현재 레벨캡, 시설 잠금 상태
2. **체육관 상세**: 관장, 형식, 레벨캡, 파티 규칙, 최초·반복 보상, 입장 조건
3. **파티 확인**: 각 포켓몬의 규칙 적합 여부, 출전 순서와 도전 확인
4. **사천왕 로비**: 연속 도전과 팀 잠금 규칙, 시작 확인
5. **연속 도전 진행**: 현재 상대, 완료한 단계, 다음 상대와 중단 경고
6. **결과 및 해금**: 승패, 새 뱃지·계급·레벨캡, 챔피언 칭호, Tower·Factory 해금

첫 스파이크는 실제 Minecraft의 `리그 홈` 한 화면이다. 여기서 백엔드와 시각 방향을 승인한 뒤 `리그 홈 → 체육관 상세 → 파티 확인 → 결과` 네 화면 수직 단면으로 확장한다(MUST). 웹 시안 승인을 선행 조건으로 두지 않는다(MUST NOT).

## 3. League 전용 컴포넌트

- `LeagueRankEmblem`: 몬스터볼·수퍼볼·하이퍼볼·마스터볼·챔피언 계급
- `LeagueBadgeTrack`: 8개 뱃지의 미획득·획득·현재 목표 상태
- `LeagueChallengeCard`: 잠김·가능·완료·진행 중 도전 카드
- `LeagueTrainerPortrait`: 초상화, 리소스 스킨 3D 모델 또는 기본 실루엣
- `LeagueLevelCap`: 현재와 승리 뒤 레벨캡 변화
- `LeaguePartyRule`: 레벨·인원·중복·금지 규칙 결과
- `LeagueGauntletTrack`: 사천왕 4명과 챔피언의 연속 진행
- `LeagueUnlockSummary`: 새 계급, 기능과 보상의 결과 묶음

각 상태는 색뿐 아니라 아이콘, 형태와 번역 문자열로도 구분한다(MUST). 두 개 이상의 실제 화면에서 반복되지 않은 요구를 범용 MbcUI API로 승격하지 않는다(SHOULD NOT).

## 4. 테마와 자체 리소스팩

League 기본 테마는 코드 기본값과 애드온 JAR에 포함한다(MUST). 별도 설치가 없어도 제품 화면이 완전해야 한다. 권장 namespace와 경로는 다음과 같다.

```text
assets/cobblemon_more_battle_content_league_challenge/mbc_ui/themes/league_challenge.json
assets/cobblemon_more_battle_content_league_challenge/textures/gui/mbc_ui/panels/*.png
assets/cobblemon_more_battle_content_league_challenge/textures/gui/mbc_ui/icons/*.png
assets/cobblemon_more_battle_content_league_challenge/textures/gui/mbc_ui/backgrounds/*.png
assets/cobblemon_more_battle_content_league_challenge/textures/gui/portraits/*.png
```

`assets/.../mbc_ui/screens/`는 사용하지 않는다(MUST NOT). 선택형 `League Challenge Visual Pack`은 다음만 바꿀 수 있다(MAY).

- 패널, 테두리, 배경과 장식 텍스처
- 계급·뱃지·잠금·완료 아이콘
- 관장·사천왕·챔피언 초상화
- 허용된 범위의 짧은 UI 애니메이션 프레임
- 색, 투명도와 텍스처 ID처럼 `cosmetic`으로 명시된 토큰

외부 팩은 화면 구조, 행동 ID, 확인 단계, 포커스 순서, 필수 경고·잠금 의미, 패널 위치·히트박스, 계급 조건, 레벨캡, 도전 순서, 팀, 보상과 서버 승인 결과를 바꾸면 안 된다(MUST NOT).

일반 로컬 리소스팩의 모든 변경을 보안 경계로 막는다는 의미는 아니다. 위 항목은 MBC가 공식적으로 호환성을 보장하는 Visual Pack 계약이다. 허용 토큰 검증이 실패하면 외부 테마 전체를 버리고 코드 기본값과 내장 텍스처로 돌아간다(MUST).

## 5. 디자인 토큰

첫 테마는 개별 화면에 색을 직접 박지 않고 최소 다음 cosmetic 토큰을 사용한다.

- 표면: `background`, `chrome`, `panel`, `panelRaised`, `panelSelected`
- 상태: `locked`, `available`, `cleared`, `active`, `warning`, `error`
- 문자: `textPrimary`, `textSecondary`, `textMuted`, `textOnAccent`
- 텍스처: 프레임, 카드, 버튼 상태, 아이콘과 초상화 슬롯 ID
- 형태: 레이아웃 상자를 바꾸지 않는 모서리와 장식 테두리

화면 여백, 카드 간격, 상단·하단 행동 영역, 아이콘·초상화 슬롯 크기와 히트박스는 화면 계약이 소유한다(MUST). 외부 테마 토큰으로 노출하지 않는다. 계급별 색은 강조색으로만 사용하고 본문 대비와 상태 의미를 침범하지 않는다(SHOULD).

## 6. fixture와 선택형 웹 보조 도구

실제 네트워크 구현 전 다음 fixture를 준비한다(MUST).

- 0개, 2개, 3개, 5개, 8개 뱃지와 챔피언 상태
- 잠김·가능·완료·진행 중 체육관
- 규칙 적합 파티와 레벨·도구·중복 위반 파티
- 긴 관장명·보상명과 누락 초상화
- 한국어·영어
- 사천왕 0~4승과 챔피언 직전·승리 결과

첫 검증은 실제 Minecraft에서 `320×240`, `426×240`, `640×360`으로 수행한다(MUST). 웹 도구는 Minecraft 컴포넌트 표면이 안정된 뒤 반복 속도 이득이 확인될 때만 만든다(MAY). 만들 경우 같은 fixture와 내보낸 컴포넌트·상태·행동 manifest를 사용하지만 CSS 배치와 Minecraft 픽셀 배치의 동등성을 약속하지 않는다(MUST NOT).

## 7. 단계와 승인 게이트

| 단계 | 산출물 | 다음 단계 조건 |
| --- | --- | --- |
| LGUI-0 | 최소 League 모듈과 개발 환경 전용 GUI 진입점 | 프로덕션 환경에서 진입점 미등록, 서버 기능·저장 없음 |
| LGUI-1 | 실험적 League 홈 fixture와 타입 있는 화면·상태·행동 계약 | 등록되지 않은 행동·중복 ID·잘못된 상태 거부 |
| LGUI-2 | 코드 드로잉과 owo 후보의 Minecraft 홈 스파이크 | 세 폭·한영·3D·입력·클리핑 비교 자료 확보 |
| LGUI-3 | 백엔드와 첫 시각 방향 결정, 미선택 스파이크 제거 | 실제 게임 캡처와 접근성·성능 검토 통과 |
| LGUI-4 | 코드 기본값, 내장 League 테마와 Visual Pack 계약 | 별도 팩 없이 완전하며 잘못된 외부 테마 폴백 성공 |
| LGUI-5 | Minecraft 네 화면 수직 단면과 안정 API 승격 검토 | 최소 두 화면의 실제 반복 사용과 전체 흐름 승인 |
| LGUI-6 | 선택형 fixture·manifest 웹 보조 도구 | 유지 비용보다 반복 속도 이득이 확인될 때만 채택 |
| LGUI-7 | 서버 권위 상태 연결 | 오래된 상태·조작 요청을 서버가 거부 |
| LGUI-8 | 사천왕·챔피언 화면 확장 | 수직 단면의 공용 컴포넌트 재사용 확인 |

### 2026-09-25 LGUI-2 중간 증거

`badges_3` fixture의 코드 드로잉과 owo 후보를 실제 Minecraft에서 `320×240`, `427×240`, `640×360`으로 비교했다. 두 후보는 같은 정보 구조와 반응형 배치를 유지했고, owo도 기본 위젯 외형 대신 League 전용 surface와 renderer를 적용할 수 있었다. 영어·한국어 화면과 키보드 포커스·행동·내레이션 전달·닫기 경로를 각각 확인했다. 이어 `long_disabled` fixture를 `427×240` 한영으로 비교해 긴 관장명·보상명, 포커스 가능한 잠긴 행동과 fixture 3D 모델의 scissor 경계까지 확인했다. 캡처와 차이는 [UI_BACKEND_SPIKE_RESULT.md](UI_BACKEND_SPIKE_RESULT.md)에 기록했다.

이는 LGUI-2 완료가 아니다. 실제 리소스팩 관장 스킨과 `default`/`slim` 모델, 물리 마우스·음성 출력과 성능 증거가 남아 있다. owo 기본 어댑터는 내부 위젯 내레이션을 바닐라 화면으로 전달하지 않았으므로, 채택 시 MBC 비공개 백엔드가 전달 어댑터를 소유해야 한다(MUST). 비교를 위한 League의 직접 owo 참조는 일회성 개발 예외이며 LGUI-3 전에 MBC 비공개 렌더러로 이동하거나 제거해야 한다(MUST).

## 8. 시각·기능 검증

- 세 논리 화면 크기의 동일 상태를 실제 Minecraft에서 나란히 비교한다.
- 두 백엔드 후보는 같은 fixture와 화면 크기로 비교하고 가장 큰 차이부터 기록한다.
- 한국어와 영어에서 제목, 잠금 이유, 보상명이 잘리지 않는지 확인한다.
- 마우스, 키보드 포커스, ESC, 스크롤, 툴팁과 내레이션을 실제 게임에서 확인한다.
- 리소스 재로드 중 잘못된 외부 테마, 누락 아이콘과 누락 초상화를 각각 검증한다.
- 포켓몬·트레이너 3D 슬롯은 패널 밖으로 새거나 다른 스크롤 영역을 침범하지 않아야 한다.
- 리소스팩이 화면 구조, 행동과 확인 단계를 바꾸지 못함을 테스트한다.
- 빌드, 웹 스냅샷과 fixture manifest는 실제 Minecraft 화면의 시각·입력·성능 증거로 보고하지 않는다.

## 9. 구현 전에 남은 선택

1. 두 Minecraft 스파이크 중 채택할 렌더링 백엔드
2. League 테마의 구체적인 미술 방향과 색상 팔레트
3. 계급 엠블럼과 뱃지 아이콘을 새로 제작할지 PokeBadges 공개 자산을 참조할지
4. 기본 관장 초상화를 별도 제작할지 3D 스킨 슬롯만 사용할지
5. 외부 Visual Pack 예제를 릴리스 JAR과 별도 ZIP으로 함께 배포할지
6. Minecraft 화면 안정화 뒤 웹 보조 도구를 실제로 만들 가치가 있는지

1번과 2번은 실제 Minecraft League 홈 스파이크를 본 뒤 빡대리님이 결정한다. 구현자는 임의 팔레트, 제3자 자산이나 웹 시안을 확정본으로 넣으면 안 된다(MUST NOT).
