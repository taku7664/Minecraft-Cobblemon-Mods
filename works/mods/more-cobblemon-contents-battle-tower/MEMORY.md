# more-cobblemon-contents-battle-tower 작업 기록

## [2026-10-08 20:50] 승리 BP를 모드별로 다시 정함 (구현만, 빌드·테스트 안 함)

- **사용자 결정:** 100BP 품목이 약 20판이 되도록 낮춘다. 일반 모드는 반복할 수 있는 콘텐츠(클리어 뒤 연승 0, 일반 모드는 계속 선택 가능, 클리어 보너스에 첫 클리어 조건 없음)라서 승리 2 고정, 클리어 +10은 매번 준다. 무한 모드는 2부터 10승마다 +1. 타워 에이스 +5, 챔피언 +10, 무한 10승마다 +10은 유지. 싱글·더블은 같은 공식이다.
- **구현:** `TowerProgression.bpPerWin(mode, win)`과 `TOWER_BASE_BP`를 두고, `TowerStreakStage`에서 `bpPerWin`을 뺐다. 구간은 이제 난이도·상대 선택에만 쓴다. 바로 앞 커밋의 "21승부터 5승마다 +1"은 이 공식으로 대체됐다.
- **결과:** 일반 20연승 80BP(판당 4.0), 무한 30층까지 180BP(판당 6.0), 50층까지 판당 7.0.
- **변경:** `TowerProgression.kt`, `TowerStreakRules.kt`, 테스트 3개, 위키 `tower.html`.

## [2026-10-08 19:45] 보스 보너스를 타워 에이스 +5, 챔피언 +10으로 나눔 (구현만, 빌드·테스트 안 함)

- **사용자 결정:** 일반 모드 5·15연승째 타워 에이스는 +5, 10·20연승째 챔피언과 무한 모드의 모든 보스(전부 챔피언)는 +10.
- **구현:** `rewardForNextVictory`가 `nextBossIsChampion`으로 갈라 `TOWER_ACE_BP_BONUS`·`TOWER_CHAMPION_BP_BONUS`를 쓴다(옛 `TOWER_BOSS_BP_BONUS` 제거).
- **결과:** 일반 클리어 125BP(판당 6.25), 무한 30승까지 210BP(판당 7.0). 무한 모드는 30층이면 충분하다는 사용자 판단으로 승당 BP 상한을 두지 않았다.
- **변경:** `TowerProgression.kt`, `TowerProgressionTest`, `TowerPlaySessionServiceTest`, 위키 `tower.html`·`hub.html`.

## [2026-10-08 19:20] 승리 BP 상향 (구현만, 빌드·테스트 안 함)

- **사용자 결정:** 구간 기본값을 모두 +1(입문 2, 실전 3, 고급 4, 프로 5)하고, 21승부터는 5승마다 1씩 계속 올린다(21~25승 5, 26~30승 6 …). 상한은 두지 않았다. 보스 +5, Normal 클리어 +30, 무한 10승마다 +10은 그대로다.
- **구현:** `TowerProgression.bpPerWin(win)`을 새로 두고 `rewardForNextVictory`가 이를 쓴다. `TowerStreakStage`는 난이도·상대 선택에도 쓰여 구간 경계는 바꾸지 않았다.
- **결과 수치:** Normal 한 번 115BP(판당 5.75, 전 95). 무한 30승까지 판당 6.0, 50승까지 7.8, 91~100승 구간은 판당 21.5.
- **변경:** `TowerStreakRules.kt`, `TowerProgression.kt`, 테스트 3개(`TowerStreakRulesTest`, `TowerProgressionTest`, `TowerPlaySessionServiceTest`), 위키 `tower.html`·`hub.html`. 테스트는 돌리지 않았다(빌드는 요청 시에만).

## [2026-10-08 07:55] `/mcc tower access [player]` 추가

- **이유:** NPC 타워 안내원 대화가 타워 입장 조건을 물을 방법이 없어 아무도 안 붙이는 태그를 보고 있었다(cobblemon-npc `MEMORY.md`). 대화 조건 `cmd:mcc tower access`로 터미널과 같은 규칙을 묻게 한다.
- **동작:** `BattleContentAccess.check(..., BATTLE_TOWER, OPEN)`이 허용이면 1, 거부면 0과 거부 사유를 돌려준다. `/mcc tower` 아래라 관리자(권한 2)용이다. 대화 조건은 권한 2로 실행되므로 일반 플레이어 대화에서도 쓸 수 있다.
- **빌드:** `:more-cobblemon-contents-battle-tower:build -x test` 성공, JAR 무결성 확인.
- **배치:** `develop-product/server/mods`, `develop-product/client/mods`에 배치(백업 `develop-product/deployment-backups/20261008-tower-access`). 서버·게임 모두 꺼진 상태에서 복사했다.
- **실게임 검증:** 안 함. 서버를 켠 뒤 챔피언 계정으로 안내원에게 말을 걸어 터미널이 열리는지 봐야 한다.
