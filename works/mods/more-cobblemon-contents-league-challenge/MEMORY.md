# MEMORY — more-cobblemon-contents-league-challenge

## [2026-10-10 06:00] 리그 터미널에 트레이너 얼굴 표시 (구현·테스트·빌드·개발 배치, 실게임 미확인)

- **사용자 요청:** 리그 터미널 경로 칸과 '다음 도전' 칸의 배지 아이콘을 관장 트레이너 스킨 초상화로 바꾼다. 리그(L) 칸은 다음에 상대할 사천왕·챔피언 얼굴로.
- **구현:** `LeagueChallengeView`에 `skin`을 추가(gson JSON이라 이전 클라이언트와 호환). 서버 `send()`는 관장은 자기 외형 스킨, 리그 칸은 그 경로의 연전이 진행 중이면 다음 상대(`awaitingNext`면 `index + 1`), 아니면 첫 사천왕 스킨을 보낸다. 클라이언트는 `PlayerFaceRenderer`로 얼굴(모자층 포함)을 그린다. 스킨이 리소스팩에 없으면(RCT Trainers+ 미설치) 예전처럼 배지를 그린다. 존재 여부 캐시는 탭을 열 때 비운다.
- **검증:** 리그 `unitTest` 99개 통과, `remapJar` 성공, `jar --validate` 통과. 커밋 `70d76534`, `main` = `origin/main`.
- **배치:** `develop-product/{server,client}/mods` 교체, SHA-256 `8688fd36…` 세 곳 일치. 이전 JAR은 `develop-product/deployment-backups/20261010-league-trainer-faces/`. 서버·게임 꺼진 상태였고 켜지 않았다.
- **미확인:** 실게임 화면(얼굴 크기·위치, 잠긴 칸 어둡게 덮임, 사천왕 진행 중 L 칸 얼굴 바뀜).

## [2026-10-09 18:23] 위키·등산가 대사·양언어 오류 안내의 야생 레벨 문구 정리 (리소스 빌드 확인)

- `ko_kr`·`en_us`의 `spawn_scaling_conflict` 안내에서 구형 0~10 감소 설명을 지역 기준과 플레이어 캡 중 낮은 값에서 3~9 낮은 레벨을 적용한다는 설명으로 고쳤다. 사용자 최종 지시에 따라 위키·덕배 대사는 산식·구역 수치를 빼고 기존 Cobblemon과 달라지는 사용자 체감만 안내한다. 소스·리소스 문구만 수정했으며 런타임 코드·설정값은 변경하지 않았다.
- 최신 정적 재검사에서 양언어 번역 JSON 구문, NPC 대사 27개의 시작·다음 노드 참조, 덕배의 `lines` 이외 구조가 HEAD와 같음을 확인했다. 위키 `index`·`growth`·`legends`·`levels`·`pokedex` 5개 페이지의 상대경로 링크와 `levels` 야생 영역의 개발자 설명 잔여 없음, `diff --check`도 통과했다. 문구 수정이므로 별도 행동 테스트를 추가하거나 재실행하지 않았다.
- **리소스 빌드:** `remapJar` 성공(45초), `jar --validate` 통과, JAR의 한국어·영어 오류 안내가 소스와 같음을 확인했다. SHA-256은 `149875e948b2fb5bf4b84cde6ae7fd613555b48bab5436134526eba714dbac21`이다.
- 개발·운영 배포, `/npc reload`, 서버·클라이언트 기동·실게임 확인, 위키 화면 검증은 수행하지 않았다.

## [2026-10-09 18:03] 야생 포켓몬 구역 기준 레벨과 스폰 추첨 규칙 (구현·테스트·빌드 확인, 배포·실게임 미실행)

