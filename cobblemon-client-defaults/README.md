# Cobblemon Client Defaults

Minecraft 1.21.1 / Fabric 클라이언트 전용 초기 설정 모드입니다. `preLaunch`에서 실행되며
Minecraft 클래스나 CLC 클래스에 접근하지 않고 파일만 준비합니다. 서버에는 설치하지 않습니다.

현재 적용 항목은 CLC 레벨캡 HUD 끄기입니다. CLC가 설치되어 있으면
`config/cobbled_level_control/client.toml`의 `[client.hud] enabled`를 `false`로 설정합니다.
파일이나 항목이 없으면 생성하며, 나머지 값과 TOML 주석은 유지합니다. TOML 작성기가
들여쓰기와 줄바꿈을 다시 정리할 수 있습니다. 이미 `false`이면 원본 파일을 다시 쓰지 않습니다.

CLC의 서버 레벨 제한 설정은 변경하지 않습니다.

## 적용 방식

각 설정 항목은 `ONCE`(최초 한 번) 또는 `ALWAYS`(매 실행)를 선택할 수 있습니다.
**CLC는 기본값이 `ALWAYS`입니다.** 사용자가 CLC HUD를 다시 켜도 다음 게임 실행에서
꺼지며, CLC 설정 파일을 삭제하면 다음 실행에서 다시 생성합니다.

Mod Menu에서 적용 방식을 바꾸거나 `config/cobblemon-client-defaults/client.toml`을
직접 수정할 수 있습니다. 이 파일이 없으면 처음 적용할 때 다음 설정을 생성합니다.

```toml
[clc_hud]
mode = "ALWAYS"
```

`ONCE`로 바꾸면 성공한 항목을
`config/cobblemon-client-defaults/applied-defaults.properties`에 `clc-hud-v1=true`로
기록합니다. 그 이후에는 사용자가 CLC 설정을 변경하거나 삭제해도 건드리지 않습니다.
`ALWAYS`는 이 완료 기록을 무시하고 매 실행에서 적용합니다. 아직 한 번 적용 기록이 없는
상태에서 `ONCE`로 바꾸면 다음 실행에서 한 번 적용한 뒤 기록합니다.

CLC가 없으면 설정 파일이나 완료 기록을 새로 만들지 않고, 나중에 설치했을 때 적용합니다.
잘못된 TOML, 지원하지 않는 적용 방식 또는 파일 접근 오류는 로그에 남기고 게임 시작을
계속합니다. `ONCE`의 성공 기록이 없으면 다음 실행에서 다시 시도합니다.
Mod Menu에서 저장한 적용 방식은 다음 게임 실행부터 사용합니다.

## 빌드 및 배포

```powershell
.\gradlew.bat :cobblemon-client-defaults:build --configure-on-demand
.\deploy-dev.cmd cobblemon-client-defaults -SkipBuild
```

빌드에는 매 실행 적용, 최초 한 번 적용, 기존 값·주석 보존, CLC 미설치,
잘못된 설정 및 파일 접근 실패를 검증하는 단위 테스트가 포함됩니다.
