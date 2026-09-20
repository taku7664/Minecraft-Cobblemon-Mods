# Design QA — 챔피언스식 전투 정보 화면

- source visual truth path: `C:\Users\박주형\.codex\generated_images\01a05562-1795-7042-855a-bdd64385bbb2\exec-e2b35434-a27a-4dac-856f-48835abb19ab.png`
- implementation screenshot path: 없음
- source pixels: 1680 × 945
- intended implementation viewport: Minecraft GUI 854 × 480 기준, 화면 크기에 맞춘 등비 축소
- state: 전투 중 Tab 정보 화면, 첫 아군 포켓몬 선택, 전장 효과 행 포커스
- density normalization: 실제 구현 캡처가 없어 수행하지 못함

## Findings

- [P1] 실제 렌더 결과를 아직 비교할 수 없음
  - 위치: `ChampionsBattleInfoOverlay`
  - 근거: 선택 시안은 열어 확인했지만 새 JAR의 실제 전투 화면 캡처가 없다.
  - 영향: 3D 모델 크기, 한글 잘림, GUI 배율, 패널 투명도와 포커스 대비를 눈으로 판정할 수 없다.
  - 수정: `cobblemon-dev`에서 전투에 진입해 Tab 화면을 854 × 480 상당 GUI 상태로 캡처하고 같은 크기로 시안과 함께 비교한다.

## Required fidelity surfaces

- Fonts and typography: 코드상 계층과 축척은 구현했으나 실제 Minecraft 글꼴의 줄바꿈·잘림은 미검증.
- Spacing and layout rhythm: 기준 좌표와 등비 축소는 구현했으나 실제 3D 모델 외곽과의 겹침은 미검증.
- Colors and visual tokens: 인디고·보라·마젠타·라임 포커스 토큰은 구현했으나 모니터상 대비는 미검증.
- Image quality and asset fidelity: 2D 대체물을 쓰지 않고 Cobblemon 3D 모델 렌더러를 사용하지만 실제 모델별 크기 차이는 미검증.
- Copy and content: 한글 레이블과 주요 전장 효과 번역을 추가했으나 실제 번역 누락 표시는 미검증.

## Full-view comparison evidence

- 소스 시안은 확인했다.
- 구현 스크린샷을 얻지 못해 합성 비교 입력을 만들 수 없었다.

## Focused region comparison evidence

- 구현 스크린샷 부재로 상단 팀 탭, 능력 변화 목록, 전장 효과 목록과 공개 정보 리본을 확대 비교하지 못했다.

## Comparison history

- 1차: 구현 빌드와 단위 테스트 통과. 실제 전투 캡처 부재로 P1 유지.

## Implementation checklist

- 실제 전투에서 Tab 화면 캡처
- 같은 화면에서 방향키 포커스, 정지 마우스, 실제 마우스 이동 상태 각각 캡처
- GUI 배율 최소·기본 상태에서 한글 잘림과 3D 모델 겹침 확인
- 시안과 구현 캡처를 같은 크기로 합쳐 P1/P2 차이를 수정

final result: blocked
