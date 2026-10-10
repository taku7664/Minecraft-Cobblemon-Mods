# 서버 오픈 체크리스트

새 운영 서버를 열거나 서버를 옮길 때 모드 설치와 별도로 맞춰야 하는 **서버 설정**을 모은 문서다. 설정 파일은 저장소에 들어가지 않으므로(`develop-product/server/`는 무시 대상), 여기 적힌 값을 새 서버의 `config/`에 직접 반영한다. 값을 바꾸면 이 문서도 함께 고친다.

| 항목 | 값 |
|---|---|
| Last reviewed | 2026-10-11 |
| 기준 서버 | 저장소의 `develop-product/server` |

## 필수 설정

| 파일 | 키 | 값 | 이유 |
|---|---|---|---|
| `config/pokemontoitem/config.json` | `command.poketoitem`, `command.itemtopoke` | `0` | 기본값 2(OP 전용)면 일반 플레이어가 포켓몬을 아이템으로 바꿀 수 없다. `/pokefusion`은 이 아이템을 재료로 쓰므로 포켓퓨전도 함께 막힌다. 안내 메시지도 두 명령을 소개한다. |
| `config/pokefusion.json` | `commandPermissionLevel` | `0` | 모든 플레이어가 `/pokefusion`을 쓴다. |
| `config/more-cobblemon-contents/hub_tabs.json` | `command_permission_level` | `0` | 모든 플레이어가 `/mcc`로 허브(대시보드·상점·PvP)를 연다. `/mcc` 아래 관리 명령은 이 값과 관계없이 OP 전용이다. `[안내]`의 상점·PvP 안내가 `/mcc`를 소개한다. |
| `config/more-cobblemon-contents/hub_tabs.json` | `terminals."more_cobblemon_contents:holo_battle_terminal"` | `["more_cobblemon_contents:dashboard", "more_cobblemon_contents:shop", "more_cobblemon_contents:pvp"]` | 본체 홀로 배틀 터미널은 `/mcc`처럼 대시보드·상점·PvP만 연다. 리그·배틀타워·배틀팩토리는 각자 터미널에서 연다. 2026-10-04 이전에 만들어진 파일에는 여섯 탭이 모두 저장돼 있으니 이 값으로 고친다. |
| `config/styled-nicknames.json` | `nicknameFormat` | `"${nickname}"` | 기본값 `"#${nickname}"`이면 채팅 이름 앞에 `#`이 붙는다. `#`은 닉네임과 본래 이름을 구분하는 표시라서, 빼면 다른 사람 이름을 흉내 낸 닉네임을 구분하기 어려워진다. |
| `config/styled-nicknames.json` | `changePlayerListName` | `true` | 기본값 `false`면 Tab 접속자 목록에 닉네임 대신 계정 이름이 뜬다. |
| `config/jbro-policy.json` | `tips`, `tipIntervalSeconds` | 코드 기본 안내 목록, `60` | 기본 1분마다 나오는 `[안내]` 목록이다. 파일이 없으면 코드 기본값으로 만들어진다. 기존 파일도 초기 설정 시 간격을 60초로 맞추며, 이후 운영자가 목록과 간격을 바꿀 수 있다. |
| `config/jbro-policy-discord.json` | `botToken`, `inquiryChannelId`, `statusChannelId`, `newsChannelId`, `webhookUrl` | 서버 디스코드 봇의 토큰, 문의·상태·소식 채널 ID, 또는 문의용 웹훅 주소 | 봇 토큰이 있으면 서버가 켜져 있는 동안 봇이 온라인으로 뜨고, 상태 채널에 시작·정상 종료 때마다 새 채팅 메시지를 보낸다. 문의는 봇과 채널이 있으면 봇이, 없으면 웹훅이 올린다. 문의 채널과 웹훅이 모두 비어 있으면 문의(`/문의`, 위키 문의하기)가 꺼진다. 토큰과 주소는 이 문서나 저장소에 적지 않는다. |
| `config/jbro-policy-discord.json` | `verifiedRoleId`, `syncNickname` | 마크 인증을 마친 사람에게 줄 역할 ID, `true` | 비어 있으면 `/디코인증`과 `/verify`가 꺼진다. `@everyone`은 `#인증` 채널만 보고, 인증 역할이 나머지 채널을 보게 디스코드 권한을 맞춘다. 봇 역할은 인증 역할보다 위에 두고 역할 관리·별명 관리 권한을 준다. 문의 채널에서는 봇에게 비공개 스레드 만들기·스레드에서 메시지 보내기·스레드 관리를 준다. |
| `config/jbro-policy-discord.json` | `commandChannelId` | 플레이어용 봇 명령 채널 ID | 비어 있으면 봇 명령을 어느 채널에서나 받는다. 정해 두면 `/피츄`만 일반 채널에서도 답하고 나머지 명령은 이 채널로 안내한다. 그래서 `/피츄`를 쓸 잡담방은 "애플리케이션 명령 사용"을 끄지 않는다(2026-10-11 확인: 잡담방은 막지 않음, 명령별 권한도 없음). 명령 목록을 숨길 채널만 `@everyone`의 그 권한을 끈다. |
| `config/jbro-policy-discord.json` | `inviteUrl` | `https://discord.gg/HbKxTFeGB` | `/디코인증`이 코드와 함께 서버 디스코드 초대 링크를 보여 준다. 비어 있으면 링크 없이 코드만 나온다. `https://discord.gg/` 또는 `https://discord.com/invite/` 형식이 아니면 디스코드 설정 전체가 꺼진다. |
| `config/jbro-policy-discord.json` | `rankRoleIds` | 리그 등급(`POKE_BALL`~`CHAMPION`)별 디스코드 역할 ID | 비어 있으면 등급 역할을 맞추지 않는다. 디스코드 역할 목록에서 봇 역할을 맨 위로 올려 둔다. 봇은 자기 역할보다 아래 역할만 주고 뗄 수 있다. |
| `config/jbro-policy-discord.json` | `adminChannelId`, `adminAccess` | 관리자 명령을 받을 비공개 채널 ID, 운영진 디스코드 사용자·역할 ID별로 쓸 수 있는 명령 | 비어 있으면 디스코드 관리자 명령(`/announce`, `/players`, `/ban`, `/bp`, `/give`, `/spawn`, `/console`)이 꺼진다. 채널은 운영진만 볼 수 있게 만든다. `/console`은 서버 콘솔과 같은 권한이므로 꼭 필요한 사람에게만 준다. 문의 검토의 "처리 완료" 버튼을 누를 사람에게는 `resolve`를 준다. |
| `config/jbro-policy-inquiry-review.json` | `enabled`, `command`, `model` | `true`, `agy`, `gemini-3.8-flash-low` | 문의를 Antigravity CLI로 로그와 대조한다. 서버를 실행하는 Windows 계정에서 `agy`를 설치하고 로그인해 둬야 한다. 봇 토큰, 문의 채널, 관리자 채널이 모두 있어야 켜진다. 문의 채널은 공개해도 되고(카드와 요약 답글만 올라감), 관리자 채널은 비공개로 둔다. |
| `config/jbro-policy-inquiry-review.json` | `autoResolve`, `autoMaxBpPerInquiry`, `autoMaxBpPerDay` | `true`, `300`, `600` | 기록으로 확실한 문의는 피츄가 직접 처리하고, 모호한 것만 관리자 채널에 넘긴다. 지금 자동으로 실행하는 것은 보상 누락이 확인됐을 때 문의한 본인에게 주는 `/bp add` 하나이고, 1건·하루 한도를 넘으면 넘긴다. 하루 지급량은 `jbro-policy/inquiries/auto-bp.json`에 남는다. 끄면 모든 문의를 넘긴다. |
| `config/mega_showdown/config.json` | `dynamaxAnywhere` | `true` | 기본값 `false`면 반경 `powerSpotRange`(20) 블록 안에 파워스폿이 있어야 다이맥스할 수 있다. 서버는 어디서나 다이맥스밴드만 끼우면 되도록 설계했다(배틀타워 기믹 선택 포함). 시작 훅이 매 실행 `true`로 맞추지만, 설정 파일이 없을 때는 건너뛰므로 새 서버는 메가쇼다운이 파일을 만든 다음 실행부터 적용된다. 서버를 다시 켜야 반영된다. |
| `server.properties` | `simulation-distance` | `6` | 몹·작물·레드스톤이 돌아가는 범위다. 10이던 값을 2026-10-11에 6으로 줄였다(개발 서버, 운영 저장소 `b08deda` 반영). 시야 거리(`view-distance`)는 별개이며 10을 유지한다. 서버를 다시 켜야 반영된다. |
| `startup-hooks.json` | `more_cobblemon_contents_league_challenge`의 `requires` 중 `pokebadges` | `{ "mod": "pokebadges" }` (버전 조건 없음) | 리그챌린지 JAR이 PokeBadges 버전을 묶지 않는다(`"pokebadges": "*"`). 예전 `>=1.6.1 <1.7.0` 조건이 남아 있으면 PokeBadges 2.0.0을 넣었을 때 `run.bat`이 시작 훅 단계에서 서버를 띄우지 않는다. |
| `startup-hooks.json` | `simple_myroom` 규칙의 `version` | `"*"` | MyRoom은 2026-10-07에 독립 모드 SimpleMyRoom으로 나가면서 버전이 1.0.0부터 다시 시작했다. 예전 `>=1.3.0 <2.0.0`이 남아 있으면 `run.bat`이 서버를 띄우지 않는다. BetterEmote(`player_popup_emotes`)는 시작 훅에 규칙이 없다. |

