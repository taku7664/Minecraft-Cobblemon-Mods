# Shift 전투 로그

- 독자: 빡대리님과 Battle UI 유지보수자
- 버전: 0.1.9
- Updates: [표면 테마](SURFACE_THEME_2026-09-27.md), [가독성 수정](READABILITY_FIX_2026-09-28.md)
- 기준: 승인된 좌우 대화형 초안. 후속 요청에 따라 **양쪽 초상화 모두 행동한 포켓몬의 얼굴**로 통일한다. 최초 초안의 플레이어·트레이너 스킨은 구현하지 않는다.

## 동작

- 기본 왼쪽 Shift로 로그를 열고 다시 누르면 닫는다. 누르고 있는 동안에는 반복 토글하지 않는다.
- Esc, 설정된 취소 키, 하단 닫기 영역으로도 닫는다. 휠·위/아래·Page Up/Down·Home/End로 기록을 읽는다.
- 열 때 최신 기록으로 이동한다. 과거 기록을 읽는 중에는 새 기록이 와도 맨 아래로 강제 이동하지 않는다. 아래 끝으로 돌아오면 최신 기록을 따라간다.
- 왼쪽은 내 편, 오른쪽은 상대 편이다. 관전 중에는 Cobblemon 기본 화면과 같이 side2를 왼쪽에 둔다.
- 턴은 중앙 구분선으로 표시한다. 사용 기술과 확인 가능한 효과·급소·상태이상 결과를 같은 행동 카드에 묶는다. 날씨·지속 피해·출처 불명 문구는 중앙에 표시한다.
- 기존 대사 큐와 로그창은 같은 수신 기록을 사용하지만, 로그를 읽는 동작으로 대사를 확인하거나 전투 명령을 전송해서는 안 된다(MUST NOT).
- 로그 모달이 열려 있는 동안 배경 전투 명령의 키·문자 입력·클릭·휠은 처리되지 않아야 한다(MUST). TAB 정보창과 로그창은 동시에 열리지 않는다.
- 기록은 기존대로 최대 500개이며 전투 시작·종료 때 비운다. 서버 저장·파일 저장·종료한 전투 다시 읽기는 이번 기능에 포함하지 않는다.

## 초상화와 출처

`TranscriptSources`는 공개된 활성 포켓몬의 UUID, 이름, 소유자, 좌우, 종, 외형을 메시지 수신 시 보관한다. 카드가 나중에 교체된 현재 포켓몬 얼굴로 바뀌지 않는다. 양쪽 모두 Cobblemon의 `drawPosablePortrait`와 기본 전투 HUD 얼굴 구도를 사용하므로 별도 이미지팩이나 외부 다운로드가 필요 없다.

소유자가 있는 메시지는 `cobblemon.battle.owned_pokemon`의 인자를 비교한다. 야생 이름은 공개된 야생 포켓몬 중 유일하게 일치할 때만 연결한다. 번역된 문장에 임의의 영문 소유격 규칙을 적용하거나, 같은 이름의 포켓몬 중 임의의 대상을 고르면 안 된다(MUST NOT). 식별되지 않는 서버 확장 문구는 얼굴 없는 중앙 기록으로 남긴다. 모델 리소스에 문제가 있으면 해당 얼굴 칸에 `?`를 표시하고 로그에 한 번 기록한다.

받은 기록 순서대로 턴을 계산한다. `BattleStateTracker`가 패킷 전체를 먼저 처리하므로 그 마지막 턴 값을 로그의 시작 턴으로 사용하지 않는다. HP 초기값도 첫 기록 패킷에서 다시 지우지 않는다.

## 시각 및 설정

- 남색·청록 외곽, 대각선 모서리, 내 편 청록/상대 편 보라색 카드, 그림자 없는 글씨를 유지한다.
- 카드 높이는 줄바꿈 결과에 맞춘다. 작은 창은 본문만 스크롤하며 제목·닫기 영역은 고정한다.
- 색과 카드 표면은 `BattleUiTheme.transcriptSelf`, `transcriptOpponent`, `TRANSCRIPT_OPPONENT`에 모았다.
- 키는 Minecraft 조작 설정에서 변경한다. 글자 크기는 기존 ModMenu → Battle UI → 크기 설정에 추가했다. 한국어·영어 번역을 함께 제공한다.
- MBC 의존성, 서버 프로토콜, 사용자 리소스팩 목록은 변경하지 않는다.

## 검증 재현

