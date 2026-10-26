# MEMORY

## [2026-10-26 21:20] PvP 결과 전체채팅 알림 (컴파일·단위 테스트 확인, 빌드 배치·실게임 미확인)

- **빡대리님 요청:** PvP 결과를 전체채팅에 "player1이(가) player2에게 싱글(더블)배틀 1:1에서 패배했습니다"처럼 띄우고, 이름은 닉네임 모드(Styled Nicknames) 것을 쓴다.
- **구현:** `settleCompletion`이 결과를 받아들인 뒤 `broadcastResult`가 `playerList.broadcastSystemMessage`로 보낸다. 키 `chat.more_cobblemon_contents.pvp.result`(패자·승자·형식), 형식은 기존 `pvp.format.single/double`. 무승부·취소(`pending.cancelled`)와 이미 정산된 재시도는 보내지 않는다.
- **닉네임:** Styled Nicknames(`changeDisplayName: true`)가 `Player.getDisplayName`을 바꾸므로 의존성 없이 `displayName`을 쓴다. 로그아웃 기권이면 정산 시점에 패자가 플레이어 목록에 없어서, 배틀 런타임이 종료 콜백에서 시작 때 잡아 둔 `ServerPlayer`로 이름을 뽑아 `PendingPvpCompletion.displayNames`에 실어 보낸다. 끊긴 플레이어의 옛 객체에서도 닉네임이 나오는지는 확인하지 않았다(안 나오면 계정 이름).
- **확인:** F 복사본에서 `:more-cobblemon-contents-pvp:unitTest` 244/244 통과. 처음엔 `displayName`이 nullable이라 컴파일이 깨져 `?: name`을 붙였다. 결과 알림 전용 단위 테스트는 없다. 빌드 배치·서버 기동·실제 두 사람 대전은 하지 않았다. 브랜치 `feature/pvp-result-broadcast`에만 커밋했고 main에는 넣지 않았다.

## [2026-10-18 13:50] `6bc4fd51` 개발 서버·클라 배포: MCC 애드온 피드백 수정 (빌드·해시 확인, 서버 기동·실게임 미확인)

- `origin/main` `6bc4fd51`을 워크트리 `.claude/worktrees/mcc-addon-feedback`에서 `remapJar`로 빌드했다(EXIT 0, Kotlin in-process, 빌드 출력 `F:/AI/builds/mcc-addon-feedback`). 세 모듈을 한 번에 `assemble`하면 2G 힙에서 GC 과부하로 데몬이 멈춰, 모듈별 `remapJar`로 나눴다. `unzip -t`와 JDK 21 `jar --validate`를 통과했다.
- 개발 서버·클라이언트에 타워·리그·PvP 3개만 넣었고 빌드본과 SHA-256이 같다(PvP 64D4448A…). 백업 `develop-product/deployment-backups/20261018-1348-6bc4fd51-mcc-addons`. 개발 서버와 게임은 꺼져 있었고 서버는 켜지 않았다.
- MCC 본체·UI·NPC는 넣지 않았다. 설치된 본체 이후 main의 본체 변경은 Better AI 내부와 배틀 UI(`extended`)뿐이라 애드온이 쓰는 API는 같다. 개발 서버 본체(05:13, `74bd86b0`)와 개발 클라 본체(03:51)는 원래부터 다르다(다른 세션 배치).
- PPakemon 프로필·운영(VPS)·deploy-product에는 넣지 않았다. PvP 방 패킷 형식이 바뀌어 운영 서버와 같이 바꿔야 한다. 실게임은 확인하지 않았다.

## [2026-10-18 07:40] 초대 목록 정리·초대 화면 재구성·방 중복 금지 설정 (단위 확인, 빌드 배치·실게임 미확인)

