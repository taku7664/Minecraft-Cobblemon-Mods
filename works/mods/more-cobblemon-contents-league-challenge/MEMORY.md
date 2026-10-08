# MEMORY — more-cobblemon-contents-league-challenge

## [2026-10-08 15:20] 1~4단계 컴파일과 개발 클라이언트 확인 (`WildNpcRoleCheckHarness`)

- **빌드:** `:cobblemon-npc:build :jbro-policy:build :more-cobblemon-contents-league-challenge:build -x test -x unitTest` 성공.
- **하네스:** `MCC_NPC_ROLE_CHECK=1` + `--quickPlaySingleplayer npc-role-check`(`mcc-hub-capture` 복사본). 다른 세션이 MCC 테스트를 돌리는 중이라 `-x :more-cobblemon-contents:compileKotlin -x :more-cobblemon-contents:compileJava`로 띄웠다. 캡처 `run/screenshots/npc-role-*.png`, 판정 로그 `ROLE CHECK`. 클라이언트는 끝나면 스스로 닫힌다.
- **확인됨(실게임):** 돌보미 대화창(레터박스·이름표 얼굴) → 회복 후 파티 체력 전부 가득. 교환꾼이 파티에서 꼬렛을 원함 → 교환 결과 랑딸랑(uncommon, Lv.16 = 레벨캡으로 낮아짐), 어버이 `NPC` '교환꾼 Ivan', 오랭열매 1개 반환. [PC 좀 볼게] → `PCGUI` 열림. 야생 트레이너 대화창 [좋아, 승부하자! / 다음에 하자.]. 자연 스폰 7회 모두 `SpawnCause.entity`가 `ServerPlayer`.
- **보인 문제(미해결):** 하네스로 세운 역할 NPC·트레이너 몸이 RCT 스킨이 아니라 초록 머리 기본 모습으로 그려진다(이름표 얼굴은 RCT 스킨). 이번 변경 전부터인지 확인하지 않았다. 교환 캡처는 NPC가 나무 안에 서서 카메라가 잎에 가렸다(하네스 배치 문제).
- 하네스가 처음에 `NPCClasses.getByName`을 썼다가 `cobblemon:` 접두사가 붙어 실패했다. 다른 모드 NPC 클래스는 `getByIdentifier`로 찾는다.

## [2026-10-08 13:40] 교환꾼 NPC (4단계, 구현만, 빌드·테스트 안 함)

- **구현:** `WildTrader`(대화·교환·PC), `WildSpeciesRarity`(월드 스폰 풀 bucket으로 종 희귀도, 전설·환상·패러독스·울트라비스트 라벨 제외). 스폰 때(`ENTITY_SPAWN`) `SpawnCause.entity` 플레이어의 파티+PC에서 원하는 종을 고르고(common 80/uncommon 15/rare 5, 없으면 ultra-rare) NPC 태그 `mcc_trade_want:`·`mcc_trade_owner:`·`mcc_trade_owner_name:`에 적는다. 고를 게 없거나 원인이 플레이어가 아니면 스폰을 취소한다.
- **교환:** 스폰시킨 플레이어만. 파티 1번이 원하는 종이면 확인(별명·★·도구 반환 안내) → [보낼게] 때 UUID 재확인 → 1번을 빼고(도구는 인벤토리, 가득 차면 발밑) 한 단계 희귀한 종을 레벨(캡 이하)에 맞춰 만들어 넣는다. 이로치 1/100, 어버이는 NPC(`setOriginalTrainer(String)`, 이름은 모드 `ko_kr.json`에서 직업명을 찾아 "교환꾼 Alec"처럼). 교환 후 떠난다.
- **PC:** `PCLinkManager.addLink(uuid, pc) { NPC 8블록 안 }` + `OpenPCPacket` (`ProximityPCLink`는 PC 블록이 필요해서 안 씀).
- **야생 규칙:** jbro-policy에 `WildPokemonPolicy.applyWildRolls`를 공개하고 리플렉션으로 부른다. jbro-policy JAR도 같이 배치해야 적용된다.
- **데이터:** `wild_trader`(수집가 스킨, 배틀 불가), 스폰 uncommon 20. 대사는 포켓몬 이름 뒤 조사가 받침에 따라 틀리지 않게 조사 없이 썼다.
- **미확인:** 컴파일, `SpawnCause.entity`가 실제로 플레이어인지, 교환한 포켓몬 생성(진화 단계 맞춤 포함), 실게임 전부.

