# [2026-10-10 06:30] BlueMap·Map Link 전체 제거·보존 및 패쳐 검증 완료

- 사용자가 개발 클라이언트·서버와 배포 클라이언트·운영 서버 모두에서 BlueMap의 JAR·설정·잔여물 전체 제거를 요청했습니다. 연결용 Map Link와 관련 위키 지도 페이지·스크립트, BlueMap 전용 서버 시작 훅도 제거 대상입니다.
- 메인 작업자 보고 기준으로 통합 패쳐는 서버·클라이언트 복사에서 BlueMap·Map Link 모드 ID와 관련 설정·전용 데이터·훅을 제외하고 대상에 남은 해당 JAR을 모드 ID로 제거합니다. 이전 BlueMap 활성 지도별 저장소 백엔드 선택 방침은 폐기했습니다. JourneyMap 서버 제외는 유지하며 서버 패쳐 `psm1`과 테스트는 계속 로컬 Git-ignored 파일로 두고 원격에 게시하지 않았습니다.
- 서버 패쳐는 제외·기존 JAR 삭제 회귀 테스트의 실패를 먼저 확인한 뒤 수정했습니다. 실행 중 `tools` 직접 복사 루프가 `Test-PrivatePath`를 우회해 BlueMap 훅 제외 검사가 실패한 문제를 발견했고 해당 루프에도 필터를 적용해 회귀 검사를 통과시켰습니다.
- 최종 검증은 Windows PowerShell 5.1과 PowerShell 7에서 서버 테스트 24그룹 및 클라이언트 회귀 검사가 모두 통과했습니다. 통합창 검사는 PowerShell 5.1 `-STA`에서 통과했습니다. 로컬 통합 패쳐 설치본을 갱신하고 원본·설치 모듈의 SHA-256 일치를 확인했습니다. Java/Kotlin 소스 변경이 없어 JAR 빌드는 수행하지 않았습니다.
- 개발 서버, `cobblemon-dev`·`PPakemon` 클라이언트, 로컬 운영 저장소 `MinecraftPPakemonServer`, `deploy-product/client`·`deploy-product/server` 출력에서 BlueMap·Map Link JAR과 전용 파일명 잔여가 없음을 확인했습니다. 19개 경로의 파일 3,044개를 제거했으며 제거 파일은 서버·클라이언트 밖 Git-ignored `build/bluemap-removal/removed-runtime`에 백업했습니다. 두 서버의 BlueMap 시작 훅 호출과 네 설치 위키의 지도 페이지·JavaScript·목차·CSS를 제거했습니다.
- 기존 `world`·`saves` 파일 총 808개의 SHA-256이 작업 전후 완전히 같았고 Xaero 개인 지도·웨이포인트를 보존했습니다. 이 기록 담당자는 메인 작업자의 제거·보존 및 테스트 결과를 정리했으며 검사를 재실행하지 않았습니다.
- 소스 `main` 커밋 `aef686afd3e7ade7d327d2383f8fcf63127f5ec3`과 운영 `main` 커밋 `3024d91617e081f0b4df40afc0aa14e8c4932e7d`가 각각 `origin/main` 및 원격 ref와 일치했습니다. 소스 main에서 재생성한 위키 ZIP은 양 서버 설치본과 SHA-256 `df5c5f2620294b05a3c6f25ed2d58085366f9d5932ef0987841888fce4354097`이 같으며 두 `startup-hooks.json`에서는 `wiki_files.sha256`만 갱신했습니다. 이 후속 결과 기록의 커밋·푸시는 메인 작업자가 별도로 담당합니다.
- 테스트·파일 검증만 완료했고 실제 게임·서버 기동은 수행하지 않았습니다. 별도 클라우드 호스트에 접속하거나 Pull·캐시 제거도 수행하지 않았습니다.

# [2026-10-09 19:52] 로컬 서버 패쳐의 불필요 파일 필터 보완