- **빡대리님 피드백:** 초대에 안 들어와 있는 사람이 목록에 뜬다. 초대 UI가 너무 별로다. 룸 설정에 기타 제한(중복 도구 금지, 중복 포켓몬 금지)을 넣고 비좁지 않게 배치.
- **목록 해석:** 초대 피커 후보는 "접속자 − 이 방 멤버"였고, 방장 클라이언트는 방이 바뀔 때만 갱신받아 나간 사람이 남았다. 다른 방·대전 중인 사람과 이미 초대한 사람도 구분 없이 섞였다. 방 화면 관전자 목록에는 초대만 받은 사람이 구조상 나오지 않는다(입장한 사람과 자리 없는 방장만). 사용자가 말한 목록이 피커라는 것은 추정이다.
- **서버:** 후보에서 다른 방·대전 중인 사람을 뺀다. 방 상태에 `invitedIds`(초대했지만 안 들어온 사람)를 싣는다. 오프라인·이미 이 방·다른 방이나 대전 중인 대상 초대를 `target_offline`/`target_in_room`/`target_busy`로 거절한다. 접속·퇴장 때 모든 로비를 다시 보내고, 1초마다 로비별 후보·초대 상태 서명을 비교해 바뀐 로비만 다시 보낸다(첫 관측도 보냄). 거절하면 방 멤버에게 다시 보낸다.
- **중복 금지:** `PvpRoomSettings.speciesClause`·`itemClause`(기본 켜짐, 이전에 항상 켜져 있던 동작 그대로). 방 시작 시 팀 등록에 넘기고, 1:1 도전은 둘 다 켠다. 직렬화에 두 boolean이 붙어 클라·서버가 같은 JAR이어야 한다.
- **초대 UI:** 피커 행을 얼굴·이름·상태·[초대]/[양도] 버튼으로 바꾸고, 초대 후에도 피커에 머문다. 초대/양도 전환은 하단 버튼. 초대 팝업에 "형식 · 기믹 · 중복 금지" 줄(초대 패킷에 방 설정을 실음).
- **비좁음:** 설정 4행은 가장 작은 허브(305x184)에서 라벨이 칩 위로 올라가면 128px가 필요하고 카드 본문은 101px다. `PvpRoomSettingsFit`가 실제 글꼴 폭으로 필요한 높이를 재서 3:3:4 → 2:2:4 → 1:1:3 순으로 설정 카드를 넓히고, 그래도 안 되면 짧은 라벨(공개·형식·기믹·중복 / ACCESS·FORMAT·GIMMICK·DUPES)을 쓴다(영문 최소 크기가 이 경우). 손계산으로 한·영 세 크기 모두 들어간다.
- **실수:** 파이썬으로 고칠 때 `"\n"`이 실제 줄바꿈으로 들어가 팝업이 컴파일되지 않았다(R8 지적). 초대 람다가 `Boolean`을 돌려 `() -> Unit`과 안 맞았다(T3). `pvpInviteCandidates` 끝에 기본값 람다를 붙여 기존 테스트의 끝 람다가 그쪽으로 묶였다(T4).
- **확인:** Codex가 `1aaa7f45`에서 `:more-cobblemon-contents-pvp:unitTest` 244/244 통과(로그 Claude 확인). Codex 리뷰 R8·R9·R12 지적 반영, R14 지적 없음. 실제 화면 캡처·두 사람 초대 실게임·빌드 배치는 하지 않았다.

## [2026-10-16 11:30] PvP 초대를 콘텐츠 테마 팝업으로 (컴파일·단위 테스트 확인, 빌드 배치·실게임 미확인)

- **빡대리님 요청:** 채팅 [입장]/[거절] 대신 초대 팝업.
- **구현:** `PvpInvitePopup.show`를 초대 수신 때 부른다. 화면이 비었거나 `MccHubScreen`일 때만 띄우고, 전투·상자·채팅 입력 등 다른 화면 중에는 띄우지 않는다. 채팅 줄은 항상 같이 남는다. 입장·거절은 채팅 클릭과 같은 `PvpInviteChatClickHandler` 경로로 보낸다. Esc는 `CobblemonUiDialogScreen.dismiss`로 답하지 않고 닫는다(나중에 채팅으로 답할 수 있게).
- **확인:** 커밋 `89a4f353`. Codex가 F 복사본에서 컴파일, PvP `unitTest` 239/239 통과(로그는 Claude가 확인). Codex 리뷰 지적 없음. 초대 팝업을 다루는 단위 테스트는 없다. 빌드 배치와 실게임 팝업은 확인하지 않았다.
- **배포 주의:** `cobblemon-ui`의 `CobblemonUiDialogScreen.dismiss`를 쓰므로 이 PvP JAR은 같은 커밋 이후의 cobblemon-ui와 같이 배포한다. 위 기록대로 UI가 바뀌면 MCC·NPC 내장 사본도 같이 다시 넣는다.
- **테스트 태스크:** 이 모듈은 `tasks.test`가 꺼져 있다. 테스트는 `:more-cobblemon-contents-pvp:unitTest`로 돌린다.