- **사용자 결정:** 한 구역은 64 × 64블록, 구역 기준 레벨은 10~83이며 시작 구역은 10이다. 구역 기준은 플레이어 레벨캡과 무관하게 고정한다. 인접 구역으로 이동할 때 점진적으로 변하고, 무작위 결과가 나쁘다는 이유로 고레벨 지역이 없어지면 안 된다.
- **스폰 계산:** `유효 지역값 = min(구역 기준 레벨, 플레이어 레벨캡)`을 먼저 계산하고, 실제 개체 레벨은 양끝을 포함한 `random(유효 지역값 - 9, 유효 지역값 - 3)`에서 균등 추첨한다. 넓은 범위에서 뽑은 최종 결과를 캡으로 clamp하는 방식은 사용하지 않는다. 구역 기준 10~83과 실제 포켓몬 스폰 범위를 혼동하지 않는다. 캡이 구역 기준 이상일 때 시작 구역의 개체는 1~7, 기준 83인 구역의 개체는 74~80이다.
- **구현:** 16구역 간격 앵커 사이를 선형 보간한다. 각 32 × 32구역(2,048 × 2,048블록) 권역에서 무작위 위치의 기준 83 앵커를 하나 보장하고, 월드 시드와 차원을 반영한다. 시작 원점 앵커는 10이며 같은 권역의 최고 앵커는 다른 자리에 둔다. 보간 후 반올림하며 인접 구역의 기준 차이는 최대 5다. 원점인 월드 스폰 좌표는 첫 사용 때 `SavedData`에 저장해 서버 재시작이나 관리자의 worldspawn 이동에도 유지한다.
- **설정·안내:** 이전 `floor_level` 데이터팩은 오류로 거부하며 `region_min`·`region_max`·`transition_regions`로 이전해야 한다. README, 새 V2 계약, Mod Menu의 한국어·영어 안내, 위키 `levels`·`legends` 문구와 표도 새 계산을 반영했다.
- **검증:** 수정 전 회귀시험 2개가 기존 10~97 범위 혼재와 기본 감소량 10 때문에 실패함을 확인했다. 수정 후 순수 로직 테스트 14개와 리그 전체 `unitTest` 99개가 통과했다. 원점 `SavedData` 코드 포함본 컴파일과 첫 `remapJar` 빌드는 성공했다(`BUILD SUCCESSFUL`, 47초).
- **빌드 실수와 수정:** 첫 빌드가 진행 중일 때 Mod Menu 안내 번역을 추가해 첫 JAR에는 새 `ko_kr`·`en_us` 안내 키가 없었다. ZIP 내용 검사에서 발견한 뒤 `remapJar`를 다시 실행해 성공했다(`BUILD SUCCESSFUL`, 33초). 재빌드 JAR에서 두 언어 안내 키 포함을 확인해 수정 완료했다. 99개 코드 테스트 통과 결과에는 영향이 없다.
- **병합 전 JAR 검사:** JDK 21 `jar --validate` 통과. `active.json`의 기본값은 `below_cap: 6`, `spread: 3`, `region_chunks: 4`, `region_min: 10`, `region_max: 83`, `transition_regions: 16`이며 `WildSpawnLevel`·`WildSpawnRegions`·`WildSpawnOrigin` 클래스 포함을 확인했다. 병합 전 검사본 SHA-256은 `2b909f91eaa674b7df61c9295f118c7f7b6f22cf219b0bf35fc024e1cf9e5bf4`다.
- **main 반영·재검증:** 구현 커밋 `c1c4ce43`을 다른 최신 main 변경을 보존한 `544f0f40`으로 병합했다. 워크트리 HEAD·main·origin/main은 `544f0f407a32b6ba36281b44a11b28bab3130bca`로 같으며 원격 `ls-remote`로 일치 확인했다. 병합된 커밋 소스로 `unitTest` 99개 재통과, `remapJar` 재성공(44초), `jar --validate`와 새 `ko_kr`·`en_us` 안내 키 포함을 확인했다. 최신 병합 소스 JAR의 SHA-256은 `ac67b61b63a4d3f6ad67a1114db74efbda75f7990cfb8cacc33ba1a538667c26`다.
- **미실행:** 개발·운영 배포, 서버 기동·실게임 확인, Mod Menu·위키 화면 확인은 수행하지 않았다.

## [2026-10-09 14:30] 리그 첫 승리 BP: 사천왕 0, 챔피언 150·하드 300 (구현만, 빌드·테스트·배포 안 함)

- **사용자 결정:** 사천왕은 BP를 주지 않고(일반·하드 모두), 챔피언 난천은 일반 150, 하드 300을 준다. 관장은 그대로 10·30이다. 사천왕을 리그에서 빼는 것이 아니라 BP만 없앴다.
- **변경:** `rewards/{aaron,bertha,flint,lucian}{,_hard}.json`의 `first_bp`를 0, `cynthia.json` 150, `cynthia_hard.json` 300. 합계는 일반 190→230, 하드 550→540. 위키 `league.html`(표와 합계), `hub.html` 수입 표, NPC 대사 원본(상점 아가씨)도 고쳤다.
- **배포(2026-10-09 16:14):** `unitTest` 94개 통과, `9a938b31` 기준으로 빌드한 리그 JAR을 `develop-product/{server,client}/mods`에 넣었다(SHA-256 앞자리 `edc3d106d2db8f5f`로 세 곳 일치). 상점 아가씨 대사는 서버 `config/cobblemon_npc/dialogues/`에, `league.html`·`hub.html`은 서버·클라이언트 위키에 복사했다. 백업은 `develop-product/deployment-backups/20261009-161447-league-champion-bp`. 서버 기동·실게임 확인은 안 했다.
- **미확인:** 사천왕을 이겼을 때 BP 0이면 승리 알림이 BP 없이 나오는지는 실게임에서 확인하지 않았다(`BattleResultNotices.victory`의 기본값이 0이라 문제없을 것으로 추정).

## [2026-10-09 18:10] 교환꾼 안내·자리표시 방어 코드 빌드·개발 배치

