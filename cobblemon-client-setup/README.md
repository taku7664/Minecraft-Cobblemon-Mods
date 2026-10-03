# Cobblemon Client Setup

Minecraft 1.21.1 / Fabric 클라이언트 전용 초기 설정 모드입니다. `preLaunch`에서 실행되며
Minecraft 클래스나 다른 모드 클래스에 접근하지 않고 파일만 준비합니다. 서버에는 설치하지 않습니다.

## 이전 이름에서 업데이트

모듈·JAR 이름은 `cobblemon-client-setup`, 모드 ID는 `cobblemon_client_setup`,
Mod Menu 표시명은 `Cobblemon Client Setup` / `코블몬 클라이언트 셋업`입니다.
업데이트할 때 기존 `cobblemon-client-defaults` JAR을 제거하고 새 JAR만 설치합니다.

게임 시작 전에 이전 `config/cobblemon-client-defaults` 폴더를
`config/cobblemon-client-setup`으로 이동합니다. 설정값·주석·최초 적용 완료 기록과 다른
보관 파일을 그대로 옮기므로, 이름 변경 때문에 최초 한 번 규칙이 다시 적용되지 않습니다.
두 폴더가 이미 함께 존재하거나 이동이 실패하면 원본을 유지하고 프리셋 적용을 건너뛰며
로그를 남깁니다. 이후 폴더를 정리하면 다음 실행에 다시 시도합니다.

## 적용 항목

CLC가 설치되어 있으면
`config/cobbled_level_control/client.toml`의 `[client.hud] enabled`를 `false`로 설정합니다.
파일이나 항목이 없으면 생성하며, 나머지 값과 TOML 주석은 유지합니다. TOML 작성기가
들여쓰기와 줄바꿈을 다시 정리할 수 있습니다. 이미 `false`이면 원본 파일을 다시 쓰지 않습니다.

CLC의 서버 레벨 제한 설정은 변경하지 않습니다.

단축키 정리는 설치된 모드의 다음 6개 키만 `options.txt`에서 `key.keyboard.unknown`으로
변경합니다. 다른 키, 옵션과 줄바꿈은 유지하며, 항목이나 파일이 없으면 추가합니다.
`options.txt`가 없거나 완전히 비어 있으면 Minecraft 1.21.1의 설정 데이터 버전
`version:3955`도 먼저 기록합니다. 버전 없는 최신 키 문자열을 오래된 숫자 키 형식으로
변환하려다 옵션 로딩이 실패하는 것을 방지합니다. 기존 파일의 버전은 변경하지 않습니다.

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

## Xaero 지도 설정

Xaero's Minimap 26.5.0 / World Map 1.46.0의 기본 프로필을 게임 초기화 전에 준비합니다.
설치된 지도 모드만 처리하며 다른 프로필 파일은 수정하지 않습니다.

- Minimap `config/xaero/minimap/profiles/default.cfg`: `waypoints_in_world`, `waypoints_on_minimap`, `display_radar`를 `true`, `deathpoints`를 `false`로 설정합니다.
- World Map `config/xaero/world-map/profiles/default.cfg`: `waypoints`, `render_waypoints`, `display_minimap_radar`를 `true`, `map_teleport_allowed`를 `false`로 설정합니다.
- 두 기본 프로필의 `ignore_enforcement_if_edit_permission`을 `false`로 설정합니다.
- 미니맵 기본 프로필의 `minimap_shape = 1`로 원형을 선택합니다. `config/xaerohud.txt`에서 미니맵 모듈만 `x=0`, `y=0`, `centered=false`, `fromRight=true`, `fromBottom=false`로 변경하여 우측 상단에 둡니다. 다른 HUD 모듈과 무관한 속성은 유지합니다.
- `config/xaero/minimap/default_radar_categories_client.json`의 기본 `icons`를 `2`(항상 표시)로 설정합니다. 파일이 없으면 번들된 기본 분류를 만들고, 기존 분류·이름 표시 등 다른 값은 유지합니다.
- 전체 지도는 J, 미니맵 설정은 Y입니다. Xaero 기본 웨이포인트 키는 충돌을 피하려고 미지정으로 둡니다.
- 시작 훅이 기존 Xaero 월드의 `xaero/minimap/<월드>/config.txt`에서 `teleportationEnabled:false`를 적용합니다. 접속 중 새로 생성된 월드는 `jbro-policy`가 처리합니다.
- `resourcepacks/E19-Xaero-Icons-1.5.1.zip`이 설치되어 있으면 활성화하고 가장 높은 우선순위에 둡니다. 팩을 다운로드하거나 포켓몬 모델·텍스처를 교체하지 않습니다.
- JourneyMap이 설치되어 있지 않으면 기존 JourneyMap 키와 전용 팩 2개의 선택 항목을 제거합니다. 모드·팩 파일 자체는 삭제하지 않습니다.

