# [2026-10-16 16:07] VPS 운영 서버 TM 프리셋 적용·재기동

- 사용자 승인: 한국 시각 16:00 정상 종료·적용·재기동에 명시적 동의 후 16:10으로 변경했고, 최종적으로 접속자가 없으니 즉시 진행하라고 지시했습니다. `16-10-vps` 일회성 자동화는 PAUSED로 전환해 중복 재시작을 막았습니다.
- 운영 소스: `MinecraftPPakemonServer`의 main `4bd07710b9008559acf19ac183da7f1bb9577183`를 커밋·푸시했습니다. 변경은 이전 0.1.0 JAR 삭제, 0.1.1 JAR 추가, `config/cobblemon-custom-species/species-overrides.json` 세 경로뿐입니다. VPS의 HEAD와 실제 원격 main 일치도 확인했습니다.
- 기존 운영 설정은 빈 overrides였습니다. 일반 VPS 업데이터는 기존 config를 보존하므로 이번 명시적 컨픽 적용을 빠뜨리지 않도록, 검증한 세 경로만 교체하는 일회성 작업으로 진행했습니다. 설치 전 pinned Git 커밋에서 LFS JAR·컨픽을 준비하고 SHA-256을 대조했습니다. 다른 모드·기존 설정·시작 규칙·월드·플레이어·광장 데이터는 직접 교체하지 않았습니다.
- 준비 중 PowerShell 중첩 here-string 문법 오류가 있었으며 실행 전에 구문 오류로 중단되어 VPS 변경은 없었습니다. 스크립트 생성을 분리해 수정했습니다. 최초 배포 시도는 16:02:49 접속자 1명 확인으로 정상 종료 전에 중단됐습니다. 사용자의 즉시 재시작 승인은 유지하고, 정식 공지 후 30초 유예를 둔 별도 재시도로 진행했습니다. 최초 결과와 재시도 결과는 운영 서버 폴더 밖 관리 작업 디렉터리로 분리해 남겼습니다.
- 공지 정정: 초기 `tellraw` 공지는 게임 콘솔에 명령을 전송했지만 Discord 공지 연동 경로를 거치지 않았습니다. 소스의 `Announcements`와 `DiscordNews`를 확인해 `/announce`로 바로잡았습니다. 16:04:08 재부팅 공지와 16:06:15 완료 공지가 게임 로그에 남았습니다. 실제 Discord 채널 수신은 직접 확인하지 않았습니다.
- 정상 종료·백업: 16:04:38/16:04:40 전체 차원 저장 로그와 Java 종료·게임 포트 닫힘을 확인한 뒤, 서버 외부 `/home/ubuntu/.local/share/ppakemon-backups/manager/20261016-160446-b2ae1081`에 월드 전체·설정·교체 파일을 백업했습니다. 백업은 약 27초 걸렸고 16:05:13 설치, 16:05:14 기동을 시작했습니다.
- 기동·실행 확인: 16:06:03 `Applied 1097`, 16:06:10 `Done (4.455s)` 확인. 16:07:14 직접 실행한 `customspecies status`도 Applied 1097이며, VPS Java PID 141095와 25565 포트 수신을 확인했습니다. 구버전 JAR는 없고 0.1.1 하나입니다. 정식 공지부터 완료까지 약 2분 3초이며 실제 서버 중단은 그보다 짧아 5분 복구 목표 안에 완료했습니다. 결과 JSON의 `downtime_seconds=122.8`은 30초 유예를 포함한 작업 시간이지 정확한 포트 중단 시간이 아닙니다.
- 설치본 SHA-256: JAR `a4f681a4adc84b8724c27c1f982b534a18fd98d45d2c2460ddd723bb5b028245`, 컨픽 `0da3f38b322e7a647942ca50b122d87048b40b5f17fa17fae5e3d141905d66b8`. 기존 검증 빌드·개발 서버/클라이언트와 같습니다. 새 빌드·테스트는 실행하지 않았고, VPS 실게임 TM 제작·학습은 미확인입니다.
- 백업 전송: 서버 재기동 이후 기존 관리 도구의 Google Drive 전송·checksum 검증이 진행 중입니다. 약 4.3GB 백업이며 기존 8MB/s 제한을 유지해 서버가 가동된 채 백그라운드에서 끝나도록 두었습니다. 검증 실패 시 VPS 백업을 보존합니다. 원격 작업은 `~/.local/share/ppakemon-manager/custom-species-20261016-retry1/`의 progress/result/job.log로 확인하며, PC의 준비·검증 자료는 `F:/AI/Temp/custom-species-champions-20261015/vps-deploy-20261016`에 있습니다.

