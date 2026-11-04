# cobblemon-battle-speed 작업 기록

## [2026-11-04 09:15] 0.3.0: 포켓몬 모델 애니메이션 배속 (실게임 미확인)

- **요청:** 사용자가 실게임에서 애니메이션이 빨라지는지 모르겠다고 했다. 0.2.0까지는 서버 대기·체력바·HUD 타일만 빨라지고 포켓몬 모델 동작은 원래 속도였다.
- **원리(Cobblemon 1.8.1 소스·바이트코드 확인):** 모델 애니메이션 시간은 `PosableState.animationSeconds = (age + partialTicks) / 20`이고, `PokemonClientDelegate.tick(PokemonEntity)`가 매 tick `incrementAge()`로 `age`를 1 올린다. `incrementAge`는 파티클·효과음(`tickEffects`)과 주 애니메이션 종료 판정도 함께 돌린다. 클라이언트는 엔티티의 `battleId`(동기화 데이터)를 안다.
- **구현:** `PokemonClientDelegateMixin`이 `tick` 끝에서 배속 배틀(`battleId`가 서버가 보낸 배틀 ID와 같음)의 포켓몬에게 `incrementAge`를 (배속 - 1)번 더 부른다. 소수 배속은 나머지를 다음 tick으로 넘긴다. `updatePartialTicks`도 같은 비율로 늘려 tick 사이가 끊기지 않게 했다. `tick`은 브리지 메서드 `tick(Entity)`가 있어 `PokemonEntity` 디스크립터로 지정했다. 배속 패킷에 `modelSpeed`를 추가했다(0.2.0과 패킷 형식이 달라 서버·클라 같은 버전 필요). 설정은 `apply.modelAnimations`이고 화면의 "빨라지는 부분"에도 넣었다.
- **한계:** 몬스터볼 비행, 회수, 돌진 이동, 기술 파티클 자체 속도는 그대로다. 관전자가 아닌 주변 플레이어에게는 패킷이 가지 않아 1배속 모델로 보인다.
- **빌드:** 단위 테스트 24개 통과. 임시 서버에서 0.3.0 기동, `reload`, 새 설정 파일에 `modelAnimations` 생성 확인. 클라이언트 mixin은 서버 기동으로 확인할 수 없다.
- **미확인:** 실게임에서 모션이 빨라지는지, 고배속에서 모션이 튀거나 대기 동작이 어색한지.

## [2026-11-03 21:15] 0.2.0 개발 배치와 커밋 실수 (실게임 화면 미확인)

- **커밋 실수:** `ddaae2216`에서 `config/` 소스 패키지를 넣으려고 `git add -f works/mods/cobblemon-battle-speed`를 쓰는 바람에, 무시되던 `build/` 정션 안의 class·JAR·임시 파일 77개가 같이 들어가 `main`에 푸시됐다. 바로 `4f2eac401`로 지웠다(강제 푸시는 하지 않음, 이력에는 남아 있다). 강제 추가는 `config/` 아래 파일 경로만 지정할 것.
- 병합: `main` = `4f2eac401`. 남은 모듈 파일 39개는 빌드에 쓴 작업 트리 소스와 내용이 같고, 루트 `build.gradle.kts`·`gradle/`·`gradlew`도 같아서 빌드본을 재사용했다.
- `develop-product/{server,client}/mods/cobblemon-battle-speed-0.2.0.jar` 설치. SHA-256 `10b716506c49c14b74d388c287072f0d8ab1c56e486a155de6b2360bcb4b27f2`로 빌드본과 같고 `jar --validate` 통과. 이전 0.1.0은 `develop-product/deployment-backups/20261103-battle-speed-0.2.0/{server,client}`로 옮겼다. 배치 전 java 프로세스·25566 리스너 없음 확인. 개발 클라이언트에 Mod Menu 11.0.4·Cloth Config 15.0.140 있음. `recordDecisions`는 `true`.
- 개발 서버는 꺼진 그대로다. PPakemon·deploy-product·운영·VPS는 그대로다.

## [2026-11-03 21:05] 0.2.0: Mod Menu 설정 화면 (실게임 화면 미확인)

