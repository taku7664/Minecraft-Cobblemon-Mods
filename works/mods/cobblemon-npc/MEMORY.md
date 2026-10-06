# cobblemon-npc 작업 기록

## [2026-10-06 10:36] 대화 중 배틀캠이 NPC를 비춘다 (빌드·개발 클라이언트 캡처 확인)

- **빡대리님 지시:** 전투 연출 대사처럼 NPC 대화에도 카메라 포커스를 건다.
- **구현:** 서버 세션이 말하는 NPC의 엔티티 ID를 기억하고 `DialogueShowPayload.npcEntityId`로 보낸다(`/npc talk`처럼 NPC 없는 대화는 -1). 클라이언트 `NpcDialogueScreen`이 처음 열릴 때 `NpcDialogueCamera.focus`, 닫힐 때(`removed`) `release`. 배틀캠은 선택 의존(`compileOnly`, `isModLoaded`일 때만 호출)이고 개발 실행에는 `runtimeOnly`로 넣었다. 시간 제한 5분은 안전장치다.
- **프로토콜:** `DialogueShowPayload` 코덱 끝에 VarInt가 붙었다. 클라이언트와 서버 NPC JAR을 같이 바꿔야 한다.
- **배틀캠 쪽 변경:** NPC는 `RandomLookAroundGoal`로 고개를 돌리므로, 연출 카메라 방향을 장면 시작 때 머리 방향으로 고정한다(배틀캠 `MEMORY.md`).
- **검증:** `NpcSceneCaptureHarness`(`NPC_SCENE_CAPTURE=1`, `--quickPlaySingleplayer npc-capture`, 일회용 월드)로 난천 스킨 NPC 앞에서 `tower_guide` 대화를 열어 캡처했다. 카메라가 NPC 얼굴 정면으로 가고 대화창이 뜬다. 닫으면 1인칭으로 돌아온다. 개발 실행 폴더 `run/`은 이번에 처음 만들었다(옵션·월드·RCT 팩은 리그 개발 실행에서 복사).
- **남은 점:** 클로즈업에서 NPC 이름표가 머리 위에 떠 있다. 연출 중 이름표 숨기기는 레터박스 작업(4단계)에서 다룬다.

## [2026-10-05 15:55] 대화창 넘김 소리 통일, 전신 모델 대신 이름표에 얼굴

- 사용자 결정: NPC 대화창 넘김 소리는 배틀 대화창 소리로 통일한다. 박스 왼쪽의 전신 모델은 없애고, 좌상단 이름표의 이름 앞에 머리(얼굴)만 붙인다.
- 구현: `NpcDialogueScreen`은 UI 모드의 배틀 대화창 API를 쓰지 않고 직접 그린다. 소리를 바닐라 `UI_BUTTON_CLICK`(피치 1.4)에서 `BattleUiSounds.click()`(Cobblemon `GUI_CLICK`)으로 바꿨다. 선택지 버튼은 마우스로 누르면 바닐라 클릭이 같이 나서, cobblemon-ui의 `CobblemonUiButton.create`에 `downSound` 옵션(기본 true)을 추가하고 대화창 선택지만 false로 만든다.
- 얼굴은 `PlayerFaceRenderer`로 12px, 스킨은 `NpcSkins.skin()`으로 가져온다. 이름 없이 스킨만 있으면 얼굴만 든 이름표를 그린다.
- 빌드: 본 코드 빌드 성공, JAR 무결성 확인. cobblemon-ui `unitTest`는 267/275 통과. 실패 8건은 모두 `GalleryHarnessModeTest`(`internal` 하네스 클래스를 테스트에서 못 찾음)로 이번 변경과 무관한 기존 문제이고, `compileTestKotlin`도 같은 이유로 깨져 있다.
- 배포: 서버 `develop-product/server/mods`에 NPC JAR 배치(백업 `develop-product/deployment-backups/*-npc-dialogue`). 클라이언트 `develop-product/client/mods`에 UI·NPC JAR 배치(사용자가 게임을 끈 뒤, 같은 백업 폴더의 `client`).
- 실게임 검증: 안 함.
