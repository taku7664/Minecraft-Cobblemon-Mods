# Linux 서버 실행과 패키징

## 현재 서버 루트에서 실행: run.sh

운영 VPS 서버 파일 루트는 `/srv/MinecraftPPakemonServer`입니다. 실행 파일 `run.sh`, 모드 `mods/`, 설정 `config/`, 월드 `world/`가 이 안에 있습니다.

`run.sh`는 운영 저장소 루트의 Fabric 런처·설정·월드를 그대로 사용합니다.
별도 `release/data` 폴더나 월드 심볼릭 링크를 만들지 않습니다. 기존 Windows `run.bat`과
PowerShell 시작 훅도 유지하며, Linux에서는 같은 시작 훅을 PowerShell 7로 실행합니다.
Java 21 이상과 PowerShell 7 이상(`pwsh`)을 설치하고 운영 설정·EULA를 준비한 뒤 실행합니다.

```bash
cd /srv/MinecraftPPakemonServer
bash run.sh
# 메모리 크기를 바꾸려면:
JAVA_XMS=2G JAVA_XMX=6G bash run.sh
```

`JAVA_XMS`·`JAVA_XMX`의 기본값은 2G·4G입니다. `JAVA_HOME`으로 Java 경로를 지정할 수 있습니다.
시작 훅이 실패하면 게임 서버를 실행하지 않습니다. 종료 후 자동 재시작은 하지 않으며
재시작 정책은 호스트의 서비스 관리 기능에서 설정합니다. 기존 운영 `run.sh`는 로컬 패쳐가
바이트 그대로 보존하고, 없을 때만 개발 설치본에서 복사하며 개발 포트 25566 지정은 제거합니다.

Git Bash 모의 실행과 Windows에서의 훅 검증은 실제 Linux 서버 기동 검증과 구분합니다.
2026-10-10 메인 작업자의 직접 SSH 검증으로 Ubuntu VPS에서 운영 `run.sh`의 실제 기동과
서버 내부 Minecraft status 응답·Voice Chat UDP 리스닝·위키 localhost HTTP 200을 확인했습니다.
공인 TCP 25565의 Minecraft status 응답과 UDP 24454 패킷의 VPS 도달도 확인했습니다.
실제 클라이언트 입장·음성 양방향 대화·AI 문의·10명 부하는 미검증입니다.
이 문서의 기록 담당자는 원격 검증을 재실행하지 않았습니다.

### VPS tmux 콘솔

현재 서버는 `ppakemon` tmux 세션에서 실행하며 SSH 접속이 끝나도 유지됩니다.
VPS에 SSH로 접속한 뒤 콘솔을 엽니다.

```bash
tmux attach -t ppakemon
```

콘솔에서 분리하려면 `Ctrl+B`를 누른 다음 `D`를 누릅니다. 서버를 정상 종료하려면 콘솔에
`stop`을 입력합니다. 동일 세션이나 서버가 이미 실행 중이면 아래 명령으로 중복 실행하지 않습니다.
정상 종료한 서버를 다시 실행할 때는 다음 명령을 사용합니다.

```bash
cd /srv/MinecraftPPakemonServer
tmux new-session -d -s ppakemon -c /srv/MinecraftPPakemonServer 'export PATH="$HOME/.local/bin:$PATH"; export JAVA_XMS=2G JAVA_XMX=4G; bash ./run.sh'
```

systemd 서비스 설치·자동 재시작·재부팅 자동 시작은 아직 적용하지 않았습니다.

#### Windows에서 바로 열기

이 PC 바탕화면의 **빡케몬 서버 콘솔**을 더블클릭하면 PowerShell 창에서 현재 Minecraft 서버
콘솔로 접속합니다. `list`나 `op <닉네임>` 같은 서버 명령을 입력할 수 있습니다.
콘솔에서 빠져나오려면 `Ctrl+B` 다음 `D`, 서버를 정상 종료하려면 `stop`을 사용합니다.
기존 SSH 키 파일이 이 PC에 있어야 합니다. 바로가기는 PC 로컬 전용이며 원격 운영 저장소나
배포 제품에 넣지 않습니다.

## 기존 별도 패키징: package-server.ps1 / start-linux.sh

아래 방식은 새 ZIP의 `release/`와 외부 `SERVER_DATA_DIR`을 분리하는 기존 패키징 도구입니다.
`start-linux.sh`는 데이터 폴더를 링크하고 Java를 실행하며 현재 `run.sh`의 시작 훅 흐름과 다릅니다.
운영 저장소를 그대로 실행하려면 위의 `run.sh` 방식을 사용합니다.

기준: Minecraft 1.21.1 / Fabric Loader 0.19.5 / launcher 1.1.2 / Java 21.
기존 run.bat과 원본 설정은 변경하지 않는다. PowerShell 5.1 이상에서 실행한다.
예시의 ZIP 출력 폴더는 미리 만들어 둔다. pwsh가 없으면 powershell로 대체한다.

```powershell
# 저장소 루트에서 먼저 포함/누락 목록 검토 (파일을 만들지 않음)
pwsh -File works/tools/server-deploy/package-server.ps1 -DryRun
# 전체 패키지는 사용자가 필요할 때 직접 생성. 기존 ZIP은 덮어쓰지 않는다.
pwsh -File works/tools/server-deploy/package-server.ps1 -OutputZip C:\배포\server-20261001.zip
# 월드가 필요하면 서버 운영 중이 아닌 별도 백업 스냅샷만 명시한다.
pwsh -File works/tools/server-deploy/package-server.ps1 -OutputZip C:\배포\server-with-world.zip -WorldSnapshot C:\백업\offline-world
```

