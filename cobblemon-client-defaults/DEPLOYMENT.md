# cobblemon-dev 배포 기록 — 2026-10-03

## 0.1.0 — CLC HUD 자동 끄기

- 소스 커밋: `28b9aff997dafcf6c20dd6f2edb38f3b18d2416e`
- 대상: `C:\Users\박주형\AppData\Roaming\ModrinthApp\profiles\cobblemon-dev\mods`
- 파일: `cobblemon-client-defaults-0.1.0.jar`
- SHA-256: `9F3D1C98DE1D743BD667C9908290D678290632925BEA6BF42AB7ED79EFDA39CF`
- 검증: 단위 테스트 18개, Gradle build, JDK jar 검증 통과. 빌드와 배포 파일의 해시 일치, 같은 mod ID의 설치 파일은 하나.
- CLC 기본 적용 방식: `ALWAYS`. 게임을 실행할 때마다 `client.hud.enabled=false`를 적용.
- 기존 CLC 클라이언트 설정은 배포 전부터 `false`였으며 배포에서는 수정하지 않음.
- 초기 설정 모드의 `client.toml`은 첫 게임 실행에서 생성 예정.
- 실제 게임 실행, HUD 표시 및 Mod Menu 화면은 이번 배포에서 확인하지 않음.
- 클라이언트 전용 배포. 서버에는 배포하지 않음.

## 0.1.1 — 단축키 최초 한 번 정리

- 소스 커밋: `ab74a4d53988b2c035700b2cdef540c289bc3ffb`
- 대상: `C:\Users\박주형\AppData\Roaming\ModrinthApp\profiles\cobblemon-dev`
- 파일: `mods/cobblemon-client-defaults-0.1.1.jar`
- SHA-256: `C5BB0D4F8DEB51E342A0DAAEF2929AA98B5945B668410A4C5FF14233F5A9AFF6`
- 검증: 단위 테스트 33개, Gradle build, JDK jar 검증 통과. 한국어·영어 번역 키 14개 일치. 빌드와 배포 파일 해시 일치, 같은 mod ID의 설치 파일은 하나.
- 이전 JAR 백업: `codex-deploy-backups/20261003-175110033/cobblemon_client_defaults/cobblemon-client-defaults-0.1.0.jar`
- 설정 백업: `codex-deploy-backups/20261003-175205182/client-defaults-config/` 아래 기존 `options.txt`, Crafting Tweaks와 CLC TOML을 원본 경로대로 저장.
- 실행 중인 클라이언트가 없음을 확인한 뒤, 배포 JAR에 들어 있는 적용 코드로 현재 프로필도 초기 설정함. Minecraft는 실행하지 않음.

`options.txt`에서 다음 6개 값만 변경했으며, 다른 줄은 모두 유지했음을 독립 비교로 확인했습니다.

```text
key_key.cobbled_level_control.toggle_hud:key.keyboard.unknown
key_talkingheads.keybinding.modToggle:key.keyboard.unknown
key_key.hide_icons:key.keyboard.unknown
key_zoomify.key.zoom.secondary:key.keyboard.unknown
key_key.craftingtweaks.compress_stack:key.keyboard.unknown
key_key.craftingtweaks.refill_last_stack:key.keyboard.unknown
```

`config/craftingtweaks-common.toml`의 `client.mode`를 `BUTTONS`로 변경하고, 파싱한 모든
다른 값을 원본과 비교하여 유지됨을 확인했습니다. 주석은 유지되며 TOML 들여쓰기와
줄바꿈은 작성기에 의해 정리되었습니다. 이미 꺼져 있던 CLC TOML은 바이트 단위로 동일합니다.

새로 생성한 `config/cobblemon-client-defaults/client.toml`:

```toml
[clc_hud]
mode = "ALWAYS"

[keybindings]
mode = "ONCE"
```

`applied-defaults.properties`에는 설치된 5개 모드의 `keybindings-<mod ID>-v1=true`를
기록했습니다. 같은 적용 코드를 한 번 더 호출했을 때 적용 수가 0이었고, 옵션·Crafting
Tweaks·완료 기록이 바이트 단위로 동일하여 재실행이 파일을 변경하지 않음을 확인했습니다.

| 배포 후 파일 | SHA-256 |
| --- | --- |
| `options.txt` | `1C1F7BCF922255E47BC9ED5C646727EA8C4CF68F4B15D4F2CD2B240FB1E3E372` |
| `config/craftingtweaks-common.toml` | `7D687DDF881DBB3ACA4206DCF974420C0A0531B6EE2E5A8D33D05CCEAE779389` |
| `config/cobblemon-client-defaults/client.toml` | `26720E2A0B0FF0B6E1A8A466E95F068F9BEC74D324D47CD73818E20ED0EFC296` |
| `config/cobblemon-client-defaults/applied-defaults.properties` | `05639AEA136217C74B03B0C918210B2F1A5697B3E5428AC7BB70430A1D9AB8F3` |

