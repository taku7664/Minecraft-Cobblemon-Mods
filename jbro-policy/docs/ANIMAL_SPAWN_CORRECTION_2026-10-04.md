# 기본 동물 차단 방식 정정

2026-10-03의 `VANILLA_ANIMAL_BLOCK_DEPLOYMENT_2026-10-03.md`는 당시 배포 기록이다. 그 문서에서 `spawn-animals=false`가 포켓몬도 제거한다고 판단한 부분은 잘못됐다. Cobblemon의 `MinecraftDedicatedServerMixin`은 `PokemonEntity`를 `shouldDiscardEntity`에서 예외 처리한다.

2026-10-04에 Jbro Policy의 기본 동물 차단 믹스인을 제거했다(소스 커밋 `0bcaff28`). 개발 서버에는 `jbro-policy-0.1.1.jar`를 배포했고 SHA-256은 `9E132CEBA5C2F0C88DBF7BC46DC37A865B32D66996D044D8D1BE0A02EB2F2471`이다. 개발 서버 훅과 `server.properties`는 `spawn-animals=false`로 설정했다.

개발 서버 실행 중 `doMobSpawning=false`를 확인했다. 로드된 청크에서 명령으로 생성한 피카츄는 다음 틱 이후에도 조회됐고, 젖소는 생성 직후 조회됐으나 약 10초 뒤 조회되지 않았다. 같은 위치의 marker는 남아 있어 청크가 로드된 상태였다. 테스트 marker와 강제 로드는 제거했다. 자연 발생 포켓몬의 생성 빈도와 운영 서버의 실제 동작은 이번 실험에서 확인하지 않았다.

운영 서버에는 Jbro Policy가 설치되어 있지 않다. 서버가 정지한 상태에서 `startup-hooks.json`과 `server.properties`의 `spawn-animals=false`를 확인했다. 전체 시작 훅은 별도 Xaero 설정 검사 오류로 중단됐고 운영 서버는 기동하지 않았다.

근거: [Cobblemon `MinecraftDedicatedServerMixin`](https://github.com/Cobblemon-Global/Cobblemon/blob/main/common/src/main/java/com/cobblemon/mod/common/mixin/MinecraftDedicatedServerMixin.java).
