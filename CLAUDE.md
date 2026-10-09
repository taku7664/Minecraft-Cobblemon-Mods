# CLAUDE.md

> 글로벌 지침(`~/.claude/CLAUDE.md`) 상속: 사고규율(Before/After Acting), 행동규율(Break/Cross/Ground), 환경식별, 호칭, Python/uv 규칙 등.

## 응답 언어 (최우선)

- 사용자에게 보내는 모든 글은 한국어로 쓴다. 최종 답변, 작업 중간의 진행 보고, 도구 호출 사이에 쓰는 한두 줄 설명까지 전부 해당한다.
- 코드, 로그, 커밋 메시지, 도구 출력이 영어여도 답변은 한국어다. 영어 로그나 코드를 길게 읽은 직후, 긴 작업을 끝내고 결과를 보고할 때 특히 틀리기 쉬우니 보내기 전에 언어를 확인한다.
- 코드 식별자, 명령어, 파일 경로는 원문 그대로 두고, 설명은 한국어로 쓴다.

## Project Rules

- 모드 소스는 `works/mods/<모드>`, 스크립트는 `works/tools/`, Gradle 루트(`gradlew`, `settings.gradle.kts`, `gradle/`)는 `works/`에 있다. Gradle은 `works/`에서 실행한다. 저장소 루트에는 규칙·문서·`server-wiki/`·제품 폴더만 둔다.
- 개발 서버는 저장소 안 `develop-product/server` 하나뿐이다. 옛 `Mincraft-Cobblemon-Server`나 옛 `dev-server/` 경로는 참조하지 않는다.
- 배포 서버는 `C:\Users\박주형\Documents\GitHub\MinecraftPPakemonServer`이고, 같은 이름의 비공개 GitHub 저장소를 사용한다. 저장소 루트의 `run.bat`으로 실행하며 게임 포트는 25565(개발 서버는 25566)다. `deploy-product/server/MinecraftPPakemonServer.lnk`로 폴더를 연다. 이 바로가기는 JAR·VERSION.txt 전용 규칙의 사용자 지정 예외다. 단순 “배포”는 계속 개발 서버를 뜻하며, 운영 서버 변경은 대상이 명시된 경우에만 한다. 새 배포 서버에는 실행 파일·모드·설정·데이터팩·실제 에셋만 두고, 작업 기록·안내 MD·manifest 등 검증 산출물은 넣지 않는다.
- 운영 서버 업데이트는 기능 파일만 반영하며 기존 설정·실행 옵션·시작 규칙·월드·플레이어·광장 데이터를 보존한다. 정상 저장·종료 상태에서 복사한다. `world/datapacks/`는 기능 에셋으로 업데이트하되 생성된 `cobblemon-startup-hooks.zip`은 보존한다. `world/dimensions/jbro_policy/plaza/`와 `world/data/jbro_policy_plaza_biome.dat`는 대상에 광장이 없는 최초 설치에서만 함께 복사한다.
- 운영 루트의 `deploy-from-dev.bat`을 더블클릭해 변경 목록을 검사한 뒤 배포하거나 복구한다. `tools/deploy-server.ps1`, `tools/server-deployment.psm1`의 원본은 `works/tools/server-deploy/`에 있다. 개발 서버에 설치된 파일을 복사하며 자동 빌드는 하지 않는다. 백업은 서버 밖 `%LOCALAPPDATA%\MinecraftServerDeploy`에 두며, 배포 전 기존 `world` 전체(`playerdata` 포함)를 작업별 `world-snapshot/`에 백업한다. 자동 복구와 복구 버튼은 교체한 기능 파일만 되돌리며 이후 플레이 진행도를 덮어쓰지 않는다.
- 개발 클라이언트는 저장소 안 `develop-product/client`로 다룬다. 이 경로는 Modrinth 프로필 `%APPDATA%\ModrinthApp\profiles\cobblemon-dev`를 가리키는 정션이다. Modrinth가 링크 뒤의 프로필을 거부해서 실제 파일은 AppData에 둔다. 게임이 켜져 있으면 JAR을 복사하지 않는다.
- 릴리스 산출물은 `deploy-product/client`, `deploy-product/server`에 모은다. 완성된 JAR과 `VERSION.txt` 외에는 아무것도 두지 않는다(백업·임시파일·로그·소스 JAR 금지). 자세한 규칙은 `AGENTS.md`의 Product folders.
- 임시파일과 중간 산출물은 `build/`나 세션 scratchpad에 두고, 제품 폴더나 저장소 루트에 남기지 않는다.
<!-- 대화 중 발견된 프로젝트 규칙이 여기에 추가됩니다. -->

- 테스트를 위해 실행한 클라이언트는 검증이 끝나면 직접 종료한다.

### 메인 병합과 배포

- 사용자가 명시적으로 브랜치에서만 작업하거나 병합하지 말라고 한 경우를 제외하고, 작업 브랜치·워크트리 변경은 완료 전에 `main`에 병합하고 `origin/main`에 푸시해야 한다(MUST). 작업 브랜치 커밋·푸시만으로 완료 보고하지 않는다.
- 배포는 `main` 병합·푸시 후 커밋된 `main` 소스로 빌드한 산출물을 사용해야 한다(MUST). 기존 빌드본은 대상 모듈 소스·리소스·빌드 설정·관련 의존 소스가 병합된 `main`과 동일함을 먼저 확인한 경우에 재사용할 수 있다(MAY). 다르면 재빌드한다. 설치본 SHA-256이 검증된 빌드본과 같으면 재복사할 필요는 없다.
- 완료 보고에서는 `main` 병합 커밋과 `origin/main` 동기화, 배포 대상과 빌드본의 해시 일치, 실행하지 않은 검증을 구분하여 확인된 결과만 적어야 한다(MUST).

## 모듈 MEMORY.md

`MEMORY.md`는 다음 작업자가 이전 결정의 이유, 모듈 소유권, 검증 범위와 남은 문제를 다시 추측하지 않도록 남기는 작업 기록이다. `README.md`는 현재 기능과 사용법을 설명하는 문서이므로 작업 경과나 시행착오를 대신 기록하지 않는다.

- 모듈 작업을 시작하기 전에 이 `CLAUDE.md`, 적용되는 `AGENTS.md`, 해당 모듈의 `README.md`와 `MEMORY.md`를 읽고 현재 워크트리·대상 모듈을 확인한다. `MEMORY.md`가 없으면 없음을 확인한 뒤 진행한다.
- 모듈 소유권이나 사용자 결정, 재발하기 쉬운 실수, 배포·검증 결과가 생기면 해당 모듈의 `MEMORY.md`에 기록한다. 사용자가 `MEMORY.md` 기록을 지정하면 `README.md`로 대체하지 않는다. 기록할 맥락이 생긴 모듈에 파일이 없다면 새로 만든다.
- 기존 형식을 우선 따른다. 새 파일은 `[YYYY-MM-DD HH:MM]` 제목으로 최신 항목을 위에 두고, 무엇을 왜 바꿨는지와 확인된 결과·미확인 사항을 간결하게 적는다. 구현, 빌드, JAR 배치, 서버 기동, 실게임 검증을 서로 구분한다.
- 확인하지 않은 결과를 완료로 쓰지 않는다. 비밀값과 불필요한 로그를 남기지 않는다. 같은 내용을 `README.md`와 중복하지 말고, 현재 사용법이 바뀌었다면 `README.md`도 별도로 갱신한다.
