# MEMORY — more-cobblemon-contents-league-challenge

## [2026-10-07 21:14] PokeBadges 버전 범위 해제 (`e4099c3d`, 빌드만 확인, 서버·클라이언트 배포)

- **빡대리님 지시:** 클라이언트에 PokeBadges 2.0.0을 넣자 `>=1.6.1 <1.7.0` 범위 때문에 에러가 났다. `depends.pokebadges`를 `*`로 풀었다.
- 2.0.0의 `PokeBadgesApi`·`BadgeOperationResult`는 1.6.1과 같고 `openBadgeBox`만 추가됐다(javap 비교). 컴파일은 여전히 1.6.1(`A93HZDyB`)에 대고 한다.
- 지시에 따라 테스트 없이 빌드만 했다(`-x test -x unitTest`). 계약 테스트 기대값은 `*`로 고쳤지만 돌려 보지 않았다.
- 개발 서버에는 서버가 꺼진 뒤 배포했다(백업 `20261007-211420-league-pokebadges-any`). 클라이언트는 게임을 끈 뒤 같은 백업 폴더에 백업하고 배포했다. 서버 mods의 PokeBadges는 1.6.1, 클라이언트는 2.0.0이다.

## [2026-10-07 00:37] 야생 레벨 쏠림 격자 4 → 8청크

- **빡대리님 요청:** 청크마다 쏠림이 너무 빨리 바뀐다고 해서 덜 바뀌게 했다. `active.json`의 `wild_level.region_chunks`를 4에서 8로 올렸다(격자 한 칸 128 × 128블록, 옆 청크로 넘어갈 때 쏠림 변화 최대 0.5 → 0.25). 코드 기본값(`WildLevelRule.regionChunks = 4`)은 그대로 두었다.
- **확인:** 단위 테스트 91개 통과, JAR 안 `active.json` 값 8 확인. 실게임에서 체감은 아직 확인하지 않았다.
- 쏠림은 시드·차원·꼭짓점 좌표로 계산하고 저장하지 않아서, 서버를 재시작하면 기존 월드에도 새 격자가 바로 적용된다.

## [2026-10-06 19:30] 하네스 나뭇잎 벽 옵션 (LumaVale 투시 확인용)

- `MCC_SCENE_CAPTURE_LEAVES=1`이면 첫 전투 전에 플레이어→트레이너 선 양옆 4블록에 높이 5, 길이 11의 나뭇잎 벽을 세우고, 배틀캠 구도가 바뀌는 시점(2·10·19초)에 `2-through-*` 캡처를 남긴다. 이때 전투 시작 대사는 기다리는 동안 지나가서 `scenes played`가 2로 찍힌다(실제로는 3장면 모두 나옴).
- 셰이더 확인은 개발 실행 폴더 `run/mods`에 Sodium·Iris JAR, `run/shaderpacks`에 LumaVale, `run/config/iris.properties`에 팩 지정으로 한다(빌드 스크립트는 그대로).

## [2026-10-06 10:22] 트레이너 JSON `scenes`, 난천 대사

- 트레이너 JSON의 선택 필드 `scenes`: 순간(`battle_start`, `last_pokemon`, `player_won`, `player_lost`) → 대사 번역 키 1~8줄. `LeagueCatalogParser`가 순간 이름과 줄 수를 검사하고, `Challenge.scenes` → `trainerScenes()` → `ManagedPveBattles.Request.scenes`로 넘긴다(일반 도전과 `/mcc league test` 모두). 동작은 MCC `MEMORY.md`의 `TrainerScenes`.
- 난천(`cynthia`, `cynthia_hard`)에 네 순간 대사를 넣었다. 키는 `trainer.more_cobblemon_contents_league_challenge.cynthia.scene.<순간>.<n>`, 문구는 Claude가 썼다. 다른 트레이너는 같은 방식으로 `scenes`와 lang 키만 추가하면 된다.
- `TrainerSceneCaptureHarness`는 이제 난천 대사 데이터와 두 마리 팀으로 세 장면을 차례로 캡처한다.

