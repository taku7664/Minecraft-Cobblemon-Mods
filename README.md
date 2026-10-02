# Cobblemon Mods

Cobblemon을 하면서 아쉬웠던 부분을 하나씩 직접 채워 가기 위해 만들었던 모드들입니다.
Minecraft 1.21.1과 Cobblemon 1.8.1을 기준으로 만들고 있으며, 각 모드는 서로 따로 설치해서 사용할 수 있습니다.
모드 아이콘이나 안정성은 차츰 다듬어가고 있습니다.

## 모드 소개

### More Cobblemon Contents

배틀타워, 배틀팩토리, PvP, 리그 챌린지 같은 콘텐츠를 한곳에서 즐길 수 있게 하는 모드 묶음입니다.
본체(`more-cobblemon-contents`)에는 관리 배틀 엔진, 상대 트레이너 AI, BP 보상과 상점, 전적,
홀로그램 배틀 터미널과 모든 콘텐츠를 모아 보는 Hub 화면이 들어 있습니다. 배틀타워, 배틀팩토리,
PvP, 리그 챌린지는 각각 따로 설치하는 콘텐츠 모드이며, 설치한 콘텐츠만 Hub에 탭으로 나타납니다.

플레이어는 `/mcc`나 홀로그램 터미널로 Hub를 엽니다. `/mcc`로 여는 Hub에는 대시보드, 상점, PvP가 나옵니다. 리그, 배틀타워, 배틀팩토리는 각자의 홀로그램
터미널에서 열리고, 이 터미널들은 기본적으로 대시보드와 상점, 그리고 자기 콘텐츠를 보여 줍니다. 본체의 홀로
배틀 터미널은 모든 콘텐츠를 보여 줍니다. 어느 탭을 보여 줄지는 서버의
`config/more-cobblemon-contents/hub_tabs.json`에서 바꿀 수 있습니다. `command`는 `/mcc`에,
`terminals`는 터미널 블록 ID별로 적용됩니다. `command_permission_level`은 `/mcc`로 Hub를 여는 데 필요한 권한
레벨로, 기본값 0이면 모든 플레이어가, 2면 OP만 열 수 있습니다. 고친 뒤 `/reload`하면 반영됩니다.

상대 AI는 기본으로 본체 안에서 작동하고, OpenRouter API 키를 설정하면 사람과 대결하는 듯한
판단을 더할 수 있습니다.

트레이너와 포켓몬 목록은 데이터팩으로 늘릴 수 있습니다. 배틀타워는 `mcc-battle-tower`,
배틀팩토리는 `mcc-battle-factory`, BP 상점은 `mcc-bp-shop`, 리그 챌린지는 `league-challenge`
아래에서 종류별 폴더로 나뉩니다. 파일 하나라도 잘못됐거나 ID가 겹치면 일부만 적용하지 않고,
이전에 정상적으로 읽은 전체 목록을 그대로 사용합니다. 모듈 구성은
[`more-cobblemon-contents/README.md`](more-cobblemon-contents/README.md)에 적어 두었습니다.

#### 야생 트레이너

리그 챌린지를 설치하면 월드에 트레이너가 나타납니다. 등산가는 산에, 수영선수는 해변에, 영매는 밤에 나오는 식으로
일반 트레이너 104종과 에이스 트레이너 20종(엘리트 트레이너, 베테랑, 드래곤 조련사, 타입별 엘리트)마다 출현 지역과
포켓몬이 다릅니다. 에이스는 드물게 나옵니다. 말을 걸어 승부를 받으면 자기 포켓몬으로 일반 Cobblemon 배틀을 하므로
경험치와 진화가 그대로 적용됩니다. 이기면 일반 트레이너는 10 BP, 에이스는 20 BP를 주고, 승패는 채팅으로 알려 줍니다.
같은 트레이너와는 잠시 뒤에 다시 붙을 수 있고, 진 트레이너는 곧 떠납니다.