## [2026-10-13 03:05] PvP VS 인트로와 개발 배포

- **사용자 요청:** PvP에도 VS 연출. 빠르게 넣는 쪽을 골랐다.
- **방식:** `PvpVersusClient`가 클라에서만 띄운다. 배틀이 열리고 PvP 태그가 붙어 있으며 관전 중이 아니면 상대 액터의 프로필로 `VersusTier.PVP`(2.4초)를 재생하고, 끝나면 바로 흰 화면을 걷는다. 서버는 배틀을 붙잡지 않는다. 리그처럼 서버에서 붙잡으면 `lounge.activate`가 시작 전 배틀에 관전자를 넣게 되는데, 이 경우는 검증된 적이 없어서 피했다.
- **알고 둔 부작용:** 첫 턴 제한 시간이 VS 아래에서 흐른다(양쪽 같음). VS가 덮인 동안 아래 배틀 화면이 입력을 받을 수 있다.
- **배포:** main `c58489cc`에서 UI·MCC·NPC·PvP 빌드, `jar --validate` 통과, MCC·NPC 내장 UI 해시가 단독 UI(54B20735…)와 같다. 개발 클라 4개·서버 3개 교체, 해시 일치. PvP 593E1451…, MCC 3BB7E125…, NPC 2EE08AAA…. 백업 `develop-product/deployment-backups/20261013-0305-pvp-versus`. **UI를 바꾸면 MCC·NPC를 같이 다시 넣어야 한다**(같은 0.2.0 내장 사본이 옛것이면 `VersusTier.PVP`가 없어 크래시).
- **확인 안 함:** 실제 두 플레이어 대전에서 VS가 뜨는지(하네스로 그림만 확인), 서버 기동.

## [2026-10-13 02:35] 개발 배포

- main `fa69822f` 빌드본(SHA-256 3507DB84…)을 개발 서버·클라이언트에 넣고 해시 일치를 확인했다. MCC·UI·NPC·리그와 함께 교체(MCC `MEMORY.md` 같은 시각 항목). 실게임 확인은 안 했다.

## [2026-10-13 02:00] 대기방 설정 칩·관전자 얼굴 카드·선출 화면 독립

