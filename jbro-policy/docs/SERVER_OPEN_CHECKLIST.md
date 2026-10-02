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
| `config/jbro-policy-discord.json` | `botToken`, `inquiryChannelId`, `statusChannelId`, `newsChannelId`, `webhookUrl` | 서버 디스코드 봇의 토큰, 문의·상태·소식 채널 ID, 또는 문의용 웹훅 주소 | 봇 토큰이 있으면 서버가 켜져 있는 동안 봇이 온라인으로 뜨고, 상태 채널의 카드를 열림·닫힘으로 고친다. 문의는 봇과 채널이 있으면 봇이, 없으면 웹훅이 올린다. 문의 채널과 웹훅이 모두 비어 있으면 문의(`/문의`, 위키 문의하기)가 꺼진다. 토큰과 주소는 이 문서나 저장소에 적지 않는다. |
| `config/jbro-policy-discord.json` | `verifiedRoleId`, `syncNickname` | 마크 인증을 마친 사람에게 줄 역할 ID, `true` | 비어 있으면 `/디코인증`과 `/verify`가 꺼진다. `@everyone`은 `#인증` 채널만 보고, 인증 역할이 나머지 채널을 보게 디스코드 권한을 맞춘다. 봇 역할은 인증 역할보다 위에 두고 역할 관리·별명 관리 권한을 준다. 문의 채널에서는 봇에게 비공개 스레드 만들기·스레드에서 메시지 보내기·스레드 관리를 준다. |
| `config/jbro-policy-discord.json` | `commandChannelId` | 플레이어용 봇 명령 채널 ID | 비어 있으면 봇 명령을 어느 채널에서나 받는다. 다른 채널은 `@everyone`의 "애플리케이션 명령 사용"을 꺼서 명령 목록을 숨긴다(인증·관리자 채널은 제외). |
| `config/jbro-policy-discord.json` | `rankRoleIds` | 리그 등급(`POKE_BALL`~`CHAMPION`)별 디스코드 역할 ID | 비어 있으면 등급 역할을 맞추지 않는다. 디스코드 역할 목록에서 봇 역할을 맨 위로 올려 둔다. 봇은 자기 역할보다 아래 역할만 주고 뗄 수 있다. |
| `config/jbro-policy-discord.json` | `adminChannelId`, `adminAccess` | 관리자 명령을 받을 비공개 채널 ID, 운영진 디스코드 사용자·역할 ID별로 쓸 수 있는 명령 | 비어 있으면 디스코드 관리자 명령(`/announce`, `/players`, `/ban`, `/bp`, `/give`, `/spawn`, `/console`)이 꺼진다. 채널은 운영진만 볼 수 있게 만든다. `/console`은 서버 콘솔과 같은 권한이므로 꼭 필요한 사람에게만 준다. 문의 검토의 "처리 완료" 버튼을 누를 사람에게는 `resolve`를 준다. |
| `config/jbro-policy-inquiry-review.json` | `enabled`, `command`, `model` | `true`, `agy`, `gemini-3.8-flash-low` | 문의를 Antigravity CLI로 로그와 대조한다. 서버를 실행하는 Windows 계정에서 `agy`를 설치하고 로그인해 둬야 한다. 봇 토큰, 문의 채널, 관리자 채널이 모두 있어야 켜진다. 문의 채널은 공개해도 되고(카드와 요약 답글만 올라감), 관리자 채널은 비공개로 둔다. |

## 설치할 모드

| 모드 | 이유 |
|---|---|
| Simple MyRoom (`simple-myroom`) | `[안내]` 목록이 `/room` 명령을 소개한다. 서버 전용이라 클라이언트에는 넣지 않아도 된다. |

## 설치할 프로그램

서버 컴퓨터에 설치한다. 모드가 아니라 운영체제에 까는 프로그램이다.

| 프로그램 | 이유 | 확인 |
|---|---|---|
| Antigravity CLI (`agy`) | 문의 자동 검토가 이 프로그램으로 로그와 문의를 대조한다. 없으면 검토가 매번 실패하고, 관리자 채널에 실패 카드만 올라간다. | 서버를 실행하는 Windows 계정에서 설치하고 로그인한다. 그 계정의 터미널에서 `agy --version`과 `agy models`가 동작해야 하고, `agy`가 PATH에 없으면 `config/jbro-policy-inquiry-review.json`의 `command`에 전체 경로를 적는다. Gemini CLI(`gemini`)는 개인 무료 계정을 더 이상 지원하지 않아 쓸 수 없다. |

## 확인만 할 설정

| 파일 | 키 | 값 | 이유 |
|---|---|---|---|
| `config/cobbled_level_control/server.toml` | `restrictBattles`, `restrictCatching`, `restrictLeveling` | `true` | 리그 챌린지의 레벨캡이 레벨업·포획·배틀에 적용되는 전제다. 안내 메시지의 레벨캡 설명도 이 값을 기준으로 쓴다. |

## 예전 서버와의 차이

예전 운영 서버(`Mincraft-Cobblemon-Server`)는 2026-10-01 기준으로 `pokemontoitem` 두 명령이 권한 2로 남아 있다. 그 서버를 다시 쓴다면 위 표대로 고친다.
