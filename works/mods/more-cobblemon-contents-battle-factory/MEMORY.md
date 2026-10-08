# more-cobblemon-contents-battle-factory 작업 기록

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

