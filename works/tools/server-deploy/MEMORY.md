# [2026-10-10 16:28 KST] 외부 게임 응답·음성 UDP 도달 확인

- 메인 작업자의 직접 검증 보고 기준으로 로컬 Windows Python socket에서 공인 `210.207.108.196:25565`의 Minecraft status handshake가 성공했습니다. 응답은 version.name `1.21.1`, protocol `767`, players `0/20`, description `MinecraftPPakemonServer`였습니다. 기록 담당자는 검증을 재실행하지 않았습니다.
- Windows에서 UDP 24454로 보낸 28byte 포트 프로브 1패킷이 VPS `enp5s0` tcpdump에서 목적지 `172.22.0.40:24454`로 포착됐습니다. `ufw-user-input`의 ACCEPT counter도 TCP 25565·UDP 24454 각각 1이었습니다. Java PID `10378`의 두 포트 리스닝과 `tmux ppakemon` 실행 유지도 확인했습니다.
- 사용자가 에그 콘솔의 TCP 25565·UDP 24454 인바운드 허용 규칙 추가를 마쳤다고 답했고, 이후 검증에서 이전 외부 타임아웃이 해소됐습니다. 업체 내부 동작이나 NAT 방식은 단정하지 않습니다.
- 실제 게임 입장·음성 양방향 대화·AI 문의·10명 부하는 여전히 미검증이며 systemd 서비스·재부팅 자동 기동은 설정하지 않았습니다.

# [2026-10-10 16:21 KST] VPS 최초 기동·내부 응답 확인, 외부 게임 접속 실패

- 빡대리가 EULA 동의 설정과 실제 기동을 명시적으로 승인했습니다. 메인 작업자의 직접 SSH 확인 기준으로 기존 `/srv/MinecraftPPakemonServer` 클론을 재사용했고 `git pull --ff-only`는 `Already up to date`, `main=origin/main=3fad1d136426cfca435bad48b284614f03cc14a8`, `git lfs fsck`는 OK였습니다. 기동 전 추적 파일의 working tree·staged가 모두 깨끗했고, 없던 Git-ignored `eula.txt`에 `eula=true`를 설정했습니다. 기록 담당자는 원격 검증을 재실행하지 않았습니다.
- `tmux` 세션 `ppakemon`에서 서버 루트를 작업 경로로 삼고 agy 경로·`JAVA_XMS=2G`·`JAVA_XMX=4G`를 지정해 `bash ./run.sh`로 실행했습니다. SSH 종료 후에도 실행이 유지됐습니다. Java PID `10378`, Minecraft `1.21.1`·Fabric `0.19.5`·Java `21.0.12.1` 및 `[16:15:47] Done (28.416s)!`를 확인했습니다. `Unknown preset name: underwater.` 경고가 두 번 관찰됐지만 Done까지 기동했고 경고 원인·실게임 영향은 조사하지 않았습니다. systemd 서비스 설치·자동 재시작·재부팅 자동 시작은 수행하지 않았으며 tmux로만 실행 중입니다.
- 서버 내부 `127.0.0.1:25565`의 Minecraft status handshake가 성공했습니다(version `1.21.1`, protocol `767`, players `0/20`, MOTD `MinecraftPPakemonServer`). Voice Chat UDP 24454 시작 로그와 리스닝, 위키 `0.0.0.0:8100` 리스닝과 localhost HTTP 200도 확인했습니다. 이는 외부 클라이언트 접속이나 음성 통신 검증이 아닙니다.
- 활성 데이터팩 30개에 `CCC_2.21-data.zip`과 생성된 `cobblemon-startup-hooks.zip`이 포함됐습니다. `execute in jbro_policy:plaza run time query daytime`이 `The time is 4353`으로 성공했고 광장 파일 24개·`world/data/jbro_policy_plaza_biome.dat`가 존재했습니다. Showdown 기동·nativeAI generation 활성화 로그와 시작 훅의 `inquiry.cli-version`·models 성공을 확인했습니다. 문의·리뷰 Discord 봇은 설정 부재로 비활성화됐으며, 비밀 설정 복사는 미승인이라 수행하지 않았습니다.
- UFW를 활성화해 기본 incoming deny·outgoing allow, IPv4/IPv6의 TCP 22·25565와 UDP 24454만 허용하고 8100 외부 접근은 허용하지 않았습니다. 새 SSH 접속은 정상이었지만 Windows에서 공인 IP `210.207.108.196:25565` 접속은 두 번 `TimeoutError`, 8100도 `TimeoutError`였습니다. Minecraft UFW ACCEPT counter는 0, 외부 재시도와 동시에 진행한 `enp5s0`의 10초 tcpdump도 captured/received 0 packets였습니다. 업체 방화벽·NAT 가능성은 추정이며 확정 원인은 모릅니다. 사용자는 에그 콘솔 방화벽을 아직 확인하지 않았다고 답했고, TCP 25565·UDP 24454를 출발지 `0.0.0.0/0`에 허용하도록 안내한 상태입니다. 업체 방화벽 미설정도 확정하지 않았습니다.
- 서버 내부 응답만 확인했으며 실제 클라이언트 입장·외부 음성·10명 부하·실제 AI 문의·게임 동작은 미검증입니다. 게임 소스 패치는 하지 않았으며 보유 SSH 키 내용·인증코드·토큰은 기록하지 않았습니다.
- 기동 후에도 운영 HEAD와 `origin/main`은 위 커밋의 0/0 상태지만, 기동으로 일부 추적 `config/`·`server.properties`·`world/datapacks/cobblemon-startup-hooks.zip`·광장 데이터가 수정됐습니다. 이는 기동 전 clean 상태와 구분합니다. 해당 변경을 되돌리거나 커밋하지 않았으며 생성된 일반 월드·설정·로그도 운영 runtime 상태로 보존했습니다. 후속 Pull에서도 이 runtime 변경과 월드가 보존 대상입니다. `.gitignore` 정책은 변경하지 않았습니다.

