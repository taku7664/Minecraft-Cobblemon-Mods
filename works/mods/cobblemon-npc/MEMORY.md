# cobblemon-npc 작업 기록

## [2026-10-08 12:10] 코드로 여는 대화 API(`NpcTalks`)와 컴포넌트 패킷 (구현만, 빌드 안 함)

- **이유:** 야생 트레이너 대화를 이 대화창으로 옮긴다(리그 `docs/WILD_NPC_ROLES.md` 1단계).
- **패킷:** `DialogueShowPayload`의 speaker·lines·choices가 문자열에서 `Component`로 바뀌었다(`ComponentSerialization.TRUSTED_STREAM_CODEC`). 번역 키를 클라이언트 언어로 읽게 하려는 것이다(전용 서버는 모드 언어를 모른다). **프로토콜이 바뀌어 클라이언트·서버 NPC JAR을 같이 바꿔야 한다.** 대화 파일 대사는 `Component.literal`로 보낸다.
- **API:** `jbro.cobblemon.npc.api.NpcTalks.open(player, NpcTalk(speaker, lines, choices, npc, skin))`. 선택지(`NpcTalkChoice`)마다 서버 함수가 붙고, 고르면 창이 닫힌다. 그 함수가 다른 대화를 열면 같은 세션 번호를 써서 창을 닫지 않고 이어 보여 준다(`DialogueSessions.answering`). 함수가 예외를 던지면 기록하고 창은 닫는다.
- **미확인:** 사용자 지시로 빌드하지 않아 컴파일도 확인하지 않았다. 실게임도 안 함.

## [2026-10-08 10:00] 대사 다듬기

- 쉼표로 끝나 다음 페이지로 문장이 잘리던 곳(등산가·브리더·연구원·아가씨)을 한 줄로 합쳤다. 대화창이 줄바꿈하므로 한 페이지에 들어간다고 보지만 실게임으로 확인하지 않았다.
- 어른 NPC(등산가)에게 하는 선택지를 존댓말로 맞추고, 어색한 문장(팩토리 단골·정비공·마이룸·수집가)을 고쳤다. 리그 안내원 배지 4~7개 대사는 "절반이나"로, 타워 안내원 인사와 거절 대사는 챔피언 조건이 드러나게 바꿨다.
- 위키와 맞춘 표현: 야생 레벨은 "근처에 있는 사람"의 레벨캡, 알파는 "능력치 두 개 이상이 31", 챔피언은 "등급 때문에 못 잡는 전설이 없다"(엔트리 조건·이미 잡은 종은 남는다).

## [2026-10-08 09:40] 안내원 대사에서 "터미널"을 뺀다

- **사용자 결정:** 플레이어에게 보이는 대사에 "터미널"이라는 말을 쓰지 않고 "도전할래?"처럼 뭉뚱그린다.
- 리그 안내원은 "지금 리그에 도전할래?" → [응, 도전할래!] / [다음에 할게.], 타워·팩토리는 화면을 열기 전 마지막 대사를 응원으로 바꿨다(`docs/dialogues`, 묶음 예제, `run/` 설정, 개발 서버 설정). 명령(`@server /mcc terminal ...`)은 그대로다.

## [2026-10-08 09:20] 기자 NPC(`extra_reporter`)를 디스코드 연동 여부로 가른다