- **빌드:** `main` = `origin/main` = `abda4ff4`, 리그 소스 변경 없음 확인 후 `remapJar`(테스트 안 함). `jar --validate` 통과, JAR 안에 새 교환 대사·`WildNpcSpawns$Kind` 확인. `3cb56d50`(자리표시 방어)·`abda4ff4`(교환꾼 안내) 포함.
- **배치:** `develop-product/server/mods`·`client/mods` 교체, SHA-256 일치(`a5ce4701…`), 폴더마다 리그 JAR 하나. 이전 JAR은 `develop-product/deployment-backups/20261009-trader-offer-standin-guard/`. 서버·게임 꺼진 상태, 켜지 않음. 실게임 미확인.

## [2026-10-09 18:00] 교환꾼이 줄 포켓몬을 미리 말함

- **사용자 요청:** 교환꾼이 어떤 포켓몬과 바꿔 주는지 말해야 한다.
- **구현:** `WildTrader.prepare`가 원하는 종과 함께 줄 종(원하는 종보다 한 등급 위에서 무작위)을 정해 태그 `mcc_trade_offer:`에 적는다. 제안 대사 "혹시 %1$s 있어? 바꿔 주면 내 %2$s 줄게!", 교환 확인 때 "대신 내 %s 줄게."(레벨에 맞춰 `WildSpawnSpecies.fit`으로 이전 단계가 된 실제 종). 교환 때 그 종을 만든다. 태그가 없는 예전 교환꾼은 "귀한 포켓몬"이라 말하고 예전처럼 무작위. 이름 뒤 조사 문제를 피하려고 "내 ○○ 줄게" 꼴로 썼다. 컴파일 확인.

## [2026-10-09 17:40] 싱글 월드에 Cobblemon 테스트 NPC("AI Man"·"The Kitchen Sink"·사치박사)가 나타남

- **확인:** 사용자 스크린샷. 싱글 월드 `새로운 세계/entities/r.-4.-1.mca`에 `cobblemon:ai_test`·`kitchen_sink`·`sacchi` NPC 세 개, 모두 `VariationAspects` 비었고 Level 1, 태그 없음. Cobblemon 1.8.1 자연 스폰 풀엔 NPC가 없다. `NPCEntity`는 저장된 클래스를 못 찾으면 `NPCClasses.classes.first()`로 되살리고(바이트코드 확인), 이 테스트 클래스들은 `canDespawn: false`라 계속 남는다.
- **추정(미확정):** 새 자리표시 변환(`WildNpcSpawns`)이 클래스 없이(또는 엉뚱한 클래스로) NPC를 세웠고, 저장·로드 때 테스트 클래스로 바뀌었다. 스폰 데이터 파싱(`SpawnLoader.gson`, `NPCClassReferenceAdapter`)은 정상으로 보여 정확한 경로는 못 찾았다.
- **수정:** 변환 때 파싱한 클래스 객체를 쓰지 않고 JSON의 `npcClass` ID로 `NPCClasses.getByIdentifier`를 다시 찾는다. 우리 정의(`WildTrainers.definitions`)에 없는 클래스는 건너뛰고, 설정 뒤 ID가 다르면 스폰을 취소한다. 변환마다 INFO 로그(`Wild NPC stand-in became ...`)를 남겨 실게임에서 경로를 확인할 수 있게 했다. 컴파일만 확인, 빌드·배치·실게임 안 함.
- **남은 NPC 정리:** `/kill @e[type=cobblemon:npc,nbt={NPCClass:"cobblemon:ai_test"}]`(kitchen_sink·sacchi도 같은 식). 자동 삭제는 사용자 결정 전이라 넣지 않았다.

## [2026-10-09 16:35] 역할 NPC 월드 가중치 150 → 15

- **사용자 결정:** 역할 NPC가 너무 자주 나온다 → 15. 트레이너 50은 그대로. 트레이너를 50으로 줄이면서 역할을 150으로 둬 역할이 트레이너의 3배가 됐던 것을 바로잡았다. 평원 낮 약 0.1%, 숲 낮 약 0.25%(추정).
- **빌드·배치:** `main` = `origin/main` = `f64ffb42`에서 `remapJar`(테스트 안 함), `jar --validate` 통과, JAR 안 월드 가중치 50/15 확인. `develop-product/server/mods`·`client/mods` 교체, SHA-256 일치(`a4ae1a2e…`). 이전 JAR은 `develop-product/deployment-backups/20261009-wild-npc-weight-15/`. 서버·게임 꺼진 상태, 켜지 않음. 실게임 미확인.

## [2026-10-09 16:22] 스폰 그룹 구조 빌드·개발 배치

- **빌드:** `main` = `origin/main` = `67ba5039`, 리그 소스 변경 없음 확인 후 `remapJar`(테스트 안 함). `jar --validate` 통과. JAR 안에 `WildNpcSpawns`, `wild_spawns/{trainers,roles}.json`, 자리표시 클래스 두 개, 월드 스폰 풀 50/150 확인.
- **배치:** `develop-product/server/mods`·`client/mods` 교체, SHA-256 일치(`3f7c96f8…`), 폴더마다 리그 JAR 하나. 이전 JAR은 `develop-product/deployment-backups/20261009-wild-spawn-groups/`. 서버·게임 꺼진 상태였고 켜지 않았다.
- **미확인:** 실게임(자리표시가 실제 트레이너로 바뀌어 나오는지, 스킨·이름).

