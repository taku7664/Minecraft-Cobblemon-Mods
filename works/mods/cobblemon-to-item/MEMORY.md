# cobblemon-to-item 작업 기록

## [2026-10-12 23:45] 인벤토리 아이템 툴팁 경로·합성 사이드바 지원 개발 배포 (0.1.2)

- **구현:** `AbstractContainerScreen.renderTooltip`가 `GuiGraphics`의 List overload를 호출해 기존 ItemStack hook을 우회하는 경로를 확인하여, hoveredSlot에서 공유 `PokemonTooltip`을 호출하는 믹스인을 추가했다. `supportsSidebarMenu`는 생존 `InventoryMenu`와 등록 메뉴 `pokefusion:fusion`만 허용한다. 서버 GUI 이동·복원은 실제 `player.containerMenu`의 커서를 사용하며, 인벤 아이템 Shift 복원은 기존 inventoryMenu 검사로 제한해 합성 재료 입고를 유지한다.
- **소스·검증:** 구현 `15a67d2b`·`e16b32fb`를 main `d57859fc8059480cc155f4086707041574c72fb3`에 병합·푸시하고 origin/main 0/0을 확인했다. 관련 src·빌드/루트 설정이 main과 같아 빌드본을 재사용했다. 두 모드 build 성공, 신규 모드 단위 테스트 17/17 통과(사이드바 자체 테스트 없음). ZIP 전체 읽기/CRC·JDK `jar --validate`·메타데이터·access widener·검증용 코드 제품 미포함을 확인했다.
- **배치·보존:** 실제 기본 저장소 `develop-product/{server,client}/mods`에 설치(client는 `cobblemon-dev` 정션), 두 설치본 SHA-256은 빌드본 `9946505d6f4ca69397af0188ac72de50c0e49f69a2d6c1fd6915ce9195d58b35`과 같다. 이전 0.1.1/사이드바 0.2.1은 `F:/AI/builds/cobblemon-to-item-28bf/deployment-backups/20261012-234526`에 백업 후 설치 폴더에서 제거했다. 다른 mods 전체 파일 해시는 같고 config/world에는 쓰지 않았다. 대상 게임·서버 종료와 25566 미수신을 확인했다. 최초 검사에서 다른 프로필 PPakemon을 감지하여 복사 전에 중단했고, 실제 대상 개발 프로필 검사로 좁혀 통과했다(PPakemon 종료 안 함). mod ID 중복 없음도 확인했다(서버 48·클라이언트 84개).
- **게임 검증 한계:** 이전 격리 `smoke-runtime-6.log`에서 기본 클릭→커서→빈 엔트리 복원과 네트워크 데이터·네이티브 툴팁 캡처 PASS를 확인했다. 실제 사용자 모드 구성의 검증은 아니다. 추가 inputmods 격리 부트는 `team_reborn_energy` 누락으로 실패했으며 사용자 월드/설치 오류로 판단하지 않는다. 검증 하네스는 `F:/AI/builds/cobblemon-to-item-28bf/paused-smoke-20261012`에 보존하고 제품에서 제외했다. 사용자가 직접 확인하기로 하여 추가 서버·게임 기동과 합성창 실게임 검증은 하지 않았다. 실제 일반 인벤 빈 엔트리 복원 실패의 원인은 미확정이며 해결을 보장하지 않는다.
- **추가 미확인:** 이전 격리 화면에서 같은 Koko가 사이드바 HP 282/282, 아이템 HP 282/302로 다르게 표시됐으나 원인은 미확정이다. 이번 배포에는 HP 계산 수정이 없으며, 전체 툴팁 정확성을 확인한 것으로 확대하지 않는다.

## [2026-10-12 21:57] 툴팁 수정 빌드·main 반영·개발 설치 (0.1.1, 실게임 미확인)

