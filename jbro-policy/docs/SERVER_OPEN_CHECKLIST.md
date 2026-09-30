# 서버 오픈 체크리스트

새 운영 서버를 열거나 서버를 옮길 때 모드 설치와 별도로 맞춰야 하는 **서버 설정**을 모은 문서다. 설정 파일은 저장소에 들어가지 않으므로(`dev-server/`는 무시 대상), 여기 적힌 값을 새 서버의 `config/`에 직접 반영한다. 값을 바꾸면 이 문서도 함께 고친다.

| 항목 | 값 |
|---|---|
| Last reviewed | 2026-10-01 |
| 기준 서버 | 저장소의 `dev-server` |

## 필수 설정

| 파일 | 키 | 값 | 이유 |
|---|---|---|---|
| `config/pokemontoitem/config.json` | `command.poketoitem`, `command.itemtopoke` | `0` | 기본값 2(OP 전용)면 일반 플레이어가 포켓몬을 아이템으로 바꿀 수 없다. `/pokefusion`은 이 아이템을 재료로 쓰므로 포켓퓨전도 함께 막힌다. 안내 메시지도 두 명령을 소개한다. |
| `config/pokefusion.json` | `commandPermissionLevel` | `0` | 모든 플레이어가 `/pokefusion`을 쓴다. |
| `config/more-cobblemon-contents/hub_tabs.json` | `command_permission_level` | `0` | 모든 플레이어가 `/mcc`로 허브(대시보드·상점·PvP)를 연다. `/mcc` 아래 관리 명령은 이 값과 관계없이 OP 전용이다. `[안내]`의 상점·PvP 안내가 `/mcc`를 소개한다. |
| `config/styled-nicknames.json` | `nicknameFormat` | `"${nickname}"` | 기본값 `"#${nickname}"`이면 채팅 이름 앞에 `#`이 붙는다. `#`은 닉네임과 본래 이름을 구분하는 표시라서, 빼면 다른 사람 이름을 흉내 낸 닉네임을 구분하기 어려워진다. |
| `config/jbro-policy.json` | `tips`, `tipIntervalSeconds` | `dev-server` 파일과 같게 | 30초 `[안내]` 목록이다. 파일이 없으면 코드 기본값으로 만들어지고, 기본값은 `dev-server`와 같게 유지한다. |

## 설치할 모드

| 모드 | 이유 |
|---|---|
| Simple MyRoom (`simple-myroom`) | `[안내]` 목록이 `/room` 명령을 소개한다. 서버 전용이라 클라이언트에는 넣지 않아도 된다. |

## 확인만 할 설정

| 파일 | 키 | 값 | 이유 |
|---|---|---|---|
| `config/cobbled_level_control/server.toml` | `restrictBattles`, `restrictCatching`, `restrictLeveling` | `true` | 리그 챌린지의 레벨캡이 레벨업·포획·배틀에 적용되는 전제다. 안내 메시지의 레벨캡 설명도 이 값을 기준으로 쓴다. |

## 예전 서버와의 차이

예전 운영 서버(`Mincraft-Cobblemon-Server`)는 2026-10-01 기준으로 `pokemontoitem` 두 명령이 권한 2로 남아 있다. 그 서버를 다시 쓴다면 위 표대로 고친다.