## [2026-10-09 14:20] 야생 NPC 스폰을 그룹 자리표시 하나로

- **사용자 결정:** 트레이너가 너무 자주 나온다. 종류마다 스폰 항목을 두는 대신 "트레이너" 스폰 하나를 두고, 스폰될 때 어떤 트레이너가 될지 정한다. 트레이너 월드 가중치 50. 역할 NPC도 같은 방식, 역할별 가중치는 유지.
- **구현:** 월드 스폰 풀(`spawn_pool_world/wild_trainers.json`)은 자리표시 두 개뿐이다. `spawn_wild_trainer` 50, `spawn_wild_npc` 150(예전 역할 네 항목 합계 그대로, 역할 쪽 빈도는 바꾸지 않음). 둘 다 uncommon·지상·하늘 보임·밝기 8·딥다크 제외. 종류별 조건·가중치는 `league-challenge/wild_spawns/{trainers,roles}.json`(예전 항목 그대로). `WildNpcSpawns`가 `ENTITY_SPAWN`에서 자리표시를 그 자리 조건(`SpawnDetail.isSatisfiedBy`, Cobblemon `SpawnLoader.gson`으로 읽음)이 맞는 종류 중 가중치로 골라 `setNpc`+`initialize(1)`로 바꾼다. rare로 적힌 종류는 가중치 0.1배. 교환꾼이 뽑혔는데 원할 종이 없으면 남은 역할 중에서 다시 고른다. 맞는 종류가 없으면 스폰 취소.
- **생성기:** `tools/wild-trainers/gen_wild_trainers.py`는 이제 `wild_spawns/trainers.json`만 쓰고 월드 스폰 풀은 건드리지 않는다. 자리표시 이름 키는 생성기가 지우는 `wild_trainer.` 접두사를 피해 `npc.<ns>.wild_spawn.*`.
- **검증:** 리그 본 코드·테스트 컴파일 통과. 정합성 테스트(`WildTrainerDataTest`의 그룹 테스트) 추가했지만 돌리지 않았다. 빌드·배치·실게임 안 함. 실제로 자리표시가 바뀌어 나오는지, 스킨·이름이 정상인지는 게임에서 확인해야 한다.

## [2026-10-09 04:58] 이름 음차·상대 도구 가리기·상태 패널 빌드·개발 배치

- **빌드:** `main` = `origin/main` = `7fb43f41`, 리그 소스에 커밋 안 된 변경 없음을 확인하고 `works/`에서 `remapJar`만(테스트 안 함). `jar --validate` 통과. JAR 안에 개인 이름 키 679개, `NOT_PERSONAL_NAMES`, `LeagueHubTab` 포함 확인.
- **배치:** `develop-product/server/mods`·`client/mods` 교체, 빌드본과 SHA-256 일치(`f87b9eb4…`), 폴더마다 리그 JAR 하나. 이전 JAR(`22e0c389…`)은 `develop-product/deployment-backups/20261009-league-names-items-panel/`. 배치 때 서버·게임 꺼져 있었고 켜지 않았다.
- **실수 기록:** 빌드 없이 올린 MCC 상점 툴팁 커밋(`04b75e23`)이 파이썬 치환 중 `"\n"`을 실제 줄바꿈으로 넣어 MCC 컴파일을 깼다(다른 세션이 `5f7d193d`로 수정). 코틀린 문자열 이스케이프를 셸·파이썬으로 끼워 넣을 땐 커밋 전에 바이트를 확인한다. 또 Bash 샌드박스는 `~/.gradle`을 실제와 다른 곳으로 보므로 Gradle은 PowerShell로 돌린다. `git archive | tar`로 뽑은 사본도 줄바꿈이 바뀌어 빌드용으로 못 쓴다.
- **미확인:** 실게임 확인 안 함.

## [2026-10-09] 리그 상태 패널 제목 줄바꿈

- **문제:** 허브 리그 탭 "리그 상태" 패널의 제목(`StatusBody`)이 `drawLine` 한 줄이라 챔피언 문구("챔피언 달성! 배틀타워와 배틀팩토리를 이용할 수 있습니다.")가 "…"로 잘렸다(사용자 스크린샷).
- **수정:** 제목도 `font.split`으로 줄바꿈하고, 아래 설명은 제목 줄 수만큼 내려 그린다. 한 줄 제목일 때 간격(14px)은 전과 같다.
- **미확인:** 빌드·실게임 화면 확인 안 함.

## [2026-10-09] 야생 트레이너 배틀에서 상대 도구 가리기