- **검증·소스:** `:build` 성공, 단위 테스트 17개 모두 통과(복원 원본 보존 회귀 3개 포함). ZIP 전체 항목 읽기/CRC·JDK 21 `jar --validate`·메타데이터·access widener 검증을 통과했다. 구현 `7265623f`를 main `88ee52bac9e52825d89d7e8c26f07776b5b304f9`에 병합했고 origin/main과 0/0 동기화를 확인했다. 최초 push의 GitHub `commit_refs` 오류 후 원격 상태를 확인하고 같은 커밋을 재시도하여 성공했다. 두 모드 src·빌드 설정과 루트 Gradle/settings/wrapper가 병합 main과 같아 검증 빌드본을 재사용했다.
- **배치:** 21:57:53 실제 기본 저장소 `develop-product/{server,client}/mods`에 0.1.0 대신 0.1.1을 설치했다(client는 `cobblemon-dev` 정션). 서버·클라이언트 설치본 SHA-256은 빌드본 `f9e3e0bd6030d9c5b484e2eb55de78987c0b72156d7a15bf375476c715d3e7ee`과 같다. 이전 두 모드 JAR은 `F:/AI/builds/cobblemon-to-item-28bf/deployment-backups/20261012-215753/{server,client}`에 백업하고 설치 폴더에서 제거했다.
- **보존·검사 한계:** 배치 직전 게임·서버 프로세스 없음과 25566 미수신을 확인했다. 교체 대상 외 mods 전체 파일 SHA-256 일치를 확인했고 config/world에는 쓰지 않았다. 외부 Debugify/MegaShowdown 메타데이터 description의 literal newline으로 Python strict JSON 검사가 실패하여 외부 JAR 수정 없이 `strict=False`로 mod ID 중복 검사를 통과했다(서버 48·클라이언트 84개).
- **미확인·범위:** 코사원은 서버·게임을 기동하지 않았으며, 기존 상세 툴팁 부재의 정확한 런타임 예외와 실제 해결 여부는 미확인이다. fusion/policy와 기존 아이템 마이그레이션은 변경하지 않았다. 운영/VPS·릴리스는 건드리지 않았다.

## [2026-10-12 21:38] 상세 툴팁 읽기 경로·조작 안내·이로치 색상 수정 (소스 수정만)

- **구현:** 공유 `PokemonTooltip` 하단 조작 hint와 한영 번역키를 제거하고, 이로치 이름과 ★를 `ChatFormatting.YELLOW`로 표시하도록 수정했다. 아이템 툴팁의 서버 `Pokemon.loadFromNBT` 호출은 `TooltipPokemonReader`의 공개 `Pokemon.CLIENT_CODEC` 읽기로 바꿨다. 서버 저장에서 생략 가능한 클라이언트 필수 컨테이너는 복사본에만 보완하고 서버 Evolution controller는 그 복사본에서 제외한다. 저장된 form과 실제 복원에 사용하는 원본 NBT는 유지한다. 캐시된 읽기 실패에 로그를 추가했다.
- **확인된 범위:** JSON 파싱·`git diff --check`만 통과했다. 원본 복원 데이터 보존·생략 기본값 보완·기존 값 유지 회귀테스트 3개를 추가했지만 실행하지 않았다. 빌드·테스트·배포·서버/게임 기동·실게임 확인은 전혀 하지 않았다. 빡대리에게 빌드·배포 전 수정 내용을 설명하기 위한 소스 수정 단계이며 main 병합·푸시도 하지 않았다.
- **진단 한계:** 첨부 이미지에서 상세 툴팁이 표시되지 않는 현상은 관찰했다. 기존 코드가 예외를 삼켜 정확한 런타임 예외는 증명하지 못했으며, 이번 수정이 실제 현상을 해결했는지는 미확인이다.

## [2026-10-12 21:18] 개발 서버·클라이언트 설치와 기존 PokemonToItem 제거 (0.1.0, 실게임 미확인)