기본 포함: 버전이 지정된 Fabric 런처, Fabric 메타데이터에서 client 전용이 아닌 mods/*.jar,
config, defaultconfigs, plaza-assets, Cobblemon showdown 시뮬레이터와 formats/custom-formats,
Linux 실행 파일, 안전한 server.properties.example, 포함/누락 manifest.json.
libraries/versions는 포함하지 않으며 첫 실행 때 런처가 받는다(인터넷 필요).
클라우드에 준비한 기본 서버와 파일을 섞지 않고 새로운 릴리스 폴더를 사용한다.

기본 제외: 모든 월드, 로그·백업·캐시·.fabric·.mixin.out·.omc·journeymap 런타임 데이터,
플레이어/운영자/화이트리스트/차단 목록, eula.txt, 기존 run.bat 및 운영 도구,
showdown 운영 server/tools/config-example, 소스맵(.map), OpenRouter 설정, node_modules, 소스 저장소와 빌드 결과.
plaza-assets에는 지도 월드 사본도 있어 .dat/.mca 및 region/entities/poi 등 월드 데이터는
제외하고 .schem 같은 배치용 자산만 포함한다. 설정 폴더의 미검토 바이너리/DB/압축파일은 제외한다.
설정의 비밀번호/API 키 등의 키 또는 URL·IP·UUID가 발견되면 파일 전체를 누락한다.
Showdown 코드에서는 일반 token 변수와 전투 정보 표시용 secret 인자를 보존하고
문자열로 지정한 비밀번호/API 자격 증명을 검사한다.
라이선스 문서의 공개 URL은 보존한다. 필수 시뮬레이터 파일이 누락되면 패키징은 중단한다.
서버 접속·RCON·리소스팩 주소는 복사하지 않고 비활성화된 예제 설정을 만든다.
누락 설정은 manifest를 확인해 Linux에서 별도로 재설정한다. 로컬 Windows 절대 경로를
쓰는 설정도 Linux 경로로 검토한다. 이 검사는 모든 형태의 비밀을 보장하지 않으므로
배포 전에 설정을 검토한다. JAR/그림/음원 등 바이너리는 내용 검사하지 않는다.

월드 옵션은 region/entities/poi, DIM-1/DIM1/dimensions, datapacks/serverconfig,
level.dat/level.dat_old만 world-seed/world에 넣는다. playerdata/stats/advancements 및
모드별 플레이어 DB는 넣지 않으므로 플레이어 진행 상황은 이전되지 않는다.
level 메타데이터에 Player 태그가 있으면 중단한다. 월드 자체의 건축물·표지판 등은
보존되므로 스냅샷 사용자가 개인정보를 확인해야 한다. 별도 모드 저장 데이터는
이 목록 밖이면 포함되지 않는다. 현재 로컬 월드에서 최상위 datapacks는 발견되지 않았다.

## Linux 최초 준비와 실행

```bash
# ZIP을 비어 있는 새 경로에 해제 (예: /srv/cobblemon/releases/20261001)
mkdir -p /srv/cobblemon/data/world
cp /srv/cobblemon/releases/20261001/release/server.properties.example /srv/cobblemon/data/server.properties
# https://aka.ms/MinecraftEULA 를 읽고 동의한 경우에만 직접 작성
printf 'eula=true\n' > /srv/cobblemon/data/eula.txt
SERVER_DATA_DIR=/srv/cobblemon/data JAVA_XMS=2G JAVA_XMX=6G \
  bash /srv/cobblemon/releases/20261001/release/start-linux.sh
```

월드 복원 시에는 위 mkdir 전에 **존재하지 않는 새 data 경로**로 world-seed/world를
복사한다. 기존 월드에 ZIP을 직접 해제하거나 world-seed를 합치지 않는다.
재배포는 새 releases/<버전> 경로에 해제하고 동일 SERVER_DATA_DIR을 지정한다.
실행 스크립트는 월드를 복사하지 않고 심볼릭 링크만 연결하며 기존 다른 대상이 있으면 중단한다.
실제 전환은 운영자가 기존 서버를 정상 종료한 후 진행한다. 동일 월드를 두 서버에서 실행하지 않는다.
리소스팩 URL, Voice Chat 포트, 운영자 목록, API 키는 대상 서버에서 별도로 설정한다.
JAVA_HOME으로 Java 경로 지정 가능. mods/config의 런타임 변경은 릴리스 안에 남으므로
새 릴리스 전 운영자가 필요한 설정 변경을 별도로 반영한다.

## 검증과 한계

```powershell
pwsh -File works/tools/server-deploy/package-server.tests.ps1
```

fixture ZIP으로 한글 경로, ZIP 상대 경로, client 모드/비밀 설정 제외, 원본 설정 보존,
기존 ZIP 덮어쓰기 거부, live-world 지정 거부, 월드 스냅샷 분리와 playerdata 제외를 검사한다.
초기 실제 서버 dry-run은 1,349개 입력과 19개 누락을 확인했다. 파일명과 누락 사유만
담은 dry-run-report.json은 그 시점의 목록이며 이후에는 -DryRun으로 다시 확인한다.
Bash 구문과 가짜 Java를 사용한 심볼릭 링크/재실행/거부 조건도 검사했다.
실제 Linux 서버 부팅, 모드 의존성·게임 내 동작, 클라우드 네트워크/포트는 검증하지 않았다.
전체 서버 ZIP 생성·업로드·배포·커밋·푸시는 수행하지 않았다.
