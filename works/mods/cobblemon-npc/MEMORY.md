# cobblemon-npc 작업 기록

## [2026-10-12 22:17] NPC 편집 화면 정리와 초안 편집 환경 개선 (구현·단위 검증·main 병합/푸시, 실화면 미확인)

- **사용자 요청·범위:** 배경이 선명하게 남고 설명·버튼이 겹치는 완드 설정/대화 편집 화면을 정리하고, 페이지·선택지·연결 대상을 쉽게 편집하도록 개선한다. 개발 중 발견한 개선점도 선조치 후보고하도록 승인했다. 대상은 `cobblemon-npc` 편집 화면이며 기존 플레이용 대화창과 JSON/패킷 형식은 유지한다.
- **배경·배치:** 공통 `NpcEditorScreen`이 직접 배경 블러·어두운 전체 덮개·불투명 작업 패널을 그린다. 라벨·힌트·버튼 문구를 확보된 너비에 맞추고 긴 힌트/버튼에는 툴팁을 둔다. NPC 설정 동작은 두 줄로 배치하고 삭제 확인을 별도 화면으로 분리했다. 선택형 InvMove 연동은 편집 화면의 이동을 `FORCE_DISABLE`, 배경 숨김을 `FORCE_SHOW`로 지정한다. 반사 API 등록 실패는 경고를 남긴다.
- **편집 구조:** 기본 페이지/선택지 탭과 고급 분기/명령/상세 설정 탭을 제공한다. 페이지별 입력칸 안의 Enter는 줄바꿈이며 페이지 추가/삭제로 대사를 나눈다. 선택지는 문구·대상·조건을 별도 조작하고 대상은 노드 ID와 대사로 검색한다. 조건의 복합 표현식용 원문 모드와 서버 명령 자동완성은 유지한다. 노드 목록에는 첫 페이지 일부와 시작 표시를 붙인다.
- **초안·연결 안전성:** `DialogueDraft`가 초안과 저장 확인 스냅샷을 구분하고 최대 100단계 Undo/Redo, Ctrl+S를 제공한다. 같은 입력 그룹의 연속 타이핑은 한 단계로 묶는다. 노드 삭제는 참조 수를 확인한 뒤 진행하며 들어오는 일반/선택지 연결을 대화 종료로 바꾸고 해당 노드로 가는 조건 분기는 제거한다. 시작 노드를 지우면 남은 첫 노드를 시작으로 정하며 마지막 노드는 삭제하지 않는다. 참조 정리 전체를 실행 취소할 수 있다.
- **저장·미리보기:** 저장 응답(ACK)을 받은 뒤에만 저장 완료로 표시한다. 미저장 닫기에는 저장/버리기/계속 편집 선택을 제공한다. NPC 설정에서 대화 편집 화면으로 이동하는 것만으로 NPC 메타데이터를 자동 저장하지 않는다. 서버는 권한 부족·NPC 누락/거리 초과·대화 파일 저장 I/O 실패를 오류 응답으로 돌려준다. `DialogueDraftPreviewScreen`/`DialoguePreviewCursor`는 저장하지 않은 초안을 로컬에서 보고 페이지·연결을 탐색하는 용도이며 명령 실행과 조건 판정을 하지 않는다. 조건에 따른 실제 노출·서버 실행 결과는 실게임 검증 영역이다.
- **문서·현지화:** `README.md`, `docs/DESCRIPTION_KO.md`, `docs/OPERATOR_GUIDE.md`에 편집 사용법과 초안 미리보기의 범위를 갱신하고 ko/en 문구를 추가했다. 작업 경과는 이 항목에서만 관리한다.
- **추가 보완:** 페이지/선택지의 기존 패킷 64개 한도를 `DialogueNode.MAX_ITEMS` 공유 상수와 편집/저장 검증에 연결하고 64개 허용·65개 거부 경계 테스트를 추가했다. 저장 응답 전에는 “변경 없음”, 실제 ACK 뒤에만 “저장됨”으로 표시한다. 서버 연결이 없거나 저장 패킷을 지원하지 않으면 NPC·대화 저장 요청을 보내지 않고 초안 보존 안내를 표시한다.
- **확인:** 현재 코드 diff와 새 초안/미리보기/InvMove 구현을 확인했다. `F:/AI/build/npc-editor-2edf/check2.log`의 24/24 성공 후, `final-check.log`에서 `:cobblemon-npc:unitTest` 25개 시작·25개 성공·0개 실패와 `BUILD SUCCESSFUL in 58s`를 확인했다. 마지막 UI 상태/연결 안내 보완 뒤 `final-check2.log`에서도 25/25 성공·0개 실패와 `BUILD SUCCESSFUL in 1m 9s`를 확인했다. Gradle 캐시·프로젝트 캐시·출력·임시 경로는 F 드라이브 전용이다. 단위 검증은 실제 화면 가독성·InvMove 런타임·서버 저장 왕복 동작을 증명하지 않는다.
- **검증 소스·현지화 대조:** 메인 작업자가 검증에 사용한 모듈 main/test 전체 파일과 현재 작업 소스의 해시 일치, ko/en 158개 키와 각 문구의 `%s` 개수 일치를 확인했다.
- **실화면 확인 중단·다음 확인 맥락:** F 전용 테스트 클라이언트를 실행했으나 신규 프로필의 첫 `AccessibilityOnboardingScreen`에서 편집 화면 캡처에 도달하지 못했다. 사용자가 첨부 이미지로 초기 화면을 설명하고 “다음에 부탁하면 이거 유의해서만 해. 내가 직접볼게”라고 하여 자동 UI 검증을 중단했다. 테스트 클라이언트는 종료했고 해당 `devlaunchinjector` 프로세스가 없음을 확인했다. 다음에 이 모듈을 신규 프로필로 확인할 때는 첫 접근성/언어/Continue 안내 화면을 먼저 확인한다. 실제 편집 화면·배치·InvMove 동작은 미확인이다.
- **구현 커밋·main 병합/푸시:** 구현 커밋 `52c15104d55a9070d59f9cfbb7120b91bd13d27c`, main 병합 커밋 `37013451892683db68ed0f14122cfb7d67aa0dfa`를 확인했다. `F:/AI/build/npc-editor-2edf/push-main.log`에는 `7dd77a79..37013451 main -> main` 성공이 기록돼 있으며 메인 작업자가 종료 코드 0을 확인했다. 메인 작업자가 primary의 `HEAD`·`origin/main`과 `git ls-remote origin refs/heads/main`을 교차 조회하여 세 결과 모두 위 병합 커밋과 같음을 확인했다. 이 MEMORY 기록 자체의 커밋·푸시는 메인 작업자가 별도로 처리한다.
- **배포·미확인 범위:** 배포 요청이 없어 개발/운영/VPS 설치본은 변경하지 않았다. JAR 배치·서버 기동·실게임 검증은 완료로 취급하지 않는다.