# [2026-10-16 03:30] 개발 클라이언트 배포와 싱글플레이 프리셋

- 사용자 요청은 개발 클라이언트 배포와 컨픽 변경 설명이며, 싱글플레이에서도 같은 기술 목록을 쓰는 범위입니다. 처음에는 멀티플레이 서버의 데이터 동기화만 고려해 클라이언트 컨픽을 빈 상태로 두었고, 사용자 지적 후 바로잡았습니다. 클라이언트 배포 시 싱글플레이 설정도 함께 확인해야 합니다.
- 클라이언트 종료 상태에서 `develop-product/client/mods/cobblemon-custom-species-0.1.0.jar`를 0.1.1로 교체하고 구버전을 제거했습니다. 기존 JAR와 빈 컨픽은 제품 폴더 밖 `F:/AI/Temp/custom-species-champions-20261015/client-deployment-20261016`에 보존했습니다.
- 설치본 SHA-256은 `a4f681a4adc84b8724c27c1f982b534a18fd98d45d2c2460ddd723bb5b028245`이며 검증된 F 드라이브 빌드본·개발 서버 설치본과 일치합니다. 현재 main `b95e77b0`과 기존 빌드 당시 소스 사이에서 대상 모듈·공통 Gradle 설정의 차이가 없어 기존 빌드본을 사용했습니다. 새 빌드·테스트는 실행하지 않았습니다.
- 클라이언트 `config/cobblemon-custom-species/species-overrides.json`을 서버와 같은 프리셋으로 설치했습니다. 빈 overrides에서 종·폼 1,097개의 TM 학습 항목 6,267개를 추가한 설정이며, SHA-256 `0da3f38b322e7a647942ca50b122d87048b40b5f17fa17fae5e3d141905d66b8`이 서버·main 프리셋과 같습니다. 종족값·특성 설정은 추가하지 않았고, 신규 TM 제작 데이터 281개는 JAR 리소스입니다.
- 클라이언트 Cobblemon 1.8.1과 Mega Showdown의 JAR 해시가 서버와 같고, 월드 데이터팩 없이 클라이언트 기본 모드 데이터에 프리셋 대상 1,097개가 전부 존재함을 정적으로 확인했습니다. 설치본 로그의 Fabric Loader 0.19.5, Fabric Language Kotlin 1.14.1+kotlin.2.4.20도 의존 조건과 맞습니다.
- 미확인: 이번 작업에서 클라이언트를 실행하거나 싱글플레이 월드를 로드하지 않았으므로 실제 설정 적용 로그·TM 제작·학습 화면은 확인하지 않았습니다. 개발 서버·운영 서버·VPS·릴리스 제품은 이번 클라이언트 배포에서 변경하지 않았습니다.

# [2026-10-15 17:45] main 반영·개발 서버 적용 및 TM 등록 확인

