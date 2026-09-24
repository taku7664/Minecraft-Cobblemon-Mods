# cobblemon-dev 자동 배포

`deploy-dev.cmd`는 이 저장소의 클라이언트 모드를 빌드해 다음 프로필에 안전하게 배포한다.

`%APPDATA%\ModrinthApp\profiles\cobblemon-dev`

## 가장 쉬운 사용법

저장소 루트의 `deploy-dev.cmd`를 더블 클릭하고 배포할 모듈 번호를 고른다.

터미널에서는 모듈 이름을 바로 지정할 수 있다.

```powershell
.\deploy-dev.cmd rounding-block
```

소스 저장 시마다 자동으로 다시 빌드하고 배포하려면 감시 모드를 사용한다.

```powershell
.\deploy-dev.cmd rounding-block -Watch
```

감시 모드는 Minecraft가 실행 중이면 덮어쓰지 않는다. 게임이 종료될 때까지 최신 변경을 보류했다가 자동 배포한다. 종료는 `Ctrl+C`다.

## 선택 기능

```powershell
# 배포 가능한 모듈 목록
.\deploy-dev.cmd -List

# 사용법 요약
.\deploy-dev.cmd -Help

# Gradle check까지 실행한 뒤 배포
.\deploy-dev.cmd rounding-block -Check

# 파일을 바꾸지 않고 계획만 확인
.\deploy-dev.cmd rounding-block -DryRun

# 이미 빌드된 현재 버전 JAR 배포
.\deploy-dev.cmd rounding-block -SkipBuild
```

다른 프로필을 임시로 지정하려면 `-ProfilePath`를 쓴다. 기본 경로를 지속적으로 바꾸려면 `COBBLEMON_DEV_PROFILE` 환경 변수를 설정한다.

## 안전장치

- `fabric.mod.json`의 mod ID와 현재 프로젝트 버전으로 배포 JAR을 고른다.
- sources/dev JAR은 배포 대상에서 제외한다.
- ZIP의 모든 엔트리와 JDK 21 `jar --validate`를 검사한다.
- 기존 파일은 `codex-deploy-backups` 아래로 옮긴 뒤 새 파일을 설치한다.
- 임시 복사본과 최종 파일의 SHA-256이 빌드 결과와 같은지 확인한다.
- 동일 mod ID가 중복 설치돼 있거나 Minecraft가 실행 중이면 변경 전에 중단한다.
- 서버 전용 모듈은 클라이언트 프로필 배포 목록에서 제외한다.

이 도구는 개발 편의를 위한 현재 작업 트리 빌드다. 정식 릴리스는 별도로 깨끗한 커밋에서 빌드해야 한다.