- **사용자 지시:** 대기방은 허브 탭 안에 두고 룸 옵션 버튼만 줄인다. 관전자에 마우스를 올리면 누군지 보이게 한다. 선출 화면은 허브에서 떼어 경기장 위에 따로 띄우고, 테마는 `cobblemon-ui`의 배틀 UI 테마(`BattleUiTheme`)를 따른다. 왼쪽 나, 오른쪽 상대, 가운데 프레임은 호버 우선이고 호버가 없으면 마지막 선택을 보여준다. 키보드 이동이 돼야 한다. **상대 도구는 절대 보여주지 않는다**(공개 정보는 포켓몬·폼·타입).
- **대기방:** `MccHubKit.choices`는 MCC 공용 API라 시그니처를 바꾸지 않았다(배포된 애드온 JAR이 깨진다). 대신 `PvpRoomSettingRows`가 라벨 칸 하나에 높이 14 칩을 그린다. 기믹 4개는 폭이 모자라면 2줄이 된다.
- **관전자:** 얼굴 격자에 마우스를 올리면 32px 얼굴과 이름 카드, "관전자 n명"에는 얼굴과 이름 목록 카드가 뜬다(`PvpSpectatorCard`). 허브 밖 HUD도 이름 앞 점 대신 8px 얼굴을 그린다.
- **선출 화면:** `PvpSelectionScreen`. `acceptSelection`이 허브 대신 이 화면을 열고, 허브 탭은 "선출 화면 열기" 카드만 둔다. HUD 열기 버튼도 선출 중이면 이 화면을 연다. 화면이 떠 있는 동안 `hideGui`로 핫바·체력을 숨기고 닫을 때 원래 값으로 되돌린다. 능력치는 배틀 레벨 기준으로 종족값·IV·EV·성격 공식으로 계산한다(클라 파티 `Pokemon`의 레벨 값과 다를 수 있어서).
- **캡처 하네스:** `PvpCaptureHarness`(`MCC_PVP_CAPTURE=room,room_hover_face,room_hover_count,selection,selection_picked,selection_hover_opponent,selection_keys,selection_spectator`, `MCC_PVP_CAPTURE_GUI_SCALES=3,4`). 가짜 상대·관전자를 플레이어 목록 패킷으로 클라에 넣고, 방·선출 상태를 서버 응답처럼 `PvpHubClient`에 넣는다. 서버로는 아무것도 안 간다. `:more-cobblemon-contents-pvp:runClient --args="--width 1920 --height 1080 --quickPlaySingleplayer mcc-hub-capture"`. 실행 폴더 `run/options.txt`는 언어 `ko_kr`, 접근성 첫 화면 끔, 포커스 일시정지 끔.
- **확인함:** 컴파일·테스트 컴파일 성공. 하네스 캡처로 대기방 칩·두 호버 카드, 선출 화면(미선택·2마리 선택·상대 호버·키보드 커서·관전자 시점)을 GUI 배율 3(640×360)·4(480×270)에서 눈으로 확인했다.
- **확인 안 함:** 실제 두 플레이어 대전, 서버 왕복(확정·취소·재시도 버튼의 서버 응답), 취소 확인창에서 돌아왔을 때 화면 복귀, 단위 테스트 실행(44개 파일이라 생략).

## [2026-10-13 00:40] 종료 후 배틀·BGM 잔류 (원인은 MCC 본체) 재점검

- **증상(운영 10-12 20:55)**: PvP가 끝나도 클라 배틀과 배틀 BGM이 남았다. 원인은 MCC 본체 `BattleSceneClient`가 보류한 종료를 라운지 이탈 텔레포트의 클라 리셋이 버린 것이다. 수정·점검 내용은 MCC `MEMORY.md` 같은 시각 항목.
- **10-11 점검에서 놓친 이유**: 범위를 이 애드온 안쪽으로만 잡았다. PvP 종료 문제는 MCC 클라 믹스인·리셋까지 같이 봐야 한다.
- **이 애드온 수정**: 관전자 "돌아가기" 때 서버는 텔레포트 뒤 결과를 보내는데, 텔레포트 리셋이 퇴장 요청 기록을 지워 승인돼도 화면이 안 닫혔다. 요청 기록은 접속·접속 종료 때만 지운다(`PvpLoungeSpectatorControls`).
- **미확인**: 빌드·테스트·배포·실게임 확인 안 함.

## [2026-10-11 12:22] 오픈 전 점검 수정

