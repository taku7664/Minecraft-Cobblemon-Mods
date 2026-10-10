# jbro-policy MEMORY

작업 내역과 이슈를 `[YYYY-MM-DD HH:MM]` 형식으로 기록한다. 최신 항목을 위에 추가한다.
구현, 빌드, JAR 배치, 서버 기동, 실게임 검증은 서로 다른 상태로 적는다.

---

## [2026-10-10 19:37] DEV 상태 채널 복구·서버 켜짐 알림 실제 전송 확인

- **DEV 비교·단일 필드 복구:** DEV의 기존 상태 채널은 `🟢┃서버-상태`였고 VPS `statusChannelId`는 공백이었다. 기존 피츄 봇 인증을 서버 내부에서만 사용한 Discord GET으로 동일 봇·채널 접근 HTTP 200을 확인한 뒤 VPS의 해당 필드만 DEV 값으로 복구했다. 그 외 모든 설정값은 변경 전후 동일함을 확인했으며 토큰·전체 설정 원문은 출력하지 않았다. 운영 비밀 설정은 Git에서 제외한다.
- **정상 종료·원본 백업:** 정상 저장·종료 후 설정을 반영했고 원본은 서버 밖 `/home/ubuntu/.local/share/ppakemon-backups/discord-config/20261010-193414-ad700f2ba20d435f8b0d3b2b67782578.json`에 권한 0600으로 백업했다.
- **기동·실제 전송:** 새 Java PID `21810`, 19:35:31 fresh `Done (10.524s)`와 게임 포트 기동, 19:35:33 피츄 봇 online·슬래시 7개 등록을 확인했다. Discord 메시지 GET HTTP 200에서 새 프로세스 시작 이후 피츄 봇의 `🟢 서버가 열렸어요!` 메시지(19:35:32 KST)를 확인해 실제 전송을 검증했다. [확인한 알림](https://discord.com/channels/1555187390962860162/1555215001361317938/1558427761826332785)
- **교훈·미확인:** 기존 DEV 설정을 먼저 대조할 수 있었으므로 채널 ID부터 사용자에게 질문한 것은 불필요했다. 일반 운영 업데이트는 공백 설정도 보존하므로 DEV 기존 채널의 누락 여부를 별도로 비교해야 한다. VPS 필드가 공백이 된 경위는 확인하지 못했다. 닫힘 알림을 위한 추가 재기동은 하지 않아 닫힘 메시지 전송은 미검증이다. 위 결과는 메인 작업자의 직접 검증 보고이며 기록 담당자는 재실행하지 않았다.

## [2026-10-10 19:18] 운영 서버 재기동·디스코드 상태 알림 목적지 미설정 확인

- **정상 재기동:** 빡대리가 디스코드 서버 켜짐 알림이 보이지 않아 재기동을 요청했다. VPS의 Minecraft만 `save-all flush` → `stop`으로 종료해 `All dimensions are saved`·기존 PID 종료·TCP 25565 포트 닫힘을 확인했다. 운영체제는 재부팅하지 않았다.
- **기동 확인:** 기존 PID `16097`에서 새 PID `20874`로 기동했다. 새 `Done` 로그 19:16:50과 TCP 25565 LISTEN, 19:16:51 피츄 봇 online·슬래시 명령 7개 등록을 확인했다(메인 작업자 직접 검증 보고).
- **알림 원인:** 비밀값을 출력하지 않고 `config/jbro-policy-discord.json`의 `botConfigured=true`, `statusChannelConfigured=false`(빈 `statusChannelId`), `newsChannelConfigured=false`를 확인했다. 메인 작업자가 `DiscordBot.kt`의 `register`에서 상태 채널 ID가 비면 상태 객체 생성을 생략하고 `showStatus`가 반환함을 확인해 상태 알림 목적지 미지정을 판별했다.
- **미완료·범위:** 운영 설정 수정이나 기타 파일 배포는 하지 않았다. 알림 채널 이름/ID를 사용자에게 질문했고 답변 대기 중이며 실제 켜짐·꺼짐 메시지 전송 성공은 확인하지 않았다. 기록 담당자는 SSH·기동·소스 검증을 재실행하지 않았다.

## [2026-10-10 17:42] 디스코드 인증 설정 적용 확인 (봇·명령·권한 확인, 실제 인증 교환 미검증)

- **현재 적용:** 메인 작업자의 직접 확인 기준으로 VPS `/srv/MinecraftPPakemonServer/config/jbro-policy-discord.json`은 유효하며 수정 시각은 17:20:44, 현재 Java PID `14132`의 시작 시각은 17:24:46이다. 교체한 설정은 현재 실행에 적용돼 있으며 재시작이 필요하지 않다. 기록 담당자는 추가 조회를 하지 않았다.
- **봇·설정:** 17:25:51 로그에서 피츄 봇 online과 slash commands 7개 등록을 확인했다. 인증 역할 설정이 존재하고 `syncNickname`은 true다. 비밀값·역할 ID·원문 설정은 기록하지 않는다.
- **Discord 읽기 확인:** 읽기 전용 GET API로 빡켓몬 서버의 `/verify`와 필수 `code` 옵션 등록을 직접 확인했다. `인증됨` 역할이 존재하며 managed false·봇 역할보다 아래이고, 봇의 `MANAGE_ROLES`·`MANAGE_NICKNAMES` 권한도 true임을 확인했다(메인 작업자 보고).
- **미검증:** 실제 플레이어의 `/디코인증` → `/verify` 코드 교환, 인증 역할 부여, 닉네임 변경은 실행하지 않았다. 설정·명령 등록·권한 확인을 실제 인증 성공으로 해석하지 않는다.

## [2026-10-10 17:09] `/servercmd` 콘솔 명령 실행 추가 (구현·main·빌드·운영 배포·기동·외부 응답 확인, 채팅 실사용 미확인)

- **사용자 결정·범위:** 빡대리가 `/servercmd` 구현·테스트·운영 서버 배포를 승인했다. 개발 서버·클라이언트는 변경하지 않는 결정이며, 구현·기록은 분리 작업트리 `C:/Users/박주형/.codex/worktrees/servercmd/Cobblemon-Mods`의 `jbro-policy`에서 진행했다.
- **구현 확인:** 기록 담당자가 `admin/ConsoleCommandQueue.kt`·`admin/ServerCommand.kt`와 `JbroPolicy.kt` 등록을 직접 읽었다. 현재 실제 OP 권한 4인 플레이어만 접수하며, 실행 틱에서도 접속·권한을 다시 확인한다. 앞의 `/` 한 개는 선택 사항이고 입력은 2,048자 이하·제어 문자 금지다. 대기열 최대 32개, 틱당 최대 4개를 처리하고 서버 종료 시 대기열을 지운다.
- **출력·제약:** 기존 명령 실행 문맥 밖의 서버 틱에서 콘솔 권한으로 실행하며, 실행 중 중첩 `/servercmd` 접수를 거절하는 상태 검사를 둬 `execute`·`function` 경로도 대비했다. 결과·오류는 요청자의 채팅으로 최대 32개 메시지·4,096자까지 캡처한다. 접수 명령의 반환값과 실제 실행 결과는 구분한다. 셸 실행이나 서버 정지 후 재시작은 제공하지 않는다. 명령 인자를 제외하고 호출자·최상위 명령만 정상 실행 로그에 남긴다.
- **문서·현지화 확인:** README 사용법과 한국어·영어 `message.jbro_policy.servercmd.*` 11개씩을 확인하고 양쪽 JSON 파싱을 통과했다. 안내는 `KoreanText.message`의 `translatableWithFallback`을 사용해 새 번역 키가 없는 기존 클라이언트도 문구를 받도록 구현했다. 실제 클라이언트 표시와 명령 연쇄 동작은 아직 검증하지 않았다.
- **TDD·단위 테스트:** 메인 작업자 보고 기준으로 첫 실행은 기존 93개 통과·신규 8개 TODO 실패(총 101개)였다. 구현 후 두 번째 `:jbro-policy:unitTest`는 종료 코드 0·`BUILD SUCCESSFUL`, 101개 전부 통과(기존 93+신규 8)를 확인했다. 기록 담당자는 테스트를 재실행하지 않았다.
- **main·최종 빌드:** 구현 `8e19041b`를 main 병합 커밋 `725dfff042162fe70beba71f7c4fec5693f3bc41`에 반영하고 원격 실제 ref 일치·0/0을 확인했다. 해당 커밋의 분리 소스에서 `:jbro-policy:build`가 종료 코드 0·`BUILD SUCCESSFUL`(5분 18초), 테스트 101개 모두 통과했다. 이후 `main`이 `f8994446`으로 전진했지만 관련 모듈 소스·리소스·빌드 설정·의존 소스의 차이가 0임을 재확인해 같은 빌드본을 사용했다(메인 작업자 보고).
- **빌드 경로·산출물:** 초기 red/green은 C: Gradle 쓰기 금지 규칙이 없던 `0a404f26` 작업트리에서 실행했다. 최신 `b7660a42` 규칙 확인 후 최종 빌드의 캐시·모듈 출력·`TEMP`/`TMP`·JVM tmp는 `F:/AI/build/servercmd-20261010` 아래로, `GRADLE_USER_HOME`은 `F:/AI/caches/gradle`로 지정했다. 산출물은 `F:/AI/build/servercmd-20261010/modules/jbro-policy/libs/jbro-policy-0.1.3.jar`, SHA-256은 `0DDDC4ECBCC74F7A4A9E106A8CB7DAE4B90D26A80150CE25E95F25D554793056`이다. Java 21 `jar --validate` 종료 코드 0과 두 신규 클래스 포함을 확인했다.
- **검증 도구 보완:** 첫 최종 빌드 명령은 PowerShell에서 따옴표 없는 `-Pkotlin.compiler.execution.strategy` 옵션이 분리돼 task not found로 실패했다. 전체 옵션 문자열을 따옴표로 감싼 뒤 성공했다. PATH의 구 JDK `jar`는 major 65를 지원하지 않아 실패했고, `C:/Program Files/Java/jdk-21/bin/jar.exe`를 명시해 검증했다. 두 실패는 호출 옵션·검증 JDK 문제였다.
- **로컬 운영 배포:** `C:/Users/박주형/Documents/GitHub/MinecraftPPakemonServer`의 `mods/jbro-policy-0.1.3.jar`만 교체하고 커밋 `d4e1d01e49329307063bc6b9ca4b4ffc92cc60b9`을 푸시해 원격 0/0을 확인했다. 별도 미커밋 `startup-hooks.json`은 보존했다.
- **VPS 정상 종료·배포·보존:** `/srv/MinecraftPPakemonServer`에서 `save-all flush`의 `Saved game`, `stop`의 모든 차원 `All saved`, 기존 Java PID `10378` 종료와 TCP 25565 포트 닫힘을 확인했다. `/home/ubuntu/.local/share/ppakemon-backups/servercmd-20261010-172444/world-snapshot.tar.gz`와 이전 JAR를 백업했다. fetch 후 들어오는 변경이 policy JAR 1개임을 확인해 fast-forward 병합했다. JAR 교체 전후 config·world·player·plaza·루트 실행 설정 1,224개 파일의 SHA가 같았고 VPS 새 JAR는 위 빌드본 SHA-256과 일치했다.
- **VPS 기동·외부 응답:** 기존 `tmux ppakemon`의 `remain-on-exit`을 이용해 같은 pane을 respawn하고 콘솔을 유지했다. 새 Java PID `14132`, `[17:25:49] Done (12.780s)!`, TCP 25565·UDP 24454 리스닝과 Windows 외부 Minecraft status 응답(version `1.21.1`, protocol `767`) 성공을 확인했다.
- **최종 범위·미확인:** 구현·main 동기화·최종 빌드·로컬 운영/VPS JAR 배치·서버 기동·외부 상태 응답까지 확인했다. 17:28:11 VPS `list`는 접속자 0명으로 실제 플레이어의 `/servercmd` 실행·채팅 결과 표시·실게임은 미확인이다. 개발 서버·클라이언트와 `deploy-product` 릴리스 산출물은 변경하지 않았다. 이번 관리 작업트리는 archive 후 실제 경로 삭제·Git worktree 등록 제거를 확인했고 F 빌드 JAR는 유지하며 해시가 같았다. 빌드·배포·기동·보존·정리 결과는 메인 작업자의 직접 검증 보고이며 기록 담당자는 재실행하지 않았다.

## [2026-10-10 16:47] 사탕 제작 잠금 해제·경험사탕 XS 결과 3개 (구현·리소스 확인, 빌드·배포 미실행)

- **사용자 결정:** 사탕 제작을 잠금 해제하고 스컬크 1개와 벌집 조각 1개로 경험사탕 XS 3개를 만든다. 기존 월드의 내장팩 선택을 유지하기 위해 legacy 내장팩 ID `no_stat_candy_l_xl`는 바꾸지 않는다.
- **구현:** 냄비 XS 레시피와 Create 선택 연동 XS 레시피의 결과만 3개로 오버라이드한다. 나머지 기존 차단 레시피 8파일은 삭제해 Cobblemon 기본 레시피로 복원하며, Create 레시피의 기존 모드 로드 조건은 유지한다. 별도 월드 데이터팩 변경은 이번 작업에 포함하지 않는다.
- **검증:** 메인 작업자 보고 기준으로 두 XS 리소스의 결과 3개와 `git diff --check`를 확인했다. `:jbro-policy:test --tests *PolicyDataTest`는 프로젝트 구성 중 `:better-battle-presentation` 단계의 디스크 공간 부족으로 실패했으며 Kotlin 테스트는 실행되지 않았다. 기록 담당자는 냄비·Create XS JSON의 `count: 3`과 Create 조건 존재만 별도로 확인했다.
- **F 재검증:** 기존 `test` 태스크가 비활성이라 실제 검증 태스크 `unitTest`를 F 드라이브에서 실행했으나, 약 11분 준비 후에도 MCC `compileKotlin` 단계가 계속되어 중단했다. Kotlin 테스트 결과는 미확인이며 통과한 것으로 기록하지 않는다. 검증용으로 시작한 Java 프로세스 3개만 종료했다(메인 작업자 보고).
- **사용자 결정·규칙:** C 드라이브의 Gradle 캐시·데몬 로그·프로젝트 캐시·빌드 출력·임시파일 쓰기를 금지하고 모든 쓰기 경로를 F로 지정·사전 확인하는 필수 규칙을 `AGENTS.md`에 추가했으며 `CLAUDE.md`에 참조를 남겼다.
- **커밋·남은 상태:** 소스는 main 병합 커밋 `582c60d39152cbc25076b9b8860fb3a662da5781`에 반영됐고 `main`·`origin/main`·원격 실제 ref 일치를 확인했다(메인 작업자 보고). JAR 빌드·배치, 서버 기동, 실게임 제작 검증은 수행하지 않았다. 이 후속 기록의 커밋·푸시는 메인 작업자가 별도로 담당한다.

## [2026-10-09 15:21] 마이룸·광장 침대 사용과 침대 스폰 지정 차단 (구현만, 빌드·배치 안 함)

- **사용자 결정:** 각 모드에 따로 차단 코드를 넣지 않고 `jbro-policy` 정책으로 `myroom:rooms`와 `jbro_policy:plaza`에서 침대 사용을 막는다. `simple-myroom`은 수정하지 않는다.
- **구현:** `PlazaProtection.kt`의 `UseBlockCallback`에서 두 차원의 `BedBlock` 우클릭에 `FAIL`을 반환해 수면·침대 스폰 지정·침대 폭발 처리를 막는다. OP도 예외가 없으며 다른 차원은 `PASS`로 기존 처리에 맡긴다. 한국어·영어 안내 문구와 README를 추가했다.
- **정적 확인:** 설치된 `simple-myroom-1.0.0.jar`의 `data/myroom/dimension/rooms.json` 존재와 Fabric API 소스의 콜백 실행 순서(블록 처리 전에 호출, `FAIL`이면 처리 중단)를 메인 작업자가 확인했다. 양쪽 언어 JSON 파싱과 `git diff --check`도 통과했다(메인 작업자 보고).
- **검증 범위:** 기존의 “빌드는 지시할 때만” 사용자 결정에 따라 빌드·테스트·JAR 배치·서버 기동·실게임 검증은 실행하지 않았다. 이미 저장된 스폰을 지우거나 명령어를 통한 스폰 변경까지 막는 기능은 이번 요청에 포함하지 않았다.

## [2026-10-09 14:29] 디스코드 `/피츄 질문` 추가 (구현·빌드·AI 호출·서버 배치 확인, 기동·디스코드 실사용 미확인)

- **동작:** 필수 옵션 `질문`은 1~500자이며 답변은 공개로 보낸다. `anyChannel = true`로 일반 채팅방에서도 사용할 수 있다. Discord user ID별로 채널을 공유하는 5분 쿨다운을 두고, 같은 유저가 답변을 기다리는 중에는 중복 질문을 막는다. AI 실패·빈 답변이면 쿨다운을 해제한다.
- **AI 실행:** 별도 worker에서 최대 2개 질문을 동시에 처리한다. 기존 `inquiry-review`의 `enabled`·`command`·`model`·`timeoutSeconds` 설정을 재사용하되 질문 응답 제한시간은 최대 120초다. agy 전용 `jbro-pichu-chat` 프로필은 `inheritCustomizations: false`, `excludeDefaultComponents: true`, `tools: [finish]`로 제한한다. 질문만 전달하고 서버 로그·유저 기록은 제공하지 않는다.
- **구현 위치:** `DiscordQuestion.kt`, `PichuQuestionAI.kt`, `pichu-chat-agent.md`. Discord 비동기 응답 처리와 한국어 안내 문구를 연결했다.
- **커밋·최종 빌드:** 구현 커밋 `460749ca`를 main에 병합한 `9ea58ba23417eede00885a1ea4429c9a6b3d7997`과 `origin/main`의 같은 해시를 확인했다. 깨끗한 분리 작업트리에 이 병합 커밋을 fast-forward 반영한 소스로 `:jbro-policy:build`를 실행해 단위 테스트 93개 모두 성공, `BUILD SUCCESSFUL`(30초)을 확인했다. `jar --validate` 종료 코드 0과 새 클래스·제한 프로필 포함도 확인했다(메인 작업자 보고).
- **AI 실호출:** Java 검증용 harness에서 실제 `PichuQuestionAI.ask`를 기존 로그인 agy의 `gemini-3.8-flash-low`로 호출해 `SUCCESS`, 비어 있지 않은 피츄 설명 답변, `AI_SMOKE_OK`, 종료 코드 0을 확인했다(메인 작업자 보고). 디스코드 slash 경로를 거친 검증은 아니다.
- **프로필 확인:** `.gemini/config/agents/jbro-pichu-chat/agent.md` 설치와 CLI agent 검색을 확인했다. 초기 flat `agent.md` 경로는 검색되지 않아 폴더 안 `agent.md` 경로로 고쳤다. 로컬 CLI 내장 changelog와 protobuf·YAML 설정 태그에서 `excludeDefaultComponents` 지원을 확인했다. 무해한 sentinel 파일을 읽으라는 질문에는 파일 접근 불가 답변을 받았고 도구 호출은 없었다. CLI `init.tools`는 기본 도구 전체를 표시하므로 제한 프로필의 실제 도구 목록을 증명하는 근거로 쓰지 않는다.
- **채널 권한 확인:** Discord API 읽기 조회에서 `💬┃잡담`(`1555187393236041820`)의 `@everyone`에 `SEND_MESSAGES`·`USE_APPLICATION_COMMANDS`가 허용되고 관련 channel overwrite 거부가 없음을 확인했다. 권한은 변경하지 않았다.
- **검증 도구 보정:** Gradle init task 등록 시점 조정과 Java 인자 CP949 보정은 검증용 scratch에서만 했으며 제품 코드 수정과 무관하다.
- **개발 서버 배치:** 배치 직전 `java`·`javaw` Fabric 서버 프로세스와 25565·25566 수신이 모두 없음을 재확인한 뒤, 실제 primary 저장소의 `develop-product/server/mods/jbro-policy-0.1.3.jar`를 빌드본으로 교체했다. 빌드본·설치본 SHA-256은 모두 `38DD83FA8C39A4956AE7EF2A3722A5798CF26354D210EAB03F9A27391464BA4E`로 일치했다(메인 작업자 보고). 서버 디스코드 기능 범위이므로 개발 클라이언트와 release 산출물은 변경하지 않았다.
- **미확인:** 서버는 기동하지 않았다. 다음 서버 기동 시 slash 명령이 자동 등록되며, 실제 등록·디스코드 호출은 아직 검증하지 않았다.

## [2026-10-08 18:51] 커스텀 레시피(화약·네더의 별) 제거 (구현만, 빌드 안 함)

- **사용자 결정:** 커스텀 레시피는 없앤다. `data/jbro_policy/recipe/`의 `gunpowder_from_coal_and_flint.json`, `nether_star.json`을 지우고 README·위키(`systems.html`, `nav.js`)의 언급도 뺐다.
- **영향:** 바닐라 몹이 나오지 않는 서버라 네더의 별은 그란돈 드롭 말고는 얻을 길이 없다. 특성패치 레시피는 원래 `no_ability_patch` 팩이 막고 있어 BP 상점 전용 그대로다. 화약은 찌리리공·붐볼 드롭으로 얻는다.
- **미확인:** 빌드·배치 안 함(사용자가 배포는 아직이라고 함). 하드 챔피언 배지 아이콘의 네더의 별 텍스처는 레시피와 무관해 그대로 둔다.

## [2026-10-08 13:40] `WildPokemonPolicy.applyWildRolls` 공개 (구현만, 빌드 안 함)

- 리그 모드의 야생 교환꾼이 만든 포켓몬에 야생 개체값 구간과 숨겨진 특성 확률을 걸 수 있게 `@JvmStatic applyWildRolls(pokemon)`을 열었다. 리그는 리플렉션으로 부르므로 **이름과 시그니처를 바꾸지 않는다.** 확률값은 이 모드 설정에만 둔다.

## [2026-10-08 09:20] `/디코인증 linked` 판정 명령 추가 (구현·커밋만, 빌드·배치 안 함)

- **이유:** NPC 대화가 디스코드 연동 여부로 갈라질 방법이 없었다(cobblemon-npc `MEMORY.md`의 기자 NPC).
- **동작:** `/디코인증 linked`, `/discordverify linked`. 연동했으면 1, 아니면 0. 권한 2가 필요해 플레이어에게는 안 보이고, 대화 조건(`cmd:`)은 권한 2로 돌아서 쓸 수 있다. 디스코드 봇 설정이 있을 때만 등록된다.
- **미확인:** 사용자가 빌드는 지시할 때만 하라고 해서 빌드·테스트·배치하지 않았다.

## [2026-10-08 01:30] 전설 볼 거절 수정이 0.1.3 배포에 덮였던 일, 합쳐서 재배포

- **빡대리님 신고:** 수정 뒤에도 전투 중 아르세우스에 마스터볼을 던지면 그대로 멈춘다.
- **원인:** 00:25 Codex 세션이 main에 합치지 않은 `codex/alpha-two-perfect-ivs`(main의 c31bb117 이전 갈래)로 `jbro-policy-0.1.3`을 빌드해 서버·클라이언트에 넣으면서 0.1.2(전설 볼 수정 포함)를 뺐다. 0.1.3에는 `EmptyPokeBallLegendMixin`이 없었다.
- **조치:** 그 브랜치를 main에 병합(24498c5f, MEMORY 충돌만 손으로 해소)하고 0.1.3을 다시 빌드했다(테스트 87개 통과, 믹스인 포함 확인). 서버·게임이 꺼진 상태에서 두 곳에 교체(SHA-256 앞자리 872d7d04), 백업 `develop-product/deployment-backups/20261008-ball-music-trainer-fixes`. 서버는 켜지 않았다. 실게임 미확인.
- **재발 방지:** 다른 갈래에서 빌드한 JAR을 배포하기 전에는 main을 먼저 합친다(MEMORY의 "배포 전 main 병합" 규칙).

## [2026-10-08 00:34] 새 알파 포켓몬의 기본 IV 최소 2개를 31로 보장

- **구현:** 커밋 `71c921ef`, `jbro-policy` 0.1.3. 새로 스폰한 알파는 일반 야생 IV 추첨 뒤 기존 31 개수를 세어, 서로 다른 능력치의 기본 IV가 최소 2개는 31이 되도록 부족한 개수만 올린다. 기존 31이 3개 이상이면 유지하고 나머지 값도 보존한다. 야생 IV 구간 추첨을 꺼도 적용하며, 기존 포켓몬을 일괄 보정하지 않는다.
- **빌드:** 전체 빌드, 테스트 87개, JAR 검증 통과. 빌드 중 C: 공간이 소진되어 실패한 빌드를 멈추고 이 작업트리의 `works/.gradle/loom-cache`만 `F:/AI/Temp/codex-ac66-loom-cache-20261008`로 이동한 뒤 원래 경로에 junction을 두어 약 1GB를 확보했다. 소스·실행 폴더는 이동하지 않았다. `'-Dfabric.loom.ci=true'`는 의존성 소스 준비만 생략했다.
- **개발 배포:** 실제 저장소 `C:/Users/박주형/Documents/GitHub/Cobblemon-Mods`의 `develop-product/server/mods`와 `develop-product/client/mods`에 0.1.3을 설치하고 0.1.2를 제거했다. 양쪽 SHA-256은 `D44EE3F15C92D0A71D0A32210C9A6CBD3C601F30131101557C2175F46584F58A`로 일치한다.
- **서버 기동 확인:** 빡대리님이 정상 종료·교체·재시작을 승인했다. 00:30:04 모든 차원 저장과 이전 프로세스·25566 포트 종료를 확인하고 교체했다. 숨김 `cmd`에서 `run.bat`으로 재시작한 뒤 Java PID 39360의 25566 수신과 `latest.log`의 `jbro_policy` 0.1.3, 00:33:32 `Done (2.040s)`를 확인했다.
- **후속 종료·최종 상태:** 빡대리님의 서버 끄기 지시로 PID 39360은 00:37 모든 차원 저장 후 정상 종료했다. 다른 런처(PID 41084)의 새 Java PID 44172에도 `stop`을 보냈으나, 00:40:19 `Saving worlds` 직후 `ConcurrentModificationException`과 `Exception stopping the server`가 나와 두 번째 종료의 월드 저장 완료는 확인하지 못했다. 최종 `Win32_Process` 조회에서 모든 Fabric 서버 Java와 런처 PID 5592·41084, Java PID 44172가 없고 25566 수신도 없어 서버 꺼짐을 확인했다. 종료 오류 원인과 수정은 검증하지 않았다.
- **남은 검증:** 실제 알파 포획과 IV 확인은 하지 않았다. 소스 커밋은 `codex/alpha-two-perfect-ivs`에 push했고 원격 동기화를 확인했다.

## [2026-10-07 23:55] 전투 중 전설 포획 거절 시 전투가 멈추던 문제 (빌드·테스트 확인, 서버 배치·실게임 미확인)

- **빡대리님 신고:** 이미 잡은 아르세우스와 전투 중 마스터볼을 던지면 "던졌습니다" 로그만 남고 아무것도 못 한다. HUD 카드가 코블몬 기본 타일(볼 아이콘)로 풀린다.
- **원인:** 코블몬 1.8.1 `EmptyPokeBallEntity.onHitEntity`는 전투 안에서 `BattleCaptureAction`을 걸고, 던짐을 알리고, 던진 사람 턴을 넘긴 *다음에* `THROWN_POKEBALL_HIT`을 부른다. 우리가 그 이벤트를 취소하면 볼만 `drop()`되고 `captureFuture`가 끝나지 않아 전투가 영원히 기다렸다. HUD가 기본 타일로 바뀐 것은 cobblemon-ui가 볼 표시 중에는 일부러 코블몬 타일을 쓰기 때문(`BattleOverlayHudMixin`)이고, 볼 상태가 안 풀려 그대로 남은 것.
- **수정:** 거절 검사를 `EmptyPokeBallLegendMixin`(`onHitEntity` HEAD)으로 옮기고 `LegendPolicy.refuseCapture`로 판정한다. 거절되면 이유를 알리고 볼을 돌려준 뒤 코블몬 처리를 건너뛴다(턴도 안 넘어감). 야생이 아닌 포켓몬은 코블몬 메시지에 맡긴다. `THROWN_POKEBALL_HIT` 구독은 지웠다.
- **검증:** `:jbro-policy:build` 테스트 85개 통과, JAR 안에서 `onHitEntity`가 `method_7454`로 매핑된 것 확인. 커밋 c31bb117. 클라이언트에 배치(SHA-256 앞자리 a4c62ca6, 백업 `develop-product/deployment-backups/20261007-legend-ball-battle-hang/`). 서버는 빡대리님이 끈 뒤 같은 JAR로 교체(백업 같은 폴더 `server/`), 다시 켜지는 않았다. 실게임 미확인.

## [2026-10-07 13:00] 등급이 모자란 전설은 스폰·포켓내비 목록에서 뺌

- **빡대리님 결정:** 리그 등급으로 못 잡는 전설은 포켓내비 목록에도 안 뜨게 한다(엔트리 조건과 같은 취급). 전에는 등급이 포획만 막아서, 스폰되고 서버 알림까지 나간 뒤 볼이 거부됐다.
- **구현:** `LegendPolicy.mayMeet`에 `LegendRanks.check(...) == Allowed` 조건 추가. 플레이어 스포너, 포켓내비 스폰표, 포케스낵 후보가 모두 이 함수를 지난다. 등급을 못 읽으면(`Unknown`) 스폰도 막는다. 포획 때 검사는 스폰 뒤 등급 변화 대비로 남겼다. `/spawnpokemonfor`는 `mayMeet`을 안 거치므로 관리자 소환은 그대로.
- **확인:** Cobblenav 2.4.1 `SpawnDataHelper`가 `PlayerSpawner.getSelector().getProbabilities`로 목록을 만드는 것을 바이트코드로 확인. 이 경로가 `affectSpawnable`을 부르는지는 Cobblemon 소스로 따로 보지 않았다(기존 엔트리 조건과 같은 경로라는 전제).
- **함께 발견:** 커밋 c8dbefc9에 `RareSpawnNotice.kt`가 빠져 있어 깨끗한 체크아웃에서는 컴파일이 안 됐다. 원인은 루트 `.gitignore`의 `mods/`가 `works/mods/` 아래 새 파일을 전부 무시한 것. `!works/mods/` 예외를 넣고 빠진 파일(cobblemon-npc 3개 포함)을 3e85ec0a로 커밋했다.
- **검증:** `:jbro-policy:build` 테스트 85개 통과, 커밋 9d7038a6. 서버·클라이언트가 꺼진 상태에서 `jbro-policy-0.1.2.jar`(SHA-256 앞자리 ded8c12e)를 두 곳에 배치, 이전 JAR은 `develop-product/deployment-backups/20261007-legend-rank-spawn/`. 서버는 켜지 않았다. 등급 미달 플레이어의 포켓내비 목록은 실게임 미확인.

## [2026-10-06 08:20] 코라이돈·미라이돈·전설 패러독스를 고대·미래 차원으로, 울비·일반 패러독스 알림 (빌드·테스트 확인, 실게임 미확인)

- **빡대리님 결정:** 울트라비스트와 전설이 아닌 패러독스 14종은 Legend가 아니다(전설 파생이 아니면 풀어 줌). 몇 마리든 잡고 등급 제한도 없지만, 나타나면 알림은 간다. 코라이돈·미라이돈 엔트리는 패러독스 아무거나(고대·미래 구분 없음).
- **스폰:** `legendary_wild_spawns.json`에서 코라이돈은 `cobblemon_dimensions:ancient`의 고대 초원·고대 사막, 미라이돈은 `future`의 미래 평원·강철 산맥으로 옮겼다(오버월드 스폰 없음). 오늘 꺼 뒀던 전설 패러독스 6종을 차원 조건으로 다시 넣었다(굽이치는물결 고대 바다 물 위, 꿰뚫는화염 태고의 화산, 날뛰는우레 고대 초원, 무쇠잎새 네온 숲, 무쇠암석 수정 지대, 무쇠감투 강철 산맥; 레벨·가중치는 예전 값). 103종 모두 스폰.
- **카탈로그:** `LegendCatalog.PARADOXES`(20종)를 코라이돈·미라이돈 엔트리로. 코라이돈 ↔ 전설 패러독스가 고리를 이루므로, 엔트리 순환 테스트를 "Legend가 아닌 포켓몬에서 모든 Legend에 닿는가"로 바꿨다(any/all 의미 반영).
- **알림:** `RareSpawnNotice`가 `ultra_beast`·`paradox` 라벨이면서 Legend가 아닌 포켓몬의 스폰(플레이어 스포너, 포케스낵)을 하늘색으로 알린다. 포케스낵이 두 이벤트를 모두 지날 수 있어 persistentData 플래그로 한 번만 알린다.
- **위키:** `gen_legends.py`가 `PARADOXES` 엔트리와 차원 조건(`dimensions`), cobblemon-dimensions 바이옴 한국어 이름을 읽는다. 전설 페이지 차원 필터에 고대·미래 차원 추가. 끝의 `relative_to(REPO)` 오류(저장소 구조 변경 뒤 생긴 것)도 고쳤다. `legends.js` 재생성.
- **검증:** `:jbro-policy:build` 테스트 85개 통과. 커밋 c8dbefc9. 서버·클라이언트가 꺼진 상태에서 `jbro-policy-0.1.2.jar`(SHA-256 앞자리 e25496b2)를 두 곳에 배치, 이전 JAR은 `develop-product/deployment-backups/20261006-paradox/`. 서버는 켜지 않았다. 차원 조건이 붙은 전설 스폰과 알림은 실게임 미확인.

## [2026-10-05 16:20] 디스코드 `/위키` 개인 링크, `/전적` 분류 선택 (빌드·단위 테스트 확인, 디스코드 실사용 미확인)

- **빡대리님 지적:** 디스코드 `/위키` 링크로 들어가면 "내 정보"가 안 보인다. `/전적`은 한 번에 다 보여 줘서 난잡하니 리그챌린지·배틀팩토리·배틀타워·PvP로 나눈다.
- **원인:** 내 정보는 `/api/me`에 플레이어별 위키 토큰이 있어야 보이는데, 디스코드 `/위키`는 토큰 없는 공용 주소만 줬다.
- **`/위키`:** `/디코인증`으로 연동한 사람에게는 `WikiApi.sharedLinkFor`(MCC에 새로 연 API) 링크를 준다. 기존 `WikiApi.linkFor`는 쓰지 않는다. 오프라인 플레이어면 서버 안 포트(`localhost:8100`)를, 로컬 위키를 띄운 클라이언트면 그 PC의 `localhost` 주소를 주기 때문이다. 링크를 가진 사람은 누구나 그 플레이어 정보를 보므로 답은 모두 본인에게만 보이게(ephemeral) 했다. 연동하지 않은 사람은 공용 주소와 연동 안내를 받는다.
- **`/전적`:** 필수 옵션 `분류`(선택지 네 개)가 `닉네임` 앞에 온다. 고른 콘텐츠 카드만 보여 주고, 제목이 분류를 말하므로 카드 내용은 필드 대신 본문(description)에 넣었다.
- **검증:** `:jbro-policy:test`, `:more-cobblemon-contents:test --tests "*Wiki*"` 통과. 디스코드에서 명령을 실제로 쳐 보지는 않았다. 명령 정의가 바뀌었으니 서버를 다시 켜야 디스코드에 새 옵션이 등록된다.
- **함정:** 이 작업 중 `cobblemon-ui`의 빌드 산출물에서 `CobblemonUiOverlays.kt` 클래스가 통째로 빠졌는데 Gradle은 UP-TO-DATE로 봤다. MCC 컴파일이 `Unresolved reference 'CobblemonUiDialogScreen'`으로 깨지면 `./gradlew :cobblemon-ui:compileKotlin --rerun`으로 다시 컴파일한다.
