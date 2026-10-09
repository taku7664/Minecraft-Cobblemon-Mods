# 응답 언어 (최우선)

- 사용자에게 보내는 모든 글은 한국어로 쓴다. 최종 답변, 작업 중간의 진행 보고, 도구 호출 사이에 쓰는 한두 줄 설명까지 전부 해당한다.
- 코드, 로그, 커밋 메시지, 도구 출력이 영어여도 답변은 한국어다. 영어 로그나 코드를 길게 읽은 직후, 긴 작업을 끝내고 결과를 보고할 때 특히 틀리기 쉬우니 보내기 전에 언어를 확인한다.
- 코드 식별자, 명령어, 파일 경로는 원문 그대로 두고, 설명은 한국어로 쓴다.

# 단위 작업 진행 보고

- 구현·검증·배포·작업 기록·커밋/푸시 등 의미 있는 단위 작업을 마칠 때마다 다음 단위를 시작하기 전에 "X 작업을 마쳤습니다. 이제 Y를 진행하겠습니다."처럼 완료 결과와 다음 작업을 구분해 한국어로 보고해야 한다(MUST).
- 주요 수정이 끝나도 기록·검증·커밋/푸시가 남았다면 무엇을 마쳤고 무엇이 남았는지 보고해야 하며, 전체 요청을 완료한 것처럼 말해서는 안 된다(MUST).
- 요청한 작업을 모두 마쳐 다음 작업이 없으면 "요청한 작업을 모두 마쳤습니다. 추가로 진행할 작업은 없습니다."처럼 종료를 명시해야 한다(MUST).

# 모듈 MEMORY.md

`MEMORY.md`는 다음 작업자가 이전 결정의 이유, 모듈 소유권, 검증 범위와 남은 문제를 다시 추측하지 않도록 남기는 작업 기록이다. `README.md`는 현재 기능과 사용법을 설명하는 문서이므로 작업 경과나 시행착오를 대신 기록하지 않는다.

- 모듈 작업을 시작하기 전에 이 `AGENTS.md`, `CLAUDE.md`, 해당 모듈의 `README.md`와 `MEMORY.md`를 읽고 현재 워크트리·대상 모듈을 확인한다. `MEMORY.md`가 없으면 없음을 확인한 뒤 진행한다.
- 모듈 소유권이나 사용자 결정, 재발하기 쉬운 실수, 배포·검증 결과가 생기면 해당 모듈의 `MEMORY.md`에 기록한다. 사용자가 `MEMORY.md` 기록을 지정하면 `README.md`로 대체하지 않는다. 기록할 맥락이 생긴 모듈에 파일이 없다면 새로 만든다.
- 기존 형식을 우선 따른다. 새 파일은 `[YYYY-MM-DD HH:MM]` 제목으로 최신 항목을 위에 두고, 무엇을 왜 바꿨는지와 확인된 결과·미확인 사항을 간결하게 적는다. 구현, 빌드, JAR 배치, 서버 기동, 실게임 검증을 서로 구분한다.
- 확인하지 않은 결과를 완료로 쓰지 않는다. 비밀값과 불필요한 로그를 남기지 않는다. 같은 내용을 `README.md`와 중복하지 말고, 현재 사용법이 바뀌었다면 `README.md`도 별도로 갱신한다.

# Repository layout

- `works/` is the Gradle build: `gradlew`, `settings.gradle.kts`, `gradle.properties`, the wrapper in `gradle/`, every
  mod under `works/mods/<mod>` (project names are the folder names, e.g. `:cobblemon-ui`), and the scripts in
  `works/tools/`. Run Gradle from `works/` (`cd works; .\gradlew.bat :cobblemon-npc:build`).
- The repository root holds only rules and docs (`AGENTS.md`, `CLAUDE.md`, `README.md`, `docs/`), the server wiki
  site (`server-wiki/`), and the product folders. A new mod goes in `works/mods/` and is added to `settings.gradle.kts`.

# Workspace targets

- Development server: `develop-product/server` in this repository (git-ignored). It replaces the old `dev-server/`
  folder. Development deployments use this root only; the separate production root is listed below.
- Development client: `develop-product/client` in this repository (git-ignored), a junction to the Modrinth profile
  `%APPDATA%\ModrinthApp\profiles\cobblemon-dev`. The files stay in AppData because the Modrinth App refuses a profile
  whose content folders sit behind a link. Refer to the repository path. Do not copy JARs into it while its game is
  running.
- Keep source/build validation, client deployment, server deployment, and live gameplay verification as separate results.
- 테스트를 위해 실행한 클라이언트는 검증이 끝나면 직접 종료한다.

### Production server (2026-10-09)