- **라운지 복귀 게임 모드:** 복귀 지점에 `GameType.name`(`CREATIVE`)을 저장하고 `GameType.byName`(소문자 `creative`만 인식)으로 읽어서 모두 서바이벌로 돌아갔다. 이제 `getName()`(소문자)으로 저장하고, 복원은 `pvpReturnGameMode`가 대소문자 구분 없이 두 형식을 모두 읽는다. 모르는 값일 때만 서바이벌. 복귀 지점은 메모리에만 있어(파일 저장 없음) 옛 데이터 이전은 필요 없다.
- **사라진 차원의 복귀 지점:** 차원을 찾지 못하면 `restore`가 false를 돌려 매 틱 재시도했다. 이제 기록된 게임 모드로 오버월드 스폰에 보낸다(`restoreToOverworldSpawn`에 게임 모드 인자 추가).
- **관전자 팀 프리뷰 잔류:** 서버는 프리뷰 종료를 두 선수에게만 알렸고, 관전자의 프리뷰 화면은 남아 "돌아가기"(라운지 퇴장 → 로비 방에서 빠짐)만 누를 수 있었다. 클라이언트는 방 상태가 TEAM_PREVIEW가 아니거나 다른 방이면 관전 프리뷰를 버리고 방 화면으로 바꾼다(`PvpRoomNavigationContract.keepsSpectatorPreview`, `acceptRoom`·`showLastRoom`). 이때 허브를 새로 열지는 않는다. 서버 `notifyClosed`도 방 관전자에게 닫힘을 보낸다.
- **재시도 버튼:** 출전 실패 뒤엔 두 선수 모두 준비 상태라 `waitingForOpponent`가 먼저 걸려 "준비 취소"만 보였고 서버는 이를 거절했다. 재시도 분기를 먼저 검사한다.
- **관전자 한 명이 경기를 막던 문제:** `activate`에서 관전자 한 명의 이동·관전 등록이 실패하면 경기 전체를 되돌렸다. 이제 그 관전자만 빼고(복귀 지점으로 되돌림, 방 소속은 유지) 경기를 진행한다. 다른 배틀 중인 사람은 `canSpectate`로 미리 걸러 아예 옮기지 않는다. 관전 등록이 깨끗하게 거절된 경우엔 `stopSpectating`을 부르지 않는다(자기 배틀 화면이 닫히므로). 선수 이동 실패는 지금처럼 치명적이다.
- **배틀 중 이탈 무기록:** 승자 없이 끝나면 무조건 취소였다. 이제 코어 `ManagedBattleForfeits.of(battleId)`에 두 선수 중 한 명만 있으면 그 선수 패배·상대 승리로 일반 승리와 같은 경로(`completion`)에 기록한다. 둘 다거나 아무도 없으면 기존처럼 취소(`resolvePvpBattleOutcome`).
- **리스폰 뒤 옛 ServerPlayer:** `AFTER_RESPAWN`에서 `onlinePlayers`를 새 객체로 바꾼다. 라운지 게이트웨이·배틀 런타임·스냅샷 저장소는 `livePlayer`로 조회한다(온라인 여부는 `onlinePlayers`, 객체는 `playerList`).
- **접속자 129명 이상에서 끊김:** 룸 패킷의 초대 후보가 접속자 전원이라 클라 읽기 상한 128을 넘었다. 서버는 이름순 100명(`PVP_ROOM_MAX_INVITE_CANDIDATES`)만 보낸다. 관전자·관전자 수 상한은 512로 올리고 선출 패킷 관전자 상한도 같게 맞췄다. 쓰는 쪽도 같은 상한을 검사해 넘으면 보내지 않는다.
- **주의:** 룸·선출 패킷 상한이 바뀌어 서버·클라 PvP JAR을 함께 배치해야 한다. 이탈 패배 기록은 코어의 `ManagedBattleForfeits`(미커밋 코어 변경)가 들어간 MCC JAR이 필요하다.
- **미확인:** 빌드·테스트를 돌리지 않았다(테스트 코드만 추가·수정: `PvpLoungeCoordinatorTest`, `PvpRoomPayloadsTest`, `PvpRoomNavigationContractTest`, 새 `PvpBattleOutcomeTest`, `PvpReturnGameModeTest`). 배치·실게임 확인 안 함.

## [2026-10-11 03:17] 방 나가기·파티 하한·관전자 툴팁 개발 배포

- **빌드:** 공유 작업 트리의 MCC 코어에 다른 세션의 미커밋 Better AI 변경이 있어, `main` `1d7da204`를 `git archive`로 `F:/AI/build/pvp-deploy-20261011/src`에 내보내 빌드했다(워크트리 아님). `:more-cobblemon-contents-pvp:build -x unitTest` 성공(테스트는 직전에 76개 통과). JDK 21 `jar --validate`·ZIP CRC 통과, 새 코드 포함·테스트 클래스 미포함 확인.
- **호환성:** 배치된 코어 JAR(10-10 20:18)은 `main`보다 오래됐지만 이후 코어 변경은 상점·Better AI 내부뿐이다. PvP JAR이 참조하는 외부 멤버 384개를 배치된 코어·`cobblemon-ui` JAR과 바이트코드로 대조해 모두 해석됨(남은 6건은 바닐라 `AbstractWidget` 상속 멤버라 검사기 한계). 그래서 PvP JAR만 교체했다.
- **배치:** `develop-product/server/mods`와 `develop-product/client/mods`의 PvP JAR 교체. 빌드본·서버·클라 SHA-256 `f049a4b1…` 일치, `.deploying` 잔여물 없음, PvP JAR 하나씩만 있음. 배치 전 25565/25566 리스너와 게임 프로세스 없음. 이전 JAR은 `develop-product/deployment-backups/20261011-0320-pvp-room-leave/{server,client}`.
- **미확인:** 서버를 켜지 않았고 실게임 확인 안 함. 목록 패킷 형식이 바뀌어 서버·클라 PvP JAR 버전이 다르면 접속 중 끊길 수 있다(이번엔 둘 다 같은 JAR). `deploy-product` 릴리스 아님.

