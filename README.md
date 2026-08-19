# Cobblemon Mods

Cobblemon을 하면서 아쉬웠던 부분을 하나씩 직접 채워 가기 위해 만들었던 모드들입니다.
Minecraft 1.21.1과 Cobblemon 1.7.3을 기준으로 만들고 있으며, 각 모드는 서로 따로 설치해서 사용할 수 있습니다.
모드 아이콘이나 안정성은 차츰 다듬어가고 있습니다.

## 모드 소개

### More Battle Content

배틀타워, 배틀팩토리, PvP 같은 배틀 콘텐츠를 한곳에서 즐길 수 있게 하는 본체 모드입니다.
BP 보상과 상점, 홀로그램 배틀 터미널도 이 모드에 들어 있습니다.

<img width="384" height="39" alt="image" src="https://github.com/user-attachments/assets/25dcb87d-cae6-490d-9060-d87a3a9f4f5e" />
<img width="1252" height="671" alt="image" src="https://github.com/user-attachments/assets/3eed25c9-88f1-4fae-bd02-f49c2c3100ec" />
<img width="1264" height="678" alt="image" src="https://github.com/user-attachments/assets/fb8530f4-29e9-478b-8c44-42203253de60" />
<img width="1276" height="671" alt="image" src="https://github.com/user-attachments/assets/eea10968-52f7-41f9-a7fe-ba2667f91ae7" />


### More Battle Content - Better AI

배틀타워와 배틀팩토리의 상대가 조금 더 사람답게 판단하도록 도와주는 선택형 AI 애드온입니다.
이 모드가 없어도 More Battle Content 본체는 그대로 작동합니다.

### Better Battle Presentation

배틀 중 하늘이 붉게 물드는 다이맥스 분위기와 같은 연출을 담당하는 모드입니다.
배틀의 규칙을 바꾸기보다는, 눈에 보이는 장면을 더 멋지게 만드는 데 집중합니다.

<img width="1279" height="675" alt="image" src="https://github.com/user-attachments/assets/940e31a0-c0dc-4bd9-aa18-52694129da3a" />

<img width="1285" height="678" alt="다이맥스 소개" src="https://github.com/user-attachments/assets/1cb605be-cd3a-4c86-9ba2-0bd1a259b04a" />

### Better Cobblemon Music (Client-Only)

상황에 맞는 음악을 골라 재생하고, 전투 음악이 자연스럽게 이어지도록 해 주는 클라이언트 모드입니다.
바이옴 별 테마 곡, 전설 조우, 야생 배틀

### Better Cobblemon Music - More Battle Content 연동 (Client-Only)

More Battle Content의 배틀 상황을 Better Cobblemon Music이 알아듣도록 연결해 주는 작은 연동 모드입니다.
두 모드를 함께 쓸 때만 설치하면 됩니다.

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