## [2026-10-12 04:50] 바닐라 완드 화면·스킨 탭/스크롤 빌드·개발 배포 (실게임 미확인)

- **컴파일 오류 1건(수정):** `SkinList` 안의 `selected`가 바닐라 `AbstractSelectionList`의 같은 이름 필드로 묶였다. `this@NpcSkinPickerScreen.selected`로 고쳐 `3072ccde`로 푸시했다. **바닐라 목록·위젯을 상속한 inner class에서는 화면 필드 이름이 부모 필드와 겹치는지 먼저 본다.**
- **빌드:** main `3072ccde`를 `F:/AI/build/npc-vanilla-20261012/src`에 풀어 `:cobblemon-npc:remapJar`(테스트 제외) 성공. 이번엔 캐시가 아니라 실제 컴파일했다. `jar --validate` 통과, `SkinList`·`SkinEntry`·`NpcPokemonModel` 클래스 포함 확인.
- **배치:** 게임·포트 꺼짐 확인 후 `develop-product/server/mods`·`client/mods` 교체. 세 곳 SHA-256 `e459efb7372f200e23aad2bce05e9b18c7c1250c5e471e0497b17c2841cb5d58`. 이전 JAR은 `develop-product/deployment-backups/20261012-045046-npc-vanilla-screens/`.
- **미확인:** 서버·클라이언트를 켜지 않아 화면은 보지 않았다.

## [2026-10-12] 스킨 선택 화면 스크롤바·사람/포켓몬 탭 (구현만, 빌드·배치 안 함)

- **사용자 요청:** "스킨 선택란에 스크롤바도 좀 만들어라", "포켓몬 모델이랑 스티브 모델 따로따로 볼 수 있게 탭도".
- **구현:** 버튼 줄 목록을 바닐라 `ObjectSelectionList`(언어·리소스팩 화면과 같은 목록)로 바꿔 스크롤바·휠·키보드 이동이 된다. 고른 줄은 노란 글자, 더블클릭(250ms)이면 바로 고른다. 위에 "사람/포켓몬" 탭 버튼(현재 탭 노랑)을 두고, 탭을 바꾸면 검색어를 비운다. 처음 열 때는 현재 스킨 종류의 탭으로 연다. 개수 표시는 현재 탭 기준.
- **미확인:** 컴파일·빌드, 화면 확인. 목록 선택이 `setFocused`→`setSelected`로 이어진다는 바닐라 동작에 기대고 있다(1.21.1 시그니처만 확인, 실제 동작 미확인).

## [2026-10-12] 완드 화면을 바닐라 UI로 (구현만, 빌드·배치 안 함)