# [2026-10-10 16:04 KST] VPS GitHub·Codex·agy 인증 완료

- 메인 작업자의 직접 SSH 확인 기준으로, 이전 설치 기록의 미인증 상태 중 GitHub·Codex·agy 인증을 마쳤습니다. `gh auth status`가 exit 0으로 통과했고 `/srv/MinecraftPPakemonServer`에서 비공개 저장소의 `git ls-remote origin HEAD`가 성공해 `3fad1d136426cfca435bad48b284614f03cc14a8`을 확인했습니다. 기록 담당자는 원격 검증을 재실행하지 않았습니다.
- Codex `0.162.1`의 device 인증은 계정 보안 설정에서 비활성화되어 실패했습니다. 공식 일반 브라우저 로그인과 `localhost:1455` SSH callback forwarding으로 전환해 `Successfully logged in`·`Logged in using ChatGPT` 및 `codex login status` exit 0을 확인했습니다.
- agy `1.3.3`은 사용자의 Google OAuth 승인 후 인증했고 `agy models`가 exit 0으로 모델 18개를 반환했습니다. 첫 실행 온보딩에서 선택 항목인 Google Interactions 대화 데이터 수집 checkbox를 끄고 `Done`을 완료했습니다. `/home/ubuntu` 전체에 AI 읽기·수정·실행 신뢰 권한을 부여하지 않고 `No, exit`로 정상 종료(exit 0)했습니다.
- 사용자 결정으로 Claude Code 설치본은 유지하되 로그인·인증 작업은 요청 범위에서 제외했습니다. 인증코드·토큰·계정 이메일·OAuth URL/state/PKCE는 이 기록에 포함하지 않았습니다.
- 실제 AI 문의·게임 동작은 미검증입니다. Minecraft 서비스 기동·EULA 동의·Discord 설정 복사·방화벽 변경은 이번 인증 단위에서 수행하지 않았습니다.

# [2026-10-10 15:42 KST] VPS GitHub·AI CLI 설치 및 경로 검증

- 사용자 요청에 따라 `ubuntu@210.207.108.196`의 Java `21.0.12.1`, Git `2.53.0`, Git LFS `3.7.1`, PowerShell `7.6.6`, agy `1.3.3`, Bash `5.3.9` 기존 설치를 재확인했습니다. 공식 GitHub CLI apt 저장소에서 gh `2.102.0`, 공식 Codex standalone 설치기로 Codex `0.162.1`, 공식 Claude native 설치기의 `stable` 채널로 Claude Code `2.1.287`을 새로 설치했습니다. Node/npm·GUI는 설치하지 않았습니다. 이 항목은 메인 작업자의 직접 SSH 검증 보고이며 기록 담당자는 원격 검증을 재실행하지 않았습니다.
- 공식 설치 스크립트를 로컬에서 읽고 SSH 다운로드본의 SHA-256 일치를 확인한 뒤 실행했습니다(Codex `150e3cf675682efeaac115aa3747add3f27887896d04ce6d0b56478d8b428bf6`, Claude `3a68d3406cf674e17bed1733a4dcf37805e2e47d87417700007d7e1aa766a944`). GitHub 저장소 keyring은 공식 공지의 SHA-256 검증을 통과했고 `apt-cache policy`도 공식 저장소와 일치했습니다. 계정 비밀값은 읽거나 복사하지 않았습니다.
- 전체 도구의 버전, Codex·Claude·agy의 help와 `bash -lc` 로그인 셸 경로 확인을 통과했습니다. gh는 `/usr/bin/gh`, agy·codex·claude는 `/home/ubuntu/.local/bin`에서 실행됩니다. Codex 실제 파일은 `/home/ubuntu/.codex/packages/standalone/releases/0.162.1-x86_64-unknown-linux-musl/bin/codex`, Claude는 `/home/ubuntu/.local/share/claude/versions/2.1.287`입니다. `tmux`·`curl`·`python3`도 존재합니다.
- 사용자 계정 로그인은 필요합니다. `gh auth status`·`codex login status`는 미인증으로 exit 1, `claude auth status`는 `loggedIn=false`, `agy models`는 `Please sign in`으로 exit 1이었습니다. 로그인 후 실제 AI 요청은 검증하지 않았습니다.
- `ppakemon.service`는 `inactive`, TCP 25565·8100 및 UDP 24454 리스너는 없었고 서버 저장소의 `git status --porcelain`은 비어 있었습니다. 당시 디스크 45G 중 4.5G 사용·40G 여유, RAM 7,931MiB 중 962MiB 사용·6,968MiB available, swap 0을 확인했습니다.
- Minecraft 기동·EULA 동의·서비스 설치·방화벽 변경·Discord 비밀 설정 복사·계정 인증은 이번 설치 범위에서 수행하지 않았습니다. 실제 AI 요청과 게임 동작은 미검증이며 CLI 설치·경로 검증을 서비스 운영 완료로 해석하면 안 됩니다.

