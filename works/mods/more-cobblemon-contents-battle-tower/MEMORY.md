# more-cobblemon-contents-battle-tower 작업 기록

## [2026-10-08 07:55] `/mcc tower access [player]` 추가

- **이유:** NPC 타워 안내원 대화가 타워 입장 조건을 물을 방법이 없어 아무도 안 붙이는 태그를 보고 있었다(cobblemon-npc `MEMORY.md`). 대화 조건 `cmd:mcc tower access`로 터미널과 같은 규칙을 묻게 한다.
- **동작:** `BattleContentAccess.check(..., BATTLE_TOWER, OPEN)`이 허용이면 1, 거부면 0과 거부 사유를 돌려준다. `/mcc tower` 아래라 관리자(권한 2)용이다. 대화 조건은 권한 2로 실행되므로 일반 플레이어 대화에서도 쓸 수 있다.
- **빌드:** `:more-cobblemon-contents-battle-tower:build -x test` 성공, JAR 무결성 확인.
- **배치:** `develop-product/server/mods`, `develop-product/client/mods`에 배치(백업 `develop-product/deployment-backups/20261008-tower-access`). 서버·게임 모두 꺼진 상태에서 복사했다.
- **실게임 검증:** 안 함. 서버를 켠 뒤 챔피언 계정으로 안내원에게 말을 걸어 터미널이 열리는지 봐야 한다.
