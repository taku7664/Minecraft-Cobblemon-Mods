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

## 0.1.4 — Xaero 원형 미니맵 우측 상단 배치

- 소스 커밋: `b7aebf78`
- 대상: `C:\Users\박주형\AppData\Roaming\ModrinthApp\profiles\cobblemon-dev`
- 파일: `mods/cobblemon-client-defaults-0.1.4.jar`
- SHA-256: `C3AC40A2C71D8FB005EA33EE67325D465F9FE9DFE4F0355E47FA562768FA13C2`
- 이전 JAR 백업: `codex-deploy-backups/20261003-214510983/cobblemon_client_defaults/cobblemon-client-defaults-0.1.3.jar`
- 설정 백업: `F:\AI\Temp\codex-xaero-round-topright-20261003\live-before`
- 검증: 테스트 51개 및 Gradle build 통과, JDK JAR 검증 통과. 한국어·영어 번역 키 16개 일치.

배포된 JAR의 Xaero 적용 코드를 종료된 개발 프로필에 실행하여 `config/xaero/minimap/profiles/default.cfg`의
`minimap_shape`를 `0`에서 `1`로, `config/xaerohud.txt`의 미니맵 모듈 `fromRight`를 `false`에서
`true`로 변경했습니다. `fromBottom=false`, `x=0`, `y=0`은 유지되어 우측 상단 기준입니다.
같은 모드 ID의 설치 JAR는 0.1.4 하나이며, 설치 JAR 해시는 빌드 산출물과 일치합니다.

적용 전후 백업 파일을 비교한 결과 초기 설정 TOML, 완료 기록, 레이더 JSON, 전체 지도 기본
프로필은 바이트 단위로 동일합니다. `options.txt`의 음악 리소스팩 참조는 비교 사이에
`cobleserver-music-resourcepack-1.3.8.zip`에서 `1.3.9.zip`으로 변경되어 있었습니다.
이는 Xaero 배치 설정과 무관하므로 그대로 보존했습니다.

실제 게임 화면에서 원형·우측 상단 렌더링은 아직 확인하지 않았습니다. 클라이언트 전용
변경이며 서버에는 배포하지 않았습니다.

## 0.1.5 — Cobblemon Client Setup 이름 변경

- 소스 커밋: `77577a4397f954abdc324234ef53c1d867d5b536`
- 대상: `C:\Users\박주형\AppData\Roaming\ModrinthApp\profiles\cobblemon-dev`
- 파일: `mods/cobblemon-client-setup-0.1.5.jar`
- 이름 및 모드 ID: `Cobblemon Client Setup` / `코블몬 클라이언트 셋업` / `cobblemon_client_setup`
- SHA-256: `A9429ACF65E97342D3BE9B8674759348710D9146AFB0B21C7257512004106A1E`
- 이전 JAR 및 설정 백업: `codex-deploy-backups/20261003-215846439/client-setup-rename`
- 검증: 테스트 56개, Gradle build 및 JDK JAR 검증 통과. 한국어·영어 번역 키 16개 일치.

기존 `cobblemon-client-defaults-0.1.4.jar`를 활성 mods 폴더에서 제거하고 새 JAR를
배포했습니다. 설치 파일 해시는 빌드 산출물과 일치합니다. 기존 설정 폴더를
`config/cobblemon-client-setup`으로 이동했으며 `client.toml`과
`applied-defaults.properties`는 바이트 단위로 동일합니다. 기존 설정과 최초 적용
완료 기록을 보존했으며, 이전 이름의 활성 JAR와 설정 폴더는 남아 있지 않습니다.

업그레이드 시 이전 설정 폴더가 있으면 preLaunch에서 같은 이동을 수행합니다.
이전 폴더와 새 폴더가 모두 있으면 덮어쓰지 않고 해당 실행의 프리셋 적용을 건너뜁니다.
Minecraft는 실행하지 않았으며 서버 파일은 변경하지 않았습니다.

