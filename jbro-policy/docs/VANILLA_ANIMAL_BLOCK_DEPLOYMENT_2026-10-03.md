# 기본 동물 신규 생성 차단 적용 기록

2026-10-03. `doMobSpawning=false`와 별개로 새로 추가되는 기본 동물을 전용 서버의 Jbro Policy에서 차단한다.

> 2026-10-04 경로 변경: 현재 서버는 저장소의 `develop-product/server` 하나다. 아래의 옛 서버 경로와 백업은 당시 적용 기록이며, 현재 점검·배포 대상이 아니다. 현재 설정 기준은 [서버 오픈 체크리스트](SERVER_OPEN_CHECKLIST.md)를 따른다.

- 소스 커밋: `58fe2cc9`.
- 당시 대상: `C:\Users\박주형\Documents\GitHub\Cobblemon-Mods\dev-server`.
- 교체한 파일: `mods/jbro-policy-0.1.0.jar`. 빌드 결과와 배포 파일의 SHA-256은 모두 `9BCBED78D0BDA88ABF18B6139D511622CCCF7BED0DF055A6C3683586FFF393D0`이다.
- 이전 JAR 백업: `deployment-backups/20261003-vanilla-animal-block/jbro-policy-0.1.0.jar.before`.
- `:jbro-policy:remapJar --offline` 성공. 교체 후 개발 서버가 정상 기동했다.
- 로드된 청크에서 `minecraft:marker`는 생성·조회됐고, `minecraft:cow`와 `minecraft:salmon`은 `NoAI`·`NoGravity` 상태로 소환해도 개체 조회에 잡히지 않았다. 테스트 marker와 갑옷 거치대를 제거하고 강제 로드를 해제했다.
- 기존에 저장된 동물은 이 변경으로 삭제되지 않는다. 포켓몬은 `minecraft` 네임스페이스가 아니므로 차단 조건에 해당하지 않지만, 실제 포켓몬 신규 스폰은 이번 점검에서 따로 확인하지 않았다.
- 운영 서버 `C:\Users\박주형\Documents\GitHub\Mincraft-Cobblemon-Server`에는 Jbro Policy가 설치되어 있지 않아 이 JAR을 추가하지 않았다.
