# PPakemon Client Setup MEMORY

작업 내역과 결정은 `[YYYY-MM-DD HH:MM]` 형식으로 최신 항목을 위에 기록한다.
구현, 빌드, 클라이언트·서버 배포, 실게임 검증은 서로 다른 상태로 적는다.

---

## [2026-10-11 15:10] ppakemon-client-setup으로 이름 변경, 훅 파일을 config 밖으로 0.1.20

- **사용자 지시:** "관련 컨픽 다 삭제해", "ppakemon-client-setup으로 바꿔". `cobblemon-client-setup`(그 전 `cobblemon-client-defaults`)은 2026-10-03 AI 세션이 지은 이름이고 사용자가 정한 적이 없다.
- **정정:** 앞선 답변에서 기록 파일을 다른 곳으로 옮겼다고 헷갈리게 말했지만, 0.1.19까지는 `config/cobblemon-client-setup/`에 그대로 있었다(해시로 무시만 함).
- **구현:** 모듈·JAR `ppakemon-client-setup`, 모드 ID `ppakemon_client_setup`, 표시명 `PPakemon Client Setup`/`빡케몬 클라이언트 셋업`, Gradle 속성 `ppakemon_client_setup_version`. 기록·적용 방식·Xaero 백업은 프로필의 `ppakemon_client_setup/`에 둔다. 시작할 때 `config/cobblemon-client-defaults/`, `config/cobblemon-client-setup/`, `cobblemon_client_setup/`을 옮기고 지운다. 새 폴더에 같은 파일이 있으면 새 것을 남기고 `config` 사본을 지운다. Java 패키지 `jbro.cobblemon.clientsetup`은 그대로다. 클라이언트 패쳐는 새 ID를 필수로, 옛 ID를 제거 대상으로 바꿨다(테스트 통과, 설치본 갱신). main `3b656084`, 테스트 91개 통과.
- **배포:** JAR SHA-256 `1B8797795E5F43D575C0BAC70560BFD40E7E27D4393AC40CE8753A394FB2E089`. 개발 클라이언트·`deploy-product/client`에서 `cobblemon-client-setup-0.1.19.jar`를 `develop-product/deployment-backups/2026-10-11-ppakemon-client-setup-0.1.20/`로 빼고 새 JAR을 넣었다. 개발 클라이언트 프로필은 새 JAR의 이동 코드로 `config/cobblemon-client-setup/`을 `ppakemon_client_setup/`으로 옮겼다. `PPakemon`은 게임을 끈 뒤 옛 JAR을 지우고 새 JAR을 넣었으며, 같은 이동 코드로 `config/cobblemon-client-setup/`과 `cobblemon_client_setup/`을 `ppakemon_client_setup/`으로 옮겼다. 사용자 지시("기존 파일도 다 삭제해")로 `develop-product/deployment-backups`의 옛 이름 JAR 백업 폴더 5개와 `cobblemon-dev/codex-deploy-backups/.../cobblemon_client_setup`을 지웠다. 제품 폴더·Modrinth 프로필에 옛 이름 파일은 0개다.
- **주의:** 모드 ID가 바뀌어 옛 JAR과 새 JAR이 함께 있으면 둘 다 돈다. 팩·프로필에서 옛 JAR을 반드시 뺀다.

## [2026-10-11 14:40] Iris 새로고침과 내보내기 키 충돌을 매 실행 해제 0.1.19

- **사용자 지적:** 기존 유저는 상관없고 신규 유저도 R 중복이 났다. 원인은 0.1.18 항목의 팩 기록 파일과 같다(신규 설치에도 그 파일이 들어감).
- **구현:** 기록·적용 방식과 별개로 `KeybindingSetup.resolveSendOutConflict`가 매 실행 `key_iris.keybind.reload`와 `key_key.cobblemon.throwpartypokemon`을 비교한다(줄이 없으면 둘 다 기본값 R). 같으면 새로고침 키만 `unknown`으로 바꾸고, 다르면 플레이어 선택으로 보고 둔다. main `24e8a699`, 테스트 90개 통과.
- **배포:** JAR SHA-256 `0AE8F90B53A31510ED9347181F2989AB78F21EC32EE9FB94008B0ABFCEC10686`. 개발 클라이언트와 `deploy-product/client`의 0.1.18을 `develop-product/deployment-backups/2026-10-11-client-setup-0.1.19/`로 옮기고 0.1.19를 넣었다. `PPakemon`은 게임 실행 중(PID 44464)이라 0.1.17 JAR이 잠겨 옮겨지지 않았다. 잘못 함께 복사된 0.1.19는 바로 지워서 0.1.17만 남겼다(중복 모드 방지). 사용자가 게임을 끈 뒤 0.1.17을 같은 백업 폴더 `ppakemon/`로 옮기고 0.1.19를 넣었다(해시 일치). 실행 확인 정규식이 틀려 "꺼짐"으로 잘못 판단했었다.
- **미확인:** 새 Modrinth 설치로 실제 실행해 본 결과는 없다. 팩 재업로드 전까지 플레이어에게는 반영되지 않는다.