- **경위:** 사용자가 Mod Menu에서 설정 버튼이 없는 걸 보고 지적했다. 앞서 "설정 화면은 만들지 않았다"고 답하고 만들지 묻는 질문을 남겼는데, 사용자는 권한 질문에만 답해 그대로 넘어갔다. 화면을 만들지 묻는 질문은 다른 답에 묻히지 않게 따로 확인할 것.
- **구조:** 설정은 서버에 있으므로 화면도 서버를 거친다. 접속 시·단계 변경 시·설정 적용 시 서버가 `sync`(내 단계 + 설정 JSON)를 보낸다. 화면의 "내 배속"은 `set_level`을 보내 명령어와 같은 `BattleSpeedService.changeLevel`을 탄다. "서버 설정"은 `permissions.reload` 권한이 있을 때만 보이고, 저장하면 설정 JSON 전체를 `save_config`로 보낸다. 서버는 권한을 다시 확인하고 `fromJson`으로 정규화한 뒤 임시 파일 경유로 저장하고 `applyConfig`(배틀 배속 재전송 + 전원 sync)한다. 화면에서 숨기는 건 겉모양일 뿐이고 권한 판단은 서버가 한다.
- Mod Menu·Cloth Config는 `modCompileOnly` 선택 의존성이다. Cloth가 없으면 안내 화면만 뜬다. 아이콘(빨리감기 + 단계 막대)과 Mod Menu 한국어 이름·설명(`modmenu.*Translation.*`)도 넣었다.
- **빌드:** `:cobblemon-battle-speed:unitTest :cobblemon-battle-speed:remapJar` 성공, 단위 테스트 24개 통과(설정 JSON 왕복·저장 테스트 추가).
- **서버 기동:** 임시 폴더에서 0.2.0으로 `Done`, 콘솔 `reload` 성공, 서버 mixin 4개 적용 확인. 임시 폴더는 지웠다.
- **미확인:** 실제 클라이언트에서 설정 버튼·화면이 열리는지, 저장이 서버에 반영되는지.

## [2026-11-03 16:50] 개발 서버·클라이언트 배치 (서버 기동·실게임 미확인)

- `feature/battle-speed`를 `origin/main`에 병합해 `8d76fea5f`로 푸시했다(충돌 없음). 로컬 `main`은 다른 워크트리(`F:\AI\worktrees\codex-legend-despawn-20261102`)가 체크아웃 중이라 옮기지 않았다.
- 빌드본 재사용: 모듈 소스·`settings.gradle.kts`·`gradle.properties`는 브랜치와 병합 `main`이 같고, 루트 `build.gradle.kts`·`gradle/`·`gradlew`는 빌드 당시 체크아웃 `aa0fab224`와 병합 `main`이 같다. 빌드 뒤 바뀐 건 JAR에 안 들어가는 README·MEMORY뿐이다.
- `develop-product/{server,client}/mods/cobblemon-battle-speed-0.1.0.jar` 새로 설치. SHA-256 `c9cc7a75e736749ee4d349f2d7eee57c2033f304b2ea29b703c97f7550e026ea`로 빌드본과 세 곳 모두 같고 `unzip -t` 통과. 새 파일이라 백업할 이전 JAR은 없다. 배치 전 25566 리스너·java 프로세스 없음 확인. 개발 서버 `openrouter.json`의 `recordDecisions`는 `true`.
- 서버·클라이언트 둘 다 필요하다. 서버만 있으면 진행은 빨라지지만 체력바가 원래 속도로 줄고, 클라이언트만 있으면 아무 일도 안 일어난다.
- 개발 서버는 꺼진 상태 그대로 두었다. PPakemon 프로필·deploy-product·운영·VPS는 그대로다.
- **미확인:** 개발 서버 기동, 실게임 배틀 배속, 클라이언트 mixin 적용.

## [2026-11-03 16:20] 새 모드: PvE 배틀 진행 배속 (실게임 미확인)

- **요청:** 애니메이션이 아니라 실제 진행이 빨라지는 배틀 배속. PvE만, 최대 4배속, 1·2·3·4단계. 전부 JSON으로 조정하고 명령어로도 바꿀 수 있게. 다른 모드에 넣지 말고 따로 모드로.
- **원리(Cobblemon 1.8.1 소스·바이트코드 확인):** 배틀 계산은 즉시 끝나고, 느린 건 서버가 일부러 기다리기 때문이다. 클라이언트 메시지 큐는 진행을 막지 않는다. 대기는 세 군데에서 생긴다.
  - `WaitDispatch(초)`: `dispatchWaiting` 고정 대기. Cobblemon 명령어 쪽에서 1.5초 17곳, 2초 4곳, 2.5초·0.5초 각 1곳. Mega Showdown도 `dispatchWaiting`/`dispatchWaitingToFront`만 쓴다(`UntilDispatch`는 조건이 항상 true).
  - `ScheduledTask`: `afterOnServer`·`delayedFuture`(기술 연출 `pause`/`animation` 키프레임 딜레이, 기절 대기, 내보내기 간격, 돌진 timeout). `Schedulable.after`는 기본값 생성자를 거쳐 본 생성자로 가므로 본 생성자 하나만 잡으면 한 번만 줄어든다.
  - 기술 연출의 두 번째 키프레임부터는 앞 키프레임 future가 끝날 때 `ActionEffectTimeline.chainKeyframes`에서 시작된다. 배틀 tick 밖이지만 context `providers`에 `PokemonBattle`이 들어 있다.
