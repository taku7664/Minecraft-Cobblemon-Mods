# cobblemon-party-sidebar 작업 기록

## [2026-10-12 04:50] 빌드·개발 배포 (실게임 미확인)

- **빌드:** 사용자 지시("빌드해서 배포해"). main `39f45541`(모듈 파일 미수정 상태)에서 `:cobblemon-party-sidebar:build -x test`. `GRADLE_USER_HOME=F:/AI/caches/gradle`, `--project-cache-dir`·TEMP·`java.io.tmpdir`는 `F:/AI/builds/cobblemon-mods-main/`, 출력은 같은 폴더의 `f-build-dirs.init.gradle`로 `out/cobblemon-party-sidebar`. 첫 시도에 성공(1분 46초). `unzip -t` 통과, `fabric.mod.json`의 진입점·mixin·EMI 플러그인 클래스가 모두 JAR에 있음. refmap은 없고 mixin 대상 이름이 intermediary로 직접 리매핑돼 있다(jbro-policy와 같은 방식).
- **배치:** 마인크래프트 프로세스·25565/25566 수신 없음을 확인하고 `develop-product/server/mods`·`client/mods`에 새로 넣었다(기존 파일 없어 백업 없음). 세 곳 SHA-256 `99581a2a4f246df5cda3231266831df927875dd2383e98cf77dd9c8a36b02a88`. PPakemon 프로필·운영·VPS는 건드리지 않았다(서버 절반이 필요해 운영 서버 없이 넣으면 표시와 순서 교체만 된다).
- **미확인:** 서버·클라이언트를 켜지 않아 실게임 동작은 하나도 보지 않았다.

## [2026-10-12 04:43] 모듈 신설 (구현만, 컴파일·빌드·배치·실게임 미실행)

- **사용자 요청:** 서바이벌 인벤토리 왼쪽에 파티 6마리를 세로로, 한 줄은 `[모델][지닌 물건]`. 모델은 PokemonToItem처럼, 지닌 물건은 인벤토리와 드래그 앤 드롭. 크리에이티브 제외, 빈 파티 칸은 빈 슬롯 모양, 모델끼리 드래그하면 순서 교체(지닌 물건도 따라감), 모델 호버 툴팁(레벨·이름·성별, HP, 기술 4개, 능력치별 개체값·노력치)에 성격·특성 추가. 이름은 사용자가 `cobblemon-party-sidebar`로 정했다.
- **클라·서버 양쪽 모드인 이유:** 사용자는 처음에 클라이언트 전용으로 생각했다. Cobblemon 1.8.1 서버행 패킷 핸들러를 전부 확인했는데 지닌 물건을 바꾸는 패킷은 없다(`SetItemHiddenHandler`는 표시 여부만). `Pokemon.swapHeldItem`을 클라이언트에서 부르면 로컬 사본만 바뀐다. 그래서 `HeldItemClickPayload` 하나를 두고 서버(`HeldItemExchange`)가 커서 아이템과 교체한다. 순서 교체는 Cobblemon의 `SwapPartyPokemonPacket`/`MovePartyPokemonPacket`을 그대로 쓴다(빈 칸으로는 Move, Swap은 양쪽이 차 있어야 받아 준다).
- **모델:** PokemonToItem은 클라이언트 코드가 없고 Cobblemon `PokemonItem.from(...)` + 내장 `PokemonItemRenderer`를 쓴다. 같은 방식으로 `renderItem`한다. 기절한 포켓몬은 회색 틴트.
- **서버 규칙:** 인벤토리 메뉴가 열린 상태(`containerMenu === inventoryMenu`)에서만, 배틀 중 거부, 엔티티 `isBusy` 거부, Cobblemon 엔티티 `offerItem`과 같은 held 태그 검사·같은 아이템 무시, 커서 스택에서 1개만 준다. 돌려받은 아이템은 커서가 비면 커서로, 아니면 인벤토리(가득 차면 떨굼). 시프트 클릭은 빈 칸이 없으면 거부. `HeldItemEvent` 취소는 결과로 판별한다.
- **화면:** `InventoryScreen` mixin. `renderBg` 끝에서 칸을 그려 커서 아이템 아래에 두고, `render` 끝에서 툴팁·드래그 중 모델을 그린다. 레시피 책이 열리면 책 왼쪽으로 비키고, 좁은 화면이나 자리가 없으면 숨긴다. 인벤토리에서 시작한 드래그를 칸에 놓으면 바닐라 `isQuickCrafting`을 끈다. 칸에서 집어 인벤토리 슬롯에 놓으면 `handleInventoryMouseClick(PICKUP)`을 보낸다(서버는 받은 순서대로 처리하므로 집기→놓기 순서가 유지된다는 판단, 미확인). 툴팁 능력치 표는 `ClientTooltipComponent`로 열을 맞추고 `renderTooltipInternal`을 access widener로 연다. EMI가 있으면 제외 영역 등록.
- **확인한 것:** 쓴 Cobblemon API를 1.8.1 소스 JAR에서, MC 메서드 시그니처를 1.21.1 매핑 JAR(javap)에서, EMI 제외 영역 API를 1.1.24 JAR에서 대조했다. `build/`는 `F:\AI\caches\cobblemon-works-build\cobblemon-party-sidebar` 정션으로 만들어 두었다.
- **미확인:** 컴파일·빌드(빌드는 요청 시에만), JAR 배치, 실게임 동작 전부. 특히 드래그 아웃 시 서버 동기화 순서, `renderBg` 안에서 그린 아이템이 바닐라 슬롯 하이라이트·커서 아이템과 겹치는 깊이, 툴팁 폭, 모델이 16px 칸에서 보이는 크기.