- **원인:** 야생 트레이너는 `BattleBuilder.pvn`으로 Cobblemon 일반 배틀을 연다. MCC 배틀의 도구 가리기(`Cobblemon173BattlePokemonAppearance.hideHeldItems`)는 관리 배틀 엔진과 PvP에서만 불려서 여기서는 상대 도구가 보였다.
- **수정:** `BATTLE_STARTED_PRE`에서 야생 트레이너 배틀일 때 NPC 쪽 `BattlePokemon`의 `originalPokemon`·`effectedPokemon` 둘 다 `heldItemVisible = false`로 둔다. 플레이어 쪽은 실제 포켓몬이라 설정이 배틀 뒤에도 남으므로 건드리지 않는다. 배틀 복사본(`Pokemon.clone`)이 이 값을 넘기는지는 바이트코드로 확인하지 못해서, 파티 원본이 아니라 배틀 시작 직전 양쪽에 건다.
- **미확인:** 빌드·테스트·JAR 배치·실게임 확인 안 함.

## [2026-10-08 23:27] 야생 NPC 개인 이름 한글 음차

- **사용자 결정:** 이름표의 영어 개인 이름("낚시꾼 Alec")을 음차한다("낚시꾼 알렉"). 직업명만 남기는 안은 쓰지 않는다.
- **구현:** 스킨에서 나오는 개인 이름 679개를 `npc.<modid>.personal_name.<이름>` 키로 `ko_kr`·`en_us`에 넣었다. `WildTrainers.name()`은 `Component.translatableWithFallback`으로 붙이므로 키가 없으면 영어 그대로 나온다. 교환한 포켓몬의 어버이 이름(문자열)은 서버에서 `ko_kr.json`을 읽어 한글로 쓴다. RCT 패러디 이름 `agathare`/`brunore`/`lancere`는 공식 한국어명 국화/시바/목호, `red`는 레드로 옮겼다.
- **버그 수정:** 이름 없는 스킨 `rct_bug_catcher_*`·`rct_biker_goon_*`에서 "Catcher"·"Goon"을 개인 이름으로 붙이던 것을 `NOT_PERSONAL_NAMES`로 막았다.
- **테스트:** 모든 스킨의 개인 이름에 두 언어 키가 있는지 보는 `WildTrainerDataTest`를 추가했다. 이미 월드에 있는 NPC는 로드될 때 `name()`이 다시 불려 새 이름으로 바뀐다(코드상, 실게임 미확인).
- **미확인:** 빌드·테스트 실행, JAR 배치, 실게임 확인 모두 하지 않았다(빌드는 요청 시에만).

## [2026-10-08 21:56] main 병합 후 빌드·개발 배포 재확인

- **병합:** 기믹 API 브랜치를 현재 main과 병합한 `5d4afd558bd3c1fa63e7d81d47c568ff5d564933`을 main에 푸시했다. 기존 BP 테스트 기대값 수정 `3c247482`·`10e14da4`도 포함한다. 앞선 브랜치 배포 기록은 이번 main 검증으로 보완하며 이전 실패 기록은 보존한다.
- **빌드·테스트:** 깨끗한 워크트리에서 현재 main 소스로 MCC 5개 모듈 빌드 성공(2분 19초). 리그 테스트 93개 통과.
- **배치:** main의 추가 보상 변경을 포함한 JAR로 개발 서버·클라이언트 양쪽을 교체했다. 새 SHA-256은 `22e0c389b6324093b7da652d4f9d1a21cfd624d64b22cbb0405d465bd78d50dc`다. 실제 저장소 `develop-product/server/mods`·`develop-product/client/mods`의 5개 JAR 모두 main 빌드본과 해시 일치, 중복 Fabric ID·`.deploying` 잔여물 없음. JDK 21 `jar --validate`·ZIP CRC 재검사 통과. 백업·manifest는 실제 저장소 `build/mcc-gimmick-deployment/20261008-215627-main/`에 있다.
- **미확인:** 서버·게임이 꺼진 상태에서 확인했다. 서버 기동·실게임 검증은 하지 않았으며 `deploy-product` 릴리스도 아니다.

## [2026-10-08 21:48] 공통 배틀 기믹 API 전환 JAR 개발 배포

- **구현:** `e8f428ea`에서 리그·관리자 배틀의 기존 단일 기믹 선택을 공통 `mechanicFlags: Int`로 변환해 PvE API에 전달한다. 콘텐츠의 기믹 선택 규칙은 유지한다.
- **테스트:** 리그 테스트 93개 통과.
- **빌드·무결성:** 0.1.0 JAR 생성, JDK 21 `jar --validate`와 ZIP CRC 검사 통과.
- **배치:** 실제 저장소 `C:/Users/박주형/Documents/GitHub/Cobblemon-Mods/develop-product/server/mods`와 `develop-product/client/mods`에 배치했다. 원본·서버·클라이언트 SHA-256 일치, 중복 Fabric ID와 `.deploying` 잔여물 없음 확인. 배치 전 서버·게임 프로세스 및 25565/25566 리스너가 없었다. 이전 JAR·manifest는 실제 저장소 `build/mcc-gimmick-deployment/20261008-214821/`에 보관했다.
- **미확인:** 서버 기동·실게임 검증은 하지 않았다. `deploy-product` 릴리스가 아니라 개발 배포다.

## [2026-10-08 21:47] BP 재조정 묶음 테스트·배포 (빌드·JAR 배치 확인, 서버 기동·실게임 안 함)