# [2026-10-10 15:08 KST] VPS 설치·정적 검증 완료, 최초 기동 승인 대기

- 이 항목은 메인 작업자의 직접 관찰 보고를 기록한 것으로, 기록 담당자는 VPS 검증을 재실행하지 않았습니다. Ubuntu 26.04 LTS x86_64 VPS(2 vCPU·8GB RAM·50GB NVMe)에 `ubuntu@210.207.108.196:22` 키 인증 접속이 성공했습니다. 키 내용은 읽거나 출력하지 않았으며 Windows 키 ACL만 현재 사용자 `Read`로 제한했습니다.
- Java `21.0.12.1`, Git LFS `3.7.1`, PowerShell `7.6.6`, agy `1.3.3` 설치와 버전 확인을 마쳤습니다. Microsoft 26.04 저장소에는 `powershell` 패키지가 없어 공식 GitHub universal deb를 사용했고, 공식 `hashes.sha256`의 UTF-16LE 인코딩을 디코딩해 SHA-256 일치를 확인했습니다. agy 공식 설치기의 SHA-512 검증도 성공했습니다. `/home/ubuntu/.local/bin`을 `PATH`에 포함한 `inquiry.cli-version`은 성공했지만 모델 조회는 Google 로그인 부재로 실패했습니다. Google 로그인은 사용자 본인 수행이 필요한 상태입니다.
- 운영 저장소의 `main=origin/main` 커밋 `3fad1d136426cfca435bad48b284614f03cc14a8`에서 추적된 1,572개 파일을 `/srv/MinecraftPPakemonServer`에 설치했고 전체 원본 SHA-256 일치를 확인했습니다. 모드 46개, `world/datapacks/`, 초기 광장 차원과 `world/data/jbro_policy_plaza_biome.dat`를 포함하며 로컬 패쳐·BlueMap·JourneyMap·Map Link·MD·일반 월드·플레이어 기록은 제외했습니다.
- Git bundle과 실제 LFS 바이트를 함께 전송했습니다. LFS 71개 파일의 SHA 일치와 `fsck` 성공을 확인했습니다. 파일의 stat 캐시에 pointer 크기만 남은 상태는 정확한 LFS 경로에만 `git add --renormalize`를 적용해 갱신했으며, 이후 staged·working tree가 모두 커밋과 일치했습니다. `origin`은 비공개 GitHub 저장소 URL로 연결했지만 VPS의 GitHub 원격 인증은 아직 준비하지 않았습니다.
- `run.sh`의 `bash -n`과 실제 Linux PowerShell 시작 훅의 `-DryRun`이 exit 0으로 통과했습니다. 운영 배포본에서 제외된 Discord 설정은 VPS에도 없는 상태입니다. systemd 259 서비스 초안은 `/tmp`에만 두었으며 `systemd-analyze verify`를 통과했습니다. 기존 OS `xfs_scrub`의 `CPUAccounting` 폐기 경고만 관찰했고 서비스는 설치·시작하지 않았습니다.
- 서비스 계획은 `User=ubuntu`, `WorkingDirectory=/srv/MinecraftPPakemonServer`, `Xms2G/Xmx4G`, agy 실행 경로 포함, `Restart=on-failure`, `SIGINT` 종료와 180초 대기입니다. UFW는 현재 `inactive`이며 방화벽은 변경하지 않았습니다. Minecraft EULA 동의·최초 기동과 함께 TCP 22·25565 및 UDP 24454만 허용하고 8100 외부 접근을 차단하는 적용 여부, 개발 Discord 설정 복사 여부를 사용자에게 질문한 상태입니다.
- 실제 서버 기동, 서비스 설치·자동 시작, 방화벽 적용, 외부 게임·음성 연결, 실게임·10명 부하 검증, GitHub 원격 인증은 미완료입니다. EULA·최초 기동과 Discord 설정 복사에 대한 사용자 응답도 아직 받지 않은 상태이며, 설치·정적 검증 결과를 서비스 운영 완료로 해석하면 안 됩니다.