## [2026-10-11 14:20] 팩에 실린 완료 기록 무시 0.1.18

- **신고:** 사용자 "사람들 다 R키 중복이라는데?" — 내 PC `PPakemon` 프로필에는 `keybindings-iris-v1=true`, `key_iris.keybind.reload:key.keyboard.unknown`으로 적용돼 있었다.
- **원인(직접 확인):** `Downloads/PPakemon 1.0.0.mrpack`(10-11 13:53)에 `overrides/config/cobblemon-client-setup/applied-defaults.properties`가 들어 있었다. 제작자 프로필 기록 그대로라 모든 규칙이 `true`, `fresh-install=false`였고, `options.txt`는 없었다. 그래서 플레이어 훅은 Iris 새로고침 해제·나머지 단축키 정리·Rounding-Block 끄기·첫 실행 기본값을 모두 이미 적용된 것으로 보고 건너뛰었다. Iris는 기본 R로 남아 Cobblemon 내보내기와 겹쳤다. 팩에는 `config/iris.properties`(LumaVale 0.1.4, 켜짐)도 들어 있다.
- **구현:** `SetupFiles.readState`가 기록의 `profile=`(config 폴더 절대 경로 SHA-256 앞 16자리)를 확인하고, 다르거나 없으면 빈 기록에서 시작한다. `XaeroWorldReset`은 복사된 기록에 완료 표시가 있으면 옮기지 않고 기록만 남긴다(팩이 초기화 뒤에 만들어져 지도가 새 월드 것). `CopiedRecordTest` 5개 추가, 전체 89개 통과.
- **영향:** 0.1.17 이하 기록에는 해시가 없어서 내 PC 프로필도 단축키 정리·Rounding-Block 끄기가 한 번 다시 적용된다(개인 재지정 키가 있으면 그 키만 되돌아감). 언어·리소스팩 기본값은 `options.txt`가 이미 있으면 업데이트로 보고 건드리지 않는다. 그래서 팩을 받아 이미 한 번 실행한 플레이어는 한국어·팩 리소스팩 기본값을 자동으로 받지 못한다(사용자 결정 대기).
- **빌드·배포:** main `97a3cfa0`, JAR SHA-256 `282B54AB34B9C4FA2D8DC24F4563F134C4A2675E7FB1713462C998A469522097`. 개발 클라이언트와 `deploy-product/client`에 넣고 0.1.17은 `develop-product/deployment-backups/2026-10-11-client-setup-0.1.18/`로 옮겼다. `PPakemon` 프로필은 게임 실행 중(PID 44464)이라 아직 0.1.17이다.
- **팩 안내:** 다음 팩에는 0.1.18을 넣고 `config/cobblemon-client-setup/applied-defaults.properties`를 뺀다(0.1.18은 실려도 무시하지만 불필요). `config/iris.properties`도 실으면 팩 업데이트 때마다 플레이어 셰이더 설정을 덮는다.

## [2026-10-11 12:17] 첫 실행·업데이트 구분, Iris 설정 파일 수정 0.1.17