- **사용자 요청:** "NPC완드 쓰면 나오는 GUI를 그냥 마크 기본 UI로 해줘... 너무 구림". 사용자는 그때 개발 클라이언트로 포켓몬 NPC를 확인 중이라 JAR을 교체하지 않았다.
- **구현:** 공통 기반 `NpcEditorScreen`에서 UI 키트 공유 테마·패널·버튼·라벨을 걷어내고 바닐라 `Button`(높이 20)·`EditBox`(20)·흰 제목·회색(`0xA0A0A0`) 라벨·초록/빨강 상태 줄로 바꿨다. 패널 자리에는 제목만 가운데에 그린다. 버튼 종류는 글자색으로만 남겼다(PRIMARY 노랑 = 고른 줄·주 동작, DANGER 빨강). 그래서 설정·대화 편집·스킨/대화 선택·Mod Menu 설정 화면이 한꺼번에 바뀐다. 스킨 미리보기는 틀 없는 모델과 이름만 그린다. 대화창(말하는 화면)은 그대로다.
- **미확인:** 컴파일·빌드, 화면 확인. 대화 편집 화면은 필드 높이가 18→20이 되어 조건·명령 줄(ROW 22)이 빽빽할 수 있다.

## [2026-10-12 04:26] 포켓몬 모델 NPC 빌드·개발 배포 (실게임 미확인)

- **빌드:** 사용자가 클라·서버를 끄고 "빌드해서 배포해"라고 지시. 공유 트리에 다른 세션의 미커밋 파일이 있어 main `311219d3`(이번 기능 `e9838b78` 포함, 이후 커밋은 jbro-policy만)을 `F:/AI/build/npc-pokemon-20261012/src`에 풀어 `:cobblemon-npc:remapJar`(테스트 제외, F 드라이브 전용 경로)로 빌드했다. 성공(2분 58초). Kotlin 컴파일은 빌드 캐시 적중이라 JAR에 `NpcPokemonModel`·`NpcPokemonForms`·`NpcSkinRef$Pokemon`·`NpcEntity.hitboxPending`이 들어 있음을 직접 확인했고, `jar --validate`·ZIP 검사도 통과했다.
- **배치:** 게임 프로세스·25565/25566 수신 없음을 확인한 뒤 `develop-product/server/mods`·`client/mods`의 `cobblemon-npc-0.1.0.jar`를 교체했다. 세 곳 SHA-256 `88b8d762887c31c8c3940dec76d2fdb1f97a84f28b236c7b75fd3cfa9f0bb972`. 이전 JAR은 `develop-product/deployment-backups/20261012-042633-npc-pokemon-model/`. PPakemon 프로필·운영·VPS는 건드리지 않았다.
- **미확인:** 서버·클라이언트를 켜지 않아 실게임 모델·히트박스·초상화는 확인하지 않았다. 파싱 JUnit도 돌리지 않았다.

## [2026-10-12] 포켓몬 모델 NPC (구현만, 빌드·테스트·배치·실게임 미실행)

- **사용자 요청:** NPC에 포켓몬 모델도 넣을 수 있게. 작업 중 "히트박스도 실제 포켓몬 모델로 됨?"이라고 물어 히트박스도 포켓몬 크기로 맞췄다.
- **형식:** 스킨 칸에 `pokemon:<종족>`(기본 `cobblemon` 네임스페이스), 띄어 쓴 특성(`pokemon:vulpix alolan shiny`), 다른 모드 종족은 `pokemon:<ns>:<종족>`. 해석이 안 되면 기존 텍스처 해석으로 넘어간다(`pokemon` 네임스페이스 텍스처 호환). NBT·패킷 형식은 그대로(`NpcSkin` 문자열).
- **구현:** `NpcPokemonModel`은 Cobblemon `VaryingModelRepository` 포저를 엔티티 없이 자체 `PosableState`(NPC `tickCount`로 시간 진행)로 그린다. Cobblemon `PosableEntityModel`처럼 1.5 올려 그리고, 크기는 `form.baseScale`. 히트박스는 `NpcEntity.getDefaultDimensions`에서 `form.hitbox × baseScale`(PokemonEntity와 같은 계산, effectiveScale 제외), 눈높이는 종족 `standingEyeHeight`(없으면 0.85). 스킨 변경 시 `refreshDimensions`, 클라이언트에 종족 데이터가 늦게 오면 20틱마다 다시 잰다. 대화창 얼굴 자리는 포켓몬 초상화(`CobblemonUiRenderSlot.drawContent`), 스킨 선택 화면은 구현된 전 종족을 도감순으로 넣고 한국어 이름 검색을 지원한다.
- **확인한 것:** 쓴 Cobblemon·MC API 시그니처를 Cobblemon 1.8.1 소스 JAR과 MC 1.21.1 매핑 JAR에서 대조했다. 파싱 JUnit을 `NpcSkinRefTest`에 추가했으나 **돌리지 않았다**.
- **미확인:** 컴파일·빌드(빌드는 요청 시에만), JAR 배치, 실제 모델 방향·높이·애니메이션, 큰 포켓몬 히트박스가 광장 블록에 끼는지, 12px 대화 얼굴 칸의 초상화 모양. 퀵(quirk) 애니메이션 효과·파티클은 엔티티가 없어 돌지 않을 수 있다(추정).