- 소스 수정 `ed82dff8`과 프리셋·제작 데이터 `3a8c723a`를 main의 `af299687fd3220a25e13893e70b030c23d6d8316`으로 병합·푸시했고 `origin/main` 동기화를 확인했습니다. 이 기록의 후속 커밋·푸시는 아직 메인 작업자에게 남아 있습니다.
- 최종 빌드: 병합된 main의 빌드 입력 314개와 F 드라이브 미러의 SHA-256이 모두 일치했습니다. 전체 main `settings`를 유지한 `--configure-on-demand :cobblemon-custom-species:build`가 24초에 성공했고 Kotlin 테스트 16개가 다시 통과했습니다. Python 테스트 8개도 통과했습니다. 앞선 선택 모듈 include는 초기 검증용이며, 최종 빌드는 원본 settings를 사용했습니다. Gradle 캐시·build·임시 경로는 모두 F 드라이브였습니다.
- 설치 대상은 `C:/Users/박주형/Documents/GitHub/Cobblemon-Mods/develop-product/server` 하나입니다. 포트 25566이 닫힌 정지 상태를 확인한 뒤, 기존 0.1.0 JAR와 빈 설정을 `F:/AI/Temp/custom-species-champions-20261015/deployment-backup`에 백업하고 0.1.1 JAR로 교체하며 이전 JAR를 제거했습니다.
- 최종 빌드 JAR와 설치본 SHA-256은 `a4f681a4adc84b8724c27c1f982b534a18fd98d45d2c2460ddd723bb5b028245`로 일치했습니다. 설정은 main Git blob의 LF 바이트로 설치했고 SHA-256 `0da3f38b322e7a647942ca50b122d87048b40b5f17fa17fae5e3d141905d66b8`이 일치했습니다. 체크아웃의 CRLF 파일을 그대로 복사하면 sources의 해시와 달라지는 점을 확인했습니다. 적용량은 override 1,097개·추가 TM 학습 항목 6,267개·신규 제작 데이터 281개입니다.
- 기동·적용: 기존 Java 21 `run.bat`으로 17:41:07 기동, 17:41:38 `Done (1.957s)` 및 포트 25566 수신(PID 70028)을 확인했습니다. 로그에서 Fabric Loader 0.19.5, 모드 0.1.1, 기본 데이터팩 `cobblemon_custom_species` 자동 로드를 확인했습니다. 17:41:34 `Applied 1097`, 17:42:19와 17:44:27의 `customspecies status`에서 같은 적용 수를 확인했습니다.
- TM 등록 검증: `javap`로 `TmArgumentType.parse`가 `TechnicalMachines.getByResourceLocation`을 검증함을 확인했습니다. 존재하지 않는 플레이어 태그 조건 아래 `technicalmachine unlock @p only cobblemon:<id>` 281개를 파싱하여 실제 TM의 실패 0개를 확인했고, 잘못된 ID 대조군은 예상대로 `InvalidTM` 1개였습니다. 실행 조건이 false여서 플레이어 상태를 바꾸지 않았습니다. 이는 TM 레지스트리 등록 확인이며 실게임 제작·학습 검증은 아닙니다.
- 설치 JAR의 신규 TM 281개는 모두 Cobblemon 자체 기술 타입과 일치하며 기존 335개 TM 파일 교체는 0개였습니다. 개발 서버는 가동 상태로 유지했습니다. 개발 클라이언트·운영 서버·VPS·릴리스 폴더는 변경하지 않았습니다. 기동 시 기존 시작 훅의 `ALWAYS` 동작이 자연 실행됐으며 설정 파일을 별도로 수동 변경하지 않았습니다.
- 미확인: 실게임 아이템 제작과 포켓몬에 대한 TM 학습은 아직 확인하지 않았습니다. 위 배포·기동·검증 결과는 메인 작업자가 전달했으며, 기록 담당자는 현재 HEAD와 `origin/main`의 `af299687` 일치를 직접 확인했습니다.

# [2026-10-15 17:37] 챔피언스·마지막 본가 세대 TM 프리셋 (0.1.1)

