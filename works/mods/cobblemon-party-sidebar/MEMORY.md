# cobblemon-party-sidebar 작업 기록

## [2026-10-12 20:38] 신규 아이템 모드 연결·클릭 시점 아이템화·슬롯 교환 (0.2.0, 배포·실게임 미확인)

- **최종 사용자 결정:** 엔트리를 일반 클릭하는 순간 아이템화하고 커서에 든다. 커서 A로 B가 있는 엔트리를 클릭하면 A를 엔트리에 넣고 B를 아이템화하여 커서에 든다. 원래 엔트리로 자동 반환하지 않는 마크 슬롯 방식이다. 처음에는 드래그 유지였으나 사용자 후속 요청으로 클릭 시점 아이템화로 변경했으며, 모델 ghost 드래그와 별도의 NativeParty 파티 재정렬 경로를 제거했다. I키 아이템화도 제거한다.
- **공간 부족:** 일반 클릭은 빈 커서만 필요하므로 인벤토리가 가득 차도 집을 수 있다. Shift 클릭으로 직접 인벤토리·퀵슬롯에 넣을 때만 빈칸이 필요하며, 없으면 거부하고 포켓몬을 유지한다. 명령의 공간 부족 시 바닥 드롭은 그대로다.
- **소유권:** 외부 PokemonToItem 연결과 자체 상세 툴팁/아이템 데이터 생성은 제거했다. `cobblemon-to-item` 0.1.0+를 필수로 연결하고 공개 `PokemonTransfers`·`PokemonItemData`·`PokemonTooltip` API를 사용한다. 잡은 볼 아이콘을 레벨 앞에 표시하고, 크기는 Cobblemon의 XS/S/M/L/XL 단계로 표시한다.
- **복원:** 일반 인벤토리 화면에서 포켓몬 아이템을 인벤토리·퀵슬롯에서 Shift 클릭하면 가장 낮은 번호의 빈 엔트리로 들어간다(처리는 신규 모드 소유). 커서 아이템으로 엔트리를 클릭하거나 들고 있는 아이템을 놓는 기존 release 경로는 공통 `restoreCursor`로 지정 칸 복원/교환을 처리한다. Shift 복원은 파티가 가득 차면 거부하지만 일반 클릭은 점유 칸과 교환할 수 있다. 우클릭의 PC 넘김은 신규 모드가 담당한다. 상자·합성 화면은 기존 Shift 동작을 유지한다.
- **예외 처리:** 신규 모드 서버 API의 배틀·권한·생존·관전자 검사, 화면/슬롯·UUID 재확인, 원본 보존과 실패 롤백을 공유한다. `RestorePayload`에 대상 엔트리 UUID(빈칸은 `null`)를 포함하여 서버에서 대상 변경을 거부한다. 교환 실패 시 양쪽 원본을 복구한다. 마지막 한 마리 아이템화도 허용한다.
- **검증:** 클릭 시점 아이템화·커서 교환 최종 변경 이후 `build`와 최종 `assemble`·`validateAccessWidener`를 통과했다. 불필요해진 `renderTooltipInternal` access-widener 항목을 제거했다. JAR CRC·진입점·번역키·`jar --validate` 검증도 통과했으며 SHA-256은 `2b48f9a057ec86c76858c9df6a02b46504d4e0afdfd11b6adeb884428c9000cd`. Gradle 쓰기 경로는 F 드라이브로 분리했다.
- **미실행·미확인:** JAR 설치·교체, 서버 기동, 실게임 클릭/Shift 입출고·툴팁 렌더링은 실행하지 않았다. 기존 `PTI_NBT` 마이그레이션은 이번 범위에서 제외했다.
- **API 의존성:** 신규 모드는 `implementation(project(path = ":cobblemon-to-item", configuration = "namedElements"))`로 연결한다. 기본 프로젝트 의존성의 remapJar가 개발/테스트 실행에 들어가면 매핑이 달라 API 호출이 실패할 수 있다(pokefusion에서 실제 확인).

## [2026-10-12 05:24] 아이템화 수정·툴팁 안내·끌어다 놓기 개발 배포 (실게임 미확인)