## [2026-10-11 13:06] 광장 상점 주인 NPC 추가·개발·운영·VPS 동기화 (VPS 기동 확인, 실게임 미확인)

- **사용자 결정:** 간호순에 이어 오박사 앞에 상점 주인을 둔다. 대화로 MCC 상점 탭을 연다. 스킨은 웹에서 찾았다. minecraftskins.com의 "ORAS Pokemart Male"(24143724)를 사용자가 골랐고, 허브 상점 탭의 상점 주인도 같은 스킨으로 바꿨다.
- **구현(main `bb014767`):** MCC `/mcc terminal shop [player]`(관리자 전용, `/mcc` 탭 구성을 상점 탭에서 연다), 스킨 `more_cobblemon_contents:textures/npcs/shopkeeper.png`(64×64, 일반 팔), 내장 `mcc-bp-shop.json` 상점 주인 스킨, 대화 `docs/dialogues/plaza_shopkeeper.json`(`@server /mcc terminal shop`). MCC JAR은 다른 세션이 `96a7c829`로 배포한 것에 이 변경이 들어 있다(설치본에서 스킨·명령 클래스 확인).
- **배치:** 서버를 켜지 않고 광장 엔티티 파일에 직접 썼다. VPS `r.-1.0.mca`(서버 정지 상태)의 청크 (-1,0)에 오박사 NBT를 복제해 상점 주인(UUID `6dc89162-211d-4ebd-a2d9-9e386ecc46ef`, `[-0.5, 80, 11.5]`, yaw 180)을 추가했다. 다른 청크와 기존 엔티티는 그대로임을 확인했다. 세 곳 광장은 NPC 고개 방향만 달라서, 같은 파일을 개발·운영에도 넣어 맞췄다. 개발 기존 파일 백업은 `F:/AI/build/shopkeeper-plaza/dev-backup/`.
- **설정:** 개발·운영·VPS의 `bp-shop.json`(세 곳 해시 같음)에서 상점 주인 스킨 한 줄만 바꿨다. VPS 매니저 업데이트는 VPS에 이미 있는 `config/`와 `world/` 파일을 보존하므로, 대화 파일만 업데이트(job `365349b2…`)로 넣었다. 상점 설정과 광장 파일은 서버 밖 `~/.local/share/ppakemon-backups/config-edit/20261011-shopkeeper/`에 백업한 뒤 직접 교체했다. 운영 커밋은 `54f9d763`이고, VPS 작업트리도 이 커밋과 같다.
- **VPS 기동(사용자 지시 "서버 켜놔"):** 13:05:35 `Done`, 25565 LISTEN, 피츄 봇 online, 상점 카탈로그 95개 로드. `data get entity`로 상점 주인 `NpcDialogue=plaza_shopkeeper`를 확인했다. 개발 서버는 켜지 않았다.
- **주의:** 공유 작업트리에서 `git commit -- <파일>`은 그 파일에 섞인 다른 세션의 미커밋 수정까지 커밋한다. `bb014767`에 허브 터미널 거리 제한이 딸려 들어가 `97bd098b`에서 빼냈다(작업트리는 보존). 이날 Git Bash의 ssh는 한글 홈 경로를 깨뜨려 known_hosts를 못 읽었고, Windows OpenSSH(`C:/Windows/System32/OpenSSH/ssh.exe`)로 접속했다.
- **미확인:** 실제 게임에서 상점 주인 대화, 상점 탭 열림, 스킨 표시는 확인하지 않았다.

## [2026-10-11 04:35] 오박사 대사 운영 반영, 기자 디스코드 링크 교체

- **오박사:** 사용자가 VPS에서 직접 고친 `plaza_oak.json`(SHA-256 앞자리 `a2715d44`)을 그대로 가져와 운영 저장소 `ccf20e7`에 커밋하고 개발 서버에도 넣었다(개발 저장소에는 이 대사의 원본이 없다).
- **기자:** `extra_reporter.json`의 디스코드 버튼 링크가 11-07 만료 초대(`HbKxTFeGB`)여서 만료 없는 `https://discord.gg/rcvmf3txDE`로 바꿨다. 개발 원본·개발 서버·로컬 운영(`5c81e61`)·VPS 네 곳이 같은 해시 `87c34083`이고, VPS는 콘솔 `npc reload`로 31개 대화를 다시 읽었다(재시작 없음). 실게임 버튼 클릭은 확인 안 함.

## [2026-10-11 02:53] VPS 광장 간호순 대화 누락 복구 (콘솔 명령, 실게임 미확인)