- 소유 범위: 기술 목록 override와 추가 TM 제작 데이터는 `cobblemon-custom-species`가 담당합니다. 다른 모듈·Mega Showdown 서버 데이터·사용자 월드는 변경하지 않았습니다. README는 사용법, 이 파일은 결정 이유와 검증 범위를 기록합니다.
- 사용자 결정: 챔피언스 등장 종·폼에는 사용 가능한 전체 습득 기술을 TM으로 추가하며 기존 기술은 유지합니다. 미등장 종·폼은 9세대를 우선하여 마지막 본가 세대의 알기술을 사용하고, 진화 전 계보에서도 같은 세대의 알기술만 모아 진화형에 추가합니다. BDSP는 포함하고 ZA·아르세우스 등 외전은 제외합니다.
- 폼별 세대 보강: 거다이맥스폼은 원종이 SV에 재등장해도 마지막 본가 출전이 8세대이므로 알기술 기준을 `maxgen=8`로 제한합니다. 원종의 9세대 알기술을 가져오지 않도록 회귀 검증을 추가했습니다. 최종 fallback 세대 분포는 8세대 348개·9세대 698개입니다.
- 새 TM 제작 데이터는 별도 데이터팩이 아니라 이 모드 JAR의 `data/cobblemon/tms/`에 둡니다. 해당 기술의 Cobblemon 타입 주얼 1개와 기본 해금을 사용하며, 기존 335개 TM 제작법은 보존합니다. 설정을 비워도 JAR 안 제작 데이터는 유지됩니다.
- 원본 고정: Project Pokémon `champout` 커밋 `50e7233b78c3b81df29563f9695386c28e77fc95`, Pokémon Showdown 커밋 `3a766f2cfb5e51c1e428cfe87d0dba91a8f01640`. 입력 파일별 해시는 `presets/champions-mainline-eggs/sources.json`에 있습니다.
- 원본 함정: `personal_dump`에는 비활성 기술도 있으므로 `waza.json`의 `available=1`로 거릅니다. 이 필터 후 별도 Showdown 챔피언스 목록 258개 비교가 모두 일치했고, 원본 JSON masterdata와 텍스트 목록 396개도 검증했습니다. 서버에 없는 외형 18패턴은 원종과 기술 목록이 같음을 확인하여 합쳤습니다.
- 생성 결과: 서버 1,025종·1,445폼 전체를 `coverage.json`에 기록했습니다. 챔피언스 대상 378개, 마지막 본가 알기술 검토 대상 1,067개이며, 알기술이 없는 대상도 검토 수에 포함됩니다. 실제 override는 1,097개, 추가 TM 학습 항목은 6,267개, 신규 TM 제작 데이터는 281개(최종 보강에서 `grudge` 추가)입니다. 변경할 기술이 없는 대상은 override에서 생략합니다.
- 재현 경로: 모듈의 `tools/generate_tm_preset.py`와 `presets/champions-mainline-eggs/{species-overrides,coverage,sources}.json`. Python 실행 시 `-B`를 사용합니다. 프리셋 SHA-256은 `0da3f38b322e7a647942ca50b122d87048b40b5f17fa17fae5e3d141905d66b8`입니다. 서버 입력 구성이 바뀌면 기존 커버리지 수치를 그대로 가정하지 않습니다.
- 구현 수정: `AtomicOverrideService`는 명시적 폼의 moves 작업 결과가 현재값과 같아도 `MOVES` 쓰기를 강제합니다. 같은 트랜잭션에서 원종 목록이 확장될 때, 원종 목록을 공유하던 폼에 원치 않는 기술이 넘어가는 것을 막기 위한 수정입니다. 회귀 테스트로 실패를 재현한 뒤 수정했습니다.
- 검증 완료(메인 작업자 결과 전달): Python 테스트 8개, Kotlin 테스트 16개 통과. 폼 격리 수정 단계 build 성공과 신규 제작 데이터의 Cobblemon 타입 일치·기존 TM 교체 0개를 확인했으며, 최종 281개 리소스를 포함한 main 빌드는 아직 하지 않았습니다. 정적 확인으로 override 키 1,097개 중복 없음·전체 1,445폼 커버리지 일치를 확인했습니다. 기록 담당자는 README와 sources/coverage를 직접 읽어 정책·원본 커밋·대상 수치를 대조했고, 최종 sources/coverage에서 거다이맥스 세대와 수치를 다시 확인했습니다.
- 빌드 환경: 선택 모듈만 포함한 F 드라이브 소스 미러를 사용했습니다. 빌드 출력은 `F:/AI/Temp/custom-species-champions-20261015/build`, `GRADLE_USER_HOME`은 `F:/AI/caches/gradle`이며 프로젝트 캐시·build·임시 경로도 모두 F 드라이브로 지정했습니다.
- Git 상태: 폼 격리 소스 수정과 회귀 검증은 `ed82dff8`로 작업 브랜치에 커밋·푸시되었습니다. main 병합은 아직 하지 않았습니다.
- 이 기록 시점 미완료: 최종 리소스의 main 반영·병합·푸시 및 main 빌드, 개발 서버 JAR/프리셋 설치, 서버 기동 후 적용 확인, 실게임 TM 제작·학습은 아직 수행하지 않았습니다. 빌드·정적 검사 성공은 서버 적용이나 실게임 성공의 증거가 아닙니다.
