# cobblemon-npc 작업 기록

## [2026-10-05 15:55] 대화창 넘김 소리 통일, 전신 모델 대신 이름표에 얼굴

- 사용자 결정: NPC 대화창 넘김 소리는 배틀 대화창 소리로 통일한다. 박스 왼쪽의 전신 모델은 없애고, 좌상단 이름표의 이름 앞에 머리(얼굴)만 붙인다.
- 구현: `NpcDialogueScreen`은 UI 모드의 배틀 대화창 API를 쓰지 않고 직접 그린다. 소리를 바닐라 `UI_BUTTON_CLICK`(피치 1.4)에서 `BattleUiSounds.click()`(Cobblemon `GUI_CLICK`)으로 바꿨다. 선택지 버튼은 마우스로 누르면 바닐라 클릭이 같이 나서, cobblemon-ui의 `CobblemonUiButton.create`에 `downSound` 옵션(기본 true)을 추가하고 대화창 선택지만 false로 만든다.
- 얼굴은 `PlayerFaceRenderer`로 12px, 스킨은 `NpcSkins.skin()`으로 가져온다. 이름 없이 스킨만 있으면 얼굴만 든 이름표를 그린다.
- 빌드: 본 코드 빌드 성공, JAR 무결성 확인. cobblemon-ui `unitTest`는 267/275 통과. 실패 8건은 모두 `GalleryHarnessModeTest`(`internal` 하네스 클래스를 테스트에서 못 찾음)로 이번 변경과 무관한 기존 문제이고, `compileTestKotlin`도 같은 이유로 깨져 있다.
- 배포: 서버 `develop-product/server/mods`에 NPC JAR 배치(백업 `develop-product/deployment-backups/*-npc-dialogue`). 클라이언트 `develop-product/client/mods`에 UI·NPC JAR 배치(사용자가 게임을 끈 뒤, 같은 백업 폴더의 `client`).
- 실게임 검증: 안 함.