- **원인(직접 확인):** VPS `/srv/MinecraftPPakemonServer` 광장 `entities/r.0.0.mca`의 간호순(UUID `e9d88c69-170c-4ded-b0d6-3eacd7c0268c`, `[4.5, 80, 7.5]`)은 스킨만 있고 `NpcDialogue`가 빈 값이었다. 대화 JSON·NPC JAR 해시는 로컬 운영본과 같았다. 10-10 VPS 업데이트가 광장 엔티티 파일을 보존 대상으로 건너뛰어 운영본의 `plaza_nurse_joy` 값이 들어가지 않았다. **광장 NPC 값을 바꾸면 VPS에는 파일 업데이트로 반영되지 않는다.**
- **적용:** 사용자 승인 후 접속자 0명에서 tmux 콘솔로 광장 청크 `[0, 0]`을 잠깐 forceload(기존 forceload 없음 확인) → `data merge entity … {NpcDialogue:"plaza_nurse_joy"}` → `data get`으로 값 확인 → forceload 해제 → `save-all flush`. 저장 후 받은 mca NBT에서도 `plaza_nurse_joy`를 확인했다. 재시작은 하지 않았다.
- **미확인:** 실제 접속해 간호순과 대화·회복해 보는 확인은 안 했다.

## [2026-10-10 18:23] 간호순·광장·NPC JAR을 개발·운영에 적용 (빌드·파일 검증 완료)

- **사용자 승인:** 개발·운영 서버 모두 적용하고 운영 광장을 개발 광장에 백업 없이 덮어쓰도록 승인했다. 개발 클라이언트 NPC JAR 갱신도 별도로 승인했다. 아래 두 항목의 배치 보류 상태는 이번 적용으로 해소했다.
- **운영 간호순:** `C:/Users/박주형/Documents/GitHub/MinecraftPPakemonServer` 광장의 기존 간호순 `[4.5, 80, 7.5]`에서 `NpcSkin=more_cobblemon_contents_league_challenge:textures/npcs/wild/nurse_joy.png`, `NpcDialogue=plaza_nurse_joy`만 변경했다. UUID·위치·방향, 해당 청크의 나머지 NBT, 다른 청크 원시 데이터, 전체 엔티티 30개 보존을 비교 검증했다. 수정한 `entities/r.0.0.mca` SHA-256은 `bbdf34ccb2f2230c502bcb20d21f2ad13472d7e65e91e41a78cee77dbe18ab93`다.
- **광장·대화 적용:** 수정 대화 JSON을 양쪽 서버 `config/cobblemon_npc/dialogues`에 설치해 커밋된 main JSON(LF)과 양쪽 서버 파일의 바이트가 동일함을 확인했다. 운영 저장소의 `* -text` 설정에서 최초 복사본 CRLF가 `diff --check`에 검출되어, `git show`로 읽은 커밋 원본 바이트를 값 변경 없이 양쪽에 기록한 뒤 `git diff --cached --check`를 통과했다. 운영 광장 24개 파일을 개발 광장에 모두 덮어써 파일셋·전체 해시가 동일하다. 파일셋이 같아 삭제하지 않았고 승인대로 백업하지 않았다. 재귀 삭제 명령은 자동 승인 검토 정책에 거부되어 실행되지 않았으며, 파일별 `Copy-Item` 덮어쓰기로 완료했다. `world/data/jbro_policy_plaza_biome.dat`는 원래 양쪽 해시가 같아 변경하지 않았다.
- **빌드:** 커밋된 main `bc4e93ebbcbf395d620b9f2b981ed9bbdba4850b`의 NPC/UI/Battlecam 관련 소스·빌드 설정을 `F:/AI/Temp/codex-npc-6bb3-apply-20261010/source/works`에 추출했다. `GRADLE_USER_HOME=F:/AI/caches/gradle`와 작업별 F 드라이브 캐시·출력·임시 경로를 사용했다. 최종 `:cobblemon-npc:build`는 1분 13초에 성공했고 JUnit 16/16이 통과했다.
- **빌드 중 문제·해결:** 전체 모듈 소스 변환 지연으로 초기 시도를 중단했고, 공용 Loom 캐시 파일 잠금과 PowerShell의 인용 없는 `-Pkotlin.compiler.execution.strategy` 인자 분할로 후속 시도가 실패했다. 최종 실행은 문자열 인자 배열, CI/SystemProperty true, 작업별 `npc-scope.gradle` init으로 NPC/UI/Battlecam의 main 빌드 스크립트를 유지하고 다른 등록 모듈을 빈 buildfile로 비활성화했다. PATH의 구버전 `jar`가 Java 21 class version 65를 지원하지 못해 검증에 실패한 뒤 `C:/Program Files/Java/jdk-21/bin/jar.exe`로 명시하여 검증했다.
- **JAR 적용·확인:** 새 스킨·대화 picker 클래스 포함, 이전 RCT picker 부재, `jar --validate`·ZIP CRC 검증을 확인했다. 빌드본과 운영 서버·개발 서버·개발 클라이언트 설치본 3곳 SHA-256은 모두 `376154651008c55fbea518f922fa00d53d738eb12e01480649d9f745301c9f05`로 같다. 설치 전 게임 프로세스 및 25565·25566 수신이 없음을 확인했다.
- **운영 커밋·푸시:** 운영 저장소는 대화 JSON·NPC JAR·광장 `entities/r.0.0.mca` 3파일만 `fe476d7b5f0a878980b3f338382a0d8d2d00514c`로 커밋·푸시했다. `HEAD=origin/main=ls-remote main` 및 깨끗한 작업트리를 확인했다. 개발 저장소의 이번 기록 커밋·main 병합은 아직 진행 전이다.
- **미확인:** 서버·클라이언트 기동, 실제 화면, 파티 회복 실게임 검증은 하지 않았다.