## 0.1.6 — 웨이포인트 허용과 즉시 생성

- 소스 커밋: `50c1f14b`
- 대상: `C:\Users\박주형\AppData\Roaming\ModrinthApp\profiles\cobblemon-dev`
- 파일: `mods/cobblemon-client-setup-0.1.6.jar`
- SHA-256: `A56116B94CE7DF48D39868042485C2D97D9B8B30B1AC77F52E5F4CB14D76200E`
- 이전 JAR 백업: `codex-deploy-backups/20261003-232614851/cobblemon_client_setup/cobblemon-client-setup-0.1.5.jar`
- 설정 백업: `F:\AI\Temp\codex-xaero-waypoint-20261003\before`
- 검증: Gradle build와 테스트 58개, JDK JAR 검증 통과. 설치 JAR의 mod ID·버전·두 진입점과 주요 클래스 확인.

Xaero 적용 코드를 종료된 개발 프로필에 직접 실행해 미니맵과 전체 지도에서 웨이포인트
생성·표시를 켰습니다. 사망 지점과 전체 지도 텔레포트는 꺼진 상태입니다. 원형·우측 상단
배치는 유지했습니다. 기존 접속 세계의 Xaero `config.txt`에서는
`teleportationEnabled:true`를 `false`로 바꿨고, 새 클라이언트 진입점도 접속 세계의 같은
설정을 확인해 끄도록 구현했습니다. 적용 전후 `options.txt`와 HUD 배치 파일은 바이트
단위로 동일합니다.

새 B 단축키와 `/waypoint [이름]`은 Xaero의 현재 세계·웨이포인트 세트에 영구 표식을
만들도록 구현했습니다. 이름이 없으면 `Waypoint`입니다. 실제 게임에서 단축키·명령 입력,
웨이포인트 표시·저장, 텔레포트 버튼 동작은 아직 확인하지 않았습니다. 서버 프로필과
기존 금지 효과 제거는 서버 저장소에서 별도로 배포했습니다.

## 0.1.7 — 시작 훅의 기존 월드 텔레포트 차단

- 소스 커밋: `2fd3e6fd`
- 대상: `C:\Users\박주형\AppData\Roaming\ModrinthApp\profiles\cobblemon-dev`
- 파일: `mods/cobblemon-client-setup-0.1.7.jar`
- SHA-256: `12DAF49871C291386A9DE1E913910BE0BBF695F0D55E9C5CA5BE34A49D3202E9`
- 이전 JAR 백업: `codex-deploy-backups/20261003-233601574/cobblemon_client_setup/cobblemon-client-setup-0.1.6.jar`
- 검증: 테스트 60개와 Gradle build, JDK JAR 검증 통과. 배포된 JAR의 Xaero 적용 코드를 개발 프로필에 실행하여 웨이포인트 허용·원형 배치·지도 텔레포트 금지 값을 확인.
- 서버 배포본: `client-mods/cobblemon-client-setup-0.1.7.jar`, 같은 SHA-256. 서버 저장소 커밋 `39275b7`.

`preLaunch`에서 기존 Xaero 월드별 `config.txt`의 `teleportationEnabled`도 게임 초기화 전에
끄도록 했습니다. 새 월드에서는 클라이언트 진입점이 매 틱 같은 값을 확인해 끕니다.
`ONCE`를 사용하면서 이전 Xaero 정책의 완료 기록이 있는 클라이언트도 새 정책을 한 번
적용하도록 완료 기록 버전을 `v2`로 올렸습니다. 기존 `v1` 기록은 삭제하지 않습니다.

개발 프로필의 기존 월드 값은 이미 `false`였으며 재적용 후에도 유지됐습니다. 실제 Fabric
클라이언트 시작과 B·`/waypoint` 입력, 텔레포트 버튼의 게임 내 동작은 확인하지 않았습니다.

## 0.1.8 — 게임 기능을 jbro-policy로 이전