## 설치할 모드

| 모드 | 이유 |
|---|---|
| Simple MyRoom (`simple-myroom`) | `[안내]` 목록이 `/room` 명령을 소개한다. 서버 전용이라 클라이언트에는 넣지 않아도 된다. |

## 설치할 데이터팩

`world/datapacks/`에 넣는다. 새 데이터팩은 다음 기동 때 자동으로 켜진다.

| 데이터팩 | 이유 |
|---|---|
| `CCC_2.21-data.zip` (Complete Cobblemon Collection 2.21의 `data/`, 충돌 파일 제외) | CCC가 모델을 붙인 종을 구현된 종으로 표시하고, 패러독스·울트라비스트 등의 스폰을 더한다. 하드 충호의 땅을기는날개도 CCC 모델을 쓴다. 클라이언트에는 같은 버전의 CCC 리소스팩(`CCC_2.21.zip`)이 있어야 모델이 보인다. **원본 CCC를 그대로 넣지 않는다.** CCC는 `data/cobblemon/species/`의 9종(아르세우스·디아루가·기라티나·펄기아·볼트로스·비크티니·지가르데·실버디·오거폰)을 폼 없이 통째로 바꾸고 additions로 폼을 다시 붙인다. 그대로 넣으면 Cobblemon과 Mega Showdown의 폼 정의가 덮여서 하드 난천의 기라티나 오리진폼 같은 엔트리가 깨질 수 있다. 그래서 Cobblemon·Mega Showdown과 경로가 겹치는 파일 23개(위 9종, Mega Showdown 스폰 14개)와 그 9종을 대상으로 하는 `species_additions` 58개를 빼고 만든다. 또 운영 결정(2026-10-06)으로 울트라비스트 11종과 패러독스 20종의 스폰을 모두 빼서, 그 종만 담긴 스폰 파일 31개(`ub_spawns_ccc`, `paradox_spawns_ccc` 전부 포함)를 넣지 않는다. CCC를 올릴 때도 같은 기준으로 다시 만든다. |

