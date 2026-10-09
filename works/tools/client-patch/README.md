# 클라이언트 프로필 패쳐

개발 클라이언트에 설치된 기능 파일을 Modrinth의 `PPakemon` 배포 프로필로 갱신하는 Windows 로컬 도구입니다.
게임을 종료한 뒤 저장소의 `develop-product/patch-products.bat`를 실행하고
**클라이언트 탭 → 변경 목록 검사 → 변경사항 패치**를 누릅니다.
폴더를 바꾸면 반드시 다시 검사합니다. 빌드·다운로드·게임 실행은 자동으로 하지 않습니다.

기본 원본은 `%USERPROFILE%/Documents/GitHub/Cobblemon-Mods/develop-product/client`,
대상은 `%APPDATA%/ModrinthApp/profiles/PPakemon`, 위키는 이 도구가 있는 저장소의 `server-wiki`입니다.
Minecraft 1.21.1 / Fabric 프로필을 런처에서 먼저 만들어야 합니다. Minecraft·Fabric Loader·Java·런처 프로필은 패치하지 않습니다.

## 패치 범위

| 대상 | 처리 |
|---|---|
| `mods/*.jar` | Fabric 메타데이터로 클라이언트/공용 모드를 고릅니다. 같은 모드 ID의 기존 JAR만 제거하고 새 버전을 설치합니다. 서버 전용 모드와 BlueMap·Map Link는 제외합니다. |
| 시작 훅 | `cobblemon-client-setup` JAR을 갱신합니다. 이전 `cobblemon-client-defaults` JAR은 제거합니다. 설정과 적용 완료 기록은 보존합니다. |
| 위키 | `config/more-cobblemon-contents/wiki/`의 홈·문서·도감 데이터·이미지·스크립트·스타일·폰트를 저장소의 최신 파일로 갱신합니다. README·MEMORY·문서 템플릿은 제외합니다. |
| 기능 리소스팩 | 음악, 코블몬 한국어 번역, Xaero 포켓몬 아이콘, Galmuri 폰트, RCT 트레이너 외형, 스폰 알림의 ZIP 6계열만 복사합니다. 기존 같은 계열 ZIP의 이름을 유지하면서 내용도 갱신합니다. |

BlueMap·Map Link JAR과 관련 설정·전용 데이터, 위키 지도 페이지·스크립트는 재설치하지 않습니다.
대상에 남은 해당 JAR은 파일명이 바뀌었어도 Fabric 모드 ID로 확인해 제거합니다.

개인 설정(`config`의 위키 외 전부), `defaultconfigs`, `options.txt`, `optionsshaders.txt`,
`servers.dat`, 월드·플레이어·지도·웨이포인트, 계정, 개인 추가 모드, 셰이더와 다른 리소스팩은 변경하지 않습니다.
개발 폴더에서 사라진 파일은 이 패쳐가 이전에 관리한 파일만 삭제합니다. 파일명으로 개인 모드를 일괄 삭제하지 않습니다.
셰이더·Whimscape·CCC·MoreRadicalTextures 등 그래픽 선택 팩은 복사하지 않습니다.
폴더형 리소스팩은 갱신하지 않습니다. 새로운 팩은 자동 활성화하지 않으므로 게임에서 직접 선택하세요.
기존 선택된 ZIP은 같은 이름으로 갱신하므로 팩 선택과 순서를 보존합니다.

패쳐는 개인 컨픽을 쓰지 않지만, 다음 게임 시작에서 셋업 모드는 **기존 ONCE/ALWAYS 규칙**을 실행합니다.
새 훅 규칙의 최초 적용 여부까지 고정하지는 않습니다. 이를 바꾸려면 셋업 모드의 설정 화면에서 조절하세요.

## 검사와 복구

SHA-256으로 같은 파일은 건너뜁니다. 검사 후 원본·대상 변경은 재검사를 요구합니다.
실행 중인 Minecraft와 잠긴 JAR을 검사하고, 하위 정션/심볼릭 링크·폴더 탈출 경로는 거부합니다.
원본의 루트 정션은 개발 프로필의 기존 구조를 위해 허용합니다.

교체·삭제 파일과 작업 기록은 게임 밖 `%LOCALAPPDATA%/MinecraftClientPatch`에 보관합니다.
적용 실패 시 교체 파일을 자동 복구합니다. 강제 종료로 중단됐으면 **최근 패치 복구**를 먼저 누릅니다.
복구도 개인 설정과 플레이 진행을 건드리지 않으며 패치 후 별도로 수정한 파일은 덮어쓰지 않고 중단합니다.

창 없이 검사만 하려면:

```powershell
.\patch-client.ps1 -Mode Preview
```

검증:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\client-patch.tests.ps1
```

모드 ID 교체, 개인 파일 보존, 위키 교체, 재실행, 복구, 오래된 검사 거부,
리소스팩 이름 유지, 작업 중 실패 자동 복구, 후속 사용자 수정 보호와 경로 거부를 임시 폴더에서 확인합니다.
실게임 호환성·모드별 동작·새 리소스팩의 실제 화면/음악 확인은 별도입니다.