- **문제(검토로 발견):** 0.1.16 `OptionsSetup`은 완료 기록만 보고 적용해서, 업데이트한 기존 플레이어도 다음 실행 때 언어가 `ko_kr`로 바뀌고 꺼 둔 팩이 다시 켜지며 팩 순서가 바뀔 뻔했다(운영 `PPakemon` 프로필은 0.1.16 미실행 상태에서 교체). 셰이더는 OptiFine용 `optionsshaders.txt`에 써서 Iris가 읽지 않았다. Iris 1.8.8 JAR에 `optionsshaders` 문자열이 없고 `config/iris.properties`만 읽는 것을 직접 확인했다. 0.1.16 기록의 "운영 클라이언트 셰이더 설정이 틀렸다"는 진단도 잘못이었다(실제 `iris.properties`는 정상).
- **사용자 결정:** "첫 실행과 업데이트를 구분해야 한다."
- **구현:** 규칙이 처음 돌 때 `options.txt`가 없거나 비었으면 새 설치(`fresh-install=true`), 아니면 업데이트(`false`)로 한 번 기록한다. 새 설치만 언어·리소스팩·셰이더 기본값을 넣고, 업데이트는 기록만 남긴다. 셰이더는 `config/iris.properties`로 옮기고 기록을 `shader-defaults-v2`로 올렸다. 매 실행 공통으로 켜진 우리 팩 파일이 사라졌으면 같은 자리를 설치된 새 버전으로 바꾼다. 팩 버전은 숫자로 비교하고(`CCC_2.21` > `CCC_2.9`), 폴더형 팩은 우리 팩으로 인정해 zip을 중복으로 켜지 않는다. `XaeroSetup`의 E19 아이콘팩도 파일명 고정 대신 패턴과 최신 버전으로 찾는다.
- **빌드:** main `97d3e36f`, 테스트 84개 통과. JAR SHA-256 `1E57D054411579C7136D397B01EB72636BF7F9CDA423BBA77FF914D6BA967931`.
- **배포:** 개발 클라이언트, 운영 `PPakemon` 프로필, `deploy-product/client`의 0.1.16을 `develop-product/deployment-backups/2026-10-11-client-setup-0.1.17/`로 옮기고 0.1.17을 넣었다(세 곳 해시 일치, 게임 꺼진 상태).
- **미확인:** 실제 클라이언트 실행 결과. `XaeroWorldReset`의 서버 식별은 IP 폴더명만 보는데, 지금 서버는 도메인이 없어 고칠 대상이 없다(도메인을 쓰게 되면 폴더명도 추가해야 함).
- **모드팩 안내:** 팩에서 빼야 할 파일은 `options.txt`, `config/iris.properties`(`optionsshaders.txt` 아님), `config/cobblemon-client-setup/applied-defaults.properties`, `xaero/`.

## [2026-10-11 05:20] 첫 실행 언어·리소스팩·셰이더 기본값 0.1.16

- **이유:** 사용자가 모드팩에 무엇을 넣을지 묻다가, 팩에 `options.txt`를 넣으면 업데이트 때마다 플레이어 설정을 덮어쓸 수 있으니 훅으로 처음 한 번만 넣자고 결정했다. 셰이더는 처음부터 켜기로 했다(사용자 결정).
- **구현:** `OptionsSetup` — `options-defaults-v1`(한국어, 첫 실행 안내 건너뛰기, 팩 리소스팩 10개를 운영 클라이언트 순서대로 켬, 이름은 패턴이라 버전이 바뀌어도 잡힘, 옛 버전 항목 제거, 플레이어 팩·내장 팩 유지)과 `shader-defaults-v1`(Iris와 LumaVale이 있을 때 가장 높은 버전 켜기). 매 실행 `shaderPack`이 없는 LumaVale 파일을 가리키면 설치된 버전으로 바꾼다(켜짐·꺼짐 유지).
- **발견:** 운영 클라이언트 `optionsshaders.txt`가 설치되지 않은 `LumaVale-0.1.0.zip`을 가리키고 있었다(설치본은 0.1.3). 이 파일을 팩에 넣었다면 셰이더가 켜지지 않았을 것이다(실행 확인은 안 함). 새 훅이 다음 실행 때 고친다.
- **빌드·배포:** main `d5eb23e6`, 테스트 80개 통과(새 6개), JAR `C8F13046D1506EC9862F2E65E7CE3870F0A3875AB5C078498B5210A7A9617BF1`, `jar --validate` 0. 개발 클라이언트·`PPakemon`·`deploy-product/client`의 0.1.15를 백업 후 교체(해시 일치). 백업 `F:/AI/build/xaero-reset-20261011/deployment-backups-0.1.16/`. 실제 실행 결과는 아직 확인하지 않았다.
- **모드팩 안내:** 이제 팩에서 `options.txt`·`optionsshaders.txt`·`config/cobblemon-client-setup/applied-defaults.properties`·`xaero/`를 뺀다.

## [2026-10-11 05:00] 운영 서버 월드 초기화에 맞춘 Xaero 지도 캐시 정리 0.1.15