## [2026-10-10 18:04] 운영 광장 간호순 확인과 장소 가정 없는 인사 수정 (대화 수정만)

- **대상 확인:** 사용자는 운영 서버 광장에 이미 있는 간호순을 바꾸라고 했다. `C:/Users/박주형/Documents/GitHub/MinecraftPPakemonServer/world/dimensions/jbro_policy/plaza/entities/r.0.0.mca`의 slot 0에서 `cobblemon_npc:npc`, 이름 `간호순`, 좌표 `[4.5, 80, 7.5]`, 빈 `NpcSkin`·`NpcDialogue`를 조회했다.
- **사용자 결정·수정:** 대화 흐름을 먼저 제시한 뒤 “일단 포켓몬센터가 아니야. 대화 수정해”라는 지시에 따라 `docs/dialogues/plaza_nurse_joy.json` 인사만 “안녕하세요! 포켓몬을 돌보는 간호순이에요.”로 바꿨다. 회복 선택지·명령·분기는 보존했고 `works/tools/npc/README.md`에서도 포켓몬센터라는 장소 가정을 삭제했다.
- **확인·남은 작업:** JSON 파싱, 인사 한 줄을 제외한 원본 JSON 구조 동일성, `diff --check`를 확인했다. 이번 단위에서는 빌드·실제 NPC 및 운영 설정 변경·서버 기동을 하지 않았다. 최신 지시는 대화 수정이며 실제 대화 배치·스킨 적용은 아직 미완료다.

## [2026-10-10 17:13] 스킨·대화 검색 목록과 간호순 설치 스크립트 (구현·생성 검증만)

- **구현:** RCT 전용 스킨 목록을 모든 활성 모드·팩의 trainer/npc/skin/player 폴더에 있는 64×64 또는 64×32 PNG 검색·미리보기로 확대했다. 이름 검색과 전체 텍스처 ID 툴팁을 제공하며, 대화 순환 버튼은 서버가 제공한 ID를 검색·스크롤하는 목록으로 교체했다. MCC 간호순 스킨 경로는 `more_cobblemon_contents_league_challenge:textures/npcs/wild/nurse_joy.png`와 같은 폴더의 `nurse_joy_2.png`다.
- **간호순:** `docs/dialogues/plaza_nurse_joy.json`은 무료 파티 회복 대화다. `cmd:healpokemon` 성공 때만 회복 완료를 표시하고 배틀 중 등 실패 때는 안내한다. `works/tools/npc/New-PlazaNurseBundle.ps1`은 필수 X/Y/Z와 OutputDirectory를 받아 설치용 대화·콘솔 명령·안내 파일만 생성하며 서버를 변경하거나 실행하지 않는다.
- **사용자 결정:** 개발 서버 지정이 잘못되었다고 정정한 뒤 “잠시만 기다려봐 일단 스크립트만 작성해바”라고 지시했다. 실제 배치 대상·좌표는 확정하지 않았다. 빌드·JUnit 실행·서버/클라이언트 배포·서버 기동·실제 NPC 배치·실게임 검증은 모두 보류했다. JUnit 소스는 추가했으나 실행하지 않았다.
- **확인:** Windows PowerShell 5.1로 스크립트 출력 생성을 확인했고 JSON·좌표·두 번째 스킨·중복 방지 조건·기존 출력 거부·제품 경로 거부 검증이 통과했다. `F:/AI/Temp/npc-nurse-6bb3-check`의 좌표 `(1.5, 80, -2.5)`는 생성 검증용이며 실제 배치 좌표가 아니다. 서버 변경·기동·배포는 수행하지 않았다.

## [2026-10-09 18:23] 등산가 덕배의 야생 레벨 설명을 사용자 체감 차이로 정리

- 사용자 최종 지시에 맞춰 `extra_hiker.json`도 산식·구역 수치 대신 장소마다 다른 강함·현재 캡에 따른 조절·성장 후 재방문을 안내하며, 대사를 3줄씩 정리했다.
- 최신 덕배 JSON에서 `lines`를 제외한 `speaker`·`start`·`nodes`·`choices`·`next` 구조가 HEAD와 같음을 확인해 이름·시작·선택지·분기 보존을 검증했다. 전체 대사 JSON 27개의 시작·다음 노드 참조 및 `diff --check`도 통과했다. 런타임 코드·설정값은 변경하지 않았으며 문구 수정이므로 별도 행동 테스트를 추가하거나 재실행하지 않았다.
- 개발·운영 배포, `/npc reload`, 서버·클라이언트 기동·실게임 확인은 수행하지 않았다.

