# more-cobblemon-contents-battle-factory 작업 기록

## [2026-10-08 21:47] BP 재조정 묶음 테스트·배포 (빌드·JAR 배치 확인, 서버 기동·실게임 안 함)

- **테스트:** `unitTest` 통과. MCC는 전체 2161개 중 상점 테스트 1개만 실패해서 고친 뒤 그 클래스만 다시 돌렸다. 타워 192, 팩토리 111, 리그 93, jbro-policy 87, NPC 14개는 모두 통과했다. 바뀐 BP에 맞춰 고친 테스트는 세 개다. 타워 `bp_per_win` 1→2, 팩토리 첫 승 3→2, 상점 `ability_` 필터에서 특성가드(도구) 제외(`10e14da4`).
- **배포:** MCC·타워·팩토리·리그·jbro-policy·NPC JAR을 `develop-product/{server,client}/mods`에 넣었다(무결성·엔트리포인트 확인). NPC 대사 4개는 서버 `config/cobblemon_npc/dialogues/`에, 위키는 서버·클라이언트 `config/more-cobblemon-contents/wiki/`에 복사했다. 백업은 `develop-product/deployment-backups/20261008-214654-bp-rebalance`.
- **주의:** 다른 모듈은 `test`가 SKIPPED라서 테스트가 안 돈다. `unitTest`로 돌려야 한다. 테스트 필터는 `--tests` 대신 `-Ptests=<클래스명>`을 쓴다.

## [2026-10-08 21:25] 승리 BP를 원래보다 1씩 낮춘 2·3·4 (구현만, 빌드·테스트 안 함)

- **사용자 결정:** 원래 3·4·5(상한 5)에서 1씩만 낮춘다. 21:15에 "2/3/4…"를 상한 없이 계속 오르는 것으로 잘못 읽어 반영했다가(`291dab22`), 사용자가 지적해 상한 4로 고쳤다.
- **결과:** 21승 88BP(판당 4.2), 49승 230BP(4.7). 라운드 막판 +5, 헤드 +10은 그대로.
- **변경:** `FactoryRunSession.kt`(`VICTORY_BASE_BP = 1`, `VICTORY_MAX_BASE_BP = 4`), `FactoryVictoryRewardTest`, 위키 `factory.html`·`hub.html`.
## [2026-10-08 21:00] 더블 진입점 확인: 이미 없음

- **사용자 요청:** 팩토리 더블과 싱글 버튼을 UI에서 없애고 싱글을 기본으로 한다(코드는 남긴다).
- **확인:** 허브 탭 `FactoryHubTab.buildOptions`는 형식 선택 없이 싱글·오픈 레벨로만 시작한다(10-04 이전부터). 화면 문구·NPC 대화(`factory_guide`, `extra_factory_regular`)에도 더블 진입점이 없다. 남은 경로는 관리자 명령 `/mcc factory ... <format>`과, 클라이언트가 보내는 `Start` 의도의 형식 값뿐이다. 둘 다 그대로 뒀다.
- 위키 `factory.html`에서 더블 언급을 뺐다.

## [2026-10-08 20:50] 승리 BP 원래 값으로, 21·49승 보너스는 싱글·더블 똑같이 (구현만, 빌드·테스트 안 함)

- **사용자 결정:** 승리 3·4·5, 라운드 막판 +5, 21·49승 +10(바로 앞의 4·5·6 / +10 / +15 상향을 되돌림). 싱글과 더블은 똑같이 받아야 한다.
- **구현:** `victoryRewardBp(win)`에서 형식 인자를 뺐다. 21·49승 보너스는 `FACTORY_HEAD_BATTLES`만 본다. 팩토리 헤드 상대·난이도(`isFactoryHeadBattle`)는 여전히 싱글에만 나온다. 더블에도 헤드 상대를 낼지는 정하지 않았다.
- **결과:** 21승까지 109BP(판당 5.2), 49승까지 279BP(판당 5.7).
- **변경:** `FactoryRunSession.kt`, `FactoryBattleCompletionService.kt`, 테스트 2개, 위키 `factory.html`.

## [2026-10-08 19:20] 승리 BP 상향 (구현만, 빌드·테스트 안 함)

- **사용자 결정:** 라운드 기본값 +1(4/5/6), 라운드 마지막 판 +5 → +10, 팩토리 헤드 +10 → +15. 헤드 보너스는 여전히 싱글 21·49승에만 붙는다.
- **결과 수치:** 21승까지 150BP(판당 7.1, 전 109), 49승까지 373BP(판당 7.6). 4라운드부터는 라운드당 52BP.
- **변경:** `FactoryRunSession.kt` 상수와 주석, `FactoryVictoryRewardTest`, `FactoryBattleCompletionServiceTest`, 위키 `factory.html`·`hub.html`. 테스트는 돌리지 않았다.

## [2026-10-08 08:20] `/mcc factory access [player]` 추가

- **이유:** 팩토리도 타워처럼 리그 챔피언 제한이 있는데, NPC 대화가 이걸 물을 방법이 없었다(타워 안내원 문제와 같은 구멍, cobblemon-npc `MEMORY.md`). 대화 조건 `cmd:mcc factory access`로 터미널과 같은 규칙을 묻는다.
- **동작:** `BattleContentAccess.check(..., BATTLE_FACTORY, OPEN)`이 허용이면 1, 거부면 0과 사유. `/mcc factory` 아래라 관리자(권한 2)용이고, 대화 조건은 권한 2로 돌아서 일반 플레이어 대화에서도 쓸 수 있다.
- **빌드·테스트:** `:more-cobblemon-contents-battle-factory:build` 성공(테스트 111개 통과), JAR 무결성 확인.
- **배치:** `develop-product/server/mods`, `develop-product/client/mods`(백업 `develop-product/deployment-backups/20261008-tower-access`). 서버·게임 꺼진 상태.
- **실게임 검증:** 안 함.

