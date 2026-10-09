# jbro-policy MEMORY

작업 내역과 이슈를 `[YYYY-MM-DD HH:MM]` 형식으로 기록한다. 최신 항목을 위에 추가한다.
구현, 빌드, JAR 배치, 서버 기동, 실게임 검증은 서로 다른 상태로 적는다.

---

## [2026-10-09 14:29] 디스코드 `/피츄 질문` 추가 (구현·빌드·AI 호출 확인, 배치·봇 실사용 미확인)

- **동작:** 필수 옵션 `질문`은 1~500자이며 답변은 공개로 보낸다. `anyChannel = true`로 일반 채팅방에서도 사용할 수 있다. Discord user ID별로 채널을 공유하는 5분 쿨다운을 두고, 같은 유저가 답변을 기다리는 중에는 중복 질문을 막는다. AI 실패·빈 답변이면 쿨다운을 해제한다.
- **AI 실행:** 별도 worker에서 최대 2개 질문을 동시에 처리한다. 기존 `inquiry-review`의 `enabled`·`command`·`model`·`timeoutSeconds` 설정을 재사용하되 질문 응답 제한시간은 최대 120초다. agy 전용 `jbro-pichu-chat` 프로필은 `inheritCustomizations: false`, `excludeDefaultComponents: true`, `tools: [finish]`로 제한한다. 질문만 전달하고 서버 로그·유저 기록은 제공하지 않는다.
- **구현 위치:** `DiscordQuestion.kt`, `PichuQuestionAI.kt`, `pichu-chat-agent.md`. Discord 비동기 응답 처리와 한국어 안내 문구를 연결했다.
- **빌드 검증:** 메인 작업자의 보고 기준으로 최신 `origin/main`(`a6d1e20d`)을 fast-forward 반영한 뒤 `:jbro-policy:build`를 다시 실행해 단위 테스트 93개 모두 성공, `BUILD SUCCESSFUL`(44초)을 확인했다.
- **AI 실호출:** Java 검증용 harness에서 실제 `PichuQuestionAI.ask`를 기존 로그인 agy의 `gemini-3.8-flash-low`로 호출해 `SUCCESS`, 비어 있지 않은 피츄 설명 답변, `AI_SMOKE_OK`, 종료 코드 0을 확인했다(메인 작업자 보고). 디스코드 slash 경로를 거친 검증은 아니다.
- **프로필 확인:** `.gemini/config/agents/jbro-pichu-chat/agent.md` 설치와 CLI agent 검색을 확인했다. 초기 flat `agent.md` 경로는 검색되지 않아 폴더 안 `agent.md` 경로로 고쳤다. 로컬 CLI 내장 changelog와 protobuf·YAML 설정 태그에서 `excludeDefaultComponents` 지원을 확인했다. 무해한 sentinel 파일을 읽으라는 질문에는 파일 접근 불가 답변을 받았고 도구 호출은 없었다. CLI `init.tools`는 기본 도구 전체를 표시하므로 제한 프로필의 실제 도구 목록을 증명하는 근거로 쓰지 않는다.
- **채널 권한 확인:** Discord API 읽기 조회에서 `💬┃잡담`(`1555187393236041820`)의 `@everyone`에 `SEND_MESSAGES`·`USE_APPLICATION_COMMANDS`가 허용되고 관련 channel overwrite 거부가 없음을 확인했다. 권한은 변경하지 않았다.
- **검증 도구 보정:** Gradle init task 등록 시점 조정과 Java 인자 CP949 보정은 검증용 scratch에서만 했으며 제품 코드 수정과 무관하다.
- **미확인:** 서버가 꺼져 있어 디스코드 slash 명령 등록·호출은 아직 검증하지 않았다. JAR 배치, 서버 기동, 실게임 동작도 검증하지 않았다.

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