# [2026-10-10 14:47] 서버 루트를 사용하는 Linux 실행기 추가

- 사용자가 기존 Windows BAT·PowerShell 실행을 보존하면서 Linux 실행기를 추가하도록 요청했습니다. `run.sh`는 기존 서버 루트를 그대로 사용하고 Java 21 이상·PowerShell 7 이상으로 시작 훅을 통과한 뒤 Fabric 런처를 실행합니다. 메모리 기본값은 `JAVA_XMS=2G`, `JAVA_XMX=4G`이며 자동 재시작은 호스트 관리 기능에 맡깁니다.
- 기존 `package-server.ps1`·`start-linux.sh`는 별도 `release/`와 외부 `SERVER_DATA_DIR`을 사용하는 패키징 방식으로 유지합니다. 현재 서버 루트의 `run.sh` 방식과 혼동하지 않도록 사용법을 `LINUX-SERVER.md`에 구분했습니다. 운영 저장소에는 안내 MD를 설치하지 않습니다.
- 메인 작업자 보고 기준으로 시작 훅의 Windows 경로·Java classpath를 플랫폼에 맞게 처리했습니다. Git Bash 모의 실행 8항목, Windows PowerShell 5.1·PowerShell 7에서 실제 Java/Fabric 버전 어댑터와 격리된 시작 훅 실행이 각각 통과했습니다. 실제 Java가 `world/session.lock` 바이트 잠금을 잡은 상태의 훅 쓰기 거부도 포함해 운영 훅 검증 5항목이 두 PowerShell 버전에서 각각 통과했습니다. 이 기록 담당자는 테스트를 재실행하지 않았습니다.
- 로컬 서버 패쳐는 대상에 `run.sh`가 없을 때만 개발 설치본을 복사하며 개발 포트 25566 지정을 제거하고, 기존 운영 실행기는 바이트 그대로 보존합니다. 메인 작업자 보고 기준으로 보존·복구 테스트 25그룹이 PowerShell 5.1·7에서 각각 통과했습니다. 패쳐 `server-deployment.psm1`과 테스트의 로컬 Git-ignored 범위는 유지하며 원격 추적 대상이 아닙니다.
- `run.sh`를 개발·로컬 운영 루트에 설치했습니다. 기록 담당자가 원본과 운영 설치본의 SHA-256 `DFEBDF9335F644218F5197BF002A957D6BFBAD68667D6094118E8F4EE6B80BAF` 일치를 확인했습니다. 메인 작업자 보고 기준으로 개발 설치본은 `port_args=(--port 25566)`만 다르고 운영은 `port_args=()`여서 `server.properties` 포트를 사용합니다. 공통 PowerShell 파일 4개의 양쪽 설치본 해시도 일치하며 기존 두 `run.bat`의 작업 전후 해시는 불변입니다.
- 소스 `main` 커밋 `bb804d1112c5e1c18600986186def1822a50699d`와 운영 `main` 커밋 `3fad1d136426cfca435bad48b284614f03cc14a8`을 푸시했습니다. 기록 담당자가 두 저장소에서 `main...origin/main`의 0/0 일치를 확인했습니다. 운영 `run.sh`는 실행 권한 `100755`, LF·BOM 없음으로 반영했습니다. 운영 변경은 `.gitattributes`, `run.sh`, 공통 시작 훅 PowerShell 4개이며 운영 안내 MD나 로컬 패쳐를 원격에 추가하지 않았습니다.
- 메인 작업자 보고 기준으로 운영 설정의 `server-startup-hooks.ps1 -DryRun`이 PowerShell 7의 `-NoProfile -NonInteractive` 실행에서 exit 0으로 통과했으며 파일을 쓰지 않았습니다. 기존 미설치 클라이언트 지도 모드와 Discord 설정 부재 경고는 남아 있습니다. 실제 클라우드 Linux 접속·Pull·OS 기동·외부 접속·실게임은 수행하지 않았습니다. Java/Kotlin 모드 빌드나 게임 기능 패치는 이 문서 작업에서 수행하지 않았습니다. 이 후속 기록의 최종 커밋은 메인 작업자가 별도로 담당합니다.