- **대상·배치:** 사용자 "배포 적용해" 요청으로 실제 저장소 `C:/Users/박주형/Documents/GitHub/Cobblemon-Mods/develop-product/{server,client}/mods`에 0.1.0을 신규 설치했다. client는 Modrinth `cobblemon-dev` 프로필로 연결된 정션이다. 양쪽에서 `PokemonToItem-fabric-0.2.0.jar`를 제거하고 사이드바·pokefusion·jbro-policy를 함께 교체했다.
- **빌드 근거:** `main`·원격이 `5c280c99`로 같고 구현 커밋 `e0d9bc47`가 선조임을 확인했다. 작업 HEAD `02f8af91`의 전체 Git tree도 main과 같아 앞서 검증한 산출물을 재사용했으며, 빌드·테스트를 다시 실행하지 않았다. 신규 모드 양쪽 설치본 SHA-256은 빌드본 `566be8ac2cc1038c52fef8a4799024279e5d2f7e771043c1e98ca95a1b7a7b16`과 같다.
- **안전·보존:** 첫 안전검사에서 게임 PID 35148을 감지하여 파일 변경 전에 중단했다. 이후 프로세스 종료와 25565/25566 미수신을 재확인한 뒤 복사했다. 이전 JAR 4개씩은 `F:/AI/builds/cobblemon-to-item-28bf/deployment-backups/20261012-211852/{server,client}`에 백업했다. world·config에는 쓰기 작업을 하지 않았고, 교체 대상 외 mods 파일의 SHA-256 일치를 확인했다.
- **설치 검증:** 두 대상의 설치 JAR 8개 모두 빌드본과 해시가 같다. ZIP·버전·필수 의존성, 중복 mod ID 없음, PokemonToItem ID·legacy 의존성·`.deploying` 잔여물 없음을 확인했다.
- **미실행:** 코사원은 서버·게임을 기동하지 않았고 실게임 입출고·교환·툴팁은 미확인이다. 기존 데이터 마이그레이션은 사용자 결정대로 미구현·미실행이며, 운영 서버·VPS·PPakemon·릴리스 폴더는 변경하지 않았다.

## [2026-10-12 20:38] 포켓몬 아이템화·복원과 공유 툴팁 신규 소유권 (0.1.0, 배포·실게임 미확인)