- **테스트:** `unitTest` 통과. MCC는 전체 2161개 중 상점 테스트 1개만 실패해서 고친 뒤 그 클래스만 다시 돌렸다. 타워 192, 팩토리 111, 리그 93, jbro-policy 87, NPC 14개는 모두 통과했다. 바뀐 BP에 맞춰 고친 테스트는 세 개다. 타워 `bp_per_win` 1→2, 팩토리 첫 승 3→2, 상점 `ability_` 필터에서 특성가드(도구) 제외(`10e14da4`).
- **배포:** MCC·타워·팩토리·리그·jbro-policy·NPC JAR을 `develop-product/{server,client}/mods`에 넣었다(무결성·엔트리포인트 확인). NPC 대사 4개는 서버 `config/cobblemon_npc/dialogues/`에, 위키는 서버·클라이언트 `config/more-cobblemon-contents/wiki/`에 복사했다. 백업은 `develop-product/deployment-backups/20261008-214654-bp-rebalance`.
- **주의:** 다른 모듈은 `test`가 SKIPPED라서 테스트가 안 돈다. `unitTest`로 돌려야 한다. 테스트 필터는 `--tests` 대신 `-Ptests=<클래스명>`을 쓴다.

## [2026-10-08 21:40] 야생 트레이너 승리 BP 일반 1~2 무작위, 에이스 4 (구현만, 빌드·테스트 안 함)

- **사용자 결정:** 일반 트레이너는 1~2 BP를 무작위로, 에이스는 4 BP를 준다.
- **변경:** `WildTrainerDefinition.bp`를 `LongRange`로 바꿨다. JSON `bp`는 숫자 하나나 `[min, max]`를 받고(`wild_rewards.json`과 같은 형식), 이길 때 `bp.random()`으로 지급한다. `wild_trainers/*.json` 124개(일반 104개는 `[1, 2]`, 에이스 20개는 `4`), `WildTrainerDataTest`, 생성기 `BP` 상수, 위키 생성기 `gen_trainers.py`(범위면 "1~2"로 출력), `trainers.js`(값만 치환), `wild-trainers.html`·`hub.html`, NPC 대사(배틀걸·상점 아가씨)를 같이 고쳤다.
- **미확인:** 컴파일과 테스트를 돌리지 않았다.

## [2026-10-08 20:50] 야생 트레이너 승리 BP 3/6 → 2/5 (구현만, 빌드·테스트 안 함)

- **사용자 결정:** 타워·팩토리 보상을 낮추면서 야생 트레이너도 일반 2, 에이스 5로 낮췄다.
- **변경:** `wild_trainers/*.json` 124개(일반 104, 에이스 20)의 `bp`, `WildTrainerDataTest`. 생성기 `works/tools/wild-trainers/gen_wild_trainers.py`의 `BP` 상수도 같이 바꿨다(안 바꾸면 재생성 때 3/6으로 돌아간다). 위키 `wild-trainers.html`·`hub.html`과 생성본 `assets/data/trainers.js`, NPC 대사 원본(배틀걸·상점 아가씨)도 고쳤다. `trainers.js`는 생성기를 다시 돌리지 않고 값만 바꿨다.
- 퀴즈·여행자 선물의 BP(`wild_rewards.json`의 2~5)는 승리 보상이 아니라서 그대로다.

## [2026-10-08 17:25] 개발 서버 기동 확인

- 사용자 지시로 개발 서버를 켰다. 시작 점검(bluemap·startup hooks) 통과, 리그 로그 `Loaded 128 wild trainer kinds` / `10 wild rewards` / `38 wild quiz questions`, 우리 모드 오류 없음. 광장 NPC 배치(cobblemon-npc MEMORY) 뒤 `stop`으로 저장·종료하고 `explorer.exe run.bat`으로 다시 켜 둠(17:23 Done).
- **미확인:** 플레이어 접속 상태에서 야생 역할 NPC 자연 스폰과 대화(온라인 모드라 개발 클라이언트로 못 들어감).

## [2026-10-08 17:20] 야생 역할 NPC 1~6단계 테스트·빌드·배치

- **방법:** 다른 세션이 MCC에 커밋하지 않은 Better AI 변경이 있어, 커밋된 `HEAD`(5fe75981)의 `works`·`server-wiki`를 `build/deploy-head`로 `git archive`해 그곳에서 테스트·빌드했다(작업 트리 변경은 안 섞임).
- **테스트:** NPC 14, jbro-policy 87, 리그 93, 타워 193, 팩토리 111, MCC `-Pscope=core` 609 전부 통과. MCC의 `BattleContentCommandsTest` 1건은 `/mcc terminal` 관리자 전용 변경 때 갱신을 빠뜨린 검사라 고쳤다(5fe75981).
- **빌드:** 처음엔 remap이 동시에 돌며 Gradle 힙(2G)이 모자라 실패했다(실패한 JAR은 22바이트). `-Dorg.gradle.jvmargs=-Xmx4G --max-workers=2`로 성공.
- **배치:** NPC·jbro-policy·타워·팩토리·리그·MCC JAR 6개를 `develop-product/server/mods`와 `client/mods`에 넣고 SHA-256 대조 일치. 이전 JAR은 `develop-product/deployment-backups/20261008-wild-npc-roles/`. 서버는 꺼져 있었고 켜지 않았다. 실서버 기동·접속 확인은 안 함.