트레이너의 파티는 도전한 플레이어의 리그 레벨 캡에 맞춰 승부를 걸 때마다 새로 짭니다. 누구든 1~6마리를 데려오지만
많이 데려올수록 평균 레벨이 캡에서 멀어지고, 마지막 포켓몬이 가장 높습니다. 기술, 성격, 개체값, 노력치, 지닌 도구,
배틀 AI는 캡 구간(25 미만, 45 미만, 65 미만, 그 이상)과 등급에 따라 좋아집니다. 초반 일반 트레이너는 배운 기술을 거의
아무렇게나 쓰고 도구도 드물지만, 후반 에이스는 자속 기술과 견제기를 고르고 성격이 맞으며 생명의구슬 같은 도구도
듭니다. 일격필살기, 회피율 올리기, 자폭, 반동으로 쉬는 기술, 버섯포자, 구애 도구, 기합의띠 같은 것은 누구도 쓰지
않고, 한 파티에 같은 도구는 하나만 듭니다. 트레이너 데이터는
[`tools/wild-trainers`](tools/wild-trainers)의 생성 스크립트로 만듭니다.

트레이너 외형은 RCT Trainers+ 리소스팩의 스킨을 씁니다. 이 모드는 스킨 이미지를 담지 않고
경로만 가리키므로, 외형을 보려면 클라이언트에 그 리소스팩을 켜야 합니다. 팩이 없으면 기본 스티브 스킨으로 보입니다.
관장, 사천왕, 챔피언, 라이벌, 악의 조직처럼 이름 있는 인물과 악당 조무래기의 스킨은 야생 트레이너에게 쓰지 않습니다.
리그의 관장 8명, 사천왕, 챔피언 난천은 같은 팩에 있는 자기 스킨으로 나옵니다(일반과 하드 모두).
배틀타워와 배틀팩토리 트레이너도 이 팩의 트레이너 직업 외형을 입습니다. 이름에 맞는 성별의 외형을 돌아가며 쓰고,
타워 에이스는 에이스 트레이너, 드래곤 조련사 같은 강한 직업의 외형을 씁니다. 배정은
[`tools/facility-trainers`](tools/facility-trainers)의 스크립트로 만듭니다. 스킨이 없는 트레이너는 도전자 자신의 홀로그램으로 나옵니다.

#### OP 명령어

`/mcc` 자체는 `command_permission_level`을 따르고, 플레이어는 그 밖에 자기 BP를 보는 `/mcc bp`와
`/mcc bp history [개수]`만 쓸 수 있습니다. 위키 링크는 jbro-policy의 `/wiki`로 받습니다. 아래 표의 나머지 명령어는 모두 OP(권한 레벨 2)만 쓸 수 있습니다. 저장된 데이터를 보거나 고치는 명령어는
접속하지 않은 플레이어도 지정할 수 있고, 전투나 라운지처럼 접속 중일 때만 의미가 있는 명령어는
접속한 플레이어만 받습니다.