- **빌드:** main `24e6fe5d`(툴팁 안내 `4a7b316c` 포함)과 같은 소스로 05:22 빌드. `unzip -t` 통과, 진입점·mixin·EMI·`PokemonToItemFormat`·`RestorePayload`·`PokemonItemize` 클래스 확인. `PokemonItemize` 바이트코드에 `saveToNBT` → `PokemonToItemFormat.customData` 첨부와 `pokemonTag` 검사가 들어 있음을 javap로 확인했다.
- **배치:** 사용자가 "껐어 배포해". 마인크래프트 프로세스·25565/25566 수신 없음 확인. 이전 JAR은 `develop-product/deployment-backups/20261012-052406-party-sidebar-itemize-fix/{server,client}`, 교체 후 세 곳 SHA-256 `4c154231510230f3276ffc6bc32aa4be1596d5105fbdc7bb9c310ffe9d5e92f6`.
- **미확인:** 실게임 전부. 버그 버전으로 잃은 포켓몬은 아직 복구하지 않았다.

## [2026-10-12] 합성 버튼을 유전자쐐기 아이콘 칸으로 (구현·빌드·개발 배포, 실게임 미확인)

- **사용자 반응·결정:** 글자 버튼이 "너무 짜친다" → 아이콘은 Mega Showdown 유전자쐐기(`mega_showdown:dna_splicer`, 원작 큐레무 합체 도구)로 결정. Cobblemon 1.8.1에는 합체 아이템이 없고, PokeFusion `icon.png`는 1254px 모드 아이콘이라 제외했다.
- **크기:** 처음 합성 미리보기에서 칸은 지닌 물건 칸과 같은 18px였지만, 그림이 16px를 꽉 채우고 진해서 "존나 크다"는 반응이었다. 칸은 18px로 두고 아이콘만 12px(0.75배)로 줄여 가운데 그린다. **교훈:** 미리보기는 옆 칸과 같은 배율로 비교되게 만들고, 아이템 그림이 칸을 얼마나 채우는지도 본다.
- **구현(`41323fa0`):** 6줄 아래 2px 간격, 가로 가운데 18px 슬롯(`InventorySkin.slot`)에 아이템을 그대로 그려 리소스팩을 따른다. 호버 시 밝게, 툴팁 "포켓몬 합성", `/pokefusion` 없으면 어둡게. Mega Showdown이 없으면 이전 글자 버튼. 패널 높이 계산은 `7*2+132+2+18=166`.
- **빌드·배포:** 빌드 성공, JAR `859031D5E0663DC66A511F5F341E2E6C8046AB798646439814FB659C31E80557`. 개발 서버·클라에 교체, 해시 일치(게임·개발 서버 꺼짐 확인). 백업 `F:/AI/build/plaza-fix-20261011/deployment-backups/sidebar-icon/`.
- **미확인:** 실게임 화면.

## [2026-10-12] 맨 아래 "포켓몬 합성" 버튼 (구현·빌드·개발 배포, 실게임 미확인)

- **사용자 요청:** 사이드바 맨 하단에 버튼을 넣어 포켓퓨전 창이 열리게. 피츄 도우미 화면의 아이템화·합성 버튼은 빼서 이쪽으로 옮겼다(아이템화는 이미 끌어다 놓기·I키).
- **구현(`49afe97f`):** 행 6개 아래, 패널 안쪽 4px 여백으로 폭 전체, 높이 16의 바닐라 버튼 스프라이트(`widget/button`·`_highlighted`·`_disabled`, 리소스팩을 따름). 글자는 폭에 맞게 줄인다. 클릭 시 `/pokefusion` 전송, 클라 명령 트리에 `pokefusion`이 없으면 회색. 패널 높이는 `max(인벤토리 높이, 7*2+132+4+16)`이라 바닐라·Whimscape(166) 모두 늘지 않는다. 번역 키 `button.cobblemon_party_sidebar.fusion`.
- **빌드·배포:** jbro-policy와 함께 커밋된 소스로 빌드 성공. JAR `F36FABFDBEB27454D1E16D052552E26D877A42002BE39B795EB08FD4EE8DBB11`을 개발 서버·클라에 넣고 해시 일치(게임·개발 서버 꺼진 상태 확인). 이전 JAR 백업은 `F:/AI/build/plaza-fix-20261011/deployment-backups/sidebar-fusion/`. `PPakemon`에는 이 모드가 없어 넣지 않았다.
- **미확인:** 실게임에서 버튼 위치·글자 크기·클릭.