## [2026-10-11 03:08] 룸 사용자 흐름 모의 시나리오 검증

- **요청:** 사용자가 위 수정을 믿기 어렵다며 룸 사용자 흐름을 코드로 모의 실행해 달라고 함.
- **구조:** 클라이언트 판단(`rememberedAfterRoomList`, `keepsSelectionAfterRoomList`, `canLeave`)을 `PvpRoomNavigationContract`로, 서버 목록 응답 생성을 `PvpRoomService.listStateFor`로 옮겨 실제 수신기·버튼·`sendRoomList`가 그 함수를 쓰게 했다. `PvpRoomUserScenarioTest`는 실제 `PvpRoomService`·목록 코덱·이 함수들로 흐름을 돈다. 서버의 Leave 분기는 `handleRoomIntent`를 따라 옮겨 적었고, 라운지(텔레포트·Cobblemon 관전)는 범위 밖이라 방 상태 변화와 승인 응답만 흉내 냈다.
- **시나리오:** 앉은 손님 로비 퇴장 후 HUD 열기, 옛 규칙으로 버그 재현(퇴장 → HUD 열기 → 관전자 재입장), 방 목록 갔다가 다시 열기(자리 유지), 방장 퇴장 시 위임, 마지막 인원 퇴장, 단계별 나가기 버튼 = 서버 허용 여부, 팀 프리뷰 관전자 퇴장, 배틀 중 관전자 라운지 퇴장, 챌린지 선출 화면 유지, 퇴장 후 다른 방 입장, 경기 종료 후 로비 복귀, 1마리 파티 선출. 세션 테스트에 더블 2:1 파티 경기 시작과 1마리 파티 시간 초과 자동 선출을 추가했다.
- **실행:** F 드라이브 전용 출력(`F:/AI/build/pvp-room-scenario-20261011`, init 스크립트로 7개 클래스만 실행)으로 76개 테스트 통과. 첫 실행의 1건 실패는 테스트 기대값 오류(앉지 않은 방장도 관전자)였고 기대값만 고쳤다.
- **미확인:** JAR 배치·실게임 확인은 하지 않았다. Showdown이 더블 1마리 파티를 받아 주는지는 실제 배틀에서만 확인할 수 있다.

## [2026-10-11 02:50] 방 나가기 후 관전자로 재입장되던 문제, 파티 하한 제거, 관전자 명단 툴팁