## [2026-10-09] 대화 선택지를 한 창 안의 목록 행으로 (빌드·배치함)

- 사용자 요청: 선택지마다 흰 버튼이 있는 모양이 "구리다". MCC 허브 탭 목록처럼 둥근 흰 창 하나가 선택지를 감싸고, 고른 선택지만 둥근 커서 테두리로 표시한다. 목업으로 확인받고 진행(https://claude.ai/artifact/KAtAhATEQU6EAANdbYPmyX).
- 구현: `NpcDialogueScreen`이 버튼 위젯 대신 테마 패널(`theme.surfaces.panel`) 하나와 `CobblemonUiListRows`(허브 탭과 같은 행)로 직접 그린다. 커서는 마우스를 따라가고 ↑↓로 옮기며, 확인 키·클릭·숫자 키로 고른다. 글자 앞 번호("1.")는 뺐다(숫자 키는 유지). 테두리 색·모서리는 테마 값(DS 창은 `Outline(cursor, 2)`, 타워 로비 커서 `#F07820`)을 따른다.
- **확인:** `06348f02`를 `origin/main`에 푸시(병합할 원격 커밋 없었음). `:cobblemon-npc:remapJar -x test` 빌드 성공(컴파일 포함), JAR을 `develop-product/server/mods`·`client/mods`에 넣고 SHA-256 대조 일치(3fdd8f923ede). 이전 JAR은 `develop-product/deployment-backups/20261009-140910-npc-choice-window/`. 클라이언트·서버 둘 다 꺼진 채였고 켜지 않았다.
- **미확인:** 단위 테스트는 안 돌림(요청대로 제외). 게임 화면 확인도 안 함.

## [2026-10-09] 지팡이로 NPC 복사·붙여넣기 (빌드·배치함)

- 사용자 요청: 복사 기능, "세우는 것도 웅크리고 우클릭으로". 웅크리고 NPC 우클릭 → 이름·스킨·대화를 지팡이 아이템의 `custom_data`(`NpcCopy`)에 저장, 웅크리고 블록 우클릭 → 그 설정으로 배치(플레이어를 바라봄). 그냥 우클릭은 예전대로(블록=빈 NPC, NPC=설정 창). 복사한 것이 없으면 안내만 하고 배치하지 않는다. 툴팁에 복사한 NPC 이름이 나온다.
- 권한: 복사·배치 모두 `EDIT_PERMISSION`(2). 권한 없는 사람이 웅크리고 NPC를 누르면 설정 창 경로로 가서 "OP만" 안내를 받는다.
- **확인:** `:cobblemon-npc:unitTest` 14개 통과(컴파일 포함). main(`e3a54b30` 푸시 후, NPC·cobblemon-ui·battlecam에 영향 없는 다른 세션 커밋만 더 쌓인 상태)에서 빌드해 `develop-product/server/mods`·`client/mods`에 배치, 해시 a7902e5c3e80 일치. 이전 JAR은 `develop-product/deployment-backups/20261009-npc-wand-copy/`. 서버는 꺼진 채, 실게임 확인 안 함.

## [2026-10-09] 팩토리 단골 대사에서 "4마리" 삭제

- 팩토리는 싱글(`FactoryBattleFormat.SINGLE`, 3마리)만 남았는데 `extra_factory_regular`에 더블 시절 "3마리나 4마리"가 남아 있었다. "3마리"로 고쳤다. 원본(`docs/dialogues/`)과 개발 서버 사본(`develop-product/server/config/cobblemon_npc/dialogues/`)이 같은 내용인 것 확인. 서버에 반영되려면 대화 다시 불러오기나 재시작이 필요하고, 게임에서는 확인하지 않았다.

## [2026-10-08 18:00] NPC는 `/kill`로도 안 죽는다 (빌드·배치함)

- 사용자 결정: "킬로 못죽여야지. 부활도 못시키는데". `NpcEntity.isInvulnerableTo`가 공허 낙하(`fell_out_of_world`)만 받고 `/kill`(generic_kill)을 포함한 나머지 피해는 전부 막는다. 지우는 길은 지팡이의 제거 버튼(`NpcEditorService`, `remove(DISCARDED)`)뿐. 운영 안내도 고쳤다.
- 바로 아래 17:45 항목의 "광장에서 `/kill`이 안 먹는다"는 이제 의도된 동작이다(광장 밖에서도 안 먹음). 그 항목에서 띄운 "/kill로 지워지게 고치기" 별도 작업은 취소했다.
- **확인:** `:cobblemon-npc:unitTest` 14개 통과, 빌드 후 JAR을 `develop-product/server/mods`·`client/mods`에 넣고 해시 대조(3a812a2d720d). 이전 JAR은 `develop-product/deployment-backups/20261008-npc-no-kill/`. 서버는 꺼진 채로 둠, 실게임 확인 안 함.

## [2026-10-08 17:45] 광장 NPC 정리와 `/kill`이 안 먹는 문제

- 사용자 지시: 이름 없는 NPC 삭제, 타워 안내원 이름·스킨 수정. 타워 안내원 (18.5,74,-20.5)은 `data merge`로 이름 "타워 안내원", 스킨 `rct:gatekeeper_logan_0253`(리그 안내원과 같은 문지기 계열).
- **`/kill`로 광장 NPC가 안 지워진다:** "Killed NPC"가 찍히지만 체력 20 그대로 살아 있다(`Invulnerable:0b`로 바꿔도 같음). `NpcEntity.isInvulnerableTo`는 BYPASSES_INVULNERABILITY 피해를 받게 돼 있으니, 광장의 피해 차단(jbro-policy, "광장 안에서는 피해를 받지 않습니다")이 막는 것으로 보인다(추정, 코드 미확인). 운영 안내의 "`/kill` still removes one"은 광장에서는 틀린다.
- **이번 삭제 방법:** 서버를 끈 뒤 그 NPC만 있던 엔티티 청크 (0,0)의 슬롯을 `entities/r.0.0.mca` 헤더에서 비웠다. 원본은 `develop-product/deployment-backups/20261008-wild-npc-roles/plaza-entities/r.0.0.mca`. 다시 읽어 광장 NPC 30명, 이름 없는 NPC 없음 확인.
- **청크 로드 주의:** 플레이어가 없으면 광장 청크가 안 올라와 `execute in jbro_policy:plaza ... @e`가 "No entity was found"를 낸다. `forceload add`로 잠깐 올린 뒤 쓰고 `forceload remove`로 풀었다.
- 서버는 끈 상태로 둠(사용자 지시).

## [2026-10-08 17:25] 광장에 안내원·엑스트라 NPC 26명 배치 (개발 서버)

- **방법:** 사용자 지시("서버 켜서 확인까지", "광장 가서 트레이너 배치까지"). 서버가 꺼진 상태에서 `world/dimensions/jbro_policy/plaza`의 Anvil 파일을 직접 읽어 지형 지도를 만들고, 맨 위 블록이 땅(이끼·흙·안산암·응회암·금 간 석재 벽돌 등)이고 3×3이 평평하며 물·섬 끝에서 3칸 떨어진 칸만 골랐다. 서버를 이 세션에서 명령 파일을 stdin으로 붙여 띄운 뒤 `execute in jbro_policy:plaza run summon cobblemon_npc:npc ... {NpcSkin,NpcDialogue,CustomName,Rotation}`로 넣었다.
- **배치:** 리그 안내원 (12.5,74,-25.5)·팩토리 안내원 (22.5,74,-27.5)는 기존 타워 안내원 (18.5,74,-20.5) 옆. `extra_*` 24명은 섬 전체(스폰에서 10~46칸)에 고르게 흩고 스폰 쪽을 보게 했다. 스킨은 대사 직업에 맞는 RCT 스킨(교사·인테리어 디자이너는 맞는 직업이 없어 lady·madame).
- **확인:** `save-all` 뒤 엔티티 파일을 다시 읽어 31명(기존 5 + 새 26) 저장 확인. 실제로 말을 걸어 보는 확인은 못 함(서버가 온라인 모드라 개발 클라이언트가 못 들어감).
- **기존 NPC는 안 건드림:** 타워 안내원 이름이 "NPC"이고 이상한 스킨(`rct:dumbass_gian_0023`), (4.5,80,7.5)에 대화·스킨 없는 "NPC"가 하나 있다.

## [2026-10-08 17:20] NpcTalks·컴포넌트 대화 패킷 배치 (리그 야생 NPC 배치와 함께)

- **방법:** 다른 세션이 MCC에 커밋하지 않은 Better AI 변경이 있어, 커밋된 `HEAD`(5fe75981)의 `works`·`server-wiki`를 `build/deploy-head`로 `git archive`해 그곳에서 테스트·빌드했다(작업 트리 변경은 안 섞임).
- **테스트:** NPC 14, jbro-policy 87, 리그 93, 타워 193, 팩토리 111, MCC `-Pscope=core` 609 전부 통과. MCC의 `BattleContentCommandsTest` 1건은 `/mcc terminal` 관리자 전용 변경 때 갱신을 빠뜨린 검사라 고쳤다(5fe75981).
- **빌드:** 처음엔 remap이 동시에 돌며 Gradle 힙(2G)이 모자라 실패했다(실패한 JAR은 22바이트). `-Dorg.gradle.jvmargs=-Xmx4G --max-workers=2`로 성공.
- **배치:** NPC·jbro-policy·타워·팩토리·리그·MCC JAR 6개를 `develop-product/server/mods`와 `client/mods`에 넣고 SHA-256 대조 일치. 이전 JAR은 `develop-product/deployment-backups/20261008-wild-npc-roles/`. 서버는 꺼져 있었고 켜지 않았다. 실서버 기동·접속 확인은 안 함.

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