## [2026-10-08 12:50] 야생 NPC 역할 틀(2단계)과 돌보미 회복 NPC(3단계) (구현만, 빌드 안 함)

- **빌드:** 1단계 직후 사용자 지시로 `:cobblemon-npc:build :more-cobblemon-contents-league-challenge:build` 성공(이때 테스트도 같이 돌아 NPC 14개·리그 91개 통과). 그 뒤 사용자: "테스트는 하지 말고". 2·3단계는 빌드도 테스트도 안 했다.
- **2단계:** 정의 파일 schema 3에 `role`(battle·heal·trade·quiz·gift, 없으면 battle). 배틀이 아닌 역할은 `pokemon` 없이 정의한다. 우클릭은 역할로 나누고(`WildNpcRoles.talk`), 떠나는 NPC는 `wild_npc.leaving` 대사를 한다. "볼일 끝난 플레이어"는 NPC 엔티티 태그 `mcc_wild_npc_done:<uuid>`로 남긴다. 진 트레이너가 떠나는 처리는 `WildTrainers.leave(npc)`로 뽑아 역할 NPC도 쓴다.
- **3단계:** "포켓몬 돌보미"(`wild_caretaker`, 브리더 스킨, 배틀 불가). 회복할 포켓몬이 있으면 [응, 부탁해! / 괜찮아.] → `PartyStore.heal()`, 없으면 "다들 쌩쌩하네!". 대기 시간 없음, 떠나지 않음. 스폰 풀 uncommon 가중치 60, 바이옴 제한 없음(하늘 보이는 밝은 곳, 딥다크 제외). RCT 팩에 간호사 스킨이 없어 브리더 스킨을 같이 쓴다.
- **테스트 수정(실행 안 함):** `WildTrainerDataTest`의 BP·파티 테스트가 배틀 역할만 보게 했다.

## [2026-10-08 12:10] 야생 트레이너 대화를 NPC 대화창으로 (1단계, 구현만, 빌드 안 함)

- **사용자 지시:** 트레이너 대화를 NPC 모드 대화창(레터박스 포함)으로 옮기고 선택지로 묻는다.
- **구현:** `WildTrainers.offer()`가 Cobblemon `DialogueManager` 대신 `NpcTalks.open`으로 인사 + [좋아, 승부하자! / 다음에 하자.]를 띄운다. 채팅으로 보내던 거절 대사(`say`)도 대화창으로 띄운다. 이름표 얼굴은 스킨 aspect `rct_xxx`를 `rct:xxx`로 바꿔 넘긴다. 배틀 시작·종료 연출 대사는 그대로.
- **의존:** `cobblemon-npc`를 필수 의존(`fabric.mod.json` `cobblemon_npc >=0.1.0`)으로, 빌드는 `compileOnly`/`runtimeOnly`/`testImplementation`으로 넣었다. NPC 모드는 따로 배포한다.
- **미확인:** 사용자 지시로 빌드하지 않아 컴파일·테스트·실게임 모두 확인하지 않았다.

## [2026-10-08 11:40] 교환 NPC 구현 전 확인 (Cobblemon 1.8.1 JAR, 문서만)