- **이유:** 사용자가 VPS를 광장만 남기고 같은 시드로 초기화했다. 운영 클라이언트에 `xaero/minimap`·`xaero/world-map`의 `Multiplayer_210.207.108.196`이 실제로 남아 있었고, 그대로 두면 새 월드 지도에 예전 건물·웨이포인트가 보인다.
- **구현:** `XaeroWorldReset`이 PreLaunch에서 한 번(`xaero-world-reset-2026-10-11`) 그 서버 폴더(포트가 붙은 이름 포함)를 `cobblemon_client_setup/backups/<rule>/`로 옮긴다. 지우지 않고, 다른 서버·싱글 월드는 건드리지 않는다. `127.0.0.1`은 다른 사람의 로컬 서버와 겹칠 수 있어 넣지 않았다(개발 클라이언트 캐시는 직접 `develop-product/deployment-backups/20261011-dev-world-reset/client-xaero/`로 옮김). 이미 새 월드에서 쌓인 지도도 클라이언트를 0.1.15로 처음 켤 때 한 번 옮겨지는 한계가 있다.
- **깨진 테스트 수정:** 0.1.14의 `KeybindingSetupTest` Iris 테스트가 문자열 안에 실제 줄바꿈이 든 채 커밋돼 컴파일되지 않았다(0.1.14는 `-x unitTest` 빌드라 드러나지 않음). `3d01ae46`에서 `\n` 이스케이프로 고쳤다. 셸 heredoc으로 코드를 쓰면 `\\`가 `\`로 줄어 이런 일이 생긴다. 백슬래시가 든 코드는 Write/Edit 도구로 쓴다.
- **빌드·배포:** main `3d01ae46`, `:cobblemon-client-setup:build` 성공·테스트 74개 통과, JAR `48DBEC940909767CDC1F5DABBCD2A0F98D3F926E4C37D9BFDF981DE85FC3E588`, `jar --validate` 0. 개발 클라이언트·`PPakemon`·`deploy-product/client`에서 0.1.14를 백업 후 빼고 0.1.15를 넣었다(해시 일치). 백업 `F:/AI/build/xaero-reset-20261011/deployment-backups/`. 플레이어용 Modrinth 팩 업로드는 사용자 몫. 실제 실행 시 폴더가 옮겨지는지는 아직 확인하지 않았다.

## [2026-10-11 02:23] Iris 셰이더 새로고침 키 해제, Rounding-Block 기본 비활성

- **빡대리 지시:** Iris 셰이더 새로고침 기본 R이 Cobblemon 포켓몬 내보내기 R과 겹치니 미지정으로 둔다. Rounding-Block이 기본으로 꺼져 있지 않다는 제보를 확인한다.
- **확인:** 독립 저장소 `GitHub/RoundingBlock`(1.0.0 릴리스 커밋 `6112a64`)의 `RoundingBlockConfig.DEFAULTS.enabled`는 `true`다. 셋업 훅에는 Rounding-Block 규칙이 없었다. 개발클라 `config/rounding-block.json`이 `false`인 건 파일에 이미 저장돼 있었기 때문이고, 설정 파일이 없는 새 클라이언트는 켜진 채 시작한다. 제보가 맞다. 설치된 Iris 1.8.8의 모드 ID `iris`, 키 `iris.keybind.reload`도 JAR에서 확인했다.
- **구현:** 셋업 0.1.14. `KeybindingSetup`에 `iris` 규칙(`key_iris.keybind.reload` → unknown, 기록 `keybindings-iris-v1`)을 추가했다. `RoundingBlockSetup`은 `rounding_block`이 있을 때 `enabled=false`를 최초 한 번 적용하고(`rounding-block-disabled-v1`) 다른 값은 유지한다. 잘못된 JSON은 덮어쓰지 않고 다음 실행에 재시도한다. 테스트 `RoundingBlockSetupTest`와 Iris 키 테스트를 추가했다.
- **빌드:** 사용자 지시로 `main` `7d51ad67`에서 빌드했다(테스트 제외 `-x unitTest`). JAR 무결성, 0.1.14 메타데이터, `RoundingBlockSetup` 클래스와 Iris 키 문자열 포함을 확인했다.
- **개발 클라이언트 배포:** `develop-product/client/mods`에 0.1.14를 넣고 0.1.13은 `develop-product/deployment-backups/2026-10-11-client-setup-0.1.14/`로 옮겼다. SHA-256 `7F46B56059A31CF504E6A3C1908338105F0FBA3A3FE61B87F7DB19591ADBB620`.
- **미확인:** 새 단위 테스트는 실행하지 않았다. 실게임 적용(다음 클라이언트 시작)도 확인하지 않았다. 배포 클라이언트 폴더 `deploy-product/client`에도 사용자 요청으로 같은 해시의 0.1.14를 넣었다. 옛 `client-mods`는 옛 서버 저장소 경로라서 지금은 쓰지 않는다.

## [2026-10-08 00:34] Accessories 화면 열기 기본 단축키 해제

- **구현:** 커밋 `f1e4fc3b`, 셋업 0.1.13. `accessories` 모드가 있을 때 `key_accessories.key.open_accessories_screen`을 `unknown`으로 준비한다. 독립적인 `keybindings-accessories-v1` 기록을 사용하므로 기존 단축키 적용 기록과 별개로 한 번 적용된다. 한국어·영어 도움말도 갱신했다.
- **확인·빌드:** 실제 설치된 Accessories `1.1.0-beta.53`의 메타데이터·번역과 options의 H 설정을 확인했다. 셋업 빌드, 테스트 64개, JAR 검증 통과.
- **개발 클라이언트 배포:** 실제 저장소 `C:/Users/박주형/Documents/GitHub/Cobblemon-Mods`의 `develop-product/client/mods`에만 0.1.13을 설치하고 0.1.12를 제거했다. SHA-256은 `626E6AE09BBE09B4A2D5BB68B20A39F72A87D1E88A45B39B48DD9D77FC2C2F74`다.
- **적용·검증 경계:** 다음 클라이언트 시작에서 훅이 적용된다. 실제 게임 입력은 확인하지 않았다. 소스 커밋은 `codex/alpha-two-perfect-ivs`에 push했고 원격 동기화를 확인했다.

## [2026-10-04 17:15] Xaero 현재 바이옴을 좌표 아래에 기본 표시

- **빡대리 지시:** Xaero 미니맵의 현재 바이옴 이름을 좌표 아래에 기본 표시하고 클라이언트 시작 훅에도 반영한다.
- **설정 형식:** Xaero 26.5.0은 `profiles/default.cfg`의 `info_display_config` 참조와 `profiles/info_display_config/default.cfg.txt`의 `infoDisplayOrder`·`infoDisplay` 줄을 읽는다. 기존 정보 항목의 표시 상태·색·상대 순서를 유지하며 `biome=true`를 좌표 바로 다음에 둔다.
- **적용:** 셋업 0.1.9의 preLaunch와 개발클라 설정, 서버 저장소의 클라이언트 기본 설정·배포 JAR에 반영했다. 기존 미니맵 `ONCE` v2 기록이 있어도 v3로 한 번 적용한다. 서버 실행 모드는 수정하지 않았다.
- **검증:** 셋업 테스트 61개와 빌드 통과. 설치된 Xaero 26.5.0의 `InfoDisplayIO.decode`로 배포 파일의 `coords → biome`, `biome=true`를 확인했다. 개발클라·서버 클라이언트 배포 JAR SHA-256은 `A3116BBE25DF4BFED6B7BA7DD9C800FCA64D6E1F0F9C90C63C14AB08F6960550`. 실제 게임 화면은 확인하지 않았다.

## [2026-10-03 23:43] 셋업 훅과 게임 기능의 소유권

- **빡대리 지시:** `cobblemon-client-setup`은 게임 시작 전에 다른 모드의 설정 파일과 기본 단축키를 준비하는 훅이다. `/waypoint` 같은 게임 내 명령과 B 즉시 생성 단축키를 이 모드에 구현하지 않는다. 그 기능은 `jbro-policy`가 소유한다.
- **적용 경계:** 기존 Xaero 월드의 `teleportationEnabled:false`를 시작 전에 준비하는 코드는 셋업 훅에 둔다. 접속 중 새로 생성된 월드의 설정을 감시하고 수정하는 실시간 코드는 `jbro-policy`에 둔다.
- **이전 완료 기준:** 게임 기능 코드, Fabric 클라이언트 진입점, 번역 키, 테스트, JAR 배포본과 사용 설명을 함께 옮긴다. 셋업 JAR에 명령 등록이나 즉시 생성 클래스가 남지 않았는지 확인한다.
- **검증 상태:** 기능 이전 후 `cobblemon-client-setup` 58개, `jbro-policy` 75개 테스트와 두 모듈 빌드가 통과했다. 개발클라와 서버의 `client-mods`에 두 JAR을 배포하고 해시를 확인했다. 실제 게임 입력과 화면은 별도 확인이 필요하다.