## [2026-10-06 10:02] 트레이너 연출 캡처 하네스 `TrainerSceneCaptureHarness` (개발 전용)

- `MCC_SCENE_CAPTURE=1`과 `--quickPlaySingleplayer scene-capture`로 리그 개발 클라이언트를 띄우면, 난천 스킨 관리 전투(뮤츠 Lv100 사이코브레이크 vs 잉어킹 Lv5)를 시작하고 기술을 자동으로 고르고, 이기면 난천에게 종료 연출을 걸고, 두 번째 전투로 트레이너 수를 센다. 캡처는 `run/screenshots/trainer-scene-*.png`, 판정은 로그 `SCENE CHECK`. `scene-capture` 월드(`mcc-hub-capture` 복사본)가 아니면 멈춘다.
- 개발 런타임에 배틀캠을 넣으려고 `runtimeOnly(:better-cobblemon-battlecam)`를 추가했다(JAR에는 들어가지 않는다). 결과는 MCC `MEMORY.md`.

## [2026-10-06 09:05] 야생 트레이너 승패 대사 연출 (컴파일·단위 테스트 89개 확인, 실게임 미확인)

- **빡대리님 지시:** 전투 승패 때 트레이너를 비추며 대화창을 띄운다. 실제 NPC 엔티티가 있는 건 야생 트레이너뿐이라 여기에 먼저 붙였다(리그·타워·팩토리 상대는 월드에 없는 가상 아머스탠드다).
- **구현:** `WildTrainers.settle`에서 `BattleScenes.play(player, npc, 이름, 대사 1줄)`. 대사는 `wild_trainer.scene.player_won.0~2`, `player_lost.0~2`에서 무작위로 고른다. 대사 문구는 Claude가 임의로 썼다.
- **퇴장 시간:** 진 트레이너가 사라지는 시간을 60틱(3초)에서 300틱(15초)으로 늘렸다. 서버에서는 배틀이 바로 끝나지만 클라이언트는 대사가 끝날 때까지 화면을 붙잡고 있어서, 3초면 연출 도중 대상이 사라진다.
- **배포:** MCC 코어와 같이 배포해야 한다(`BattleScenes`가 새 API).

## [2026-10-06 03:45] 하드 전투 디스토션 철회 (`7c6bcba8`)

- 실게임에서 사용자가 "너무 에바"라고 해서 `afb2cc0d`를 되돌렸다. 음악 JAR을 클라이언트에 다시 배포했고, 해시가 00:30 배포본(진단 로그만 있는 버전)과 같다. 백업은 `develop-product/deployment-backups/2026-10-06_0345-music-distortion-out`.
- 방향: 하드 루트는 이펙트 대신 전용 음원으로 간다. 후보는 공식 다른 편곡(플라티나·BDSP·마스터즈), 허락받은 팬 리믹스, Suno 오리지널. 음원이 오면 `hard_gym`, `hard_elite_four`, `hard_champion/cynthia_hard` 키에 매핑한다. Suno는 기성곡 업로드를 막는다.

## [2026-10-06 01:10] 하드 사천왕 서브에이스와 엔트리 개편, 난천 전원 100 (`c32635b5`) — 데이터 테스트·JAR 배포 확인, 실게임 미확인

- **사용자 결정:** 사천왕마다 Lv95 고정 서브에이스(준전설·환상·패러독스, 울트라비스트와 전설 제외)를 둔다.
  - 충호: 메가 핫삼, 헤라크로스 대신 깨비물거미, 쏘콘 대신 땅을기는날개(부스트에너지).
  - 들국화: 글라이온 대신 랜드로스(화신폼), 코리갑 대신 누오(천진).
  - 대엽: 부스터·마그마번을 뺀다. 순수 불꽃 타입은 최소로, 실전성 있게. 서브에이스는 풍선 히드런.
  - 오엽: 엘레이드 대신 메가 요가램, 에브이 대신 테오키스(어택폼).
  - 난천: 전원 Lv100, 한카리아스 스톤에지를 스톤샤워로.