- **보낼 포켓몬 (사용자 결정):** 고르는 화면 없이 파티 1번 자리의 포켓몬을 보낸다. 확인 대사에 레벨·이로치·별명을 넣어 개체를 알아보게 하고, [보낼게] 때 1번이 같은 개체(UUID)인지 다시 본다. Cobblemon `PartySelectCallbacks.createFromPokemon`(파티 선택 화면)도 있지만 쓰지 않기로 했다.
- **스폰한 플레이어:** `SpawnEvent.getCause()` → `SpawnCause.getEntity()`. 플레이어 값이 실제로 들어오는지는 구현 때 확인.
- **제외 라벨:** `legendary` 71, `mythical` 23, `paradox` 20, `ultra_beast` 11 (종 데이터 집계).
- **어버이(사용자 지시: NPC로):** `setOriginalTrainer(String)`이 타입 `NPC`와 이름을 저장하고, `PlayerPartyStore.add`는 어버이가 `NONE`일 때만 플레이어로 채운다. NPC 이름은 번역 키라 서버에서 `ko_kr.json`으로 풀어 넣어야 한다(전용 서버가 모드 언어를 안 읽는다는 건 알려진 동작, 구현 때 확인).

## [2026-10-08 11:20] 교환 NPC를 종 지정 + 희귀도 한 단계 위로 (문서만)

- **사용자 결정:** 교환 NPC는 스폰을 일으킨 플레이어의 파티·PC에서 원하는 종을 고른다(등급 가중치 common 80 / uncommon 15 / rare 5, 없는 등급은 빼고 재조정). 주면 한 단계 높은 등급 종을 무작위로 준다(타입 제한 없음, rare→ultra-rare 허용). ultra-rare뿐이면 ultra-rare끼리. 전설·환상·패러독스·울트라비스트는 양쪽 다 제외. 스폰시킨 플레이어만 교환할 수 있다.
- 등급은 Cobblemon 1.8.1 월드 스폰 풀 `bucket` 기준(종마다 가장 흔한 등급). 집계: common 567, uncommon 134, rare 35, ultra-rare 105.

## [2026-10-08 11:00] 야생 NPC 역할 설계 확정 (문서만)

- **사용자 결정:** 지난 항목의 미정은 제안대로 정한다. 받는 종은 타입이 겹치는 야생 종 무작위, 울트라비스트·패러독스는 받는 후보에서 제외, `cobblemon-npc` 필수 의존, 퀴즈 보상은 보상 풀에서 하나. 회복 NPC는 대기 시간 없음. 보상 NPC는 BP와 아이템이 섞인 보상 풀(`league-challenge/wild_rewards.json`, 가중치·레벨캡 범위)에서 뽑는다.
- 사용자는 회복 대기 시간을 jbro-policy에서 없앤 걸로 기억했지만, jbro-policy에는 회복 코드도 커밋 기록도 없다. 개발 서버 Cobblemon 설정은 회복장치 충전 제한이 켜져 있다(`infiniteHealerCharge: false`, 6회, 900초). 회복 NPC와는 무관해서 손대지 않았다.
- 출현 가중치 초깃값(uncommon): 회복 60, 퀴즈 40, 보상 30, 교환 20. 트레이너 uncommon 합계는 약 1498.

## [2026-10-08 10:40] 야생 NPC 역할 설계 문서 (`docs/WILD_NPC_ROLES.md`, 문서만)

- **사용자 결정:** 야생 트레이너 대화를 NPC 모드 대화창(레터박스 포함)으로 옮기고 선택지로 묻는다. 야생 NPC에 교환·회복·퀴즈(·보상) 역할을 더한다. 교환은 전설·환상 금지, 도구 반환, 이로치도 내줄 수 있고 받는 쪽 이로치는 내준 것과 무관하게 1/100, 선택지에서 PC를 열 수 있다.
- **미정:** 받는 종 규칙(타입 겹치는 종 무작위 제안), 울트라비스트·패러독스, 보상 NPC 내용, 회복 대기 시간, 퀴즈 보상, 출현 빈도, `cobblemon-npc` 필수 의존 여부.
- 구현·빌드는 아직 없다.

## [2026-10-08 08:20] `/mcc league check champion|hard|badges <min> [player]` 추가 (빌드·테스트 91개, 서버·클라이언트 배포, 실게임 미확인)

