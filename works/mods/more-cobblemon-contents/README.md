# More Cobblemon Contents Core

<img src="src/main/resources/assets/more_cobblemon_contents/icon.png" alt="More Cobblemon Contents Core icon" width="128">

Cobblemon에 전투 시설을 추가하는 MCC의 공통 기반 모드입니다. 실제 도전 콘텐츠는 원하는 애드온을 선택해 설치합니다.

## 주요 기능

- 전투 진행과 결과 처리를 공유하는 관리 전투 기반 및 내장 전술 AI
- Battle Points(BP), 보상 상점, 플레이어 전투 기록
- 배틀 허브와 홀로그램 터미널, 콘텐츠별 대시보드
- 애드온과 음악·연출 모드를 위한 공개 연동 API

## 애드온

| 애드온 | 콘텐츠 |
|---|---|
| [Battle Tower](../more-cobblemon-contents-battle-tower/README.md) | 자신의 팀으로 도전하는 연승전 |
| [Battle Factory](../more-cobblemon-contents-battle-factory/README.md) | 대여 포켓몬을 활용하는 도전 |
| [PvP](../more-cobblemon-contents-pvp/README.md) | 플레이어 대전방과 배틀 라운지 |
| [League Challenge](../more-cobblemon-contents-league-challenge/README.md) | 체육관·배지·사천왕·챔피언 진행 |

## 개발자 참고

허브 탭, 대시보드, 음악 연동 API와 운영 명령의 세부 사용법은 [DEVELOPER.md](DEVELOPER.md)에 있습니다.

## 설치

**클라이언트와 서버 양쪽에 설치합니다.** 싱글플레이에서도 같은 클라이언트 모드를 사용합니다.

| 항목 | 요구 사항 |
|---|---|
| Minecraft / Java | Java Edition 1.21.1 / Java 21 이상 |
| 로더 | Fabric Loader 0.19.5 이상 |
| 공통 필수 모드 | Fabric API, Fabric Language Kotlin 1.14.1+kotlin.2.4.20 이상, Cobblemon 1.8.1 이상 / 1.9.0 미만 |
| 본체 필수 모드 | Mega Showdown `1.2.0+1.8.1+1.21.1-release` |
| 선택 연동 | MCC 콘텐츠 애드온, Better Cobblemon Battlecam, Better Cobblemon Music |

1. 서버와 접속할 클라이언트의 `mods` 폴더에 모드 JAR과 필수 모드를 넣습니다. 필요한 지원 라이브러리는 각 의존 모드의 설치 안내를 따릅니다.
2. 본체와 사용하려는 애드온을 같은 구성으로 설치합니다. 애드온은 본체 없이 사용할 수 없습니다.
3. 플레이어는 `/mcc` 또는 콘텐츠의 홀로그램 터미널로 배틀 허브를 엽니다. 서버에서 해당 탭을 허용해야 접근할 수 있습니다.

UI 키트는 본체 JAR에 포함돼 있으므로 MCC용 UI 키트를 별도로 복제해 설치할 필요는 없습니다.

## 설정과 콘텐츠 편집

본체 설정은 서버가 관리합니다. 현재 본체에는 별도 Mod Menu 설정 화면이 없습니다. 플레이어는 `/mcc` 또는 콘텐츠 터미널로 허브를 엽니다.

- `config/more-cobblemon-contents/hub_tabs.json`: `/mcc`와 각 터미널에 표시할 탭, 허브 명령어 권한.
- `config/more-cobblemon-contents/wiki.json`: 서버 위키 연동 설정.
- `config/more-cobblemon-contents/bp-shop.json`: BP 상점 전체를 관리하는 단일 JSON입니다. 최초 로드 때 기본 파일을 생성하고 이후에는 이 파일만 읽습니다.
  - `entries`: 판매 품목 목록. 품목을 추가·삭제하거나 `price_bp`를 바꾸고 `/reload`하면 적용됩니다. 삭제한 품목은 재시작해도 돌아오지 않으며, `[]`는 빈 상점입니다.
  - `categories`, `shopkeeper`, `limits`: 분류 순서·상점 외형·구매 제한도 같은 파일에서 관리합니다. 품목이 없는 분류는 표시하지 않습니다.
  - 잘못된 JSON은 기존 정상 상점을 유지하고 오류를 기록합니다. 파일 자체를 삭제하면 기본 파일을 다시 생성합니다.
  - 기본값은 볼·사탕·학습장치·PP에이드 계열을 제외하고 마법이 부여된 황금 사과를 120 BP로 판매합니다.

서버 설정과 데이터팩을 수정한 뒤 관리자 권한으로 `/reload`를 사용합니다. `/mcc`의 기본 허브 접근 권한은 0이며, 운영 명령은 권한 2를 요구합니다. `/mcc bp`와 `/mcc bp history [count]`로 자신의 BP를 조회할 수 있습니다.

## 문제 해결

- 모드가 로드되지 않으면 클라이언트·서버의 모드 구성과 필수 의존성을 확인하세요.
- 허브에 콘텐츠가 보이지 않으면 해당 애드온 설치 여부와 본체의 `hub_tabs.json` 설정을 확인하세요.
- 데이터팩을 수정한 뒤 문제가 생기면 서버 로그에서 잘못된 콘텐츠 정의를 확인하세요. 모드 JAR을 직접 수정하는 대신 서버 데이터팩을 사용합니다.

## 라이선스와 배포 설명

이 모드의 자체 코드와 아이콘은 [MIT License](LICENSE)로 제공합니다. 의존 모드와 내장 라이브러리는 각각의 라이선스를 따릅니다.

내장 `cobblemon-ui`는 별도 저작권·라이선스 고지를 유지합니다. 원본 Cobblemon Extended Battle UI의 MIT 고지는 내장 UI JAR의 `THIRD_PARTY_LICENSE_CobblemonExtendedBattleUI`에 포함돼 있습니다. 자세한 고지는 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)에 있습니다.

공개 배포 페이지용 영문 설명은 [MODRINTH.md](MODRINTH.md)에 있습니다. [소스](https://github.com/taku7664/Minecraft-Cobblemon-Mods/tree/main/works/mods/more-cobblemon-contents) · [문제 신고](https://github.com/taku7664/Minecraft-Cobblemon-Mods/issues)