- 개발클라: `cobblemon-client-setup-0.1.8.jar` (SHA-256 `DF20ED3DF7DD4A756FA05A066BCC443FB8FFF4AE213DCDA830C07DDFDBE23CFD`)
- 함께 필요한 클라이언트 모드: `jbro-policy-0.1.1.jar` (SHA-256 `3C67C161FE53E76A3CB51AEEB8FD675A254EB2188AE23B4D20C49047CA20E474`)
- 두 JAR 모두 개발 프로필과 서버 저장소 `client-mods`에 배포했습니다. 개발 프로필의 이전 JAR은 `codex-deploy-backups`에 보관했습니다.
- 셋업 JAR은 시작 전 Xaero 설정만 준비합니다. B 키, `/waypoint [이름]`, 접속 중 새 월드의 Xaero 텔레포트 차단은 jbro-policy JAR이 담당합니다.
- 두 모듈 빌드와 테스트(셋업 58개, 정책 75개), JAR 내용 및 배포 해시를 확인했습니다. 실제 게임 입력과 텔레포트 버튼 동작은 확인하지 않았습니다.

## 0.1.9 — Xaero 미니맵 현재 바이옴 기본 표시

- 개발클라 및 서버 저장소 `client-mods`: `cobblemon-client-setup-0.1.9.jar`
- SHA-256: `A3116BBE25DF4BFED6B7BA7DD9C800FCA64D6E1F0F9C90C63C14AB08F6960550`
- 개발클라의 이전 JAR과 변경 전 기본 프로필은 `codex-deploy-backups/20261004-171317791`에 보관했습니다.
- 기본 프로필의 정보 표시 파일에서 바이옴을 켜고 좌표 다음에 배치합니다. 기존 `ONCE` 미니맵 v2 완료 기록은 v3 적용을 막지 않습니다.
- 단위 테스트 61개와 빌드 통과. Xaero 26.5.0의 디코더로 정보 표시 파일을 읽어 순서·상태를 확인했습니다. 실제 게임 화면은 확인하지 않았습니다.

## 0.1.11 — 뱃지·마이크 키 해제와 PvP룸 GUI Tab

- 소스 커밋: `2eef73f`(뱃지), `afcf093`(마이크·PvP룸).
- 개발 클라이언트: `mods/cobblemon-client-setup-0.1.11.jar`. 기존 0.1.9 JAR은 `codex-deploy-backups/20261005-011420207/cobblemon_client_setup`에 보관했습니다. 0.1.10은 실행 중인 클라이언트에 설치되지 않았습니다.
- 서버 저장소의 클라이언트 배포본: `client-mods/cobblemon-client-setup-0.1.11.jar`, 커밋 `97b6c55`.
- 세 JAR(빌드·개발 클라이언트·서버 배포본)의 SHA-256: `680DC2075ACB3931743B3A7FA0D56948E729253DBCC200CDEC5491008BB8521E`.
- 개발 클라이언트 `options.txt`: 뱃지 보관함과 보이스챗 마이크 음소거는 `key.keyboard.unknown`, PvP룸 GUI 열기는 `key.keyboard.tab`로 변경했습니다. 전투 정보 창의 Tab은 사용자의 중복 허용에 따라 유지했습니다. 변경 전 `options.txt`와 적용 기록은 `codex-deploy-backups/20261005-011506285/client-setup-keys`에 보관했습니다.
- 모듈의 63개 테스트, Gradle build, JDK JAR 검증 및 한·영 번역 키 일치 확인. 실제 게임에서 중복 Tab 입력 동작은 확인하지 않았습니다.

기존 음성 채팅 아이콘 키의 `ONCE` 완료 기록은 마이크 음소거 새 규칙을 막지 않습니다.
PvP룸 GUI 규칙도 자체 완료 기록을 사용합니다. 현재 개발 클라이언트의 두 새 완료 기록과
뱃지 완료 기록은 다음 게임 시작 시 훅이 남깁니다.