- **사용자 지시:** 연동 안 했으면 안내 링크 선택지, 했으면 디스코드 링크와 나가기 선택지.
- **구현:** `cmd:디코인증 linked`(jbro-policy에 새로 넣은 판정 명령)로 가른다. 미연동은 "연결하는 법 알려 줘"(`/wiki`로 플레이어 위키 링크를 보내고 디스코드 문서를 안내) / "나가기". 연동은 "디스코드 링크 줘"(`@server /tellraw`로 누르면 열리는 초대 링크 https://discord.gg/HbKxTFeGB) / "나가기". 위키 `public_url`이 비어 있어 고정 위키 주소 대신 `/wiki`를 쓴다.
- **미확인:** 빌드는 사용자 지시가 있을 때만 하므로 jbro-policy를 빌드·배치하지 않았다. 배치 전까지 판정이 늘 실패해 모두 미연동 대사를 본다. 개발 서버 `config/cobblemon_npc/dialogues/`에는 복사했다. 테스트·실게임 모두 안 함.

## [2026-10-08 08:50] 서버 대화 원본을 `docs/dialogues/`로, 엑스트라 트레이너 24명 추가

- **사용자 지시:** 기능 안내원만이 아니라 위키를 보고 엑스트라 트레이너 대사를 최소 20개 만든다.
- **위치:** 개발 서버 `config/`는 git에 없어서 원본을 `docs/dialogues/`에 두고 서버로 복사한다(안내원 3개 + `extra_*` 24개). `DialogueTest`가 이 폴더의 모든 파일을 코덱으로 읽고, `mcc terminal` 명령은 반드시 `@server`로 실행하는지 확인한다.
- **내용:** 위키(`server-wiki/pages`)의 레벨캡·야생 레벨·개체값·합성·숨겨진 특성·노력치·성격·야생 트레이너·전설·BP 상점·경험치·타워·팩토리·PvP·마이룸·사천왕·기믹 잠금·이모트·렉·디스코드를 캐릭터별로 나눴다. 일부는 `cmd:mcc league check ...`, `cmd:mcc tower access`, `cmd:mcc factory access`로 플레이어 진행도에 따라 대사가 갈린다.
- **터미널 명령:** `/mcc terminal`이 관리자 전용이 되어(MCC `MEMORY.md`) 안내원·예제의 명령을 `@server /mcc terminal ...`으로 바꿨다. 서버 권한으로 실행해도 열리는 화면은 말을 건 플레이어 것이다.
- **검증:** NPC 테스트 14개 통과. NPC를 실제로 세우고 말을 걸어 보지는 않았다. 엑스트라 트레이너는 아직 어떤 NPC에도 연결하지 않았다(완드로 대화 ID를 지정해야 한다).

## [2026-10-08 08:20] 팩토리·리그 안내원 대화 추가

- 개발 서버 `config/cobblemon_npc/dialogues/`에 `factory_guide`(`cmd:mcc factory access` → `/mcc terminal factory`), `league_guide`(`cmd:mcc league check ...`로 진행도별 대사 → `/mcc terminal league`)를 만들었다. 대사 속 설명(렌탈 교환, 하드 리그)은 각 애드온 언어 파일로 확인했다. 노드 연결 검사만 했고 실게임은 안 했다.
- 조건 명령 목록: `mcc tower access`, `mcc factory access`, `mcc league check champion|hard|badges <n>`. 모두 관리자 명령이지만 대화 조건은 권한 2로 돌아서 쓸 수 있다.

## [2026-10-08 07:55] 타워 안내원이 챔피언에게도 거절하던 문제

- **증상:** 조건이 맞는 플레이어도 "응, 도전할래!" 뒤에 "아직 자격이 안 되는 것 같네"로 빠졌다.
- **원인:** 예제 `tower_guide`가 `tag:tower_qualified`를 봤는데, 이 태그를 붙이는 코드가 어디에도 없다. 실제 타워 입장 조건은 리그 챔피언이고(`LeagueServer`의 `BattleContentAccess` 정책), 개발 서버의 Park_JH는 챔피언이지만 태그가 없었다.
- **수정:** 조건을 `cmd:mcc tower access`로 바꿨다(예제, `run/` 설정, 개발 서버 `config/cobblemon_npc/dialogues/tower_guide.json`, `OPERATOR_GUIDE`, 테스트). 명령은 타워 애드온에 새로 넣었다(타워 `MEMORY.md`). `cmd:` 조건은 권한 2로 돌아서 관리자용 `/mcc tower` 아래 명령도 통과한다.
- **빌드·테스트:** NPC 테스트 193개 통과. NPC JAR은 다시 배치하지 않았다(코드 변경 없음, 묶음 예제는 대화 파일이 없을 때만 쓰인다).
- **실게임 검증:** 안 함.

## [2026-10-06 12:55] NPC 대화창을 아래 띠 위쪽에

- `boxRect`의 top을 레터박스 아래 띠 높이만큼 올린다(띠와 함께 올라온다). 선택지는 박스 위에 쌓이니 같이 올라간다. 하네스는 대화가 열린 뒤 2틱 간격으로 6장을 더 찍어 슬라이드를 본다.

## [2026-10-06 12:42] NPC 대화창을 박스로 되돌리고 레터박스 위에 띄운다

- 10:55의 자막 방식은 철회(cobblemon-ui `MEMORY.md`). `NpcDialogueScreen`은 원래 박스·이름표·얼굴·선택지 배치를 그대로 쓰고, 그리기 전에 레터박스 띠를 먼저 깐다(`renderBars(graphics, 0f)`). 레터박스 소유와 카메라 포커스, NPC 시선 처리는 그대로.

## [2026-10-06 10:55] 대화를 레터박스 연출로, 대화 중 NPC가 플레이어를 본다 (빌드·개발 클라이언트 캡처 확인)

- **레터박스:** `NpcDialogueScreen`이 `CinematicScreen`이 되어 열릴 때 레터박스를 띄우고, 박스 대신 아래 띠 안의 자막(이름표+얼굴+대사)으로 그린다(cobblemon-ui `MEMORY.md`). 선택지 버튼은 아래 띠 바로 위, 자막 열 오른쪽 끝에서 위로 쌓인다. 전에 쓰던 박스 그리기(`drawBox`/`boxRect`)는 지웠다.
- **NPC 시선:** NPC는 `RandomLookAroundGoal`로 대화 중에도 고개를 돌렸다. 대화 세션이 그 NPC를 가리키는 동안 `customServerAiStep`에서 듣는 플레이어를 바라보게 했다(`DialogueSessions.listener`).
- **하네스:** NPC를 세우기 전에 플레이어 주변에 13×13 평평한 무대를 깐다. 캡처 월드 스폰이 절벽이라 지형이 카메라를 가렸다.
- **검증:** 평지에서 카메라가 NPC 얼굴 정면, NPC가 카메라를 보고, 띠 안에 이름표·얼굴·대사가 놓인다. 닫으면 1인칭, 이름표·토스트가 돌아온다.

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