## [2026-10-08 17:30] 생성기가 역할 NPC를 지키게 함

- `works/tools/wild-trainers/gen_wild_trainers.py`가 정의 파일의 `role`이 battle이 아닌 NPC(지금 돌보미·교환꾼·퀴즈 마니아·여행자)를 찾아 NPC·정의 파일, 스폰 풀 항목, 언어 파일의 직업명, RCT 스킨 변형을 그대로 남긴다. 역할 NPC는 kinds.py가 아니라 손으로 관리한다.
- **확인:** 고친 생성기를 돌리니 모든 파일 내용이 커밋본과 같았다(언어·스폰 파일은 줄바꿈만 CRLF로 달라 되돌림). 출력 `normal 104, ace 20, roles kept 4, skins 1089`.

## [2026-10-08 17:10] 1~6단계 개발 클라이언트 확인과 이름 버그 수정

- **실행:** `WildNpcRoleCheckHarness`에 퀴즈(정답→오답→재방문)와 여행자(선물→재방문) 순서를 더해 세 번 돌렸다(`-x :more-cobblemon-contents:compileKotlin -x ...compileJava`, 컴파일 통과). 클라이언트는 매번 스스로 닫혔다.
- **확인됨(실게임, 마지막 실행):** 돌보미 간호순 스킨 두 장 모두 렌더(실행마다 하나씩 나옴), 회복 후 체력 가득. 교환 꼬렛→폴리곤(uncommon, Lv.16, 어버이 '교환꾼 Terry', 열매 반환), PC 열림. 퀴즈: 보기가 섞여 나오고 정답→"경험사탕S ×1 받았다!", 오답→"아쉽다! 정답은 바로… 3,000!", 재방문→"이미 풀었잖아". 여행자: "5 BP 받았다!"(이전 실행에서는 오랭열매 ×2, 경험사탕S ×1), 재방문→인사만. 자연 스폰 원인 11/11이 플레이어.
- **이름 버그(수정):** Cobblemon은 NPC 클래스를 정하는 순간 `customName`을 직업명으로 채운다. 그래서 `ENTITY_LOAD`의 `customName == null` 조건이 늘 거짓이었고, 스킨이 이미 있는 채로 로드되면 재명명 예약도 안 걸려 "교환꾼"처럼 개인 이름이 빠졌다. 로드 때 스킨에 개인 이름이 있으면 바로 `name()`을 부르고, 말을 걸 때도 늘 `name()`을 부르게 했다. 수정 뒤 "반바지 꼬마 Destin", "퀴즈 마니아 Aidan" 확인. 하네스가 엔티티를 직접 넣어 드러났고, 자연 스폰에서도 같았는지는 확인 안 함.
- **하네스 주의:** 일회용 월드에 지난 실행의 NPC가 저장돼 남는다. 시작할 때 이 모드 NPC를 48블록 안에서 지운다. 개발 클라이언트는 실행마다 플레이어 이름(UUID)이 바뀌어 지난 실행의 "볼일 끝남" 기록이 새 플레이어에겐 안 걸린다(버그 아님).
- **미확인:** 전용 서버 배치, 실제 자연 스폰된 역할 NPC와의 대화.

## [2026-10-08 16:50] 여행자 보상 NPC (6단계, 구현만, 빌드·테스트·실게임 안 함)

- **구현:** `WildNpcRoles.gift`. "여행하다 주운 건데, 너 가질래?" → [고마워! / 괜찮아.] → `WildRewards.give`(사유 `wild_gift`) → "○○ ×n 받았다!". 플레이어마다 한 번(`mcc_wild_npc_done:`), 보상을 실제로 준 뒤에만 기록한다. 떠나지 않는다.
- **데이터:** `wild_traveler`("여행자", 캠퍼·피크니커 RCT 스킨 86장 전부 `0_wild_trainer.json`에 있음 확인), 스폰 uncommon 30. 이로써 설계 문서 1~6단계 구현이 모두 들어갔다.
- **실수 기록:** 스폰 풀 JSON을 다시 쓸 때 들여쓰기를 1로 써서 파일 전체가 바뀔 뻔했다. 이 파일은 들여쓰기 2다.
- **미확인:** 5·6단계 컴파일, 실게임 전부.

## [2026-10-08 16:30] 퀴즈 NPC와 보상 풀 (5단계, 구현만, 빌드·테스트·실게임 안 함)

