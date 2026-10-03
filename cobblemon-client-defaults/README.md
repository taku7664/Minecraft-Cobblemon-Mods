# Cobblemon Client Defaults

Minecraft 1.21.1 / Fabric 클라이언트 전용 초기 설정 모드입니다. `preLaunch`에서 실행되며
Minecraft 클래스나 다른 모드 클래스에 접근하지 않고 파일만 준비합니다. 서버에는 설치하지 않습니다.

CLC가 설치되어 있으면
`config/cobbled_level_control/client.toml`의 `[client.hud] enabled`를 `false`로 설정합니다.
파일이나 항목이 없으면 생성하며, 나머지 값과 TOML 주석은 유지합니다. TOML 작성기가
들여쓰기와 줄바꿈을 다시 정리할 수 있습니다. 이미 `false`이면 원본 파일을 다시 쓰지 않습니다.

CLC의 서버 레벨 제한 설정은 변경하지 않습니다.

단축키 정리는 설치된 모드의 다음 6개 키만 `options.txt`에서 `key.keyboard.unknown`으로
변경합니다. 다른 키, 옵션과 줄바꿈은 유지하며, 항목이나 파일이 없으면 추가합니다.

| 모드 | 해제하는 키 | 기존 기본 키 |
| --- | --- | --- |
| Cobbled Level Control | `key_key.cobbled_level_control.toggle_hud` | H |
| Talking Heads | `key_talkingheads.keybinding.modToggle` | H |
| Simple Voice Chat | `key_key.hide_icons` | H |
| Zoomify | `key_zoomify.key.zoom.secondary` | F6 |
| Crafting Tweaks | `key_key.craftingtweaks.compress_stack` | K |
| Crafting Tweaks | `key_key.craftingtweaks.refill_last_stack` | Tab |

Crafting Tweaks의 `config/craftingtweaks-common.toml`도 `[client] mode = "BUTTONS"`로
설정하여 버튼을 유지하고 단축키를 끕니다. 이 설정 역시 단축키 정리 적용 방식을 따릅니다.
파일이 이미 `BUTTONS`이면 다시 쓰지 않으며, 다른 값과 주석은 유지합니다.
기존 TOML을 다시 작성할 때는 들여쓰기와 줄바꿈이 정리될 수 있습니다.

## 적용 방식

각 설정 항목은 `ONCE`(최초 한 번) 또는 `ALWAYS`(매 실행)를 선택할 수 있습니다.
**CLC는 기본값이 `ALWAYS`입니다.** 사용자가 CLC HUD를 다시 켜도 다음 게임 실행에서
꺼지며, CLC 설정 파일을 삭제하면 다음 실행에서 다시 생성합니다.
**단축키 정리의 기본값은 `ONCE`입니다.** 이후 개인 단축키 변경과 Crafting Tweaks 모드
변경을 유지합니다. 기본 줌 C, 전투 확인 Z·취소 X·정보 Tab, 음성 채팅 V 등은 변경하지 않습니다.

Mod Menu에서 적용 방식을 바꾸거나 `config/cobblemon-client-defaults/client.toml`을
직접 수정할 수 있습니다. 이 파일이 없으면 처음 적용할 때 다음 설정을 생성합니다.

```toml
[clc_hud]
mode = "ALWAYS"

[keybindings]
mode = "ONCE"
```

`ONCE`로 바꾸면 성공한 항목을
`config/cobblemon-client-defaults/applied-defaults.properties`에 `clc-hud-v1=true`로
기록합니다. 그 이후에는 사용자가 CLC 설정을 변경하거나 삭제해도 건드리지 않습니다.
`ALWAYS`는 이 완료 기록을 무시하고 매 실행에서 적용합니다. 아직 한 번 적용 기록이 없는
상태에서 `ONCE`로 바꾸면 다음 실행에서 한 번 적용한 뒤 기록합니다.

단축키 정리는 `keybindings-<mod ID>-v1=true`로 모드별 성공을 기록합니다. 설치되지 않은
모드는 기록하지 않으므로 나중에 추가했을 때 해당 모드의 키만 정리합니다. `ALWAYS`를
선택하면 기록을 무시하고 해당 모드들의 키와 Crafting Tweaks 버튼 모드를 매번 적용합니다.

대상 모드가 없으면 해당 규칙의 설정 파일이나 완료 기록을 새로 만들지 않고, 나중에 설치했을 때 적용합니다.
잘못된 TOML, 지원하지 않는 적용 방식 또는 파일 접근 오류는 로그에 남기고 게임 시작을
계속합니다. `ONCE`의 성공 기록이 없으면 다음 실행에서 다시 시도합니다.
Mod Menu에서 저장한 적용 방식은 다음 게임 실행부터 사용합니다.
CLC와 단축키 정리의 오류는 각각 로그에 남기므로 한쪽이 실패해도 다른 쪽은 시도합니다.
단축키 정리는 필요한 파일을 먼저 읽고 검증한 후 저장합니다. 저장 도중 일부 파일이
실패하면 성공 기록을 남기지 않고 다음 실행에 재시도합니다.

## 빌드 및 배포

```powershell
.\gradlew.bat :cobblemon-client-defaults:build --configure-on-demand
.\deploy-dev.cmd cobblemon-client-defaults -SkipBuild
```

빌드에는 매 실행 적용, 최초 한 번 적용, 기존 값·주석 보존, CLC 미설치,
잘못된 설정 및 파일 접근 실패, 6개 키의 정확한 변경 범위, 나중에 설치한 모드,
개인 키 설정 유지와 두 규칙의 성공 기록 보존을 검증하는 단위 테스트가 포함됩니다.
