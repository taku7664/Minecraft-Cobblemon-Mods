# MEMORY — more-cobblemon-contents-league-challenge

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