## [2026-10-12 05:40] I키 아이템화가 포켓몬 데이터를 버리던 버그 수정, 끌어다 놓기 아이템화·빈 칸 복원 (구현·빌드만)

- **버그(사용자 발견):** "I로 아이템화한 건 우클릭으로 엔트리에 안 들어간다". `PokemonUtility.pokemonToItem`은 모델·설명 줄만 만든 보여 주기용 아이템이다. `/poketoitem` 메뉴(`PokeToItemMenu`)는 그 아이템에 `custom_data` `{slot: 파티칸, PTI_NBT: pokemon.saveToNBT(registryAccess)}`를 따로 붙인다(바이트코드 확인). 이전 구현은 앞 단계만 따라 해서 **포켓몬을 파티에서 빼고 데이터 없는 아이템을 줬다 = 그 포켓몬은 사라졌다.** 우클릭 복원은 jbro-policy `PokemonItemRestore`가 `PTI_NBT`로 한다.
- **수정:** 메뉴와 같은 custom data를 붙이고(`PokemonToItemFormat.customData`), 아이템에 `PTI_NBT`가 없으면 파티에서 빼지 않는다. **재발 주의:** 다른 모드의 "변환" 함수를 쓸 때는 실제 결과물이 원래 경로의 결과물과 같은지(데이터 포함) 끝까지 대조한다.
- **잃은 포켓몬(개발 서버):** 버그 버전으로 아이템화한 포켓몬은 데이터가 없다. 파티 파일은 05:19에 이미 빠진 상태로 저장됐다(`.dat.old`도 같은 시각). 아이템 설명 줄에 레벨·별명·성격·특성·폼·개체값·노력치·기술이 남아 있어 그걸로 다시 만들 수는 있다(친밀도·경험치·볼 등은 미보존).
- **사용자 요청 추가:** 모델을 인벤토리에 끌어다 놓으면 아이템화(놓은 칸이 플레이어 인벤토리의 빈 칸이면 그 칸, 아니면 아무 데나), 아이템화한 포켓몬 아이템을 빈 모델 칸에 놓거나 클릭하면 그 파티 칸으로 복원. 복원은 `/itemtopoke`처럼 `Pokemon.loadFromNBT` 후 `party.add`(빈 칸이 있으니 PC로 안 감) → `party.move`로 그 칸에. 권한은 각각 `poketoitem`/`itemtopoke` 명령 노드 `canUse`.
- **미확인:** 실게임 전부. 배포는 게임이 켜져 있어 보류.

## [2026-10-12 05:12] 스킨·PP·I키 아이템화 개발 배포 (실게임 미확인)

- **빌드:** main `184ccf29`과 같은 소스(빌드 뒤에는 README·MEMORY만 수정)로 `:cobblemon-party-sidebar:build -x test`, F 드라이브 경로. 성공. `unzip -t` 통과, 진입점·mixin·EMI·`InventorySkin`·`PokemonItemize` 클래스와 빈 칸 아이콘이 JAR에 있음.
- **배치:** 마인크래프트 프로세스·25565/25566 수신 없음을 확인. 이전 JAR은 `develop-product/deployment-backups/20261012-051233-party-sidebar-skin/{server,client}`에 두고 교체. 세 곳 SHA-256 `59d61fd20047c538edb84626b04eca41b7b4dddbc8d103c62780d2c333a476a0`. 개발 서버에는 PokemonToItem 0.2.0이 있고 `config/pokemontoitem/config.json`의 `command.poketoitem`이 0이라 일반 플레이어도 I키 아이템화 대상이다.
- **미확인:** 실게임 전부.

## [2026-10-12 05:20] 인벤토리 텍스처 스킨·모델 액자·빈 칸 아이콘·PP·I키 아이템화 (구현·빌드만)