- 로컬 전용 `works/tools/server-deploy/server-deployment.psm1`의 `Test-PrivatePath`가 JourneyMap 설정, BlueMap `disabled-maps/`, Showdown `translations/<언어>/helptickets.js`와 `sim/examples/`를 제외합니다. 모드는 실제 ID `journeymap`으로 제외합니다. 이 원본과 `server-deployment.tests.ps1`은 계속 Git-ignored이며 원격에 게시하지 않습니다.
- BlueMap `storages/*.conf`는 개발·운영 서버 양쪽의 활성 `maps/*.conf`에서 선택한 백엔드와 기본 `file`만 포함합니다. 저장소 표기를 해석하지 못하면 전체를 유지해 운영 백엔드가 누락되지 않도록 했습니다.
- 기록 담당자가 원본과 `develop-product/tools/patcher/server/server-deployment.psm1`의 SHA-256 일치를 확인했습니다: `7EBB251D84BEC6B6F468FC8A7715CCA5D31AA0E8731FC478E62B36BA372D5708`. 메인 작업자 보고 기준으로 클라이언트를 포함한 다른 설치 파일 4개의 해시는 불변입니다.
- 메인 작업자 보고 기준으로 Windows PowerShell 5.1과 PowerShell 7에서 각각 테스트 24개가 통과했으며 새 제외 규칙과 운영 SQL 선택 보존을 포함합니다. `product-patch.tests.ps1`도 PowerShell 5.1 `-STA`에서 BAT 상대 경로·서버/클라이언트 두 탭·검사 전 적용 비활성화를 통과했습니다. 실제 로컬 서버 미리보기는 19개(추가 2·교체 17), 약 12.46 MiB이며 주로 위키·MCC/league JAR·위키 묶음·시작 훅입니다. 불필요 파일 패턴은 0개로 확인됐습니다. 이 기록 담당자는 테스트를 재실행하지 않았습니다.
- 앞선 개발 서버 정리에서는 808개 파일(245.91 MiB)을 서버 밖 `build/server-cleanup-20261009-d6011a4cf5cc47a885b14355c2179179/`로 옮기고 나머지 5,170개의 SHA-256 보존을 확인했다고 메인 작업자가 보고했습니다.
- 패쳐 설치본만 갱신했고 게임 기능 파일은 적용하지 않았습니다. 서버 기동·실게임은 미검증입니다. 이 기록의 커밋·푸시는 메인 작업자가 별도로 담당합니다.

# [2026-10-09 19:38] main 반영·로컬 설치·두 탭 검사 완료

- 메인 작업자 보고 기준으로 최초 구현은 `a44dee8aac69a65a8d80f813f745b038d6eac04f`에서 main에 병합했습니다. 실제 BAT가 전달하는 `develop-product/..` 경로로 서버 대상 경로를 잘못 계산하는 결함을 회귀 테스트로 재현하고 저장소 경로를 `GetFullPath`로 정규화했습니다. 수정 커밋은 `995e1d82`, 최종 main 병합은 `1c04d9d0b3567169a673d992751d7f46a912b9d9`입니다. 이 main과 `origin/main`·`ls-remote`의 일치를 확인했습니다.
- 메인 저장소의 설치기를 실행해 `develop-product/patch-products.bat`와 `tools/patcher/`의 host 1개·client 2개·server 2개 파일을 설치했습니다. 내부 5개 파일의 원본·설치본 SHA-256이 모두 일치했습니다. 기존 설치 서버 모듈과 원본의 별도 해시 차이는 `ReadAllLines` 비교로 BOM/CRLF 차이임을 확인했습니다. 게임 모드 빌드는 수행하지 않았습니다.
- 최초 `-MigrateLegacy`에서 운영 서버의 `tools/deploy-server.ps1`, `tools/server-deployment.psm1` 두 코드 파일을 `%LOCALAPPDATA%/MinecraftProductPatcher/legacy`에 백업한 후 제거했습니다. 기존 `deploy-from-dev.bat`은 통합창 서버 탭을 여는 진입점으로 전환했고 `server-startup-*` 파일은 모두 보존했습니다. 이 변경은 패쳐 설치 통합이며 게임 기능 파일 패치가 아닙니다.
- 설치본 host와 BAT의 실제 상대 경로로 두 탭 UI를 검사하고 캡처를 확인했습니다. 클라이언트는 추가 1,159개(모드 84개·기능 리소스팩 6개·위키 1,069개), 477.9 MiB를 표시했습니다. 서버는 변경 21개(추가 2개·교체 17개·삭제 2개), 12.4 MB를 표시했습니다. 최초 개발 월드 잠금 시 오류 표시·적용 차단과 이후 정상 Preview를 각각 확인했습니다.
- 앞선 client fixture 검증 범위는 유지되며 새 `product-patch.tests.ps1` 경로 회귀 검증도 main 소스로 통과했습니다. 이 기록 담당자는 메인 작업자 보고를 정리했으며 테스트를 재실행하지 않았습니다.
- 서버·클라이언트 게임 기능 파일은 실제 적용하지 않았고 `PPakemon/mods`는 0개를 유지했습니다. Minecraft 실행·실게임·음악·리소스팩 활성화는 미검증입니다. 위 main 푸시는 코드 완료 근거이며 이 후속 기록 자체의 커밋·푸시는 메인 작업자가 별도로 담당합니다.

