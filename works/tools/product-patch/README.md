# 서버 / 클라이언트 통합 패쳐

`develop-product/patch-products.bat` 하나로 개발 서버와 클라이언트의 배포 프로필 갱신 창을 엽니다.
**서버 / 클라이언트** 탭마다 원본·대상 폴더, 변경 목록 검사, 적용과 최근 복구 버튼이 있습니다.
실제 적용 전 목록을 확인하며, 검사를 누르는 것만으로 게임 파일을 바꾸지 않습니다.

| 탭 | 기본 원본 | 기본 대상 |
|---|---|---|
| 서버 | 저장소 `develop-product/server` | 같은 부모 폴더의 `MinecraftPPakemonServer` |
| 클라이언트 | 저장소 `develop-product/client` | `%APPDATA%/ModrinthApp/profiles/PPakemon` |

서버는 기존 로컬 패쳐의 설정·월드·플레이어·광장 보존 및 기능 파일 복구를 그대로 사용합니다.
서버 복사에서는 JourneyMap 모드·설정, BlueMap 비활성 지도 설정, Showdown 예제와 문의 번역 파일을 제외합니다.
BlueMap 저장소 설정은 개발·운영 서버의 활성 지도가 사용하는 백엔드와 기본 `file`만 포함하며, 표기를 해석하지 못하면 전체를 유지합니다.
클라이언트는 모드·시작 훅·위키·지정 기능 리소스팩을 갱신하며 위키 외 개인 컨픽·단축키·셋업 기록·
서버 목록·월드·지도·추가 모드·셰이더를 보존합니다. 자세한 범위는
[`../client-patch/README.md`](../client-patch/README.md)에 있습니다.

클라이언트의 위키 원본은 저장소의 최신 `server-wiki`입니다. 서버 탭의 원본은 기존대로 개발 서버 설치 파일입니다.
게임·서버를 정상 종료한 뒤 패치하세요. 자동 빌드·게임 기동·런처 버전 변경은 하지 않습니다.
클라이언트 백업은 `%LOCALAPPDATA%/MinecraftClientPatch`, 서버 백업은 `%LOCALAPPDATA%/MinecraftServerDeploy`에 둡니다.

## 설치

커밋·main 병합·푸시 후 메인 저장소에서:

```powershell
.\works\tools\product-patch\install-product-patcher.ps1 -MigrateLegacy
```

이 PC에 있는 로컬 서버 패쳐 소스 `works/tools/server-deploy/deploy-server.ps1`와
`server-deployment.psm1`이 필요합니다. 해당 소스는 기존 결정대로 원격에 게시하지 않습니다.
설치본은 `develop-product/tools/patcher/`에 모으며 원본과 SHA-256을 비교합니다.
런처·설치본은 Git-ignored 개발 제품 폴더에 있으므로 원격 서버나 릴리스 제품에 포함되지 않습니다.

설치기는 게임 파일을 패치하지 않습니다. 원본을 고친 뒤 설치기를 다시 실행하면 패쳐 자체만 갱신합니다.
`-MigrateLegacy`는 같은 구현의 이전 운영 서버 패쳐를 외부에 백업하고 제거합니다.
기존 `deploy-from-dev.bat`는 통합 패쳐의 서버 탭을 여는 연결로 전환합니다.
기존 구현에 별도 변경이 있으면 제거하지 않습니다. 서버 시작 훅과 게임 데이터는 이동하지 않습니다.
