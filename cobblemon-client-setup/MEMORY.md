# Cobblemon Client Setup MEMORY

작업 내역과 결정은 `[YYYY-MM-DD HH:MM]` 형식으로 최신 항목을 위에 기록한다.
구현, 빌드, 클라이언트·서버 배포, 실게임 검증은 서로 다른 상태로 적는다.

---

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