실제 Minecraft 시작 시 `preLaunch` 호출, HUD·단축키 동작과 Mod Menu 화면 표시는 이번
배포에서 확인하지 않았습니다. 클라이언트 전용이며 서버에는 배포하지 않았습니다.

## 0.1.2 — 새 프로필의 options.txt 버전 누락 수정

- 소스 커밋: `99cfe98d4ba28a79a09a935de4ee9f6c78af086f`
- 대상: `C:\Users\박주형\AppData\Roaming\ModrinthApp\profiles\cobblemon-dev`
- 파일: `mods/cobblemon-client-defaults-0.1.2.jar`
- SHA-256: `70BFCAAA90A759B84118EB1A76D8855BCE79A417D703DE43F7373D327FD2BC65`
- 이전 JAR 백업: `codex-deploy-backups/20261003-180935331/cobblemon_client_defaults/cobblemon-client-defaults-0.1.1.jar`
- 검증: Minecraft의 실제 옵션 마이그레이션을 포함한 테스트 38개, Gradle build, JDK jar 검증 통과.
- 이번 배포는 JAR만 교체했으며, 현재 프로필의 설정과 최초 적용 완료 기록은 다시 변경하지 않음.

0.1.1은 없는 `options.txt`를 만들 때 최신 키 값만 기록하고 `version`을 누락했습니다.
Minecraft 1.21.1은 이를 버전 0으로 처리하여 숫자 키를 전제로 한 변환을 실행하며,
실제 `DataFixTypes.OPTIONS`·`DataFixers` 통합 테스트에서 `NumberFormatException`을
재현했습니다. 기존 파일 생성 테스트는 Minecraft가 파일을 읽는 단계까지 검사하지 못했습니다.

0.1.2는 `options.txt`가 없거나 비어 있을 때 `version:3955`를 함께 기록합니다.
기존 파일의 버전과 무관한 옵션, 최초 한 번 적용하는 완료 기록은 유지합니다.

Fabric 로더 소스와 설치된 CLC·Forge Config API Port·Balm의 초기화 경로는 확인했습니다.
Minecraft는 실행하지 않았으므로, 완전히 새 프로필에서의 전체 시작과 실제 게임 동작은
확인하지 않았습니다. 클라이언트 전용이며 서버에는 배포하지 않았습니다.

## 0.1.3 — Xaero 시작 훅

- 소스 커밋: `e879e707`
- 파일: `mods/cobblemon-client-defaults-0.1.3.jar`
- SHA-256: `1D6E165DDB03D041617187A1102FC870234A531134A08857F57602F4A6B72A3B`
- 검증: 테스트 49개 및 Gradle build 통과, JDK JAR 검증 통과. 한국어·영어 번역 키 16개 일치.
- 대상: 기존 `cobblemon-dev` 프로필. 빌드·배포 파일 해시 일치, 같은 mod ID의 설치 파일은 하나.
- 이전 JAR 백업: `codex-deploy-backups/20261003-183733708/cobblemon_client_defaults/cobblemon-client-defaults-0.1.2.jar`
- 설정 백업: `F:\AI\Temp\codex-xaero-hook-20261003\before-hook`에 수정 전 옵션·Xaero 설정·초기 설정·성공 기록을 보관.

`ClientDefaultsPreLaunch.onPreLaunch()`에 Xaero 프리셋 호출을 추가했습니다.
설치된 지도 모드만 처리하고 기본 적용 방식은 `ALWAYS`입니다. 웨이포인트·사망 지점·지도
텔레포트를 끄고, 아이콘 항상 표시와 지도 J·설정 Y·웨이포인트 키 미지정을 적용합니다.
설치된 E19 전용 아이콘팩을 활성화하며 기존 다른 팩의 상대 순서를 유지합니다.
Mod Menu에서 Xaero 항목도 최초 한 번 또는 매 실행 적용을 선택할 수 있습니다.

배포된 JAR의 Xaero 적용 코드를 Minecraft 없이 실행하여 현재 프로필도 준비했습니다.
독립 비교 결과 기존 `options.txt`, 미니맵 기본 프로필, 레이더 JSON 및 기존 성공 기록은
바이트 단위로 동일합니다. 초기 설정 TOML에는 `[xaero] mode = "ALWAYS"`만 추가됐고,
전체 지도 기본 프로필에는 `display_minimap_radar = true`만 추가됐습니다.

새 Xaero 전용 프로필의 옵션 파일은 Minecraft 1.21.1의 실제 설정 변환기로도 검증했습니다.
실제 Fabric 클라이언트 시작, 지도·아이콘 렌더링 및 Mod Menu 화면은 확인하지 않았습니다.
서버 웨이포인트 생성 메뉴 차단은 기존 강제 프로필과 `xaero-waypoint-lock` 데이터팩이
담당합니다. 이번 변경에서는 서버 파일을 수정하거나 서버에 이 클라이언트 모드를 설치하지 않았습니다.
