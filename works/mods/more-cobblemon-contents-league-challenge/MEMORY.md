# MEMORY — more-cobblemon-contents-league-challenge

## [2026-10-05 22:25] 관리자 테스트 브금, 하드 난천 로즈레이드, 서버 크래시

- **브금(43f5ec45):** `/mcc league test`는 Better AI 판단 로그 때문에 콘텐츠 ID를 `more_cobblemon_contents:ai_test`로 띄운다. 이 ID가 클라이언트 태그로도 가서 Better Cobblemon Music이 리그 키를 못 찾고 일반 트레이너 곡으로 떨어졌다. MCC `ManagedPveBattles.Request.clientTag`를 추가했고, 테스트 명령은 `league_challenge / <stage> / <challengeId>` 태그를 넘긴다. 정식 루트(`LeagueServer.launch`)는 원래 맞는 키를 보낸다.
- **팀(379b0b62):** `teams/cynthia_hard.json` 로즈레이드의 `spikes`(압정뿌리기)를 `gigadrain`으로 바꿨다. 사용자 지시: 풀 공격기가 없었다.
- **크래시:** 다른 세션이 21:47에 공유 작업 트리에서 MCC JAR을 빌드해 배포하면서 커밋 전이던 `clientTag` 수정이 들어갔다. 서버 리그 JAR은 옛 `Request` 생성자를 불러 `/mcc league test`에서 `NoSuchMethodError`로 서버가 죽었다(정식 리그 전투도 같은 경로였다). 이 애드온만 `ManagedPveBattles.Request`를 쓴다.
- **배포:** HEAD(379b0b62)를 scratchpad에 풀어 빌드한 리그 JAR을 `develop-product/{server,client}/mods`에 넣었다. 백업은 `develop-product/deployment-backups/2026-10-05_2225-league-clienttag`. MCC JAR은 21:47 배포본을 그대로 두었고, `javap`로 리그 JAR의 생성자 호출과 시그니처가 맞는 것을 확인했다.
- **미확인:** 서버 재기동, 실게임에서 난천 브금 재생과 기가드레인 사용은 확인하지 않았다.
- **재발 주의:** MCC의 공개 API(`Request` 같은 data class) 시그니처를 바꾸면 이 애드온 JAR을 같이 배포해야 한다.
