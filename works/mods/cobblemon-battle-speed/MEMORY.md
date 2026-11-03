# cobblemon-battle-speed 작업 기록

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