- **사용자 결정·범위:** 외부 PokemonToItem을 대체한다. 기존 `PTI_NBT` 아이템은 이번 구현에서 자동으로 읽거나 변환하지 않는다. 마이그레이션은 나중에 `jbro-policy`가 서버 데이터를 한 번 훑어 일괄 변환하는 별도 작업이며, 접속·아이템 획득 시 자동 변환은 넣지 않는다. 이번 작업에서 설치된 구버전 JAR을 삭제하지 않았다.
- **소유권·데이터:** 아이템화·우클릭 복원·일반 인벤토리 Shift 복원과 상세 툴팁은 이 모드가 소유한다. 공개 API는 `PokemonItemData`, `PokemonTransfers`, `api.client.PokemonTooltip`. Cobblemon의 `PokemonItem` 모델을 재사용하고, 전체 `Pokemon.saveToNBT` 결과를 `custom_data.cobblemon_to_item = {version: 1, pokemon: {...}}`에 보존한다. 보여 주기용 모델 데이터만으로 복원 가능하다고 판단하지 않는다.
- **입출고 규칙:** `/poke2item <1..6>`만 제공한다. 명령은 인벤토리·퀵슬롯이 가득 차면 바닥에 떨어뜨리는 것이 의도한 동작이다. 사이드바 엔트리를 일반 클릭하면 그 순간 포켓몬을 아이템화하여 커서로 든다(빈 커서만 요구하므로 인벤토리가 가득 차도 가능). Shift 클릭은 인벤토리에 바로 넣으며 빈칸이 없으면 거부한다. 커서로 A를 들고 B가 있는 엔트리를 클릭하면 A를 넣고 B를 아이템화하여 커서로 든다. 원래 슬롯으로 자동 반환하지 않는다. 일반 `InventoryScreen`의 인벤토리·퀵슬롯 Shift 클릭은 가장 낮은 번호의 빈 엔트리로 복원하며, 엔트리가 가득 차면 아이템을 유지한다. 손에 들고 우클릭하면 엔트리로 복원하고, 가득 차면 PC로 보낸다. 마지막 한 마리도 아이템화할 수 있다. 상자·합성 화면은 기존 Shift 이동 동작을 유지한다.
- **커서 API·교환 검증:** 공개 `PokemonTransfers.pickup`을 추가했다. `restoreCursor`는 커서 UUID와 대상 엔트리 UUID(`null`이면 빈칸)를 함께 재검사하여 요청 이후 대상이 바뀌었으면 거부한다. 교환 대상의 busy 상태, 원본 A의 로드/중복 여부, 반환할 B 아이템의 전체 NBT 직렬화와 복원 가능성을 먼저 확인한다. 삽입이나 커서 갱신이 실패하면 대상 B와 커서 A를 모두 복구한다.
- **예외 처리:** 입출고 공통으로 배틀·권한·생존·관전자 상태를 검사한다. 서버에서 화면/슬롯과 UUID를 다시 확인하고, 포켓몬 아이템은 수량 1과 지원 데이터 버전을 요구한다. 자신의 파티·PC에 같은 UUID가 이미 있으면 복원을 거부한다. 복원 완료 시에만 원본 소비가 확정되며, 제거·소비·삽입 단계의 실패는 원본 아이템과 저장 상태를 되돌린다. 명령 드롭은 `ItemEntity` 생성과 `addFreshEntity` 성공까지 확인하고, 실패하면 생성 엔티티를 폐기하고 원래 파티 칸을 복구한다. 설정은 잘못된 형식이나 최초 저장 실패 시 기존 파일을 보존하고 권한 4를 요구한다.
- **툴팁 인계:** 기존 사이드바 상세 툴팁을 이 모드로 옮겼다. 레벨 앞에는 잡은 볼 아이템 아이콘을 그린다. 크기는 백분율이나 배율 대신 Cobblemon `getSizeCategory()`의 XS/S/M/L/XL 단계를 사용하고, 우두머리 여부를 함께 표시한다. 사이드바와 포켓몬 아이템이 같은 공개 툴팁을 사용한다.
- **검증:** 최종 클릭 시점 아이템화·커서 교환 변경 이후 신규 모드 `build`·단위 테스트 14개 모두 통과했다. 교환 성공과 삽입 후 실패 시 양쪽 복구를 검증했다. 롤백 없는 초기 구현에서 트랜잭션 테스트 4개 중 2개 실패를 먼저 확인한 뒤 구현하여 통과시켰다. 4개 모듈 최종 검증은 신규 14개+pokefusion 37개+jbro-policy 동일 코드 108개로 합계 159개 통과, 실패·중단 0이다. 사이드바 build와 최종 assemble·`validateAccessWidener`도 통과했다.
- **산출물·커밋:** 4개 JAR의 CRC·진입점·한영 번역키 일치·공개 API 클래스·구클래스 제거·`jar --validate`를 검증했다. 신규 JAR SHA-256은 `566be8ac2cc1038c52fef8a4799024279e5d2f7e771043c1e98ca95a1b7a7b16`. 소스·README·추적 예외는 `e0d9bc47`에 커밋했다.
- **추적 누락 주의:** 기존 `.gitignore`의 `config/`가 신규 `src/main/.../config/ToItemConfig.kt`까지 가렸다. 커밋 전 실제 소스 파일과 Git index를 대조하여 발견했고, 신규 모드 소스 config 경로에만 추적 예외 2줄을 추가했다. 최종 4개 모듈의 모든 src 파일이 추적됨을 확인했다.
- **빌드 경로·범위:** Gradle 쓰기는 `F:/AI/builds/cobblemon-to-item-28bf`와 `F:/AI/caches/gradle`에 한정했다. 쓰기 경로 검사 init script의 `allprojects` 참조 오류는 `gradle.rootProject.allprojects`로 수정했다. Gradle 검증으로 게임이나 서버를 실행한 것은 아니다.
- **미실행·미확인:** JAR 설치·교체, 기존 PokemonToItem JAR 제거, 서버 기동과 실게임 입출고·툴팁 렌더링은 실행하지 않았다. 마이그레이션도 미구현이다.