- **이유:** NPC 대화가 리그 진행도로 갈라질 방법이 없었다. 리그 안내원 대화(`develop-product/server/config/cobblemon_npc/dialogues/league_guide.json`)가 `cmd:mcc league check ...`로 배지 수·챔피언·하드 챔피언에 따라 대사를 바꾼다.
- **동작:** 조건을 만족하면 1, 아니면 0. 배지는 `LeagueEngine.badgeCount`(노말 체육관 기준), 하드는 `hardChampion`. `/mcc league` 아래라 관리자용이고 대화 조건(권한 2)에서 쓸 수 있다.
- **테스트:** `LeagueAdminCommandsTest`의 명령 트리 목록에 `check`를 넣었다.
- **배치:** 백업 `develop-product/deployment-backups/20261008-tower-access`.

## [2026-10-08 01:30] 쓰러진 야생 트레이너가 서버 종료 뒤에도 남던 문제 (`aa6b5517`, 빌드·테스트 91개 확인, 서버·클라이언트 배포, 실게임 미확인)

- **빡대리님 신고:** 대화창이 끝난 뒤 NPC를 늦게 없애는데, 그 사이 서버를 끄면 NPC가 계속 남는다.
- **원인:** 이긴 트레이너는 마무리 대사를 위해 300틱 뒤 지우는데, 그 예약(`despawns`)이 메모리에만 있었다. 서버 종료(`SERVER_STOPPED`가 비움)나 청크 언로드(`getEntity`가 null이라 예약만 빠짐)가 끼면 NPC가 영영 남았다.
- **수정:** 지울 트레이너에 엔티티 태그 `mcc_wild_trainer_defeated`(NBT에 저장됨)를 붙인다. 태그가 있는 트레이너가 로드되면 `despawns`에 지금 시각으로 넣어 다음 틱에 지운다(로드 콜백 안에서 바로 지우지 않음). 이미 남아 있던 트레이너에는 태그가 없어서 이번 수정으로는 사라지지 않는다. 필요하면 `/mcc league trainer despawn`으로 지운다.
- **배포 [2026-10-08 01:30]:** 서버·게임이 꺼진 상태에서 `develop-product/{server,client}/mods`에 교체, 백업 `develop-product/deployment-backups/20261008-ball-music-trainer-fixes`. 서버는 켜지 않았다.

## [2026-10-07 21:14] PokeBadges 버전 범위 해제 (`e4099c3d`, 빌드만 확인, 서버·클라이언트 배포)

- **빡대리님 지시:** 클라이언트에 PokeBadges 2.0.0을 넣자 `>=1.6.1 <1.7.0` 범위 때문에 에러가 났다. `depends.pokebadges`를 `*`로 풀었다.
- 2.0.0의 `PokeBadgesApi`·`BadgeOperationResult`는 1.6.1과 같고 `openBadgeBox`만 추가됐다(javap 비교). 컴파일은 여전히 1.6.1(`A93HZDyB`)에 대고 한다.
- 지시에 따라 테스트 없이 빌드만 했다(`-x test -x unitTest`). 계약 테스트 기대값은 `*`로 고쳤지만 돌려 보지 않았다.
- 개발 서버에는 서버가 꺼진 뒤 배포했다(백업 `20261007-211420-league-pokebadges-any`). 클라이언트는 게임을 끈 뒤 같은 백업 폴더에 백업하고 배포했다. 이어서 개발 서버의 PokeBadges도 1.6.1에서 2.0.0으로 바꿨다(1.6.1은 `deployment-backups/20261007-211531-pokebadges-2.0.0/server`로 옮김). 이제 서버·클라이언트 모두 2.0.0이다.
- 서버 `run.bat`의 시작 훅(`develop-product/server/startup-hooks.json`, 저장소 밖 파일)도 리그챌린지에 `pokebadges >=1.6.1 <1.7.0`을 따로 요구하고 있어서 서버가 뜨지 않았다. 버전 조건을 빼서 `{ "mod": "pokebadges" }`로 바꿨다(버전이 없으면 스크립트가 `*`로 본다). JAR의 `depends`를 바꿀 때는 이 파일도 같이 고쳐야 한다.

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