## 설치할 프로그램

서버 컴퓨터에 설치한다. 모드가 아니라 운영체제에 까는 프로그램이다.

| 프로그램 | 이유 | 확인 |
|---|---|---|
| Antigravity CLI (`agy`) **(필수)** | 문의 자동 검토가 이 프로그램으로 로그와 문의를 대조한다. 없으면 검토가 매번 실패하고(`Cannot run program "agy"`), 관리자 채널에 실패 카드만 올라간다. | 서버를 실행하는 Windows 계정에서, **Claude 데스크톱 같은 앱 안의 터미널이 아니라 일반 터미널(Windows Terminal, cmd)에서** 설치하고 로그인한다. MSIX 앱 안에서 설치하면 `%LOCALAPPDATA%`가 앱 전용 폴더(`%LOCALAPPDATA%\Packages\<앱>\LocalCache\Local\agy`)로 가상화돼, 앱 밖에서 뜬 서버에는 보이지 않는다. 확인도 일반 터미널에서 한다: `agy --version`과 `agy models`가 동작하고 `%LOCALAPPDATA%\agy\bin\agy.exe`가 있어야 한다. 서버는 PATH와 이 기본 설치 위치를 알아서 찾으므로 `command`는 `agy` 그대로 두고, 전체 경로를 적지 않는다(서버 환경이 바뀌면 깨진다). 로그인 정보는 `%USERPROFILE%\.gemini`에 남는다. Gemini CLI(`gemini`)는 개인 무료 계정을 더 이상 지원하지 않아 쓸 수 없다. |

## 확인만 할 설정

| 파일 | 키 | 값 | 이유 |
|---|---|---|---|
| `config/cobbled_level_control/server.toml` | `restrictBattles`, `restrictCatching`, `restrictLeveling` | `true` | 리그 챌린지의 레벨캡이 레벨업·포획·배틀에 적용되는 전제다. 안내 메시지의 레벨캡 설명도 이 값을 기준으로 쓴다. |

## 서버 경로와 설정 이력

2026-10-04부터 서버는 `develop-product/server`로 이동했다. 예전 외부 서버 저장소는 사용하지 않으며 점검·배포 대상에서도 제외한다. 날짜가 붙은 예전 적용 기록의 경로와 백업 위치는 당시 작업을 설명하는 자료다. 안내 60초의 코드 변경과 당시 배포 검증은 [적용 기록](TIP_INTERVAL_DEPLOYMENT_2026-10-03.md)을 참고한다.