# [2026-10-09 19:31] 개발 제품 폴더의 서버·클라이언트 통합 패쳐

- 사용자가 클라이언트 단독 패쳐를 `develop-product`에 통합하고 서버·클라이언트를 모두 지원하도록 요청했습니다. 공통 창은 `patch-products.ps1`, 설치는 `install-product-patcher.ps1`이 담당합니다. 실행 진입점은 `develop-product/patch-products.bat`, 로컬 설치본은 `develop-product/tools/patcher/` 아래 서버·클라이언트 폴더입니다.
- 서버는 기존 `works/tools/server-deploy/`의 로컬 전용 패쳐를 재사용합니다. 서버 설정·월드·플레이어·광장 보존과 복구 정책을 새로 정의하지 않습니다. 클라이언트의 선택·검사·교체·복구 로직은 `../client-patch/` 소유이며 별도 BAT 진입점은 제거했습니다. 사용법은 `README.md`를 참고합니다.
- 통합 실행·설치본만 개발 제품 폴더에 허용하는 사용자 지정 예외를 `AGENTS.md`와 `CLAUDE.md`에 기록했습니다. 설치본은 원격·릴리스에 포함하지 않으며 기존 서버 소스의 로컬 전용 결정도 유지합니다. 통합창은 자동 빌드·게임 실행·런처 버전 변경을 담당하지 않습니다.

## 당시 검증 상태

- 메인 작업자가 임시 UI smoke 결과를 보고했습니다. 클라이언트 Preview는 1,159행을 표시했고 서버는 개발 월드 잠금 오류를 표시하면서 적용 버튼을 비활성화했습니다. UI 오류 처리를 확인한 결과이며 실제 서버 패치 성공을 의미하지 않습니다.
- Modrinth SQLite를 읽기 전용으로 열어 프로필의 이름·경로·게임 버전·로더·로더 버전만 조회한 결과, `PPakemon`과 개발 프로필 모두 Minecraft 1.21.1 / Fabric 0.19.5로 확인됐다고 메인 작업자가 보고했습니다. 런처 실제 실행과 모드 호환성은 이 조회만으로 확인되지 않습니다.
- 이 항목 작성 시점에는 통합 설치·설치본 SHA-256 일치·커밋·`main` 병합·푸시가 진행 중이었습니다. 이후 완료 여부는 후속 기록과 Git·설치본에서 확인해야 합니다.
- 게임 서버를 종료하거나 패치를 적용하지 않았습니다. 실물 `PPakemon` 적용, 게임 실행과 모드·리소스팩 동작 검증도 미실시입니다. 이 기록 담당자는 메인 작업자의 보고를 정리했으며 검증을 재실행하지 않았습니다.