| 명령어 | 하는 일 |
|---|---|
| `/mcc` | `hub_tabs.json`의 `command` 탭으로 Hub를 엽니다 |
| `/mcc status` | 저장소 상태, 상점과 각 콘텐츠의 카탈로그, 세션·전투·결과 저장 대기 수, 리그 레벨 캡 설정 점검 결과 |
| `/mcc records reset <플레이어> [콘텐츠] [형식]` | 전적을 삭제합니다. 진행 중인 도전·전투·저장 대기 결과가 있으면 거부합니다 |
| `/mcc battle list` | 진행 중인 MCC 전투 |
| `/mcc battle end <플레이어> forfeit` | 기권시킵니다. 패배로 기록되고, PvP에서는 상대가 이깁니다 |
| `/mcc battle end <플레이어> void` | 결과 없이 끝냅니다 |
| `/mcc battle pending [list\|retry\|drop] [플레이어]` | 모든 콘텐츠의 결과 저장 대기열을 보고, 다시 저장하거나 버립니다 |
| `/mcc bp [get\|history\|add\|remove\|set] …` | BP 잔액, 거래 기록, 지급·차감·설정 |
| `/mcc test ai-입문\|ai-표준\|ai-상급\|ai-보스` | 난천 AI 테스트 전투를 시작합니다 |
| `/mcc test stop [플레이어]` | AI 테스트 전투를 끝냅니다 |
| `/mcc tower streak get\|set\|reset …` | 배틀타워 연승 |
| `/mcc tower session <플레이어>` | 배틀타워 세션의 단계, 등록 팀, 연승, 진행 중인 전투 |
| `/mcc tower abandon <플레이어> [force]` | 포기시킵니다. 전투 중이면 기권(패배)이고, 전투가 없으면 세션을 닫고 등록 팀을 풉니다. `force`는 전투가 사라져 기권할 수 없어도 정리합니다 |
| `/mcc factory floor get\|set\|reset …` | 배틀팩토리 층 |
| `/mcc factory session <플레이어>` / `abandon <플레이어> [force]` | 배틀팩토리 도전을 보거나 포기시킵니다(타워와 같음) |
| `/mcc pvp rooms` | 비공개 방까지 모든 PvP 방 |
| `/mcc pvp room close <플레이어>` | 그 플레이어의 방을 닫습니다. 전투는 결과 없이 끝내고, 준비 중인 경기는 취소하고, 라운지 인원은 원래 위치로 보냅니다 |
| `/mcc pvp room kick <플레이어>` | 방에서 내보냅니다. 진행 중인 경기의 선수는 내보내지 않습니다 |
| `/mcc pvp challenge cancel <플레이어>` | 대기 중인 도전 신청을 취소합니다 |
| `/mcc pvp arena list` / `arena release <번호>` | 경기장 칸을 보고, 반납되지 않은 칸을 회수합니다 |
| `/mcc pvp lounge rescue <플레이어>` | 배틀 라운지에서 원래 위치로, 없으면 월드 스폰으로 보냅니다 |
| `/mcc league inspect <플레이어>` | 리그 진행 전체: 클리어, 노말·하드 챔피언, 레벨 캡, 진행 중인 도전, 받지 못한 보상 |
| `/mcc league rewards list\|retry\|drop <플레이어>` | 받지 못한 보상을 보고, 다시 지급하거나, 지급하지 않고 완료로 표시해 다음 도전을 풉니다 |
| `/mcc league run cancel <플레이어>` | 진행 중인 리그 도전을 취소합니다 |
| `/mcc league cap sync <플레이어>` | 레벨 캡을 Cobbled Level Control에 다시 맞춥니다 |
| `/mcc league validate` / `catalog` | 레벨 캡 설정 점검, 리그 카탈로그 상태와 마지막 재로드 실패 이유 |
| `/mcc league import-badges <플레이어>` | 이미 가진 PokeBadges 배지를 리그 클리어로 가져옵니다(BP·챔피언은 주지 않음) |
| `/mcc league trainer spawn <종류>` | 그 자리에 야생 트레이너를 불러냅니다 |
| `/mcc league trainer despawn [반경]` / `list [반경]` | 주변 야생 트레이너를 없애거나(전투 중 제외) 봅니다 |
| `/mcc league trainer cooldown reset <플레이어>` | 그 플레이어의 재대결 대기를 없앱니다 |

### Better Battle Presentation

코블몬에서 아쉬운 여러 연출을 개선하기 위한 모드입니다.
현재는 배틀 중 하늘이 붉게 물드는 다이맥스 분위기와 같은 연출이 있습니다.
배틀의 규칙을 바꾸기보다는, 눈에 보이는 장면을 더 멋지게 만드는 데 집중합니다.

<img width="1279" height="675" alt="image" src="https://github.com/user-attachments/assets/940e31a0-c0dc-4bd9-aa18-52694129da3a" />
<img width="1285" height="678" alt="다이맥스 소개" src="https://github.com/user-attachments/assets/1cb605be-cd3a-4c86-9ba2-0bd1a259b04a" />

### Better Cobblemon Music (Client-Only)

상황에 맞는 음악을 골라 재생하고, 전투 음악이 자연스럽게 이어지도록 해 주는 클라이언트 모드입니다.
바이옴 별 테마, 전설 조우 테마, 야생 배틀, 피격음 등의 브금을 커스텀할 수 있습니다.

### Player Popup Emotes

V 키를 누른 채 골라서 플레이어 머리 위에 아이콘을 띄우는 이모트 모드입니다.
클라이언트와 서버에 모두 설치해야 하며, 내장 이모트 9개와 인게임에서 등록하는 PNG·JPEG·GIF URL 이모트를 지원합니다.

## 폴더를 어떻게 나눴나요?

각 모드 폴더 안에 그 모드의 코드, 설정, 리소스, 테스트와 간단한 설명을 함께 넣었습니다.
그래서 필요한 모드만 골라 빌드하거나 살펴보기 편합니다.

## 빌드하기

Java 21이 필요합니다. PowerShell에서 저장소 폴더로 이동한 뒤 다음 명령을 실행하면 됩니다.

```powershell
.\gradlew.bat build
```

완성된 JAR 파일은 각 모드 폴더의 `build/libs` 안에 생깁니다.

## 라이선스

[MIT License](LICENSE)
