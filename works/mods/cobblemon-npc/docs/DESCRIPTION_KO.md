# Cobblemon NPC

게임 안에서 대화하는 NPC를 만들고 편집하는 모드입니다. 이름과 스킨, 여러 페이지의 대사, 선택지, 플레이어 조건에 따른 분기, 대화 진행에 맞춰 실행할 명령을 설정할 수 있습니다. 대화 진행과 편집 권한은 서버가 관리합니다.

![NPC 대화](media/dialogue-scene.png)

2026년 10월 6일 실제 게임 캡처입니다. 표시된 트레이너 스킨은 외부 리소스팩이며 카메라는 선택 설치 모드인 Better Cobblemon Battlecam을 사용합니다.

![대화창 잘라보기](media/dialogue-window.png)

## 설치

클라이언트와 서버에 같은 버전을 설치하세요. Minecraft 1.21.1, Java 21, Fabric Loader 0.19.5 이상, Fabric API, Fabric Language Kotlin 1.14.1+kotlin.2.4.20 이상, Cobblemon 1.8.1(1.9.0 미만)이 필요합니다. Cobblemon UI는 포함됩니다. Mod Menu와 Better Cobblemon Battlecam은 선택 설치이며 트레이너 스킨팩은 포함되지 않습니다.

## 사용 및 설정

권한 수준 2 이상의 운영자는 `/npc wand`로 지팡이를 받습니다. 블록을 우클릭하면 NPC를 배치하고, 지팡이로 NPC를 우클릭하면 이름·스킨·대화 편집, 미리보기, 삭제 화면을 엽니다. 웅크리고 NPC를 우클릭하면 이름·스킨·대화를 지팡이에 복사하고, 웅크리고 블록을 우클릭하면 그 복사본을 배치합니다. 플레이어는 NPC를 우클릭해 대화합니다. 스킨 버튼은 활성 모드·리소스팩의 트레이너·NPC 스킨을 검색하고 미리 보여 줍니다. 대화의 목록 버튼은 서버에 있는 대화 ID를 검색해 선택합니다.

서버 대화 파일은 `config/cobblemon_npc/dialogues/<id>.json`입니다. 직접 수정한 뒤 `/npc reload`로 다시 읽습니다. `/npc talk <players> <dialogue> [node]`로 NPC 없이 대화를 열고 `/npc end <players>`로 닫습니다. JSON과 조건, 명령 권한은 [운영자 가이드](OPERATOR_GUIDE.md)를 참고하세요.

Mod Menu 설정 화면에서는 대화 카메라를 켜고 끕니다. 기본값은 켜짐이며 Better Cobblemon Battlecam이 필요합니다. 완료 버튼은 `config/cobblemon_npc-client.json`에 저장하고 취소는 변경을 버립니다. NPC별 내용은 지팡이 화면에서 편집합니다. 대화 조작 키는 포함된 Cobblemon UI의 키 설정을 사용합니다.

원본 모듈 코드는 [MIT](../LICENSE)입니다. 포함된 라이브러리의 고지는 [NOTICE](../NOTICE.md)를 확인하세요. 게임·외부 스킨이 보이는 스크린샷은 해당 자산의 재배포 권리를 부여하지 않습니다.