- **나가기 원인:** 서버는 로비에서 나가기를 정상 처리했지만, 클라이언트는 방 목록을 받을 때 "목록에 그 방이 없을 때만" `PvpRoomClientState.lastRoom`을 지웠다. 다른 사람이 남은 공개 방은 목록에 남으므로 나간 뒤에도 HUD에 방이 그대로 보였고, HUD 열기(O키)·버튼 → `showLastRoom` → `Join` 핸드셰이크가 좌석 없는 멤버, 즉 관전자로 다시 넣었다. 배틀 중 관전자가 "돌아가기"로 라운지를 나갈 때도 방 목록이 오지 않아 같은 일이 났고, 팀 프리뷰 관전 화면(`selection`, spectatorMode)도 남아 있었다.
- **수정:** `PvpRoomListStatePayload`에 `memberRoomId`(받는 플레이어가 속한 방)를 추가했다. 클라이언트는 이 값이 기억한 방과 다르면 `lastRoom`을 지우고, 방이 없으면 관전용 선출 화면도 버린다. 라운지 퇴장이 승인되면 `PvpHubClient.leftRoom()`으로 방 상태를 비운다. 서버는 팀 프리뷰 중 관전자가 나가면 남은 관전자에게 프리뷰를 다시 보내고, 배틀 중 관전자를 관리자가 내보내면 목록을 보낸다. 로비가 아닐 때 앉은 플레이어의 "방 나가기" 버튼은 비활성(서버도 거절하던 동작).
- **주의:** 목록 페이로드 형식이 바뀌어 서버·클라 PvP JAR을 함께 배치해야 한다.
- **파티 하한:** 싱글 3~6/더블 4~6 등록 범위를 둘 다 1~6으로 바꿨다(0마리는 배틀이 성립하지 않아 유지). 선출 수는 `PvpBattleFormat.selectionSizeFor(파티 수)` = 형식 기본값(3/4)과 파티 수 중 작은 값. 서버 `PvpTeamRules.select`, 클라 선출 화면 모두 이 값을 쓴다. 위키 `pvp.html` 규칙 문구도 갱신.
- **관전자 툴팁:** 룸·초대 화면 상단 띠의 "관전자 n명"에 마우스를 올리면 이름 목록(최대 20명, 넘치면 "+n명 더 있음")이 뜬다. MCC 본체 `MccHubKit.strip`은 건드리지 않고 PvP 쪽에서 같은 위치에 hover 영역을 겹쳤다(오른쪽 여백 6px 가정).
- **미확인:** 사용자 규칙에 따라 빌드·테스트를 돌리지 않았다(테스트 코드는 갱신). 배치·실게임 확인 안 함. 더블에서 파티 1마리로 실제 배틀이 열리는지(Showdown 쪽)는 확인하지 않았다.

## [2026-10-08 21:56] main 병합 후 빌드·개발 배포 재확인

- **병합:** 기믹 API 브랜치를 현재 main과 병합한 `5d4afd558bd3c1fa63e7d81d47c568ff5d564933`을 main에 푸시했다. 기존 BP 테스트 기대값 수정 `3c247482`·`10e14da4`도 포함한다. 앞선 브랜치 배포 기록은 이번 main 검증으로 보완하며 이전 실패 기록은 보존한다.
- **빌드·테스트:** 깨끗한 워크트리에서 현재 main 소스로 MCC 5개 모듈 빌드 성공(2분 19초). PvP 테스트 210개 통과.
- **배치:** main 빌드본의 SHA-256이 앞서 배치한 JAR과 같아 재복사하지 않았다. 실제 저장소 `develop-product/server/mods`·`develop-product/client/mods`의 5개 JAR 모두 main 빌드본과 해시 일치, 중복 Fabric ID·`.deploying` 잔여물 없음. JDK 21 `jar --validate`·ZIP CRC 재검사 통과. 백업·manifest는 실제 저장소 `build/mcc-gimmick-deployment/20261008-215627-main/`에 있다.
- **미확인:** 서버·게임이 꺼진 상태에서 확인했다. 서버 기동·실게임 검증은 하지 않았으며 `deploy-product` 릴리스도 아니다.

## [2026-10-08 21:48] PvP 배틀 기믹 비트플래그 API 개발 배포

- **구현:** `e8f428ea`에서 PvP 배틀 실행 요청을 공통 `BattleMechanicFlags`의 `mechanicFlags: Int`로 전환했다. 룸의 기존 기믹 선택 집합은 실행 경계에서 비트플래그로 변환한다. 코어와 함께 재빌드·배치했다.
- **테스트:** PvP 테스트 모음 통과. 이번 기록에서는 정확한 실행 개수를 확인하지 않았다.
- **빌드·무결성:** 0.1.0 JAR 생성, JDK 21 `jar --validate`와 ZIP CRC 검사 통과.
- **배치:** 실제 저장소 `C:/Users/박주형/Documents/GitHub/Cobblemon-Mods/develop-product/server/mods`와 `develop-product/client/mods`에 배치했다. 원본·서버·클라이언트 SHA-256 일치, 중복 Fabric ID와 `.deploying` 잔여물 없음 확인. 배치 전 서버·게임 프로세스 및 25565/25566 리스너가 없었다. 이전 JAR·manifest는 실제 저장소 `build/mcc-gimmick-deployment/20261008-214821/`에 보관했다.
- **미확인:** 서버 기동·실게임 검증은 하지 않았다. `deploy-product` 릴리스가 아니라 개발 배포다.