- **Claude가 정한 것:**
  - 하드 팀 테스트상 에이스가 최고 레벨이고 사천왕 최고는 95라서, 사천왕 메가 에이스를 전부 95로 올렸다(서브에이스와 동레벨).
  - 대엽은 날쌩마도 순수 불꽃이라 뺐고, 파이어로(질풍날개)와 히트로토무를 넣었다. 코터스는 가뭄과 메가헬가(선파워) 조합 때문에 남겼다. 그래서 순수 불꽃은 코터스 하나다.
  - 테오키스 어택폼은 Cobblemon 습득표에 신속이 없어서 냉동빔을 넣었다. 생명의구슬은 테오키스가 갖고 후딘은 기합의띠로 바꿨다(도구 중복 금지). 하마돈은 울퉁불퉁멧으로 바꿔 누오에게 먹다남은음식을 줬다.
- **검증:** 30마리 기술·특성을 Cobblemon 1.8.1 습득표로 대조했고, `LeagueTeamDataTest`도 통과했다.
- **모델:** 땅을기는날개는 CCC 리소스팩에만 모델이 있다. 개발 클라이언트 CCC를 2.1에서 2.21(CurseForge 9/27 최신)로 바꿨고, 백업은 `develop-product/deployment-backups/2026-10-06_0105-ccc-2.21`. 배포용 클라이언트 구성에 CCC가 들어가는지는 확인하지 않았다.
- **배포:** HEAD `afb2cc0d` 빌드. 리그·MCC JAR은 서버와 클라이언트에, 음악 JAR(하드 루트 디스토션)은 클라이언트에 넣었다. 해시 일치. 백업은 `develop-product/deployment-backups/2026-10-06_0110-hard-rosters-distortion`.

## [2026-10-05 22:25] 관리자 테스트 브금, 하드 난천 로즈레이드, 서버 크래시

- **브금(43f5ec45):** `/mcc league test`는 Better AI 판단 로그 때문에 콘텐츠 ID를 `more_cobblemon_contents:ai_test`로 띄운다. 이 ID가 클라이언트 태그로도 가서 Better Cobblemon Music이 리그 키를 못 찾고 일반 트레이너 곡으로 떨어졌다. MCC `ManagedPveBattles.Request.clientTag`를 추가했고, 테스트 명령은 `league_challenge / <stage> / <challengeId>` 태그를 넘긴다. 정식 루트(`LeagueServer.launch`)는 원래 맞는 키를 보낸다.
- **팀(379b0b62):** `teams/cynthia_hard.json` 로즈레이드의 `spikes`(압정뿌리기)를 `gigadrain`으로 바꿨다. 사용자 지시: 풀 공격기가 없었다.
- **크래시:** 다른 세션이 21:47에 공유 작업 트리에서 MCC JAR을 빌드해 배포하면서 커밋 전이던 `clientTag` 수정이 들어갔다. 서버 리그 JAR은 옛 `Request` 생성자를 불러 `/mcc league test`에서 `NoSuchMethodError`로 서버가 죽었다(정식 리그 전투도 같은 경로였다). 이 애드온만 `ManagedPveBattles.Request`를 쓴다.
- **배포:** HEAD(379b0b62)를 scratchpad에 풀어 빌드한 리그 JAR을 `develop-product/{server,client}/mods`에 넣었다. 백업은 `develop-product/deployment-backups/2026-10-05_2225-league-clienttag`. MCC JAR은 21:47 배포본을 그대로 두었고, `javap`로 리그 JAR의 생성자 호출과 시그니처가 맞는 것을 확인했다.
- **미확인:** 서버 재기동, 실게임에서 난천 브금 재생과 기가드레인 사용은 확인하지 않았다.
- **재발 주의:** MCC의 공개 API(`Request` 같은 data class) 시그니처를 바꾸면 이 애드온 JAR을 같이 배포해야 한다.
