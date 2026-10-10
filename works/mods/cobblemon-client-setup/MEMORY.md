# Cobblemon Client Setup MEMORY

작업 내역과 결정은 `[YYYY-MM-DD HH:MM]` 형식으로 최신 항목을 위에 기록한다.
구현, 빌드, 클라이언트·서버 배포, 실게임 검증은 서로 다른 상태로 적는다.

---

## [2026-10-11 05:00] 운영 서버 월드 초기화에 맞춘 Xaero 지도 캐시 정리 0.1.15

- **이유:** 사용자가 VPS를 광장만 남기고 같은 시드로 초기화했다. 운영 클라이언트에 `xaero/minimap`·`xaero/world-map`의 `Multiplayer_210.207.108.196`이 실제로 남아 있었고, 그대로 두면 새 월드 지도에 예전 건물·웨이포인트가 보인다.
- **구현:** `XaeroWorldReset`이 PreLaunch에서 한 번(`xaero-world-reset-2026-10-11`) 그 서버 폴더(포트가 붙은 이름 포함)를 `cobblemon_client_setup/backups/<rule>/`로 옮긴다. 지우지 않고, 다른 서버·싱글 월드는 건드리지 않는다. `127.0.0.1`은 다른 사람의 로컬 서버와 겹칠 수 있어 넣지 않았다(개발 클라이언트 캐시는 직접 `develop-product/deployment-backups/20261011-dev-world-reset/client-xaero/`로 옮김). 이미 새 월드에서 쌓인 지도도 클라이언트를 0.1.15로 처음 켤 때 한 번 옮겨지는 한계가 있다.
- **깨진 테스트 수정:** 0.1.14의 `KeybindingSetupTest` Iris 테스트가 문자열 안에 실제 줄바꿈이 든 채 커밋돼 컴파일되지 않았다(0.1.14는 `-x unitTest` 빌드라 드러나지 않음). `3d01ae46`에서 `
`으로 고쳤다.
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