- 배포 서버 저장소: `C:\Users\박주형\Documents\GitHub\MinecraftPPakemonServer`.
- GitHub: `https://github.com/taku7664/MinecraftPPakemonServer` (비공개).
- 서버 실행 루트는 이 저장소 자체이며 `run.bat`으로 실행한다. 게임 포트는 25565이며 개발 서버의 25566과 구분한다.
- `deploy-product/server/MinecraftPPakemonServer.lnk`는 배포 서버 폴더를 여는 바로가기다. 이 사용자 지정 바로가기는 아래 산출물 전용 규칙의 예외다.
- 단순 “배포”는 계속 개발 서버를 뜻한다. “배포 서버”, “운영 서버” 또는 위 저장소를 명시한 요청에만 이 경로를 사용한다.
- 배포 서버 저장소에는 실행 파일·모드·설정·데이터팩·실제 에셋만 둔다. 작업 기록·안내 MD·manifest 등 검증 산출물과 일반 개발 월드·플레이어 기록·비밀값은 원격 배포 저장소에 넣지 않는다.
- 운영 서버 업데이트는 기능 파일만 반영하며 기존 설정·실행 옵션·시작 규칙·월드·플레이어·광장 데이터를 보존한다. 복사는 서버가 정상 저장·종료된 상태에서만 한다.
- 개발·운영 서버 구성에서 JourneyMap JAR과 관련 설정·전용 폴더를 제외하며, JourneyMap은 재배포 대상이 아니다.
- BlueMap·Map Link는 개발·배포 클라이언트와 개발·운영 서버 모두에서 제외하며 재배포하지 않는다. JAR, 관련 설정, 렌더·캐시·전용 데이터 폴더, 위키 지도 페이지·스크립트와 BlueMap 전용 서버 시작 훅도 제거 대상이다(2026-10-10 사용자 결정).
- `world/datapacks/`는 기능 에셋으로 업데이트하되, 운영 서버가 생성한 `cobblemon-startup-hooks.zip`은 보존한다. 광장 차원 `world/dimensions/jbro_policy/plaza/`와 바이옴 변환 완료 데이터 `world/data/jbro_policy_plaza_biome.dat`는 대상에 광장이 없는 최초 설치에서만 함께 복사한다.
- DEV→운영 배포 패쳐는 이 PC의 로컬 전용이다. 운영 루트의 `deploy-from-dev.bat`, `tools/deploy-server.ps1`, `tools/server-deployment.psm1`과 개발 저장소 `works/tools/server-deploy/`의 `deploy-from-dev.bat`, `deploy-server.ps1`, `server-deployment.psm1`, `server-deployment.tests.ps1`은 운영·개발 원격 저장소에서 모두 제외한다. 추적된 패쳐는 삭제 커밋을 푸시한 뒤 로컬에 복구하고 `.gitignore`로 유지한다. Cloud Pull은 삭제 커밋을 받아 패쳐를 제거한다. 정상 서버 시작 훅과 실행에 필요한 파일은 계속 배포 대상이다. 로컬 패쳐는 개발 서버에 설치된 파일을 복사하며 자동 빌드하지 않는다. 백업은 서버 밖 `%LOCALAPPDATA%\MinecraftServerDeploy`에 보관하며, 배포 전 기존 `world` 전체(`playerdata` 포함)를 작업별 `world-snapshot/`에 백업한다. 자동 복구와 복구 버튼은 교체한 기능 파일만 되돌리며 이후 플레이 진행도를 덮어쓰지 않는다.

## Product folders

- `deploy-product/client` and `deploy-product/server` hold the release outputs: the finished mod JARs a player's client
  or the live server needs, and each folder's `VERSION.txt`. A mod that runs on both sides goes in both folders.
- Put JARs in `deploy-product` or edit its `VERSION.txt` only when the user explicitly asks for a release; a plain
  "deploy" (배포) means `develop-product`.
- Except for the user-requested production-server shortcut above, nothing else goes in `deploy-product`: no sources/dev JARs, backups, logs, reports, scratch files or notes.
  Replacing a JAR removes the old one; a previous build is recovered by rebuilding its commit.
- `VERSION.txt` records, per version, what changed in that folder's set of mods. Releases are published on Modrinth;
  the record is how the changes are noticed and shared. Versions are `v0.x` until the first live release, `v1.0`.
- `develop-product/server` and `develop-product/client` are the running development server and client. Deploys there
  install JARs only; do not leave backups, temporary copies or working files in them.
- 사용자 지정 예외: `develop-product/patch-products.bat`와 `develop-product/tools/patcher/`에는 이 PC의 로컬 통합 서버·클라이언트 패쳐를 둘 수 있다. 설치본은 원격 저장소·릴리스 제품에 포함하지 않는다. 기존 서버 패쳐 소스의 로컬 전용 규칙은 유지한다.
- Intermediate and temporary files (downloads, decompiled sources, captures, logs, probes) belong in `build/` or a
  session scratch directory, never in either product folder or the repository root.

## Version control

- Every source, configuration, documentation, build, or deployment change MUST be committed and pushed as its own logical unit.
- Do not combine unrelated work in one commit. Verify the exact staged file set before every commit and verify the remote is synchronized after every push.

### 메인 병합과 배포

- 사용자가 명시적으로 브랜치에서만 작업하거나 병합하지 말라고 한 경우를 제외하고, 작업 브랜치·워크트리 변경은 완료 전에 `main`에 병합하고 `origin/main`에 푸시해야 한다(MUST). 작업 브랜치 커밋·푸시만으로 완료 보고하지 않는다.
- 배포는 `main` 병합·푸시 후 커밋된 `main` 소스로 빌드한 산출물을 사용해야 한다(MUST). 기존 빌드본은 대상 모듈 소스·리소스·빌드 설정·관련 의존 소스가 병합된 `main`과 동일함을 먼저 확인한 경우에 재사용할 수 있다(MAY). 다르면 재빌드한다. 설치본 SHA-256이 검증된 빌드본과 같으면 재복사할 필요는 없다.
- 완료 보고에서는 `main` 병합 커밋과 `origin/main` 동기화, 배포 대상과 빌드본의 해시 일치, 실행하지 않은 검증을 구분하여 확인된 결과만 적어야 한다(MUST).

## Mod implementation

- Every mod source and resource change MUST account for localization and MUST follow a consistent project structure across modules.
- When a feature requires or reasonably benefits from user configuration or editing, the mod MUST provide a `modmenu` entrypoint in `fabric.mod.json` and MUST implement the corresponding configuration screen.

## BattleCam port

- Port the Cobblemon 1.8.1 BattleCam replacement as `better-cobblemon-battlecam`.
- Use the Fabric mod ID `better_cobblemon_battlecam` so it does not collide with the external `battlecam` mod ID.
