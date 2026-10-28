# More Cobblemon Contents: PWT

<img src="src/main/resources/assets/more_cobblemon_contents_pwt/icon.png" alt="More Cobblemon Contents: PWT icon" width="128">

MCC에 포켓몬 월드 토너먼트(PWT)를 추가하는 애드온입니다. 역대 세대의 강자 8명이 단판 토너먼트로 겨루고, 3연승하면 우승합니다.

## 주요 기능

- MCC 허브의 PWT 탭과 PWT 터미널에서 참가
- 8인 단판 토너먼트: 플레이어 1명과 무작위로 뽑힌 트레이너 7명, 8강·4강·결승
- 양쪽으로 펼친 대진표: 칸마다 얼굴만 보이고, 마우스를 올리면 이름·세대·대표 포켓몬이 보임
- 우승하면 25BP를 받고, 전체 채팅(닉네임)과 MCC 소식으로 알림

## 대회 규칙

| 항목 | 규칙 |
|---|---|
| 형식 | 싱글배틀, 포켓몬 3마리를 골라 대회 끝까지 그대로 사용 |
| 레벨 | Lv. 50을 넘는 포켓몬은 전투에서만 50으로 맞춤. 낮은 포켓몬은 그대로 |
| 제한 | 같은 포켓몬·같은 도구 금지, 전설·환상·울트라비스트·패러독스 금지(상대도 같음), 기믹 전부 금지 |
| 회복 | 매 경기 전에 전부 회복 |
| AI | 8강 Advanced, 4강·결승 Boss |
| 보상 | 우승 25BP. 그 밖의 성적은 보상 없음 |
| 이탈 | 기권하거나 접속을 끊으면 그 자리에서 탈락 |
| 참가 조건 | 포켓몬리그 챔피언(리그 애드온이 없으면 누구나 참가) |

## 설치

**클라이언트와 서버 양쪽에 설치합니다.**

| 항목 | 요구 사항 |
|---|---|
| Minecraft / Java | Java Edition 1.21.1 / Java 21 이상 |
| 로더 | Fabric Loader 0.19.5 이상 |
| 공통 필수 모드 | Fabric API, Fabric Language Kotlin 1.14.1+kotlin.2.4.20 이상, Cobblemon 1.8.1 이상 / 1.9.0 미만 |
| 기반 모드 | More Cobblemon Contents Core 0.1.0 이상 / 1.0.0 미만 |
| 선택 모드 | More Cobblemon Contents: League Challenge — 챔피언 참가 조건에 사용 |

레드·그린·비주기·목호·난천·태홍의 외형은 RCT Trainers+ 리소스팩에서 가져옵니다. 팩이 없으면 그 칸은 얼굴 없이 표시됩니다.

## 상대 편집

상대는 서버 데이터팩의 `pwt/trainers/*.json`에서 정의합니다. 7명 이상이어야 대진을 짤 수 있습니다. 한 트레이너의 `pokemon`은 3~6마리이고, 경기마다 그중 3마리를 무작위로 뽑습니다. 그래서 같은 종과 같은 도구가 겹치지 않아야 하며 모두 `level=50`이어야 합니다.

기본 상대 17명은 `works/tools/pwt/generate_trainers.py`의 표에서 만듭니다. 표를 고친 뒤 스크립트를 다시 실행하고, 스킨을 바꿨다면 `works/tools/generate_managed_trainer_variations.py`도 실행합니다. 데이터팩 변경 후 관리자 권한으로 `/reload`를 실행합니다.

## 라이선스와 배포 설명

이 모드의 자체 코드와 아이콘은 [MIT License](LICENSE)로 제공합니다. 트레이너 스킨 11종은 Planet Minecraft 제작자의 팬 스킨이며, 제작자와 원본 페이지는 `textures/npcs/pwt/CREDITS.txt`에 있습니다.
