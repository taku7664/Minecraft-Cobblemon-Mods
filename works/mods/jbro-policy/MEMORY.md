# jbro-policy MEMORY

작업 내역과 이슈를 `[YYYY-MM-DD HH:MM]` 형식으로 기록한다. 최신 항목을 위에 추가한다.
구현, 빌드, JAR 배치, 서버 기동, 실게임 검증은 서로 다른 상태로 적는다.

---

## [2026-10-08 00:34] 새 알파 포켓몬의 기본 IV 최소 2개를 31로 보장

- **구현:** 커밋 `71c921ef`, `jbro-policy` 0.1.3. 새로 스폰한 알파는 일반 야생 IV 추첨 뒤 기존 31 개수를 세어, 서로 다른 능력치의 기본 IV가 최소 2개는 31이 되도록 부족한 개수만 올린다. 기존 31이 3개 이상이면 유지하고 나머지 값도 보존한다. 야생 IV 구간 추첨을 꺼도 적용하며, 기존 포켓몬을 일괄 보정하지 않는다.
- **빌드:** 전체 빌드, 테스트 87개, JAR 검증 통과. 빌드 중 C: 공간이 소진되어 실패한 빌드를 멈추고 이 작업트리의 `works/.gradle/loom-cache`만 `F:/AI/Temp/codex-ac66-loom-cache-20261008`로 이동한 뒤 원래 경로에 junction을 두어 약 1GB를 확보했다. 소스·실행 폴더는 이동하지 않았다. `'-Dfabric.loom.ci=true'`는 의존성 소스 준비만 생략했다.
- **개발 배포:** 실제 저장소 `C:/Users/박주형/Documents/GitHub/Cobblemon-Mods`의 `develop-product/server/mods`와 `develop-product/client/mods`에 0.1.3을 설치하고 0.1.2를 제거했다. 양쪽 SHA-256은 `D44EE3F15C92D0A71D0A32210C9A6CBD3C601F30131101557C2175F46584F58A`로 일치한다.
- **서버 기동 확인:** 빡대리님이 정상 종료·교체·재시작을 승인했다. 00:30:04 모든 차원 저장과 이전 프로세스·25566 포트 종료를 확인하고 교체했다. 숨김 `cmd`에서 `run.bat`으로 재시작한 뒤 Java PID 39360의 25566 수신과 `latest.log`의 `jbro_policy` 0.1.3, 00:33:32 `Done (2.040s)`를 확인했다.
- **후속 종료·최종 상태:** 빡대리님의 서버 끄기 지시로 PID 39360은 00:37 모든 차원 저장 후 정상 종료했다. 다른 런처(PID 41084)의 새 Java PID 44172에도 `stop`을 보냈으나, 00:40:19 `Saving worlds` 직후 `ConcurrentModificationException`과 `Exception stopping the server`가 나와 두 번째 종료의 월드 저장 완료는 확인하지 못했다. 최종 `Win32_Process` 조회에서 모든 Fabric 서버 Java와 런처 PID 5592·41084, Java PID 44172가 없고 25566 수신도 없어 서버 꺼짐을 확인했다. 종료 오류 원인과 수정은 검증하지 않았다.
- **남은 검증:** 실제 알파 포획과 IV 확인은 하지 않았다. 소스 커밋은 `codex/alpha-two-perfect-ivs`에 push했고 원격 동기화를 확인했다.

## [2026-10-07 13:00] 등급이 모자란 전설은 스폰·포켓내비 목록에서 뺌

- **빡대리님 결정:** 리그 등급으로 못 잡는 전설은 포켓내비 목록에도 안 뜨게 한다(엔트리 조건과 같은 취급). 전에는 등급이 포획만 막아서, 스폰되고 서버 알림까지 나간 뒤 볼이 거부됐다.
- **구현:** `LegendPolicy.mayMeet`에 `LegendRanks.check(...) == Allowed` 조건 추가. 플레이어 스포너, 포켓내비 스폰표, 포케스낵 후보가 모두 이 함수를 지난다. 등급을 못 읽으면(`Unknown`) 스폰도 막는다. 포획 때 검사는 스폰 뒤 등급 변화 대비로 남겼다. `/spawnpokemonfor`는 `mayMeet`을 안 거치므로 관리자 소환은 그대로.
- **확인:** Cobblenav 2.4.1 `SpawnDataHelper`가 `PlayerSpawner.getSelector().getProbabilities`로 목록을 만드는 것을 바이트코드로 확인. 이 경로가 `affectSpawnable`을 부르는지는 Cobblemon 소스로 따로 보지 않았다(기존 엔트리 조건과 같은 경로라는 전제).
- **함께 발견:** 커밋 c8dbefc9에 `RareSpawnNotice.kt`가 빠져 있어 깨끗한 체크아웃에서는 컴파일이 안 됐다. 원인은 루트 `.gitignore`의 `mods/`가 `works/mods/` 아래 새 파일을 전부 무시한 것. `!works/mods/` 예외를 넣고 빠진 파일(cobblemon-npc 3개 포함)을 3e85ec0a로 커밋했다.
- **검증:** `:jbro-policy:build` 테스트 85개 통과, 커밋 9d7038a6. 서버·클라이언트가 꺼진 상태에서 `jbro-policy-0.1.2.jar`(SHA-256 앞자리 ded8c12e)를 두 곳에 배치, 이전 JAR은 `develop-product/deployment-backups/20261007-legend-rank-spawn/`. 서버는 켜지 않았다. 등급 미달 플레이어의 포켓내비 목록은 실게임 미확인.