- **구현:** `WildQuiz`(문제 은행 `league-challenge/wild_quiz.json` 38문제, 출처는 `server-wiki/pages` 문서 이름), `WildRewards`(보상 풀 `league-challenge/wild_rewards.json`, 설계 문서 초깃값 10항목). 퀴즈가 보상 풀을 쓰므로 풀을 이 단계에서 같이 만들었다. 6단계(보상 NPC)는 `WildRewards.give`만 부르면 된다.
- **규칙:** NPC마다 문제 하나(태그 `mcc_quiz_question:`), 보기 순서는 매번 섞음, 답을 고르면 그 플레이어는 끝(`mcc_wild_npc_done:`), 문제 화면을 닫기만 하면 기록 안 됨. 맞히면 레벨캡에 맞는 보상 하나, 틀리면 정답을 알려 준다. 떠나지 않는다.
- **데이터:** `wild_quizzer`("퀴즈 마니아", 슈퍼너드 RCT 스킨 10장, 배틀 불가), 스폰 uncommon 40. 문제 글은 한국어만(영어 언어 파일엔 대사만 있음).
- **보상 대사:** 보상 이름 뒤 조사를 피하려고 "경험사탕S ×2 받았다!" 형태로 쓴다. BP는 `BattlePointRewards.award`(사유 `wild_quiz_<문제 ID>`, 트랜잭션은 매번 새 UUID).
- **테스트(작성만, 실행 안 함):** `WildTrainerDataTest`에 문제 은행 파싱·출처 문서 존재, 보상 아이템이 Cobblemon JAR에 있는지, 레벨캡 1~100 어디서나 보상이 하나 이상 있는지.
- **미확인:** 컴파일, 실게임 전부.

## [2026-10-08 15:50] 돌보미 스킨을 간호순으로 (구현만, 빌드·실게임 안 함)

- **스킨 출처:** 사용자가 구해 `build/skin-search/nurse-joy.png`에 둔 64×64 슬림 팔 스킨(팬 스킨, 라이선스는 사용자 판단). RCT 팩엔 간호사가 없고, Cobblemon의 `villager/profession/nurse_joy.png`는 주민 덧씌우기라 못 쓴다.
- **연결:** 모드가 직접 싣는 스킨은 `textures/npcs/wild/<이름>.png` + aspect `mcc_skin_<이름>`. 변형은 생성기가 덮어쓰는 `0_wild_trainer.json`이 아니라 같은 이름의 `10_wild_npc.json`에 둔다(Cobblemon이 같은 `name` 파일을 order 순으로 합친다). 슬림 팔이라 `cobblemon:alex.geo`. 대화창 이름표는 `WildTrainers.skinRef`가 이 aspect를 텍스처 경로로 바꾼다.
- `wild_caretaker`는 이제 간호순 스킨 두 장(`nurse_joy` = `build/skin-search/nurse-joy.png`, `nurse_joy_2` = `hamster.png`, 둘 다 슬림)을 무작위로 쓴다(사용자: "섞어서 나오게"). RCT 이름이 없어 이름표는 "포켓몬 돌보미"만 나온다.
- `WildTrainerDataTest`가 변형 폴더의 모든 파일을 읽게 고쳤다(실행 안 함).
- **주의(해결됨, 아래 17:30 항목):** 생성기가 손으로 만든 역할 NPC 파일을 지우던 문제는 고쳤다.

## [2026-10-08 15:20] 1~4단계 컴파일과 개발 클라이언트 확인 (`WildNpcRoleCheckHarness`)

- **빌드:** `:cobblemon-npc:build :jbro-policy:build :more-cobblemon-contents-league-challenge:build -x test -x unitTest` 성공.
- **하네스:** `MCC_NPC_ROLE_CHECK=1` + `--quickPlaySingleplayer npc-role-check`(`mcc-hub-capture` 복사본). 다른 세션이 MCC 테스트를 돌리는 중이라 `-x :more-cobblemon-contents:compileKotlin -x :more-cobblemon-contents:compileJava`로 띄웠다. 캡처 `run/screenshots/npc-role-*.png`, 판정 로그 `ROLE CHECK`. 클라이언트는 끝나면 스스로 닫힌다.
- **확인됨(실게임):** 돌보미 대화창(레터박스·이름표 얼굴) → 회복 후 파티 체력 전부 가득. 교환꾼이 파티에서 꼬렛을 원함 → 교환 결과 랑딸랑(uncommon, Lv.16 = 레벨캡으로 낮아짐), 어버이 `NPC` '교환꾼 Ivan', 오랭열매 1개 반환. [PC 좀 볼게] → `PCGUI` 열림. 야생 트레이너 대화창 [좋아, 승부하자! / 다음에 하자.]. 자연 스폰 7회 모두 `SpawnCause.entity`가 `ServerPlayer`.
- **스킨 의심은 오해였다:** 초록 머리는 브리더 RCT 텍스처 그대로다(돌보미가 브리더 스킨을 쓴다). 트레이너 캡처가 섞여 보인 건 하네스가 반바지 꼬마를 돌보미와 같은 자리에 세워 두 모델이 겹친 탓이라 6블록 앞으로 옮겼다. RCT 팩 v2.2는 텍스처 1559개 중 고유 그림이 174개로, 직업마다 그림이 하나다(같은 직업은 이름만 다르다). 교환 캡처는 NPC가 나무 안에 서서 카메라가 잎에 가렸다(하네스 배치 문제).
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
