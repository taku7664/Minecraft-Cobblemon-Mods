# more-cobblemon-contents-battle-tower 작업 기록

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