Xaero의 기본 적용 방식은 `ALWAYS`입니다. Mod Menu에서 `ONCE`로 바꾸면 각 지도 모드의
성공 기록을 `xaero-<mod ID>-v2=true`로 남기며, 나중에 다른 지도 모드를 설치하면 그 모드만 처리합니다.
기존 CFG의 무관한 설정·주석·줄바꿈과 다른 리소스팩의 상대 순서는 유지합니다.
레이더 JSON의 값을 바꾸면 들여쓰기를 다시 정리할 수 있습니다.

기존 서버 데이터팩은 남아 있는 `xaerominimap:no_waypoints` 효과를 제거하도록 전환해야 합니다.
이 클라이언트 모드는 서버 파일과 데이터팩을 수정하지 않습니다.

## 적용 방식

각 설정 항목은 `ONCE`(최초 한 번) 또는 `ALWAYS`(매 실행)를 선택할 수 있습니다.
**CLC는 기본값이 `ALWAYS`입니다.** 사용자가 CLC HUD를 다시 켜도 다음 게임 실행에서
꺼지며, CLC 설정 파일을 삭제하면 다음 실행에서 다시 생성합니다.
**단축키 정리의 기본값은 `ONCE`입니다.** 이후 개인 단축키 변경과 Crafting Tweaks 모드
변경을 유지합니다. 기본 줌 C, 전투 확인 Z·취소 X·정보 Tab, 음성 채팅 V 등은 변경하지 않습니다.

Mod Menu에서 적용 방식을 바꾸거나 `config/cobblemon-client-setup/client.toml`을
직접 수정할 수 있습니다. 이 파일이 없으면 처음 적용할 때 다음 설정을 생성합니다.

```toml
[clc_hud]
mode = "ALWAYS"

[keybindings]
mode = "ONCE"

[xaero]
mode = "ALWAYS"
```

`ONCE`로 바꾸면 성공한 항목을
`config/cobblemon-client-setup/applied-defaults.properties`에 `clc-hud-v1=true`로
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
CLC, 단축키 정리와 Xaero의 오류는 각각 로그에 남기므로 한쪽이 실패해도 다른 쪽은 시도합니다.
단축키 정리와 Xaero는 필요한 파일을 먼저 읽고 검증한 후 저장합니다. 저장 도중 일부 파일이
실패하면 성공 기록을 남기지 않고 다음 실행에 재시도합니다.

## 빌드 및 배포

```powershell
.\gradlew.bat :cobblemon-client-setup:build --configure-on-demand
.\deploy-dev.cmd cobblemon-client-setup -SkipBuild
```

빌드에는 매 실행 적용, 최초 한 번 적용, 기존 값·주석 보존, CLC 미설치,
잘못된 설정 및 파일 접근 실패, 6개 키의 정확한 변경 범위, 나중에 설치한 모드,
개인 키 설정 유지와 세 규칙의 성공 기록 보존을 검증하는 단위 테스트가 포함됩니다.
Xaero의 새 프로필 생성, 매 실행 복구, 무관한 설정·팩 순서 보존, 모드별 최초 한 번 적용,
잘못된 JSON과 쓰기 실패 시 재시도, 이미 올바른 파일의 재작성 방지도 검증합니다.
Minecraft 1.21.1의 실제 설정 변환기로 새 옵션 파일과 기존 버전 파일의 호환성도 확인합니다.