- **구현:** `PokemonBattle.tick`과 `chainKeyframes`를 `@WrapMethod`로 감싸 스레드별 `SpeedScope`에 현재 배틀을 쌓는다(예외가 나도 `finally`로 빠짐). 그 안에서 만들어지는 `WaitDispatch.readyTime`, `ScheduledTask.secondsRemaining`을 배속으로 나눈다. 반복 간격·`lerp`는 건드리지 않는다. 클라이언트는 서버가 보낸 `BattleSpeedPayload`(배틀 ID, 배속)로 `HealthChangeAnimation`·타일 이동 애니메이션의 `deltaTicks`를 곱한다. 우리 `cobblemon-ui` HUD도 `BattleOverlay.drawTile` → `animate`를 그대로 거쳐 같은 `hpValue`를 읽으므로 같이 빨라진다.
- **PvE 판정:** `ActorType`으로 본다. 플레이어가 한쪽에만 있으면 PvE, 양쪽이면 PvP. MCC 트레이너처럼 플레이어도 야생도 아닌 행위자는 NPC로 친다. 같은 편 플레이어가 여럿이면 `multiplePlayers`(기본 `slowest`). PvP는 `battles.pvp`가 켜져도 항상 가장 느린 선택.
- **배치 결정:** 플레이어 선택은 `config/` 밖 `world/data/cobblemon_battle_speed_players.json`에 둔다(훅 상태를 config에 두지 않는 규칙). 명령어 이름은 Brigadier가 한글 literal을 받지 않아 `battlespeed`. 안내 문구는 `translatableWithFallback`이라 클라이언트에 모드가 없어도 한국어 대체 문구가 나온다.
- **브랜치:** 공용 체크아웃이 `feature/pichu-wiki`(main 미병합)에 있고 다른 세션도 써서, HEAD·인덱스를 건드리지 않고 임시 인덱스로 `main` 기준 `feature/battle-speed`에 커밋했다.
- **빌드:** `:cobblemon-battle-speed:unitTest :cobblemon-battle-speed:remapJar` 성공(Gradle 쓰기 경로 F 검사 init 스크립트 사용, `build/`는 `F:\AI\caches\cobblemon-works-build\cobblemon-battle-speed` 정션). 단위 테스트 23개 통과. JAR `jar --validate`·`unzip -t` 통과.
- **서버 기동 확인:** 개발 서버와 별개인 스크래치 폴더에 개발 서버의 로더·라이브러리·Cobblemon·Fabric API·FLK와 이 JAR만 넣고 25599 포트 평지 월드로 띄웠다. `Done`까지 크래시 없음, `config/cobblemon_battle_speed.json` 생성, 콘솔 `battlespeed reload` 성공. `-Dmixin.debug.export=true`로 다시 띄워 `PokemonBattle`·`ActionEffectTimeline`·`ScheduledTask`·`SpectateBattleHandler`에 배속 메서드가 들어간 것을 확인했다. `WaitDispatch`는 첫 배틀 때 로드되는 클래스라 이 기동에서는 적용을 보지 못했다(대상 생성자 `(F)V`와 `readyTime` 필드는 javap로 일치 확인). 클라이언트 mixin 3개는 서버라 확인하지 못했다. 임시 폴더는 지웠다. 첫 시도는 `.fabric` 캐시 없이 런처가 라이브러리를 받다 멈추며 `libraries`의 로더 JAR이 깨졌다. 다음에 임시 서버를 만들 때는 `.fabric`까지 복사한다.
- **배치:** 하지 않았다. develop-product·PPakemon·운영·VPS 모두 그대로다.
- **미확인:** 실제 배틀에서 2~4배속으로 빨라지는지, 고배속에서 엔티티 동작(몬스터볼 비행·회수·돌진)과 진행이 어긋나 보이는 정도, battlecam 카메라 전환이 급해 보이는지, 관전자 HUD.
