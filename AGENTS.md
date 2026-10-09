# 응답 언어 (최우선)

- 사용자에게 보내는 모든 글은 한국어로 쓴다. 최종 답변, 작업 중간의 진행 보고, 도구 호출 사이에 쓰는 한두 줄 설명까지 전부 해당한다.
- 코드, 로그, 커밋 메시지, 도구 출력이 영어여도 답변은 한국어다. 영어 로그나 코드를 길게 읽은 직후, 긴 작업을 끝내고 결과를 보고할 때 특히 틀리기 쉬우니 보내기 전에 언어를 확인한다.
- 코드 식별자, 명령어, 파일 경로는 원문 그대로 두고, 설명은 한국어로 쓴다.

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
- 서버 배포에는 개발 월드의 `world/datapacks/`와 광장 차원 `world/dimensions/jbro_policy/plaza/`를 포함한다. 복사는 서버가 정상 저장·종료된 상태에서만 한다. 광장 바이옴 변환 완료 상태인 `world/data/jbro_policy_plaza_biome.dat`도 함께 옮기며, 일반 월드와 플레이어 진행도는 옮기지 않는다.

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
