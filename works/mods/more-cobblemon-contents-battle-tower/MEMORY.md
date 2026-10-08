# more-cobblemon-contents-battle-tower 작업 기록

## [2026-10-08 21:56] main 병합 후 빌드·개발 배포 재확인

- **병합:** 기믹 API 브랜치를 현재 main과 병합한 `5d4afd558bd3c1fa63e7d81d47c568ff5d564933`을 main에 푸시했다. 기존 BP 테스트 기대값 수정 `3c247482`·`10e14da4`도 포함한다. 앞선 브랜치 배포 기록은 이번 main 검증으로 보완하며 이전 실패 기록은 보존한다.
- **빌드·테스트:** 깨끗한 워크트리에서 현재 main 소스로 MCC 5개 모듈 빌드 성공(2분 19초). 타워 테스트 192개 통과. 이전 배포에서 실패했던 BP 기대값 검사는 main의 기존 수정 반영 후 통과했다.
- **배치:** main 빌드본의 SHA-256이 앞서 배치한 JAR과 같아 재복사하지 않았다. 실제 저장소 `develop-product/server/mods`·`develop-product/client/mods`의 5개 JAR 모두 main 빌드본과 해시 일치, 중복 Fabric ID·`.deploying` 잔여물 없음. JDK 21 `jar --validate`·ZIP CRC 재검사 통과. 백업·manifest는 실제 저장소 `build/mcc-gimmick-deployment/20261008-215627-main/`에 있다.
- **미확인:** 서버·게임이 꺼진 상태에서 확인했다. 서버 기동·실게임 검증은 하지 않았으며 `deploy-product` 릴리스도 아니다.

## [2026-10-08 21:48] 공통 배틀 기믹 API 전환 JAR 개발 배포

- **구현:** `e8f428ea`에서 타워의 기존 단일 기믹 선택을 공통 `mechanicFlags: Int`로 변환해 PvE 배틀 API에 전달한다. 타워 선택 규칙 자체를 다중 선택으로 바꾸지는 않았다.
- **테스트:** 192개 중 191개 통과, BP 보상 테스트 1개 실패(기대 1, 실제 2). 전체 테스트 통과로 기록하지 않는다.
- **빌드·무결성:** 0.1.0 JAR 생성, JDK 21 `jar --validate`와 ZIP CRC 검사 통과.
- **배치:** 실제 저장소 `C:/Users/박주형/Documents/GitHub/Cobblemon-Mods/develop-product/server/mods`와 `develop-product/client/mods`에 배치했다. 원본·서버·클라이언트 SHA-256 일치, 중복 Fabric ID와 `.deploying` 잔여물 없음 확인. 배치 전 서버·게임 프로세스 및 25565/25566 리스너가 없었다. 이전 JAR·manifest는 실제 저장소 `build/mcc-gimmick-deployment/20261008-214821/`에 보관했다.
- **미확인:** 서버 기동·실게임 검증은 하지 않았다. `deploy-product` 릴리스가 아니라 개발 배포다.

## [2026-10-08 21:47] BP 재조정 묶음 테스트·배포 (빌드·JAR 배치 확인, 서버 기동·실게임 안 함)

- **테스트:** `unitTest` 통과. MCC는 전체 2161개 중 상점 테스트 1개만 실패해서 고친 뒤 그 클래스만 다시 돌렸다. 타워 192, 팩토리 111, 리그 93, jbro-policy 87, NPC 14개는 모두 통과했다. 바뀐 BP에 맞춰 고친 테스트는 세 개다. 타워 `bp_per_win` 1→2, 팩토리 첫 승 3→2, 상점 `ability_` 필터에서 특성가드(도구) 제외(`10e14da4`).
- **배포:** MCC·타워·팩토리·리그·jbro-policy·NPC JAR을 `develop-product/{server,client}/mods`에 넣었다(무결성·엔트리포인트 확인). NPC 대사 4개는 서버 `config/cobblemon_npc/dialogues/`에, 위키는 서버·클라이언트 `config/more-cobblemon-contents/wiki/`에 복사했다. 백업은 `develop-product/deployment-backups/20261008-214654-bp-rebalance`.
- **주의:** 다른 모듈은 `test`가 SKIPPED라서 테스트가 안 돈다. `unitTest`로 돌려야 한다. 테스트 필터는 `--tests` 대신 `-Ptests=<클래스명>`을 쓴다.

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