## [2026-10-06 08:20] 코라이돈·미라이돈·전설 패러독스를 고대·미래 차원으로, 울비·일반 패러독스 알림 (빌드·테스트 확인, 실게임 미확인)

- **빡대리님 결정:** 울트라비스트와 전설이 아닌 패러독스 14종은 Legend가 아니다(전설 파생이 아니면 풀어 줌). 몇 마리든 잡고 등급 제한도 없지만, 나타나면 알림은 간다. 코라이돈·미라이돈 엔트리는 패러독스 아무거나(고대·미래 구분 없음).
- **스폰:** `legendary_wild_spawns.json`에서 코라이돈은 `cobblemon_dimensions:ancient`의 고대 초원·고대 사막, 미라이돈은 `future`의 미래 평원·강철 산맥으로 옮겼다(오버월드 스폰 없음). 오늘 꺼 뒀던 전설 패러독스 6종을 차원 조건으로 다시 넣었다(굽이치는물결 고대 바다 물 위, 꿰뚫는화염 태고의 화산, 날뛰는우레 고대 초원, 무쇠잎새 네온 숲, 무쇠암석 수정 지대, 무쇠감투 강철 산맥; 레벨·가중치는 예전 값). 103종 모두 스폰.
- **카탈로그:** `LegendCatalog.PARADOXES`(20종)를 코라이돈·미라이돈 엔트리로. 코라이돈 ↔ 전설 패러독스가 고리를 이루므로, 엔트리 순환 테스트를 "Legend가 아닌 포켓몬에서 모든 Legend에 닿는가"로 바꿨다(any/all 의미 반영).
- **알림:** `RareSpawnNotice`가 `ultra_beast`·`paradox` 라벨이면서 Legend가 아닌 포켓몬의 스폰(플레이어 스포너, 포케스낵)을 하늘색으로 알린다. 포케스낵이 두 이벤트를 모두 지날 수 있어 persistentData 플래그로 한 번만 알린다.
- **위키:** `gen_legends.py`가 `PARADOXES` 엔트리와 차원 조건(`dimensions`), cobblemon-dimensions 바이옴 한국어 이름을 읽는다. 전설 페이지 차원 필터에 고대·미래 차원 추가. 끝의 `relative_to(REPO)` 오류(저장소 구조 변경 뒤 생긴 것)도 고쳤다. `legends.js` 재생성.
- **검증:** `:jbro-policy:build` 테스트 85개 통과. 커밋 c8dbefc9. 서버·클라이언트가 꺼진 상태에서 `jbro-policy-0.1.2.jar`(SHA-256 앞자리 e25496b2)를 두 곳에 배치, 이전 JAR은 `develop-product/deployment-backups/20261006-paradox/`. 서버는 켜지 않았다. 차원 조건이 붙은 전설 스폰과 알림은 실게임 미확인.

## [2026-10-05 16:20] 디스코드 `/위키` 개인 링크, `/전적` 분류 선택 (빌드·단위 테스트 확인, 디스코드 실사용 미확인)

- **빡대리님 지적:** 디스코드 `/위키` 링크로 들어가면 "내 정보"가 안 보인다. `/전적`은 한 번에 다 보여 줘서 난잡하니 리그챌린지·배틀팩토리·배틀타워·PvP로 나눈다.
- **원인:** 내 정보는 `/api/me`에 플레이어별 위키 토큰이 있어야 보이는데, 디스코드 `/위키`는 토큰 없는 공용 주소만 줬다.
- **`/위키`:** `/디코인증`으로 연동한 사람에게는 `WikiApi.sharedLinkFor`(MCC에 새로 연 API) 링크를 준다. 기존 `WikiApi.linkFor`는 쓰지 않는다. 오프라인 플레이어면 서버 안 포트(`localhost:8100`)를, 로컬 위키를 띄운 클라이언트면 그 PC의 `localhost` 주소를 주기 때문이다. 링크를 가진 사람은 누구나 그 플레이어 정보를 보므로 답은 모두 본인에게만 보이게(ephemeral) 했다. 연동하지 않은 사람은 공용 주소와 연동 안내를 받는다.
- **`/전적`:** 필수 옵션 `분류`(선택지 네 개)가 `닉네임` 앞에 온다. 고른 콘텐츠 카드만 보여 주고, 제목이 분류를 말하므로 카드 내용은 필드 대신 본문(description)에 넣었다.
- **검증:** `:jbro-policy:test`, `:more-cobblemon-contents:test --tests "*Wiki*"` 통과. 디스코드에서 명령을 실제로 쳐 보지는 않았다. 명령 정의가 바뀌었으니 서버를 다시 켜야 디스코드에 새 옵션이 등록된다.
- **함정:** 이 작업 중 `cobblemon-ui`의 빌드 산출물에서 `CobblemonUiOverlays.kt` 클래스가 통째로 빠졌는데 Gradle은 UP-TO-DATE로 봤다. MCC 컴파일이 `Unresolved reference 'CobblemonUiDialogScreen'`으로 깨지면 `./gradlew :cobblemon-ui:compileKotlin --rerun`으로 다시 컴파일한다.