순수 그룹화 테스트는 먼저 실패를 확인한 다음 구현했다. 화면 배치·3D 얼굴은 단위 테스트 대신 개발 월드에서 실제 렌더러를 캡처한다. 모의 전투 입력 검사는 변환된 실제 `BattleGUI`의 메서드를 호출하되 서버 전투를 시작하거나 응답 패킷을 보내지 않는다.

```powershell
$env:COBBLEMON_BATTLE_UI_CAPTURE = '1'
$env:COBBLEMON_BATTLE_UI_CAPTURE_TRANSCRIPT_ONLY = '1'
$env:COBBLEMON_BATTLE_UI_CAPTURE_LANGUAGE = 'ko_kr' # 또는 en_us
$env:COBBLEMON_BATTLE_UI_CAPTURE_WIDTH = '1280' # 작은 창: 854
$env:COBBLEMON_BATTLE_UI_CAPTURE_HEIGHT = '900' # 작은 창: 480
$env:COBBLEMON_BATTLE_UI_CAPTURE_GUI_SCALE = '2'
$env:COBBLEMON_BATTLE_UI_CAPTURE_FONT_PACK = 'galmuri11-8px.zip'
$env:COBBLEMON_BATTLE_UI_CAPTURE_LABEL = 'transcript-verified'
.\gradlew.bat --no-daemon --configure-on-demand :cobblemon-battle-ui:unitTest :cobblemon-battle-ui:build :cobblemon-battle-ui:runClient
```

폰트 ZIP은 개발용 `run/resourcepacks`에 있어야 한다. 위 환경 변수는 해당 실행용 터미널에만 설정하며 작업 후 해제한다. 개발 환경과 opt-in 플래그가 모두 있어야 실행되므로 배포 JAR에서 자동으로 월드나 예시 화면을 열지 않는다.

개발 중 수정한 문제: 기본 메시지와 상태이상 메시지의 번역 키 영역이 달라 화상 예시 문구가 번역되지 않았고, 초상화 크기를 기본 HUD의 28px 기준 대신 48px로 계산했다. 실제 키와 기본 HUD 구도에 맞춰 수정했다. 마지막 캡처 뒤 종료 요청과 다음 렌더 프레임 사이에 페이지 인덱스가 초과하는 오류도 tick/render 양쪽 경계 검사 및 회귀 테스트로 수정했다.

전체 모드팩 서버 실전에서의 물리 키 입력·장시간 전투·모든 커스텀 종 모델은 별도 검증이다. 개발 캡처나 테스트 성공을 실전 완료로 간주하지 않는다.

## 확인 결과

- 단위 테스트 132개 통과. 기존 119개와 그룹화 5개, 출처·턴 처리 5개, 입력 연결·초상화·캡처 종료 회귀 검사 3개다.
- 실제 변환된 `BattleGUI`를 사용하는 개발 입력 검사에서 Shift 열기/반복 방지/닫기, Esc 닫기, 대사 확인 Z 차단, 배경 클릭·문자 입력 차단이 통과했다. 모의 전투의 요청 목록은 비어 있었다.
- Minecraft 1.21.1 / Fabric 0.19.5 / Cobblemon 1.8.1 / Galmuri, GUI 배율 2에서 한국어 1280×900 및 영어 854×480을 확인했다. 작은 창은 최신 기록에 맞춰 본문 윗부분을 스크롤하며, 제목과 닫기 영역은 유지된다.
- [한국어 기본](docs/captures/2026-09-28/battle-ui-transcript-verified-log-ko_kr.png), [긴 기록](docs/captures/2026-09-28/battle-ui-transcript-verified-log-long-ko_kr.png), [빈 기록](docs/captures/2026-09-28/battle-ui-transcript-verified-log-empty-ko_kr.png)
- [영어 작은 창](docs/captures/2026-09-28/battle-ui-transcript-verified-small-log-en_us.png), [긴 기록](docs/captures/2026-09-28/battle-ui-transcript-verified-small-log-long-en_us.png), [빈 기록](docs/captures/2026-09-28/battle-ui-transcript-verified-small-log-empty-en_us.png)
- 개발 로그에는 Cobblemon 초기화의 data fixer 누락 및 새 개발 플레이어의 `.old` 데이터 파일 누락 메시지가 있었다. 캡처는 모두 생성됐으며 개발 클라이언트는 수정 후 정상 종료했다. 사용자 프로필 세이브를 연 검사가 아니다.