- **사용자 피드백(스크린샷):** "레이아웃이 따로 노는 느낌", "아이템 슬롯과 모델 슬롯이란 느낌이 안 남". 개발 클라는 Whimscape 팩이라 인벤토리가 어두운 회색·나무색인데 사이드바는 바닐라 색을 직접 칠하고 있었다. 사용자가 제안(텍스처 스킨·모델 액자·인벤토리에 붙이기·빈 칸 아이콘)에 "아이콘도 넣어서 진행해"라고 답했고, 같이 "PP 현황도 안 나오네", "맨 하단에 회색 글씨로 'i키 눌러 아이템화' 넣고 i키 누르면 아이템화"를 요청했다.
- **스킨(`InventorySkin`):** 색을 칠하지 않고 현재 팩의 `textures/gui/container/inventory.png`를 잘라 쓴다. 리소스 리로드마다 `NativeImage`로 패널 폭·높이(40행/40열의 마지막 불투명 픽셀), 몸통색(`(폭-10, 68)`), 플레이어 모델 칸(`(50,40)` 내부색이 이어지는 범위 + 테두리 1px)을 잰다. Whimscape는 폭 174·칸 (24,8,53,70), 바닐라는 176·(25,7,51,72)로 나왔다. 패널은 4px 9-slice, 측면 테두리는 y 62~78 띠에서 자른다(y 8~40은 Whimscape에서 제작 결과 칸 테두리가 섞여 세로 줄무늬가 생겼다 — 미리보기 합성으로 발견). 아이템 칸은 갑옷 슬롯 `(7,7)` 18px, 모델 칸은 플레이어 모델 칸을 2px 9-slice로 22px 액자로 만든다. 256px가 아니거나 못 읽으면 바닐라 좌표.
- **배치:** 인벤토리 왼쪽에 간격 없이 붙이고 높이는 인벤토리 높이(필요하면 더 길게). 6줄 × 22px, 모델은 20px로 키워 그리고, 아이템 칸은 줄 가운데. 하이라이트는 `RenderType.guiOverlay()`로 모델 위에 덮는다.
- **빈 칸 아이콘:** Cobblemon의 지닌 물건 아이콘은 손 모양(32px)이라 안 맞아 16px 열매 실루엣(`textures/gui/empty_held_item.png`, 직접 생성)을 알파 0.28로 그린다. 포켓몬이 있는 줄에만.
- **PP:** 툴팁 기술 줄을 이름/`남은/최대` 오른쪽 정렬 표(`MoveTable`)로. 0이면 빨강, 1/4 이하 노랑.
- **I키 아이템화:** PokemonToItem `/poketoitem`은 인자 없이 선택 메뉴만 연다(바이트코드 확인). 메뉴는 `PokemonUtility.INSTANCE.pokemonToItem(pokemon)`으로 만든 아이템을 인벤토리에 넣고(가득 차면 떨굼) `party.remove`한다. 같은 동작을 `ItemizePayload` + `PokemonItemize`로 서버에서 한다. PokemonToItem은 리플렉션으로 부르고(선택 의존), 서버 명령 트리의 `poketoitem` 노드 `canUse`로 그 모드 권한 설정을 그대로 따른다. 배틀 중·엔티티 busy 거부, 내보낸 포켓몬은 `recall()` 후 제거. 클라는 `KeyMapping`(기본 I, 조작 설정에서 변경 가능)을 인벤토리 `keyPressed`에서만 읽고, 모델 칸을 가리킬 때만 보낸다. 안내 문구는 서버가 이 패킷을 받고 클라 명령 트리에 `poketoitem`이 있을 때만 그리며, 몸통 밝기에 따라 짙은/옅은 회색.
- **주의:** 개발 클라 `options.txt`에서 I는 한글 패치 한/영 전환(`key.koreanpatch.toggle_ime`)에도 묶여 있다. 인벤토리에서 둘이 같이 반응하는지는 미확인.
- **확인한 것:** 빌드 성공(F 드라이브 경로). 미리보기는 같은 계산을 Python으로 합성해 Whimscape·바닐라에서 봤을 뿐 게임 화면이 아니다.
- **미확인:** 실게임 전부(텍스처 측정 결과, 20px 모델 크기, 안내 문구 줄바꿈, I키 동작·한글 패치 충돌, 아이템화 후 파티 동기화).

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