## [2026-10-08 01:05] 룸 오류 문장이 한 줄에서 잘리던 문제

- 원인: 룸 화면·목록의 오류 칸 높이가 10px(한 줄)로 고정돼, `MccHubKit.text`가 줄바꿈해도 둘째 줄부터 버려짐.
- 구현: PvP `PvpHubTab`에서 문장을 칸 너비로 나눈 줄 수만큼(최대 3줄) 높이를 잡음. MCC 본체(`MccHubKit`)는 손대지 않음.
- 빌드 성공, 테스트 통과. 서버·클라 모두 같은 JAR로 교체(이전 JAR은 `deployment-backups/20261008-pvp-feedback-wrap/`).
- 미확인: 실게임 화면 확인 안 함.

## [2026-10-08 00:50] 룸 시작 거절 메시지에 걸린 플레이어·사유 표시

- 사용자 요청: "진영 중 한쪽의 파티가 …" 메시지가 누구의 무슨 문제인지 알려 주지 않음 → 사람과 사유를 보여 주기로 함(긴 문장 줄바꿈은 이번에 안 함).
- 구현: `PvpRoomRejectedPayload`에 `messageArgs`(최대 4개 문자열) 추가. 서버는 왼쪽 자리부터 검사해 처음 걸린 플레이어 한 명과 사유 하나만 보냄(우선순위: 파티 수 → 같은 종족 → 같은 도구 → 그 밖). 옛 키 `room.error.team_invalid`는 `.team_size`/`.duplicate_species`/`.duplicate_held_item`/`.player`로 바꿈.
- 주의: 페이로드 형식이 바뀌어 서버·클라 PvP JAR 버전이 다르면 룸 거절 패킷을 읽다가 연결이 끊길 수 있음. 둘을 같이 배치할 것.
- 빌드: `:more-cobblemon-contents-pvp:build` 성공, 테스트 통과.
- 배치: 서버·클라 모두 교체(같은 JAR, 바이트 일치 확인). 이전 JAR은 `deployment-backups/20261008-pvp-team-invalid-reason/{server,client}/`.
- 미확인: 실게임 확인 안 함.

## [2026-10-07 23:55] 룸 입장 시 클라이언트 크래시 — 옛 UI kit 시그니처로 빌드된 JAR

- 증상: PvP 룸에 들어가면 클라이언트가 `NoSuchMethodError: CobblemonUiButton$Companion.create$default(..., UiWidgetState, Function0, int, Object)`로 튕김(`PvpHubTab.seat`, 크래시 `crash-2026-10-07_23.49.05-client.txt`).
- 원인: cobblemon-ui `CobblemonUiButton.create`에 `downSound: Boolean` 인자가 추가됐는데(6fc4fdb4), 22:39에 배치된 PvP JAR은 그 이전 시그니처로 컴파일돼 있었음. 소스는 이미 새 API 기준이라 코드 수정은 없음. 같은 시각 배치된 MCC·팩토리·타워·리그 JAR에는 옛 호출이 없음을 바이트코드로 확인.
- 조치: `:more-cobblemon-contents-pvp:clean build`로 다시 빌드(성공, 테스트 209개 통과) → 새 JAR에 새 시그니처 호출만 있음 확인 → `develop-product/client/mods`에 배치. 이전 JAR은 `develop-product/deployment-backups/20261007-2355-pvp-stale-ui-call`.
- 서버: 개발 서버를 끈 뒤 같은 JAR로 `develop-product/server/mods` 교체(이전 JAR은 백업 폴더의 `server/`). 서버는 다시 켜지 않음.
- 미확인: 실게임에서 룸 입장 재확인은 아직 안 함.
- 재발 방지: UI kit 시그니처를 바꾸면 이를 쓰는 애드온 JAR을 전부 다시 빌드해 함께 배치한다. 증분 빌드 결과를 믿지 말고 의심되면 `clean` 후 빌드.
