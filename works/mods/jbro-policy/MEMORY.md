# jbro-policy MEMORY

## [2026-10-24 20:20] 1.4.0 릴리스 반영 — PPakemon·로컬 운영·deploy-product·VPS (VPS 21:00 오픈 예약, 실접속 미확인)

- **빡대리님 지시:** "8시에 예약 걸어 놓고 교체해, 9시에 다시 오픈 예약, 경과 보고, 내가 열라면 9시 전이라도 열어도 됨". 20:00 종료와 21:00(또는 지시 시) 시작이 이 지시의 동의 범위다. 공지는 이미 디스코드에 나가 있어 따로 보내지 않았다.
- **VPS 종료:** 20:00:00 예약 실행, `save-all flush`·`stop`, 20:00:18 월드 저장 로그 확인 후 종료.
- **로컬 운영:** `develop-product/server` 기준 19건 반영. 개발 서버 팩토리 JAR은 Better AI 대전 프로세스(PID 44772·43224)가 잠가 옛것이라, 반영 직후 릴리스 빌드본으로 덮어썼다. 우리 모드 13개가 릴리스 빌드와 해시 같음. 운영 저장소 `3ae5a562` 푸시(LFS 12개).
- **PPakemon:** `client-stage` 기준 21건, 재계획 0. **deploy-product:** 서버 49 = 운영 49, 클라이언트 18. VERSION.txt 두 곳에 1.4.0 블록(가라르 공개 포함), 개발 저장소 커밋·푸시.
- **VPS 반영:** 계획이 `mods/jbro-policy-0.1.7.jar`(10-19 반격 끄기 핫픽스 `ff78b374…`, 커밋 안 된 수정)와 겹쳐 멈췄다. 그 JAR을 `~/.local/share/ppakemon-backups/hotfix/20261024-before-1.4.0/`에 백업하고 그 파일만 `git checkout`으로 되돌린 뒤 `6ddd9716 → 3ae5a562` 19건 업데이트. 월드 백업 `gdrive:VPS-backups/PPakemon/manager/20261024-200127-8f13116c`(drive_verified). 20:16 완료. VPS `mods` 49개가 `deploy-product/server`와 해시 차이 0. `vps-rpc.ps1`은 10분 넘는 작업에서 대기만 끝나고 VPS 작업은 계속되므로 `-Action job`으로 결과를 받는다.
- **남은 것:** 21:00 VPS 시작(예약), 실접속(1.4.0 통과·1.3.x 거절, 가라르 탭), 개발 서버 팩토리 JAR(대전 프로세스 종료 뒤 `install-dev.ps1`).

## [2026-10-24 19:40] 1.4.0 모드팩 다시 만듦 — Better AI 커밋 포함, 가라르 공개 (PPakemon·운영·VPS·드라이브는 아직)

- **빡대리님 결정:** 17:40 팩 뒤에 main에 들어온 Better AI 커밋(`528467f8`·`ae2b9476`, 새 단순화 옵션 2개는 기본 꺼짐, 모드 메뉴 토글 두 칸 교체)도 1.4.0에 넣는다. 가라르 리그를 1.4.0에서 연다("가라르 켜야 한다").
- **빌드:** MCC를 `ae2b9476`에서(`F:/AI/build/release-1.4.0-mcc`, 관련 테스트 100/100, `jar --validate`, 내장 cobblemon-ui가 릴리스 것과 같음), 리그를 `galar.json` `active: true` 커밋 `5dbda2c3`에서(`F:/AI/build/release-1.4.0-league`, 24/24) 따로 빌드했다. `05a0f9d9` 이후 바뀐 모듈은 이 둘뿐이다. `F:/AI/build/release-1.4.0`의 `modules`·`client-stage`·`ppakemon-profile`에 넣었고(옛 JAR은 `replaced-*-05a0f9d9/`), 해시 MCC `da91fa17…`, 리그 `8ecca26d…`. `build.ps1`은 실행 때 다른 모듈 JAR을 지우므로 한 모듈 재빌드는 별도 BuildRoot에서 했다.
- **개발:** 서버·클라 MCC·리그 교체(해시 일치). 개발 서버 전용 가라르 데이터팩 `ppakemon-dev-galar`는 소스가 켜졌으니 `develop-product/deployment-backups/20261024-dev-galar-datapack/`로 뺐다.
- **모드팩:** `Downloads/PPakemon 1.4.0.mrpack` 248,446,632바이트(인덱스 70, overrides mods 21 = 프로필 복사본과 해시 같음, config 0, 가라르 켜짐). 17:40 팩은 `Downloads/old/PPakemon 1.4.0 (1740 MCC 05a0f9d9).mrpack`. 빡대리님이 드라이브에 올린 17:40 팩(링크 `1WWeqLqG…`)은 옛것이라 새 파일로 바꿔 올려야 한다(링크 바뀜).
- **패치노트:** `build/patchnotes-1.4.0/`의 세 파일과 VERSION 블록에 가라르 오픈을 넣었다(디스코드 1번 1,257자).
- **20시 남은 일:** PPakemon 패치는 `client-stage` 기준(새 MCC·리그 포함), 로컬 운영·`sync-release.ps1`·VERSION.txt·VPS. 운영 서버도 새 리그 JAR이어야 가라르가 열린다.

작업 내역과 이슈를 `[YYYY-MM-DD HH:MM]` 형식으로 기록한다. 최신 항목을 위에 추가한다.
구현, 빌드, JAR 배치, 서버 기동, 실게임 검증은 서로 다른 상태로 적는다.

---

## [2026-10-24 17:40] 1.4.0 릴리스 준비 — 개발 동기화·모드팩·패치노트 (PPakemon·운영·VPS·드라이브는 20시 점검)

- **사용자 결정:** 20:00~21:00 점검 공지(📢공지-소식, 메시지 1563465936013496381, VPS 설정의 봇 토큰. 개발 서버 설정의 토큰은 401로 만료). 20시 자동 예약은 걸지 않는다.
- **빌드:** `origin/main` `05a0f9d93`을 `F:/AI/build/release-1.4.0`에서 18개 assemble 성공(`jar --validate` 포함). 테스트는 돌리지 않았다. `PACK_VERSION` 1.4.0, jbro-policy 0.1.8 그대로.
- **개발:** 서버 to-item·타워·PvP·퓨전, 클라 to-item·팩토리·타워·PvP·퓨전 교체(해시 일치, 백업 `develop-product/deployment-backups/20261024-172256-release`). **서버 `more-cobblemon-contents-battle-factory-0.1.0.jar`는 Better AI 대전 서버(PID 44772·43224, 13:08 시작)가 클래스패스로 잡고 있어 못 바꿨다.** 그 프로세스가 끝난 뒤 `install-dev.ps1`을 다시 돌린다.
- **모드팩:** PPakemon 프로필을 `F:/AI/build/release-1.4.0/ppakemon-profile`에 복사해 패치(21건, 재계획 0)한 뒤 `Downloads/PPakemon 1.4.0.mrpack`(248,414,162바이트, 인덱스 70, overrides mods 21, config 0, 우리 모드 해시 빌드본과 같음). 실제 PPakemon 프로필은 1.3.0 그대로(20시 전 운영 접속 유지). 10-19 보류본은 `Downloads/old/`로 옮겼다. 복사본용 패처 백업(C, 425MB)은 지웠다.
- **패치노트:** `build/patchnotes-1.4.0/`(디스코드 1,013자·1,134자), VERSION 블록 갱신. 10-19 초안의 "평화로움 반격" 줄은 반격이 꺼져 있어 뺐다.
- **드라이브:** 커넥터 계정(selab.ai)에 PPakemon 파일이 없어 업로드는 사용자가 한다.
- **20시 남은 일:** PPakemon 패치, 로컬 운영 서버, `sync-release.ps1`·VERSION.txt, VPS(종료 동의 필요), 개발 서버 팩토리 JAR.

## [2026-10-24 17:13] BP·상점 자동 복구 개발 배포

- **요청·배치:** 빡대리의 개발배포 지시로 main 09bafa71d에서 검증한 jbro-policy-0.1.8.jar와 MCC core를 정식 체크아웃 C:/Users/박주형/Documents/GitHub/Cobblemon-Mods/develop-product의 server/mods 및 client/mods에 교체했다. client는 Modrinth cobblemon-dev 프로필로 연결된 기존 junction이다. 현재 main 3b3c8679c와 빌드 기준의 차이는 두 모듈 MEMORY.md뿐임을 확인하여 재빌드하지 않았다.
- **파일 검증:** 양쪽 jbro-policy SHA256 e2c51ae20c200ff330d906b29d5de8389caad7871d1983ddda902e763329b8dd로 검증 빌드와 일치한다. 양쪽 대상 mod ID는 각각 1개이며 MCC도 함께 교체했다. 전체 134개 JAR 중 요청 대상 4개만 변경되었다. 기존 JAR 백업과 전후 해시 목록은 F:/AI/builds/pichu-inquiry-117d/development-deploy-20261024-171312에 보관했다.
- **실행 범위:** 교체 전 게임 클라이언트·개발 서버 프로세스 및 25566 리스너가 없음을 확인했다. 서버/클라이언트 기동·실게임/Discord 보상 지급·실제 프로세스 중단 검증은 하지 않았다. 설정·월드·보상 기록·운영 서버·VPS·릴리스 제품은 변경하지 않았다. 이번에는 설치 해시만 확인했고 단위 검사를 다시 실행하지 않았다. 이전 복구17/17·피츄115/115 통과 및 core 기존 실패3·중단1의 검증 범위는 아래 기록과 같다.

## [2026-10-24 17:03] 원거래 기반 BP·상점 자동 복구 연동

- **병합 후 검증(2026-10-24 17:09):** main 병합 커밋 09bafa71d의 관련 빌드 입력 2,248개와 F 소스 불일치 0을 확인하고 최신 main으로 다시 JAR을 생성했다. 복구17/17·피츄115/115 통과, core678=성공674·기존 실패3·중단1은 동일하다. 상세 로그 compensation-main-verified.log와 compensation-verification.json. 개발·운영·VPS 설치·재시작은 하지 않았다.

- **사용자 요청:** BP 미지급·상점 전달 실패부터 자동 복구하고, 재문의·재시작의 중복 지급을 막으며 불명확한 결과는 관리자에게 넘긴다. 포켓몬 복구와 과거 로그 추정 보상은 이번 범위가 아니다.
- **구현:** autoResolve가 켜진 reward/shop 문의만 MCC 원거래 API에 연결했다. AI가 고른 금액·상품·명령은 사용하지 않는다. 문의 본문의 거래 UUID 또는 본인·문의 전후 구간·동일 종류의 유일한 원거래로 한정한다. 후보 여러 건·원거래 없음·미확인 상태는 관리자에게 넘기고, 실제 처리 결과 문장은 서버 결과로 교체한다. 대기 중 서버 확인이 시간 초과되면 뒤늦게 지급을 시작하지 못하도록 했다. 구형 MCC의 API 미존재도 서버 스레드에서 LinkageError를 잡아 관리자 확인으로 보낸다.
- **중복·수동 처리:** 원거래 상태는 MCC 월드 data/mcc-compensation-v1에 남고 문의 종결 시 지우지 않는다. 운영자 BP 조정은 거래 순서로 검사한다. 수동 /give 등 BP 내역에 남지 않는 지급 전에는 OP2 /bp recoveryhold <플레이어UUID> <원거래UUID>의 성공을 확인해야 한다. 외부 수동 아이템 지급을 자동 감지한다고 설명하지 않는다.
- **검증:** jbro-policy build 성공, JUnit XML 115/115 실패·중단 0. MCC 새 복구 17/17 통과. MCC core 범위 전체 678건은 성공674·실패3·중단1이며 전체 통과가 아니다. BattleSceneLifecyclePolicyTest 1건과 Cobblemon173ActionCandidateAdapterTest 2건 실패는 변경 전 HEAD 7fc3d12b0의 별도 검사에서도 동일하게 재현했다. 검증 자료 F:/AI/builds/pichu-inquiry-117d/compensation-verification.json, compensation-verified-core.log, compensation-assembled.log, final-core-junit, final-pichu-junit, baseline-junit.
- **산출물·범위:** jbro-policy-0.1.8.jar SHA256 e2c51ae20c200ff330d906b29d5de8389caad7871d1983ddda902e763329b8dd, CRC 정상. MCC와 함께 서버를 업데이트해야 복구 API를 사용할 수 있다. JAR은 F 검증 폴더에만 생성했다. 개발·운영·VPS 배치와 실제 게임/Discord 지급·강제 종료 검증은 하지 않았고 서버를 종료·재시작하지 않았다. 재시작 시나리오는 파일 저널과 BP NBT를 다시 읽는 단위 검사로 확인한 범위다.

## [2026-10-24 16:34] 피츄 문의의 자료 조회·질문 답변 확대, 불확실한 자동 보상 차단

- **사용자 요청:** 실제 문의 목록을 보고 자동 처리 범위를 개선하되 보상 예외를 엄격하게 처리한다. 이 채팅에서 조회한 10/11~10/21 문의 12건은 모두 관리자에게 넘겨졌고 BP 누락 문의는 없었다. 일반 사용자 후속 메시지 본문은 Discord API에서 비어 반환돼 실제 최종 해결 경위는 확인하지 못했다.
- **구현:** 사용법 질문(`question`)과 건의(`suggestion`)를 분리했다. 관련 위키, 로드된 정책·아이템 레지스트리, BP 상점 디스크 자료, 데이터 리소스 검색을 제공한다. 접속한 문의자 본인의 파티·PC(박스 번호)·인벤토리·엔더 상자 및 알려진 아이템화 포맷, 현재 차원 96블록 내 가까운 야생 32마리 자료를 읽는다. 검색·크기·개체 수 제한을 명시하며 일반 상자·목장·오프라인·다른 플레이어·백업은 조회하지 않는다. 검색 실패를 부재 증거로 쓰지 않게 했다.
- **자동 종결:** 제공한 reference에 실제 존재하는 근거를 인용한 질문만 허용한다. 빈 답, unknown, 남은 조치·명령, 미확인 자료뿐인 인용은 관리자 확인으로 넘긴다. 포획 등급으로 내비 표시를 추정하거나 Tip을 현재 구현의 증거로 삼지 않도록 검토 지시를 고쳤다. 새 개체 소환을 원본 복구로 제안하지 않는다.
- **보상 결정:** 기존 ‘로그 승리 수−최근 보상 건수’와 AI 지정 금액으로 `bp add`하던 실행 경로를 제거했다. 현재 제공 자료는 원래 지급 거래 ID·정확한 지급액·최종 수령 여부를 입증하지 못하며 반복 문의·수동 보상·로그 잘림·실행 결과 불명확을 안전하게 구분할 수 없다. 모든 보상·복구는 조사 후 관리자 확인이며, 자동 보상·아이템 재지급·포켓몬 원본 복원은 구현하지 않았다. 기존 BP 한도 키와 auto-bp.json은 지급을 다시 허용하지 않으며 기존 파일을 삭제하지 않는다.
- **추가 문의·설정:** `/문의추가 <번호> <내용>`은 본인의 열린 문의에 100자·3회까지 설명을 붙여 원래 시각과 함께 재검토한다. 일반 Discord 답글은 입력으로 읽지 않는다. 닫힌 문의 재생성을 막고 문의 JSON을 원자 저장한다. Mod Menu에서 로컬 enabled/autoResolve만 편집하며 재시작 후 반영한다. 원격 설정은 바꾸지 않는다. AI는 도구 없는 전용 jbro-inquiry-review 프로필을 사용한다.
- **검증:** 기준 origin/main 1957d740b와 이번 소스를 F:/AI/builds/pichu-inquiry-117d/src에 복사해 :jbro-policy:build 성공. JUnit XML 114/114, 실패·오류·건너뜀 0. 보상 명령의 대상·금액·분류와 무관한 차단, 원문 근거 검사, 추가 문의 소유권·횟수, 도구 실행 거부, 잘못된 검토 설정을 검사했다. 최초 테스트 2개는 바뀐 근거 필드명과 제거한 자동 명령 안내를 기대하던 옛 단언을 갱신했다. 빌드 준비의 Cobblemon 소스 변환 실패 및 F init 스크립트 메서드 오류를 확인·수정한 후 최종 빌드를 통과했다. 모든 Gradle 출력·캐시·임시 경로는 F로 지정했다.
- **산출물·미확인:** F:/AI/builds/pichu-inquiry-117d/out/jbro-policy/libs/jbro-policy-0.1.8.jar, SHA-256 9b6e37aad5a7208b0d5bc45af922d9c96328cc640534394a4592634760486a5d, ZIP CRC 정상. 실제 agy 응답·게임/Discord 문의 경로·Mod Menu 렌더링은 미검증이다. 개발·운영·VPS·release 제품 폴더에 JAR을 배치하지 않았고 서버 종료·재시작도 하지 않았다. 적용에는 서버 JAR 교체·재시작이 필요하며 VPS 종료에는 별도 명시적 동의가 필요하다.

## [2026-10-19 11:30] 1.4.0 배포 보류 — PPakemon·로컬 운영·deploy-product를 1.3.0으로 되돌림

- **사용자 결정:** 아직 배포하지 않는다. 배포 프로필·로컬 운영 서버·deploy-product는 1.3.0 상태로, 개발 서버·클라이언트는 1.4.0(반격 끈 0.1.8 `86f80b6e…`) 그대로 둔다.
- **PPakemon:** 02:28 패치 기록으로 `Restore-ClientPatch`(16개 복구, 백업과 해시 일치). 11:00 핫픽스가 jbro-policy-0.1.8을 바꿔 놔서 복구 도구가 멈췄기에, 패치 당시 빌드본(`355e8acf…`)을 잠깐 되돌려 놓고 복구했다. 지금 jbro-policy-0.1.7은 `495d8c…`(반격 믹스인 등록됨)이다.
- **로컬 운영:** `16ace84` 파일로 되돌린 커밋 `1f5a1d4` 푸시(`c36efa7`·`6693101` 되돌림, XS 사탕 월드팩 복구). 트리 차이 0.
- **deploy-product:** 서버 48 = 운영 48, 클라이언트 17 = PPakemon, 불일치 0. VERSION.txt 두 곳은 1.4.0 블록을 빼서(`d3397c21e`) `f74aadb1d` 이전과 같다. 블록은 `build/patchnotes-1.4.0/VERSION-1.4.0-block.txt`에 보관.
- **남은 차이:** VPS는 0.1.7에서 믹스인 설정만 바꾼 핫픽스 JAR(`ff78b374…`)로 돌고 있어, 로컬 운영·deploy-product·PPakemon의 0.1.7(`495d8c…`)과 다르다. VPS에서 파일을 받는 것은 이번 세션 권한에서 막혔다. 로컬 운영을 그대로 VPS에 올리면 반격이 다시 켜진다.
- **22:24 튕김과 조치:** 사용자가 되돌린 PPakemon으로 VPS에 들어가 피츄 버튼을 누르자 `NoClassDefFoundError: jbro/cobblemon/uikit/client/UiDialogueStyle`(PichuScreen.kt:70)로 튕겼다. 01:56에 패쳐 밖에서 넣은 `495d8c…`는 최신 main 빌드라 10-16 이후 `UiDialogueStyle`을 쓰는데, PPakemon `cobblemon-ui`(`f4955d73…`)는 1.3.0 것이라 그 클래스가 없다. 10-14 패치 기록의 1.3.0 릴리스 0.1.7(`be55de29…`, 팩 1.3.0)로 PPakemon과 `deploy-product/client`를 바꿨다. `495d8c…`는 `F:/AI/build/release-1.4.0/ppakemon-jbro-policy-0.1.7-495d8c.jar`로 보관. PPakemon의 10-14 패치 파일은 이제 모두 일치하고, 모든 모드에서 `cobblemon-ui`에 없는 uikit 참조 0건. 서버 0.1.7(`495d8c…`/VPS `ff78b374…`)과 클라이언트 0.1.7(`be55…`) 사이 새 패킷 호환은 실접속으로 확인하지 않았다.
- **교훈:** 클라이언트 프로필에는 같은 이름·버전이라도 다른 main에서 빌드한 JAR를 넣지 않는다. 서버 핫픽스는 서버에만 넣는다.
- **보관:** `Downloads/PPakemon 1.4.0.mrpack`과 `F:/AI/build/release-1.4.0`은 핫픽스 전 0.1.8(반격 켜짐)이라 배포할 때 다시 만들어야 한다.

## [2026-10-19 11:00] 야생 반격 끄기, VPS 0.1.7 핫픽스·재시작, 로컬 0.1.8 교체

- **경과:** `ecb1a4973`의 반격 믹스인 4개(무조건 야생에게 평화로움 해제)가 다른 세션의 0.1.7 빌드(`495d8c…`)에 실려 01:30 VPS에 들어갔다. "반격이 아니라 그냥 공격한다"는 제보가 왔다. 선공 경로는 찾지 못했다(알파 레벨 센서·`get_mad_at_thrower`는 원인 아님 확인, 포켓볼 피격이 피해로 잡히는지는 미확인). 30초 안에 때린 플레이어에게만 푸는 수정(`fcdb9a19a`)을 만들었으나, 사용자가 "걍 공격 끄고" 적용·재시작을 지시했다.
- **끄기(`5582f4a88`):** `jbro_policy.mixins.json`에서 `WildRetaliation*` 5개 등록만 뺐다. 코드는 남아 있다(다시 켤 때 `fcdb9a19a`의 30초 조건 버전부터 검증할 것). README의 야생 반격 설명과 1.4.0 `VERSION.txt` 두 곳의 "야생 포켓몬이 반격합니다" 줄을 지웠다.
- **빌드:** `5582f4a88`을 F에 풀어 `:jbro-policy:build` 종료 코드 0, 테스트 112개 통과(중단 0). `jbro-policy-0.1.8.jar` `86f80b6e044b8409ff03f9511ee1e32b1798a81697e8e4278e6667b101b0096c`, `jar --validate` 통과, 믹스인 설정에 반격 없음.
- **VPS(사용자 동의 "VPS서버 적용하고 재시작해줘", "지금해줘", "0.1.7 그대로 끄기"):** 0.1.8은 팩 검사가 1.4.0이라 1.3.x 접속자를 막으므로, VPS 0.1.7(`495d8c…`)에서 `jbro_policy.mixins.json`만 바꾼 JAR(`ff78b374bbc70b97625f33dd64cea7ac3dfff42298ec379d2874cd56a366558e`, 329개 중 1개 파일만 다름)을 넣었다. 접속자 0명, `save-all flush`·`stop`, 저장 로그 확인 후 교체, `tmux respawn-pane`로 10:51:40 `Done`, 믹스인 오류 없음. 이전 JAR은 VPS `~/.local/share/ppakemon-backups/hotfix/20261019-wild-retaliation-off/`. **VPS 작업 트리는 커밋 `16ace84`와 이 JAR만 다르다**(운영 저장소에 없는 핫픽스). 1.4.0을 VPS에 올리면 사라진다.
- **로컬:** 개발 서버·개발 클라·PPakemon·`deploy-product/{server,client}`·로컬 운영 서버의 1.4.0 0.1.8(`355e8acf…`, 반격 4개 등록)을 `86f80b6e…`로 교체, 해시 일치. 백업 `develop-product/deployment-backups/20261019-wild-retaliation-off/`. 운영 저장소 `6693101` 푸시.
- **미확인:** 실게임에서 야생이 더는 선공·반격하지 않는지. 1.4.0 `.mrpack`·디스코드 패치노트를 이미 배포했다면 그쪽 jbro-policy와 반격 문구는 이 교체 전 것이다.


## [2026-10-19 02:40] 1.4.0 릴리스 준비 — 개발·PPakemon·로컬 운영·deploy-product 반영 (VPS·모드팩·실접속 미완)

- **사용자 결정:** 1.4.0(앞 두 자리 변경으로 1.3.x 팩 접속 차단). `PACK_VERSION` 1.4.0, jbro-policy 0.1.7 → 0.1.8(`ad60ec63`). 다른 모듈 버전은 1.3.0 때처럼 올리지 않았다.
- **빌드:** `ad60ec63`을 `F:/AI/build/release-1.4.0`에서 18개 assemble(스킬 기본 17개 + 새 모듈 simple-trash-can) 성공, 모든 JAR `jar --validate` 통과, `:jbro-policy:unitTest` 실패 0. better-battle-presentation은 1.3.0 이후 소스 변경이 없어 빌드하지 않았다.
- **배치:** 개발 서버·클라이언트 14개 교체(백업 `develop-product/deployment-backups/20261019-022623-release`). PPakemon 16개 변경(휴지통·to-item 0.1.4·custom-species 0.1.1 추가, 옛 버전 삭제), 재계획 0건·빌드본 18개 해시 일치. 로컬 운영 서버 12개 변경, 운영 저장소 `c36efa7` 푸시. VPS가 먼저 올린 `16ace84`(0.1.7 IV 사탕 차단)는 0.1.8에 포함돼 리베이스 때 0.1.7을 지웠다.
- **XS 사탕 월드팩:** 운영의 `world/datapacks/jbro-policy-xs-candy-six.zip`을 지웠다. 0.1.8 내장 팩 `no_stat_candy_l_xl`에 XS 6개 조합(일반·Create)이 들어 있음을 JAR에서 확인했다. VPS에 반영할 때도 같은 삭제가 따라간다.
- **deploy-product:** `sync-release.ps1` 불일치 0, 서버 49(운영 49)·클라이언트 18. VERSION.txt 두 곳에 1.4.0 블록. 가라르는 `active:false`로 들어가 노트에서 뺐다.
- **미완·미확인:** VPS는 준비 시점에 켜져 있어 반영하지 않았다(종료 동의 필요). 모드팩(.mrpack)·드라이브 업로드·실접속(1.4.0 통과, 1.3.x 거절)·실게임은 아직이다.

## [2026-10-19 01:34] VPS 정책 JAR 교체·01:30 재기동 완료 (실게임 반격 미검증)

- **승인·실행:** 사용자가 새 시각 01:30 KST를 명시했다. 그 시각 정상 저장·종료를 확인하고 `mods/jbro-policy-0.1.7.jar` 한 파일을 원자 교체했다(교체 0.037초). 01:31:01 `Done (4.295s)`·새 Java PID 177951·25565 외부 TCP 및 Minecraft 1.21.1 상태 응답을 확인했다. 기존 프로세스 PID 141095는 정상 종료됐고 강제 종료·호스트 재부팅은 하지 않았다.
- **산출물·보존:** 빌드한 main `14fc99d3fb312f15a8e0fb2db74b8ee81c0a4f09`의 JAR SHA-256 `495d8c9757cb15a8f9854c5e754beb90df4cef24a2b6cb9c551d1381d4aefa7c`가 개발 설치본·VPS 설치본·사전 검증본 모두 같다. 48개 모드 전체 해시를 비교해 변경은 정책 JAR 한 개뿐이며 run.sh도 유지됐다. 월드·플레이어·광장 데이터를 교체하지 않았다. 클라이언트·로컬 운영 저장소·deploy-product는 바꾸지 않았다.
- **차단·기동 확인:** VPS 레시피 2430→2418로 정확히 12개 감소. 콘솔에서 32개 활성 팩 중 `jbro_policy:no_stat_candy_l_xl`를 확인했다. 기존 XS 6개 월드팩도 유지됐다. 로그에 `Mixin apply failed`·`InvalidInjectionException`·`InjectionError` 없음. 독립 스모크 서버에서는 Cobblemon 두 대상 클래스(AttackAngryAtTask, SwitchToFightTaskConfig) 실제 로드와 오류 없는 Done을 추가 확인했다. 포켓몬 소환 명령은 실패했으므로 야생 반격 동작이나 플레이어 제작을 검증했다고 하지 않는다.
- **자동 확인의 한계:** 정상 기동 뒤 보낸 팩 조회 응답이 2초 대기 이후인 01:31:06에 나와 작업자의 조기 확인이 실패 상태를 남겼다. 추가 재시작·롤백 없이 실제 응답과 게임 상태·해시를 읽어 검증했고, 기존 실패 이력은 보존한 채 상태에 `complete_verified`를 추가했다.
- **공지 취소 실수:** 최초 01:12:05에 01:30 재시작 공지를 보냈다. 사용자의 추가 공지 금지 지시를 즉시 작업자에 반영하지 못해 01:25:00 5분 전 공지가 나갔다. 01:25:17 기존 작업자를 중단하고 모든 announce를 차단한 작업자로 01:30 교체만 재예약했다. 01:29 공지와 재시작 전후 공지는 보내지 않았다. 사용자의 중단·취소 지시는 설명보다 예약 작업 취소를 먼저 반영해야 한다.
- **운영 Git:** VPS 운영 저장소에서 정책 JAR 한 파일만 커밋·푸시했다(`16ace845433bdab848acd120be35aeedae76cedf`). 커밋 파일 목록과 origin/main 실제 ref 일치를 확인했다. 다른 설정·광장·런타임 변경은 stage하지 않았다. 운영 저장소의 작성자 설정이 없어 기존 VPS 관리 도구의 authorName/authorEmail을 해당 commit 명령에만 적용했으며 Git 설정 파일은 수정하지 않았다.

## [2026-10-19 01:08] 개체값 사탕 차단 빌드·개발 서버 배치, VPS 교체 준비 (VPS 미교체)

- **구현·Git:** 개체값 사탕 6종 × 경험사탕 L·XL 재료 2종의 냄비 레시피 12개를 차단한 소스 `78b579c01`을 main에 fast-forward 병합·푸시했다. 경험사탕 XS 6개와 나머지 경험사탕 제작·분할, 상점 등 다른 획득 경로·기존 아이템 사용은 유지한다.
- **빌드:** 원격 main과 일치하는 `cd4c389b798d709d987bdc8a54e4f812c6993368` 소스를 F 워크트리에서 `:jbro-policy:build`로 빌드했다. 모든 Gradle 쓰기 경로는 F로 분리했다. 종료 코드 0, `unitTest` 112/112 통과(중단·실패 0), `jar --validate` 통과. JAR SHA-256 `495d8c9757cb15a8f9854c5e754beb90df4cef24a2b6cb9c551d1381d4aefa7c`.
- **실행 확인:** F 독립 검증 서버(포트 25567, 개발 서버와 라이브러리·월드 공유 없음)에서 기존 개발 정책 JAR과 새 JAR을 같은 환경으로 각각 기동했다. 레시피 수 2067→2055로 정확히 12개 감소했고 내장팩 활성·정상 기동·정상 저장 종료를 확인했다. 새 야생 반격 믹스인 4개를 포함한 기동이다. 실제 플레이어의 제작·포케스낵 UI·야생 공격은 미검증이다.
- **개발 배치:** 25566 미수신 및 게임 서버 Java 프로세스 없음 확인 뒤 `develop-product/server/mods/jbro-policy-0.1.7.jar` 한 파일만 교체했다. 설치 SHA-256은 위 빌드와 같고 다른 모드 JAR 해시는 모두 유지됐다. 원본은 F 빌드 폴더 `baseline/development-jbro-policy-0.1.7.jar`로 보존했다. 실제 개발 서버는 기동하지 않았다. 클라이언트·릴리스 제품 폴더는 수정하지 않았다.
- **클라이언트 호환·변경 범위:** 새 JAR의 `PackCheck.class`가 기존 개발·VPS JAR과 바이트 단위로 같다(팩 1.3.0 유지). 새 방출 팝업은 `canSend`를 확인하고 미지원 클라이언트에는 기존 채팅 확인을 보낸다. 필수 클라이언트 업데이트는 없다. 기존 개발 설치본 대비 신규 반영은 개체값 사탕 차단과 평화로움 야생 반격이다. VPS 기존본 대비 포켓내비 스낵 기준 열기·XS 6개 내장 리소스·방출 팝업·피츄 화면 변경도 포함된다(XS 6개는 VPS 월드팩으로 이미 적용 중).
- **실수·복구:** 첫 빌드의 불필요한 모듈 설정을 줄이려 중단한 뒤 남은 본 작업의 Loom 잠금이 공유 mappings 파일 재생성을 유발했고, 다른 Gradle 프로세스의 파일 사용 때문에 실패했다. 본 작업의 disowned 잠금만 F로 옮겨 재생성을 피했다. 이후 PowerShell에 따옴표 없이 전달한 `-Pkotlin.compiler.execution.strategy=in-process`가 잘못 분리되어 태스크 선택에 실패했다. 전체 옵션을 따옴표로 감싸 최종 빌드를 통과했다. 같은 원인의 실패를 추측으로 반복하지 말고 실제 로그로 원인을 확인한다.
- **요청 외 라이브러리 사고·사용자 승인 복구:** 최초 검증용 `libraries` 정션을 실제 개발 서버에 연결해 실행기가 ASM 5개·Mixin·intermediary·Fabric Loader 총 8개를 재기록했다. 검증 실행을 중단하고 사용자에게 공개했다. 재점검 시 7개 JAR 무결성 정상, Fabric Loader 0.19.5 한 파일은 다운로드 중단으로 불완전했다. 사용자가 해당 한 파일 복구를 명시 승인한 뒤 정상 0.19.5 JAR로 교체했다(SHA-256 `93044e4dd46de5d8136701292f05e868da096d2c9fddb4793e4fdbcc63efc695`, CRC 정상). 불완전 파일은 F `baseline/broken-fabric-loader-0.19.5.jar`에 보존하고 나머지 7개 해시 유지 확인. 이전 8개 파일과 작업 전 바이트 동일 여부는 복원할 원본이 없어 미확인이다. 이후 검증 환경은 디렉터리·라이브러리의 독립 사본을 사용했다.
- **VPS 준비·미완료:** 사용자가 10월 19일 00:30 KST에 VPS 마인크래프트 서버 재시작과 `jbro-policy`만 교체하도록 명시했으나 위 실수와 중단으로 그 시각을 놓쳤고 실행하지 않았다. 10월 18일 23:53:53 마크 내 공지 전송 확인. 현재 VPS PID 141095 유지, 기존 JAR SHA-256 `be55de294f74cc7c71c248d931ff6f1aefdef376a27b159f71a0d356a508946d`. 서버 밖 `~/.local/share/ppakemon-manager/manual/20261019-jbro-policy-iv-candy/`에 기존/신규 JAR·정상 저장 종료→단일 JAR 원자 교체→기존 실행 방식으로 재기동하는 절차를 준비했다. 새 JAR 해시·기존 48개 모드 해시·run.sh 보존·아카이브 무결성 사전 검사 통과. `restart_at`은 null이며 예약하지 않았다. 새 재시작 시점의 사용자 승인을 받은 뒤에만 VPS를 종료한다. 월드·설정·다른 모드·클라이언트·로컬 운영 저장소·deploy-product는 교체하지 않았다.

## [2026-10-18 23:53] 개체값 사탕 조합법 12개 차단 복원 (구현, 빌드·배치 대기)

- **사용자 지시·오류 원인:** 경험사탕 요청과 별개인 개체값 사탕 차단을 10월 3일 `e532c67b6`에서 제거한 것은 요청 범위를 넘긴 변경이었다. 개체값 사탕은 상점 등 다른 경로로도 얻으므로 제작분만 추적·회수하거나 이미 사용한 IV만 선별 복구할 수 있다고 가정하지 않는다. 기존 아이템·포켓몬·플레이어 데이터는 수정하지 않는다.
- **구현:** 기존 내장팩 `no_stat_candy_l_xl`에 Cobblemon 1.8.1 원본에서 확인한 개체값 사탕 6종 × 경험사탕 L·XL 재료 2종, 총 12개 냄비 레시피 차단 파일을 복원했다. 기존 `no_ability_patch`와 같은 Fabric load condition 방식이다. 경험사탕 XS 6개·S/M/L/XL 합성·분할 및 Create 연동은 유지한다. 한/영 팩 설명·README·기존 데이터 테스트를 맞췄다.
- **적용 범위·대기:** 사용자는 개발 서버 적용과 설치본 대비 변경 브리핑, 12시 30분 `jbro-policy`만 교체 후 서버 재시작을 요청했다. 예약 대상 서버와 자정/낮 시간 확인 중이며, 이 기록 시점에는 빌드·개발 배치·VPS 교체·재시작·실게임 검증 모두 미실행이다. 모드팩 버전·네트워크 payload는 변경하지 않는다.

## [2026-10-18 15:00] 평화로움에서도 야생 포켓몬이 반격 (구현·커밋, 빌드·테스트·배포·실게임 미실행)

- **사용자 제보·결정:** 실제로 해 보면 야생이 반격하지 않는다. 원인은 서버 난이도 `difficulty=peaceful`(개발 서버, 로컬 운영, VPS 모두 직접 확인, VPS는 SSH 읽기만). 평화로움은 유지하고 야생 공격만 들어가게 하라(사용자가 2안 선택).
- **막던 곳 네 군데(바이트코드 확인):** Cobblemon 1.8.1 `AttackAngryAtTask`의 `create$lambda$0$0$0`(평화로움이면 공격 대상 안 정함), `SwitchToFightTaskConfig`의 `createTask$lambda$0$0$0`(평화로움이면 fight 활동 안 감), 바닐라 `LivingEntity.canAttack`(평화로움이면 플레이어 대상 거부, `StopAttackingIfTargetInvalid`가 `Mob.canAttack`을 불러 대상을 지움), `Player.hurt`(난이도 보정 피해를 평화로움에서 0으로). Cobblemon `PokemonEntity.doHurtTarget`은 바닐라로 넘겨 `mobAttack` 피해를 쓴다.
- **구현:** `WildRetaliation.difficultyFor`가 야생 `PokemonEntity`(`pokemon.isWild()`)에게만 평화로움을 쉬움으로 보이게 하고, 믹스인 4개(`WildRetaliationAnger/Fight/Target/DamageMixin`)가 위 네 곳에 붙는다. 피해 쪽은 `scalesWithDifficulty`를 false로 바꿔 보정 없는 원래 공격력이 들어간다(쉬움 보정도 안 받음). 다른 몹·소유 포켓몬·평화로움의 나머지(적대 몹 없음, 배고픔 등)는 그대로다.
- **주의:** Cobblemon 쪽 두 믹스인은 Kotlin 람다 이름을 대상으로 한다. Cobblemon을 올리면 이름이 바뀌어 `defaultRequire 1`로 서버 시작이 실패할 수 있으니 그때 다시 맞춘다.
- **미확인:** 사용자 지시("빌드는 대기")로 컴파일·테스트·JAR 배치를 하지 않았다. 믹스인이 붙는지(특히 `remap=false` + `@At(remap=true)` 조합), 실게임에서 야생이 때리는지, 반격하는 종이 몇이나 되는지 모두 미확인. 서버 JAR이라 VPS 반영이 있어야 효과가 난다.
- **정정:** 같은 날 앞서 "JSON에 없는 `fightsMelee`는 꺼짐"으로 세어 거의 반격하지 않는다고 했다가, 기본값이 true임을 확인해 "대부분 반격한다"로 정정했었다. 그 판단은 데이터만 본 것이었고, 실제로는 평화로움이 전부 막고 있었다.


## [2026-10-18 14:10] 포켓내비 스낵 수정 빌드·개발 배치 (빌드·테스트·개발 배치 확인, 실게임 미검증)

- **빌드:** origin/main `c26033812`을 `git archive`로 `F:/AI/builds/pokenav-snack-1018/src`에 풀어 `:jbro-policy:build :ppakemon-client-setup:build` 종료 코드 0(쓰기 경로 F 확인, 다른 세션의 MCC Gradle과 출력 폴더 분리). 테스트: client-setup 92개 통과, jbro-policy 111개 중 110개 통과·실패 0·중단 1. 중단은 `real wiki questions…`가 `../../../server-wiki`를 init 스크립트가 바꾼 작업 폴더 기준으로 못 찾아 `assumeTrue`로 빠진 것으로, 이 변경과 무관하나 이번에는 돌지 않았다.
- **JAR:** `jbro-policy-0.1.7.jar` `70c530065794215f668e1b3522791dd74ac10aab2eeb203cf837cb82877c4360`, `ppakemon-client-setup-0.1.21.jar` `2abf621ab300c9fb926df03f6930680bd4e600ca3a41924a2e994f2fec6d740a`, 둘 다 `jar --validate` 통과. 버전 번호는 올리지 않았다.
- **배치:** 게임·개발 서버 꺼짐(25565/25566 미수신) 확인 후 `develop-product/server`(jbro-policy), `develop-product/client`·PPakemon 프로필(둘 다) 교체, 해시 일치. 이전 JAR은 `develop-product/deployment-backups/20261018-pokenav-snack/`. 서버는 켜지 않았다. 운영·VPS·deploy-product는 바꾸지 않았다(스낵 수정은 서버 JAR이라 VPS에 반영해야 플레이어에게 효과가 난다).
- **미확인:** 실게임에서 스낵을 보고 Tab을 눌러 스낵 목록이 뜨는지, 셋업이 Tab을 적는지.

## [2026-10-18 13:30] 포켓내비 단축키·명령이 바라본 포케스낵 기준으로 열림 (구현·커밋, 빌드·테스트·배포·실게임 미실행)

- **사용자 제보:** 포케스낵을 바라보고 포켓내비 단축키를 누르면 아이템처럼 스낵 기준 스폰 목록이 떠야 하는데 일반 목록이 뜬다.
- **원인(Cobblenav 2.4.1 바이트코드):** `Pokenav.use`는 서버에서 Cobblemon `ServerPlayer.raycast(blockInteractionRange, ClipContext.Fluid.NONE)`로 시선 블록을 찾고, 블록 엔티티가 `PokeSnackBlockEntity`일 때만 그 좌표를 `OpenPokenavPacket`의 두 번째 인자(`fixedAreaPoint`)로 넘긴다. 우리 `PokenavCommand`는 늘 `null`을 넘겼다. 아이템의 다른 분기는 없고 OS 값(Lite, 위치만 켬)은 이미 같았다.
- **구현:** `PokenavCommand.snackInSight`가 같은 레이캐스트로 스낵 좌표를 넘긴다. Tab 단축키는 `/pokenav`를 보내므로 같이 고쳐진다. `field_1348`이 `NONE`인 것은 Loom 매핑으로 확인했다.
- **미확인:** "빌드는 지시할 때만" 규칙으로 컴파일·테스트·JAR 배치를 하지 않았다. 실게임에서 스낵 목록이 뜨는지 확인하지 않았다. 서버 쪽 변경이라 개발·운영·VPS 서버 JAR 교체가 있어야 효과가 난다.
- **같이 받은 질문(야생 반격):** Cobblemon 1.8.1에는 반격 전역 컨픽이 없고 기본으로 켜져 있다. `behaviours/pokemon/auto/pokemon_non_party.json`이 종 데이터 `behaviour.combat.willDefendSelf`가 켜졌거나 알파인 야생에 `cobblemon:retaliates`를, `pokemon_combat.json`이 `fightsMelee`인 개체에 근접 공격을 붙인다. **`CombatBehaviour` 기본값은 `fightsMelee=true`, `willFlee=true`, `willDefendSelf=false`**(바이트코드 확인)라 JSON에 없는 값은 이 기본값이다. 구현된 888종 중 `willDefendSelf` 460종과 모든 알파가 반격하고, 나머지는 도망간다. 처음에 JSON에 없는 `fightsMelee`를 꺼짐으로 세어 "거의 반격하지 않는다"고 잘못 보고했다가 사용자 지적으로 정정했다. 우리 모드·개발 서버 데이터팩에 이 값을 바꾸는 파일은 없다. 실게임 미확인.

## [2026-10-18 01:56] XS 6개 월드 데이터팩 운영 적용·재시작 없이 리로드 확인

- **소스·산출물:** 소스 main `43a7b94a85c296b02d23fcc0b6ce0ebdb79f9b51`의 내장팩 `pack.mcmeta`와 두 XS 레시피로만 `jbro-policy-xs-candy-six.zip`(3파일)을 만들었다. JAR는 빌드/교체하지 않았다. 운영 저장소 main `94911adc20ef61a3dbb86aad426b8619a3103c4e`와 원격 main 일치 확인. ZIP은 Git LFS 대상이므로 커밋의 LFS pointer OID/size를 원본과 대조했고 실제 ZIP CRC·두 count 6·해시를 검증했다.
- **VPS 적용:** `/reload`로 신규 팩이 자동 활성화됐다. 01:55:30(KST) `datapack list enabled`에서 32개 팩 중 `file/jbro-policy-xs-candy-six.zip`이 맨 마지막(가장 높은 우선순위)인 것을 확인했다. 두 레시피 파일 결과는 각 6개이며 리로드의 레시피 파싱/로딩 오류 없음. 상점 224품목도 다시 정상 로드됐다. pane PID는 전후 `141095`로 유지됐고 VPS 종료·재시작은 하지 않았다.
- **로컬 배포:** 로컬 운영 저장소도 같은 운영 main을 fast-forward해 ZIP 해시 `758bc8407a86b1b7bbc2527cb8c3f9aaf7da3eaa3423e099e56eaddbeb0fd166` 일치, `git status --porcelain` 출력 없음까지 확인했다. 로컬 서버는 실행/리로드하지 않았다.
- **검증 한계·유지:** 실제 냄비 제작 및 클라이언트 레시피 UI 확인, Gradle 단위 테스트, 새 JAR 빌드는 하지 않았다. 운영의 기존 JAR 내장팩 설명/리소스는 3개로 남지만 최우선 월드팩이 같은 레시피 ID를 6개로 덮어쓴다. 이 팩을 제거하면 기존 JAR 환경에서는 3개로 돌아가므로 새 JAR 적용 전 임의 제거하지 않는다. 기본 소스는 이미 6개이며 다음 정상 빌드에 반영된다. 다른 사탕 합성·분할 규칙과 Create 설치 조건은 그대로다.

## [2026-10-18 01:53] 경험사탕 XS 냄비 제작량 3→6개·재시작 없는 반영 준비

- **사용자 요청·원인:** 냄비 경험사탕 XS 결과를 6개로 올리고 VPS 재시작 없이 가능한지 검토한다. 소스와 실제 VPS `jbro-policy-0.1.7.jar`의 legacy 내장팩 `no_stat_candy_l_xl` 모두 결과 `count: 3`인 것을 확인했다.
- **소스 변경:** 일반 냄비·Create 선택 연동 냄비 XS 결과만 6개로 수정했다. 재료(스컬크/경험치 조각 + 벌집 조각), Create 로드 조건, 다른 사탕 레시피는 그대로다. 한/영 팩 설명·pack.mcmeta·주석·기존 PolicyDataTest 기대값·README를 6개 기준으로 맞췄다. 기존 팩 ID는 보존한다.
- **검증:** 두 JSON의 결과 count만 바뀌었는지 기존 커밋과 대조했고 재료·Create 조건 보존, 번역 JSON 유효성, `git diff --check`를 확인했다. Gradle 단위 테스트·JAR 빌드는 실행하지 않았다.
- **무중단 적용 방식:** 커밋된 내장팩 `pack.mcmeta`·`data/`로 월드 데이터팩 `world/datapacks/jbro-policy-xs-candy-six.zip`을 만들어 같은 레시피 ID를 높은 우선순위에서 오버라이드한다. 실행 중인 JAR은 교체하지 않는다. `/reload` 후 활성 팩 목록·리로드 오류를 확인한다. 이 항목 작성 시 운영 데이터팩 배치·리로드·실제 냄비 제작은 아직 미실행이다.

## [2026-10-16 11:30] /release 확인을 콘텐츠 테마 팝업으로 (컴파일·단위 테스트 확인, 빌드 배치·실게임 미확인)

- **빡대리님 요청:** 채팅 [예] 클릭 대신 확인 팝업.
- **구현:** `PartyRelease.request`가 `ReleaseAskPayload`(토큰, 슬롯, 이름 Component, 레벨)를 받을 수 있는 클라이언트에는 이것을 보내고, 받을 수 없으면 기존 채팅 확인을 보낸다. 클라이언트 `ReleasePopup`은 `CobblemonUiDialogScreen` 빨간(DANGER) 팝업을 콘텐츠 테마로 띄우고, 확인하면 `/release confirm <token>`을 보낸다. 그만두기·Esc는 아무것도 보내지 않고 토큰이 2분 뒤 만료된다. 수신기는 `cobblemon_ui`가 있을 때만 등록하므로, 없으면 서버가 채팅으로 되돌아간다.
- **주의:** 이름을 서버에서 `.string`으로 바꾸면 종 이름이 서버 언어(영어)로 굳는다. 그래서 Component 그대로 `ComponentSerialization.STREAM_CODEC`으로 보낸다.
- **확인:** 커밋 `89a4f353`. Codex가 F 복사본에서 컴파일, `jbro-policy:unitTest` 111/111 통과(로그는 Claude가 확인). Codex 리뷰 지적 없음. 방출 팝업을 다루는 단위 테스트는 없다. 빌드 배치와 실게임 팝업은 확인하지 않았다.
- **테스트 태스크:** 이 모듈은 `tasks.test`가 꺼져 있다. 테스트는 `:jbro-policy:unitTest`로 돌린다(`test`는 SKIPPED로 끝난다).

## [2026-10-14 10:00] 1.3.0 릴리스 — 개발 설치본을 운영으로 (실접속 미확인)

- **사용자 결정:** 1.3.0(앞 두 자리 변경으로 1.2.x 팩 접속 차단). 배포 원본은 개발 서버·클라이언트에 설치된 JAR이며, 개발에 없던 것은 `PACK_VERSION` 1.3.0뿐이라 jbro-policy 0.1.7만 새로 빌드해 개발 서버·클라에 넣었다(0.1.6 백업 `develop-product/deployment-backups/20261014-094538-release-1.3.0`). to-item 0.1.4 승격은 되돌렸다(`f2bd5e4d`).
- **빌드:** `f10eef4e`를 `F:/AI/build/release-1.3.0`에서 17개 assemble 성공. 개발·운영·PPakemon의 같은 이름 JAR이 모두 이 빌드본과 해시가 같다(to-item은 이름이 0.1.4라 대조 안 됨). 테스트는 돌리지 않았다.
- **배치:** 로컬 운영 서버 8건(우리 모드 5 갱신, jbro-policy 0.1.7, simple-myroom 1.0.1→1.0.2) 운영 저장소 `25dabc3` 푸시. PPakemon 7건(cobblemon-ui 포함). `deploy-product/server` 48개=운영 서버, `client` 17개=PPakemon, 해시 차이 0. `VERSION.txt` 두 곳에 1.3.0.
- **사고 기록:** 로컬 운영 서버 폴더가 통째로 없어져 GitHub에서 다시 clone했다(저장소에 없는 로컬 전용 파일은 복구되지 않음). `Downloads`의 이전 `.mrpack`도 모두 없어졌다. VPS 광장 청크 93개는 사용자 요청으로 VPS에서 커밋·푸시(`e46cbc5`).
- **VPS:** 사용자 권한 승인 뒤 `update` e46cbc5→25dabc3(8건, 백업 `20261014-095750-55b87817`). VPS `mods` 48개 = `deploy-product/server` 해시 차이 0. 서버는 꺼진 그대로.
- **모드팩:** 사용자가 받아 준 1.2.0 팩의 목록으로 `Downloads/PPakemon 1.3.0.mrpack`(220,945,041바이트) 생성. 목록 70, overrides mods 20·리소스팩 6·셰이더 1·`servers.dat`, `config` 0. 드라이브 업로드는 사용자가 했고(`모드팩` 폴더, 링크 공개), 1.2.0은 `old`로 옮겼다.
- **미확인:** 실접속(1.3.0 통과, 1.2.0 거절), 실게임.

## [2026-10-13 06:25] 접속 시 모드팩 버전 검사 0.1.6 — 1.2.0에 포함해 전체 반영 (실접속 미확인)

- **사용자 결정:** 버전 강제를 1.2.0에 넣는다. 비교는 모드팩 버전의 앞 두 자리만(1.2.x끼리 통과). 모드 해시·버전 비교안은 사용자가 거절했다.
- **구현:** `pack/PackCheck.kt`. `PACK_VERSION` 상수(릴리스마다 올린다). 서버 `ServerConfigurationConnectionEvents.CONFIGURE`에서 `pack_check_request` 채널을 못 받는 클라이언트(옛 팩, jbro-policy 없음)는 바로 끊고, 받는 클라이언트에는 구성 작업으로 요청을 보내 `pack_check_reply`의 버전을 비교한다. 거절 문구는 `KoreanText.message`라 옛 클라이언트에도 한국어로 보이며, 디스코드 초대 링크(`inviteUrl`)를 붙인다. 운영자 예외는 없다.
- **검증:** 커밋 `098ed1c5`, `:jbro-policy:assemble :jbro-policy:unitTest` 성공, 111개 중 110개 통과·실패 0(`PackCheckTest` 2개 포함). 실제 서버 접속 테스트(새 클라 통과, 1.1.0 클라 거절)는 하지 않았다.
- **배치:** 개발 서버·클라이언트, PPakemon, 로컬 운영(`fa4dcbc`), `deploy-product`, VPS(`fa4dcbc`, 백업 `20261013-062439-9157bb4d`) 모두 0.1.6, 해시 일치. VPS 서버는 꺼진 그대로. `Downloads/PPakemon 1.2.0.mrpack`을 0.1.6으로 다시 만들었고, 먼저 만든 팩은 `Downloads/old/PPakemon 1.2.0 (버전검사 전).mrpack`.
- **06:40 모드팩만 갱신:** `cf789b4e`(to-item 툴팁 크기 막대 정렬, 클라이언트 코드만)를 `cf0bf895`에서 to-item만 다시 빌드해 PPakemon·`deploy-product/client`·`.mrpack`에 넣었다. 사용자 결정으로 서버(로컬 운영·VPS·`deploy-product/server`)는 그대로라, to-item 0.1.3이 서버와 클라이언트에서 해시만 다르다. 직전 팩은 `Downloads/old/PPakemon 1.2.0 (툴팁 막대 수정 전).mrpack`.
- **문제가 생기면:** 모두가 막히면 VPS 관리자 백업이나 0.1.5로 되돌린다(0.1.5는 검사가 없다). 끄는 설정은 없다.

## [2026-10-13 03:30] 1.2.0 릴리스 반영 — PPakemon·로컬 운영 서버·deploy-product (VPS 제외)

- **소스·빌드:** origin/main `b0b65557`(사용자 툴팁 지닌 물건 커밋 포함)을 F 드라이브에 풀어 우리 모드 17개를 `assemble`, 모든 JAR `jar --validate` 통과. 테스트는 돌리지 않았다.
- **설치:** 개발 서버·클라이언트에 바뀐 모드만 교체(백업 `develop-product/deployment-backups/<시각>-release-1.2.0/`). 개발 클라이언트가 켜져 있던 동안은 PPakemon 패치 기준을 개발 클라이언트 복사본(`F:/AI/build/plaza-fix-20261011/client-stage`)으로 잡았다. 클라이언트 패쳐의 실행 검사는 모든 마인크래프트 클라이언트를 막아서, PPakemon 프로세스 없음·PPakemon 모드 잠금 0을 직접 확인하고 `-SkipProcessCheck`로 적용했다.
- **결과:** PPakemon 15+1개(PokemonToItem 제거), 로컬 운영 서버 15개(운영 저장소 `8d7b7e9` 푸시), `deploy-product/server` 48개는 운영 서버와 같고 `client`는 우리 모드 17개. 모든 설치본 해시가 빌드본과 같다. `VERSION.txt` 두 곳에 1.2.0 기록.
- **기존 PokemonToItem 아이템:** VPS 월드 전체(배낭 `sophisticatedbackpacks.dat`, 청크, 엔티티, 플레이어·합성 데이터)와 개발 월드에서 `PTI_NBT` 0건(10-13 02시대). VPS 반영 때 서버를 내린 뒤 다시 검색한다.
- **VPS (10-13 06:04):** 사용자가 06:00에 정상 종료해 둔 상태에서 반영했다. 종료 후 최종 월드에서도 `PTI_NBT` 0건이라 변환은 하지 않았다. 관리자 update로 `8d7b7e9` 적용(15개, 설정·월드 보존, 백업 `~/.local/share/ppakemon-backups/manager/20261013-060443-ea73bd7c`), 모드 48개 해시가 `deploy-product/server`와 같다. 서버는 켜지 않았다(사용자가 07시에 연다). update RPC는 `head`·`target`을 같이 넘겨야 하며, 빠뜨리면 `'head'` 오류로 아무것도 바꾸지 않고 끝난다.
- **모드팩:** `Downloads/PPakemon 1.2.0.mrpack`(220MB)은 1.1.0 팩의 Modrinth 목록(70개, 해시 일치)에 우리 모드 등 20개와 리소스팩·셰이더·`servers.dat`을 넣어 직접 만들었다. 사용자 결정으로 `config`는 통째로 뺐고 데이터팩(`ATM x MSD`)은 목록으로 들어간다.
- **미실행:** 실게임 확인, 구글 드라이브 업로드(커넥터가 회사 계정이고 10MB 한계).

## [2026-10-12 23:40] 포켓 내비 레어도 순서 고정 — 개발 서버·클라이언트 배치 (0.1.5, 실게임 미확인)

- **원인(바이트코드 확인):** Cobblenav 2.4.1 `RequestLocationScreenInitDataHandler`가 `BestSpawner.config.worldBuckets.keySet()` 순서를 그대로 레어도 목록으로 보낸다. Cobblemon 1.8.1은 버킷을 맵(boss 포함)으로 읽어 순서가 보장되지 않고, 실제로 boss→흔함→매우 희귀→희귀→드묾으로 보였다(사용자 제보).
- **구현:** `BestSpawnerBucketOrderMixin`이 `BestSpawner.loadConfig` 끝에서 `SpawnBucketOrder.apply`를 부른다. world·fishing 버킷을 서식지·포켓스낵 설정에도 있는 등급(확률 높은 순) 다음에 월드 전용 버킷(boss)을 두는 `LinkedHashMap`으로 바꾼다(`BestSpawnerConfigAccessor`). 버킷 이름 목록 없이 설정에서 순서를 뽑는다. 가중치 추첨은 순서와 무관해 스폰 확률은 그대로다.
- **빌드·배치:** main `cdbcde3d`. `:jbro-policy:build` 성공, 단위 테스트 109개 통과. JAR SHA-256 `1afdc7f2d6f1915f1cb08e43bd69e8bb8adf50753c418efa4b65c5bb56dc88d8`, `develop-product/{server,client}/mods` 설치본 일치. 서버 꺼짐·cobblemon-dev 클라 꺼짐 상태에서 교체, 0.1.4는 `develop-product/deployment-backups/20261012-pokenav-bucket/`에 백업.
- **미확인:** 서버 기동·포켓 내비 화면 실확인 안 함. 운영/VPS·PPakemon·릴리스 미반영.

## [2026-10-12 21:18] 우클릭 복원 이관 버전 개발 서버·클라이언트 설치 (0.1.4, 실게임 미확인)

- **배치:** 사용자 배포 요청으로 실제 저장소의 `develop-product/{server,client}/mods`에서 0.1.3을 0.1.4로 교체했다. 우클릭 복원 소유권을 맡은 `cobblemon-to-item` 0.1.0을 함께 설치했다. 두 정책 설치본 SHA-256은 빌드본 `2489d47ca36526a29182701ac7a2e7a1efed51f171734a3ee9aeb961e21b874f`과 같다.
- **보존·근거:** main `5c280c99`와 전체 소스 tree가 같은 검증 빌드본을 재사용했다. 게임·서버 종료 상태에서 교체했고 이전 JAR은 `F:/AI/builds/cobblemon-to-item-28bf/deployment-backups/20261012-211852/{server,client}`에 백업했다. 기존 world·config·기타 모드는 변경하지 않았다.
- **마이그레이션·미확인:** 사용자 결정대로 서버 데이터 일괄 변환은 아직 미구현·미실행이다. 서버·클라이언트 기동과 실게임 검증도 실행하지 않았다. 운영/VPS·PPakemon·릴리스 대상은 변경하지 않았다. 배포 전체 안전검사·설치 검증은 신규 모드 MEMORY의 같은 시각 항목에 기록했다.

## [2026-10-12 20:38] 포켓몬 아이템 우클릭 복원을 신규 모드로 이관 (0.1.4, 배포·실게임 미확인)

- **소유권 변경:** 외부 PokemonToItem을 수정할 수 없어 정책 모드에 두었던 우클릭 복원 등록·클래스·전용 테스트를 제거했다. 신규 `cobblemon-to-item`이 아이템화·우클릭/인벤토리 복원과 배틀·권한·원본 보존 검사를 소유한다. 우클릭은 파티가 가득 차면 PC로 보내고, 저장 실패 시 아이템을 소비하지 않는다.
- **사용자 결정·마이그레이션:** 기존 PokemonToItem은 대체 대상으로 결정했다. 기존 `PTI_NBT` 데이터는 나중에 이 모드가 서버 데이터를 한 번 훑어 일괄 변환할 책임이며, 현재 범위에서는 구현하지 않는다. 신규 모드의 접속·아이템 획득 시 자동 변환으로 대신하지 않는다. 이번 작업에서 설치된 외부 모드 JAR은 삭제하지 않았다.
- **검증:** `build` 성공, 단위 테스트 108개 모두 통과(실패·중단 0). 기존 테스트 109개에서 이관한 구복원 전용 테스트 1개를 제거하여 108개가 되었다. Gradle 빌드·테스트는 게임 실행이 아니며, JAR 교체·배포, 서버 기동, 실게임 검증과 기존 데이터 마이그레이션은 미실행이다.
- **산출물:** 최종 JAR CRC·진입점·번역키·구복원 클래스 제거·`jar --validate` 검증을 통과했다. SHA-256은 `2489d47ca36526a29182701ac7a2e7a1efed51f171734a3ee9aeb961e21b874f`이다. 정책 코드는 최종 커서 클릭 변경과 무관하여 같은 코드의 108개 테스트 결과를 유지했다.

## [2026-10-11 23:05] 야생 우두머리를 리그 야생 스폰 범위 안으로 — 개발 서버·클라이언트 배치 (실게임 미검증)

- **사용자 결정:** 리그 야생 범위를 캡 −15~−5로 바꾸면서 "우두머리 포함". `capAlphaLevel(matched, pokemon, player)`가 우두머리 위치(엔티티 없으면 플레이어 위치)의 청크로 `LeagueRanks.wildLevelRange`를 읽어 Cobblemon 매칭 레벨을 그 범위로 `coerceIn`한다. 이전에는 레벨캡으로만 잘랐다. 파티 +4~20 매칭이라 대부분 범위 위쪽(캡 −5 또는 구역 기준 −5)에 붙는다.
- **빌드·배치:** 리그 MEMORY 같은 시각 항목 참고. 정책 JAR SHA-256 `3963e08d…7655600`, `develop-product/{server,client}/mods` 일치. 리그 JAR과 함께 깔아야 한다.
- **미확인:** 실게임에서 우두머리 레벨.

## [2026-10-11 19:45] 야생 우두머리 레벨을 리그 레벨캡으로 고정 — 개발·로컬 운영만 배포 (VPS 미반영, 실게임 미검증)

- **원인(바이트코드 확인):** Cobblemon 1.8.1 `AlphaLevelMatchingSensor`가 야생·비배틀 우두머리의 32블록 안 가장 가까운 플레이어가 바뀔 때마다 `getTargetLevel`로 레벨을 다시 맞춘다. 파티 최고 레벨 ≤20 +4, 21~30 +8, 31~45 +12, 46~65 +16, 66+ +20, 100 상한, 진화 최소 레벨 하한. 레벨캡·CLC 설정과 무관해서 캡을 넘었다.
- **구현:** `AlphaLevelMatchingCapMixin`이 `AlphaLevelMatchingSensor$Companion.getTargetLevel` 반환값을 `WildPokemonPolicy.capAlphaLevel`로 `LeagueRanks.levelCap` 이하로 자른다(사용자 결정: 캡과 같게). 진화 최소 레벨 하한보다 뒤에 자르므로 캡이 우선한다. 리그가 없거나 캡을 못 읽으면 그대로 둔다.
- **빌드:** `main` `f6fa907f` + 변경 5파일을 `F:/AI/build/alpha-cap-20261011`에서 빌드(쓰기 경로 모두 F). `:jbro-policy:build :jbro-policy:unitTest` 종료 0, 109개 중 108 통과·실패 0·중단 1(assumption, 신규 테스트 아님). 빌드 소스가 푸시한 `823ba5a4`와 같음을 diff로 확인. JAR SHA-256 `41ab1018ae53cb21acf2ebe677d4b7ac2340fb25c0e5e5250027541c6e858d65`, `unzip -t` 통과, 믹스인 시그니처가 `class_3222`로 리맵됨.
- **배치:** 개발 서버 `mods/`, 로컬 운영 `MinecraftPPakemonServer/mods/`(커밋 `61a7221` 푸시), `deploy-product/server/` 세 곳 해시 일치. 두 서버 모두 꺼진 상태에서 복사, 켜지 않음. 이전 JAR은 `develop-product/deployment-backups/20261011-alpha-cap/`. 클라이언트 JAR은 서버 전용 변경이라 바꾸지 않았다.
- **VPS:** 사용자 지시로 건드리지 않았다. 다만 운영 저장소에 푸시됐으므로 다음 매니저 update 때 함께 나간다.
- **미확인:** 서버 기동 시 믹스인 적용, 실게임에서 우두머리가 캡에 묶이는지.

## [2026-10-11 18:30] Cobbled Level Control 권한 노드 전부 비움 (서버 무중단, 유저 포획 미확인)

- **원인:** VPS 유저가 이로치 지그제구리를 던지면 `Catch Canceled: You do not have permission to do that!`. 문구는 `cobbled-level-control` 1.2.0의 `missingPermission`이다. `server.toml` 기본값이 이로치·전설·환상·울트라비스트·2단계·최종 진화체에 권한 노드를 요구하는데, 권한 모드가 없어서 OP가 아니면 아무도 못 가졌다. 사용자 결정: 포켓몬 제한은 jbro-policy가 맡으니 이 모드의 노드 제한은 전부 푼다. 레벨캡 스위치(`restrict*`)는 `true`로 남긴다.
- **리로드 근거(바이트코드 확인):** 설정은 Forge Config API Port로 등록되고 `ConfigTracker.openConfig`가 `disableConfigWatcher = false`면 파일 감시를 건다. `CatchingConfig.getConfig()`는 매 호출 `ConfigValue.get()`으로 읽는다. `/level-control configure`의 `value`는 `greedyString`이라 빈 값을 못 넣는다.
- **적용:** VPS `server.toml` 14줄을 백업(`/home/ubuntu/.local/share/ppakemon-backups/manual/20261011-182233-clc-nodes`) 후 같은 파일에 덮어썼다. 서버 PID 18830(18:12 기동, 이 작업과 무관한 재시작)은 그대로이고 이후 WARN/ERROR 없음. 운영 저장소 `9e939d4` 푸시, 로컬 운영 파일 MD5가 VPS와 같다(`6c49f167…`). 개발 서버와 리그 챌린지 `run/` 설정도 같은 값으로 바꿨다(CRLF 유지).
- **미확인:** 리로드는 디버그 로그라 흔적이 안 남는다. OP 아닌 유저가 이로치·진화체를 실제로 잡는지는 확인하지 못했다. VPS 작업 트리에 같은 내용의 미커밋 수정이 남아 있어, 매니저 update가 `9e939d4`를 받을 때 충돌하면 VPS 쪽 수정을 버리고 받으면 된다(내용 동일).
- **체크리스트:** `docs/SERVER_OPEN_CHECKLIST.md` 필수 설정 맨 위에 경고와 항목을 추가했다.

## [2026-10-11 03:24] 숨겨진 특성 1/30 빌드·개발 배포·운영·VPS 반영 (실게임 미검증)

- **빌드:** 커밋된 `origin/main` `5611b7e7`를 `git archive`로 `F:/AI/build/hidden-ability-20261011/src`에 풀어 빌드했다(워크트리 아님, Gradle 쓰기 경로 모두 F). `:jbro-policy:build` 종료 코드 0, unitTest 106개 모두 통과. 리그는 `assemble` 후 `unitTest`를 `WildTrainerDataTest`로 좁혀 11개 통과. 정책 JAR SHA-256 `3B08009542D332FDD481CC9734C06A50E9669E2BD5DC8CE355A705246F322521`, 리그 JAR `DCDA43CF079DA76787F71AF52CB407790958B4FEE0E6CD3E3C4AFD7F53A789A5`, 둘 다 `jar --validate` 통과.
- **같이 나간 변경:** 리그 JAR은 설치본 대비 실제 차이가 `wild_quiz.json` 하나뿐이고(나머지 210개는 줄바꿈 차이, 클래스 동일) 정책 JAR에는 미배포였던 `/피츄` 위키 답변(`67e156fc`, `PichuWiki`)이 들어 있다. 사용자가 "같이 배포"를 골랐다. 새 위키 `dimensions.html`은 서버 설치 위키에 넣지 않았다.
- **위키·대사 설치본:** 서버 설치 위키(`config/more-cobblemon-contents/wiki/pages`)는 저장소 `server-wiki` 원본과 이미 내용이 달라(BP 상점 문구 등) 원본으로 덮지 않고, 개발·운영 설치본 4쪽의 숨특 문구만 바꿨다. 과학자 대사 설치본은 커밋 원본과 해시가 같다. `startup-assets/server-wiki.zip`은 다시 만들지 않았다(훅은 빠진 파일만 채운다).
- **개발 배포:** 게임·서버 미실행, 25565/25566 미수신 확인 후 `develop-product/server`·`client`의 두 JAR을 교체했고 해시가 빌드본과 같다. 이전 JAR 백업은 `F:/AI/build/hidden-ability-20261011/deployment-backups/`. 개발 서버는 켜지 않았다.
- **운영 저장소:** JAR 2개·위키 4쪽·과학자 대사를 `1a1981a4`로 커밋·푸시했고 `ls-remote`와 같다.
- **VPS:** 접속자 0명에서 매니저 update job `53f86e4f01734e5c8b04addf3cd79e89`로 반영(백업 `/home/ubuntu/.local/share/ppakemon-backups/manager/20261011-032133-912137ae`). 03:22:44 `Done`, 25565 LISTEN, tmux `dead=0`, 피츄 봇 online, JAR·위키 해시가 운영 저장소와 같다. **매니저 업데이트는 VPS에 이미 있는 `config/` 파일을 보존하므로** 과학자 대사는 반영되지 않아, 서버 밖 `~/.local/share/ppakemon-backups/config-edit/20261011-extra-scientist/`에 백업한 뒤 직접 교체하고 콘솔 `npc reload`(`Loaded 31 dialogues.`)로 적용했다. VPS `config/jbro-policy.json`에는 옛 키 `wildHiddenAbilityRate: 30`이 남아 있고 새 코드는 이를 무시해 기본 1/30으로 동작한다.
- **미검증:** 실게임에서 야생 숨특 빈도, 과학자 대사, 퀴즈 보기, 피츄 위키 답변은 확인하지 않았다.

## [2026-10-11 02:54] 숨겨진 특성 30% → 1/30 수정 (구현만, 빌드·테스트·배포 전)

- **사용자 결정:** 숨겨진 특성 확률은 1/30이 맞고 30%는 버그다(기본 게임은 1/128). 정책 설정이 정수 퍼센트라 1/30을 담을 수 없어 키를 `wildHiddenAbilityOneIn`(N마리 중 1마리, 기본 30, 0이면 끔)으로 바꿨다.
- **옛 키:** `wildHiddenAbilityRate`는 퍼센트 뜻이라 읽지 않는다. 개발·운영·VPS `config/jbro-policy.json`에 `30`이 남아 있어도 기본 1/30으로 동작한다. 서버 설정 파일의 옛 키는 손대지 않았다(운영 업데이트도 설정을 보존한다).
- **같이 고친 곳:** 위키 `growth`·`ivs-abilities`·`legends`·`levels`, 과학자 대사(`cobblemon-npc/docs/dialogues/extra_scientist.json`), 리그 야생 퀴즈 `hidden_ability` 보기(1/30·1/128·1/10), README. 리그 야생 교환꾼은 `applyWildRolls`로 같은 확률을 쓴다.
- **미확인:** Gradle 빌드·JUnit(`WildRollsTest`, `PolicyConfigTest`, `WildTrainerDataTest`) 미실행. JAR·위키 zip·대사 배포, 실게임 확인 안 함. 서버에 깔린 대사 복사본(`config/cobblemon_npc/dialogues`)과 `startup-assets/server-wiki.zip`도 배포 때 같이 옮겨야 한다.

## [2026-10-11 04:35] 첫 접속 초대 링크 빌드·전체 배포

- **빌드:** main `dbece5a7`을 F 드라이브에 풀어 `:jbro-policy:build` 성공, 테스트 108개 통과, JAR SHA-256 `C3A2443D13FE2502D487AF547BEEA123A1BC3B554C3B88CCF0CEA58A3A00CACE`, `jar --validate` 0, `welcome.discord` 번역 포함.
- **배포(정책 JAR이 있는 모든 곳):** 개발 서버·개발 클라이언트·`PPakemon`·로컬 운영 서버·`deploy-product/client`·`deploy-product/server` 여섯 곳 해시 일치, 운영 저장소 `ccf20e7`, VPS 적용·04:27 기동. 이전 JAR 백업 `F:/AI/build/welcome-invite-20261011/deployment-backups/`.
- **링크:** 사용자가 만료 없는 `https://discord.gg/rcvmf3txDE`를 만들었다(`expires_at=null` 확인, #서버-상태 채널 초대). 개발 서버·로컬 운영·VPS `inviteUrl`과 기자 NPC 대사를 이 링크로 바꿨다. 실게임 첫 접속 메시지는 확인하지 않았다(VPS 유저 데이터 초기화로 다음 접속자는 모두 첫 접속이다).

---

## [2026-10-11 04:20] 첫 접속 메시지에 디스코드 초대 링크 (구현·커밋, 빌드·배포 미실행)

- **사용자 요청:** 첫 접속 키트 메시지에 디코 링크(`https://discord.gg/fpkNjYwCp`)를 넣자. 30일 만료를 피할 방법도 물었다.
- **구현:** `WelcomeKit.inviteUrl`을 `DiscordSettings.inviteUrl`로 채워, `/디코인증`과 같은 링크를 `welcome.discord` 번역 키로 눌러서 열리게 보낸다. 링크를 코드에 넣지 않아 링크가 바뀌어도 설정만 고치면 된다. 커밋 `11fb1a09`.
- **링크 만료(직접 조회):** 설정에 든 `HbKxTFeGB`는 2026-11-06 23:27 UTC, 새 `fpkNjYwCp`는 2026-11-09 12:10 UTC 만료. 둘 다 만료 링크다. 만료 없는 초대(`max_age=0`)를 만들어 `inviteUrl`에 넣어야 한다. 아직 설정 값은 바꾸지 않았다.
- **미확인:** Gradle 빌드·테스트, JAR 배치, 실게임 첫 접속 메시지 확인 안 함.

---

## [2026-10-11 04:06] 피츄 위키 선택 운영·VPS 반영

- 정책 JAR `F5DB967F…`와 차원 문서가 든 위키를 로컬 운영 서버·`PPakemon`·운영 저장소(`0f71835`)·VPS에 반영했다. VPS는 04:04:43 기동, 봇 online·명령 15개, 해시 일치 확인. 디스코드에서 실제로 `/피츄`에 물어본 결과는 확인하지 않았다. 자세한 배포 기록은 `works/tools/vps-manager/MEMORY.md`.

---

## [2026-10-11 03:50] 피츄 위키 선택 빌드·개발 배포 (빌드·테스트·개발 배치 확인, 서버 기동·디스코드 실사용 미확인)

- **빌드:** `origin/main` `4ff986e4`를 `git archive`로 `F:/AI/build/pichu-wiki-20261011/src`에 풀어 빌드(워크트리 아님, 이전 `plaza-fix` init·빌드 스크립트를 경로만 바꿔 재사용, Gradle 쓰기 경로 모두 F). 첫 빌드는 테스트 파일 두 줄의 `\n`이 셸 heredoc에서 실제 줄바꿈으로 바뀌어 `compileTestKotlin` 실패(본 코드는 컴파일됨) → `4ff986e4`로 수정. 재빌드 `:jbro-policy:build` 종료 코드 0, 테스트 108개 성공·중단 0(실제 위키 회귀 테스트 포함). JAR SHA-256 `F5DB967F72AE67280D59606123F8966A7449FC710EBB2EE70E0C93A46059D916`, `jar --validate` 0.
- **개발 배포:** 게임·서버 미실행과 25565/25566 미수신 확인 후 `develop-product/server`·`client`의 `mods/jbro-policy-0.1.3.jar` 교체, 두 설치본 해시가 빌드본과 같다. 개발 서버 위키(`config/more-cobblemon-contents/wiki`)는 main보다 오래돼(제작법·차원 문서 없음, 상점 가격 구버전) main 위키로 맞췄고 1,073개 파일 해시가 일치한다. 이전 JAR·위키 백업은 `F:/AI/build/pichu-wiki-20261011/deployment-backups/`. 개발 서버는 켜지 않았다. VPS·운영에는 반영하지 않았다.
- **교훈:** Git Bash heredoc(`<<'EOF'`)으로 Python·PowerShell 코드를 쓰면 `\\`가 `\`로 줄어드는 일이 이번 세션에서 세 번 있었다(시험 스크립트 경로, 테스트 `\n`, 배포 스크립트 정규식). 백슬래시가 든 코드는 Write/Edit 도구로 쓴다.

---

## [2026-10-11] 피츄 위키 참고를 질문별 구간 선택으로 바꿈 (구현·커밋, 빌드·테스트·배포 미실행)

- **이유:** 위키 전체(약 3.9만 자, 추정 2만~3만 토큰)를 질문마다 넣으면 agy 사용 한도를 많이 쓴다는 사용자 우려. 세션을 이어 가며 압축하는 안도 검토했으나, 매 턴 대화 전체를 다시 보내 토큰이 줄지 않고 공개 질문끼리 섞이거나 한 유저의 지시가 다른 유저 답을 오염시킬 수 있어 질문마다 새로 띄우는 방식을 유지했다. agy 로그(`~/.gemini/antigravity-cli/log`)에는 `input_tokens`/`output_tokens`만 있고 캐시 토큰 항목이 없어 서버 캐싱 여부는 확인할 수 없었다.
- **구현:** `PichuWiki.select` — `<h2>` 구간 161개를 두 글자 조각+단어 첫 글자(가중치 0.3)로 비교하는 IDF 점수. 가중치는 nav 제목·키워드 3, 소제목 2, 본문 출현(최대 3회)/3, 문서 제목 일치는 그 문서 모든 구간에 0.4배 가산. 의문사 조각(어디·어떻게·있어 등)은 제외. 1위 문서 점수 10 미만이면 FAQ 문서, 아니면 1위의 60% 이상인 문서 최대 2개에서 최고 구간의 25% 이상인 구간을 6천 자까지. 위키 문서 목록은 항상 붙인다. 가중치·문턱은 저장소 위키로 Python 시험 스크립트를 돌려 맞췄다(세션 임시 폴더, 저장소에 없음).
- **시험 결과(Python 프로토타입, Kotlin 동일 규칙):** 울트라홀 1,358자, 메가스톤 3,448자, 레벨캡 732자, 렉·잡담 → FAQ 878자 등 질문당 약 700~3,500자. "전설 어디서"가 "어디서" 소제목에 끌려가던 문제는 의문사 제외와 문서 우선 선택으로 해결했다. "서버 렉"은 점수 9.8로 문턱 바로 아래라 FAQ 경로로 답을 얻는다. 위키가 바뀌면 경로가 달라질 수 있다.
- **위키:** `nav.js`에 "보상"(배틀타워·팩토리·리그)과 "배지"(리그)를 키워드로 추가했다.
- **테스트:** `DiscordQuestionTest`에 구간 분리, 조사 일치, nav 순서·MCC 폴더 설정, 실제 저장소 위키(`../../../server-wiki`) 질문 7개 회귀 테스트를 넣었다. Gradle 컴파일·테스트는 "빌드는 지시할 때만" 결정에 따라 실행하지 않았다.

---

## [2026-10-11 02:36] VPS 문의 불가 원인 확인·설정 복구·재기동

- **원인(직접 확인):** VPS `config/jbro-policy-discord.json`에서 `inquiryChannelId`·`webhookUrl`이 모두 비어 있어 `/문의`가 꺼져 있었다. `adminChannelId`·`commandChannelId`·`newsChannelId`도 비었고 `inviteUrl` 키는 없었다. 개발 서버에는 모두 값이 있다. `jbro-policy/inquiries` 폴더가 없어 VPS에서 문의가 처리된 적이 없었다. agy는 `~/.local/bin/agy`에 있고 서버 Java PATH에 포함되며 로그인 토큰도 있어 원인이 아니었다(비로그인 SSH 셸의 `command -v`는 이 경로를 몰라 "없음"으로 보였다).
- **적용:** 사용자 지시("기존 서버 기준으로 다 채우되")로 VPS의 빈 필드 5개(`inquiryChannelId`, `adminChannelId`, `commandChannelId`, `newsChannelId`, `inviteUrl`)만 개발 서버 값으로 채웠다. 다른 값은 변경 전후 동일, 파일 권한 600 유지, 원본 백업은 `/home/ubuntu/.local/share/ppakemon-backups/manager/20261011-023349-discord-config/`. 값은 기록하지 않는다. 봇 토큰 GET으로 네 채널 모두 HTTP 200을 확인했다.
- **잡담방 `/피츄`:** 사용자가 잡담방에서도 `/피츄`를 쓰게 해 달라고 했다. 디스코드 GET으로 @everyone에 애플리케이션 명령 사용 권한이 있고, 잡담 채널 덮어쓰기와 명령별 권한 제한이 없음을 확인했다. VPS JAR(`CD50991F…`)의 `DiscordQuestion`에 `anyChannel` 예외가 있어 `commandChannelId`를 채워도 잡담방 `/피츄`는 답한다. 코드·디스코드 권한 변경은 하지 않았다. 실제 잡담방에서 써 본 확인은 안 했다.
- **재기동:** 접속자 0명(`list`) 확인 후 vps-manager `TmuxRuntime.stop()`(저장 로그 확인)·`start()`로 재기동했다. 새 PID 36925, 02:35:23 `Done (14.931s)`, TCP 25565 LISTEN, 02:35:26 봇 online, 슬래시 명령 7개→15개(관리자 명령 등록). 실제 `/문의` 전송·문의 검토 결과는 확인하지 않았다.

---

## [2026-10-11] `/피츄`가 서버 위키를 근거로 답하게 함 (구현·커밋, 빌드·테스트·배포 미실행)

- **사용자 신고:** 피츄가 서버 구조를 모른다. 울트라홀에 왜 못 들어가냐고 묻자 "자격이 안 된다"만 하고 이유를 설명하지 못했다.
- **원인:** `pichu-chat-agent.md`는 도구가 없고 프롬프트에도 질문만 들어가서, 피츄가 받는 서버 정보가 전혀 없었다. agy가 프로젝트 폴더를 모르는 게 문제가 아니라 애초에 아무 자료도 넘기지 않는 설계였다. 위키에도 차원·울트라홀 문서가 없었다.
- **결정:** 공개 디스코드 질문에 파일 읽기 도구를 주면 프롬프트 주입으로 서버 파일을 읽힐 수 있어 도구 없음은 유지했다. 대신 `PichuWiki`가 MCC 위키 폴더(`wiki.json`의 `directory`)의 홈과 `pages/*.html` `<main>`을 텍스트로 바꿔 질문마다 프롬프트에 넣는다(상한 6만 자, 현재 약 4만 자 추정). 검색으로 일부만 고르면 빠뜨릴 수 있어 전체를 넣었다. agent.md는 바꾸지 않았다(설치본과 다르면 호출을 거부하는 검사가 있어서, 바꾸면 기존 설치 서버에서 피츄가 멈춘다).
- **위키:** `server-wiki/pages/dimensions.html` 추가(server-wiki MEMORY 참고).
- **검증 경계:** "빌드는 지시할 때만" 결정에 따라 Gradle 컴파일·`DiscordQuestionTest`(새 테스트 3개)·JAR 배치·실제 agy 호출·디스코드 확인은 하지 않았다. 토큰 사용량이 질문당 위키 분량만큼 늘어난다.
- **VPS 문의 불가:** 같은 날 원인 확인·복구했다(위 02:36 항목). 설정 채널 값이 비어 있던 것이 원인이었고 agy는 정상이었다.

---

## [2026-10-12 06:30] 1.1.0 운영 반영: 운영 저장소·PPakemon·VPS·deploy-product, 광장 안내원 → 피츄 (VPS 서버는 꺼진 상태 유지)

- **사용자 요청:** 지금까지 개발 쪽 커밋 전부를 운영 서버·운영 프로필에 반영, 패치노트용 변경점 추적(버전 1.1.0, 예전 v0.1 기록 무시). 주요 변경은 AI 로직과 파티 사이드바·HUD 움짤. 추가로 VPS 풀 → 광장 최신화 → 광장 "안내원"을 피츄(이름·모델)로, 우클릭 시 짧은 대사 + `/피츄`.
- **빌드:** `origin/main` `480be6fc`를 F 드라이브에 풀어 우리 모드 16개를 `assemble`(테스트 없이)로 빌드. 개발 서버·클라 같은 이름 JAR 25개 교체(해시 일치). 이후 서버 `/피츄` 추가(`23cddd55`, 테스트 108개 통과, JAR `574F92B6…`)로 jbro-policy만 다시 빌드해 4곳 교체.
- **VPS 광장 분기:** VPS는 23:00부터 정상 저장·종료로 꺼져 있었고, 04:36 VPS에서 만든 미푸시 커밋 `9c94b89 광장 갱신`(광장 107파일) 때문에 원격과 갈라져(ahead 1, behind 8) 매니저 업데이트가 막혀 있었다. VPS에서 그 커밋을 원격 브랜치 `vps-plaza-20261012`로 푸시(LFS 포함)하고 로컬 운영 저장소에서 병합(`649c1ac`)했다.
- **운영 저장소:** 통합 패쳐 모듈을 명령줄로 호출(`Get-ServerDeploymentPlan`/`Invoke-ServerDeployment`, 27개). 시작 훅은 운영 파일을 두고 모드 호환·위키 해시만 바꾸며, 위키 압축본은 개발 서버 위키로 새로 만든다(개발 훅의 디스코드 검사 누락은 넘어오지 않음, 코드로 확인). 설정은 보존되므로 `jbro-policy.json` 안내 34개, `simple-myroom-messages.json` 도움말 13줄, 마이룸 디자이너 대사, `plaza_guide.json`을 따로 바꿨다. 커밋 `bb34052`, `df3bb1b`, VPS 커밋 `de663ad` 동기화.
- **PPakemon:** `Get-ClientPatchPlan`/`Invoke-ClientPatch`(39개, 위키 25개 포함, MouseTweaks 제거·Nemo's Inventory Sorting 추가는 개발 클라 기준). 모든 JAR이 개발 클라와 해시 일치.
- **VPS:** 매니저 update job `51d929af…`(`9c94b89`→`df3bb1b`, 백업 `manager/20261012-062031-b946fdf9`). 서버가 꺼져 있어 재시작 없음. 모드 해시 전부 운영 저장소와 일치. 보존된 설정 중 위 4개를 서버 밖 `config-edit/20261012-release-1.1.0/before.tar`에 백업 후 교체(이제 저장소와 동일). BP 상점·CLC·Chunky 설정은 저장소와 이미 같았다.
- **피츄 NPC:** 대화 `plaza_guide`를 `{speaker 피츄, skin pokemon:pichu, 대사 1줄, commands ["/피츄"]}`로. VPS 광장 `entities/r.-1.0.mca`를 내려받아 손실 없는 NBT 왕복(바이트 동일 확인) 도구로 그 NPC 하나의 `CustomName` "안내원"→"피츄", `NpcSkin` `Park_JH`→`pokemon:pichu`만 바꿨다(다른 엔티티 10개·UUID·태그 수 동일). 원본은 `config-edit/20261012-release-1.1.0/r.-1.0.mca.before`. 매니저 선택 커밋으로 `de663ad` 푸시. **개발 서버 광장의 안내원은 바꾸지 않았다.**
- **deploy-product:** 서버 폴더를 운영 모드 48개(서드파티 포함, 230MB)로 맞추고, 클라 폴더는 우리 모드 16개 최신 빌드(PokemonFusion 추가). 이전 JAR은 `F:/AI/build/plaza-fix-20261011/deployment-backups/deploy-product-1.1.0/`. 두 `VERSION.txt`를 1.1.0 패치노트로 다시 씀.
- **미검증·남은 것:** VPS 서버 기동은 하지 않았다(사용자 확인 대기). 실게임에서 피츄 NPC 모양·대화·화면 열림, 사이드바, AI 동작은 확인하지 않았다. 원격 브랜치 `vps-plaza-20261012`는 병합 후 남아 있다.

---

## [2026-10-12] ESC 메뉴는 "피츄 도우미" 하나만

- **사용자 요청:** 왼쪽 위 디코·위키·광장 가기를 없애고 피츄 도우미만 남김. 세 기능은 피츄 화면 안에 있다.
- **구현(`b7e3dea8`):** `JbroPolicyClient`의 ESC 버튼을 피츄 도우미 하나로 줄였다(`cobblemon_ui`가 없으면 버튼 없음). 안 쓰는 번역 키 `menu.jbro_policy.wiki·discord·plaza_enter·plaza_exit`를 지웠다(`message.jbro_policy.menu_link.*`는 서버 응답에 계속 쓴다). 안내 4개를 "피츄 도우미의 …"로 바꾸고 피츄 도우미 소개 안내를 맨 앞에 추가(34개), 개발 서버 `tips`도 교체. 위키 광장·리그 페이지, README, 체크리스트 갱신(개발 서버·클라 위키도 같은 수정).
- **빌드·배포:** 빌드 성공, 테스트 108개 통과, JAR `6BFB31664772889D273E058B3B3C87B39E805AF34DB67F0ED96598D6AC483921`. 개발 서버·클라·`PPakemon`에 교체, 해시 일치.
- **사용자 의견:** 사이드바 합성 버튼이 "너무 짜치긴 하는데 뭐…" — 바꾸라는 지시는 아니었다. 개선은 별도 요청 시.

---

## [2026-10-12] 피츄 버튼 정리: 아이템화·합성 제거, 위키·디스코드 맨 아래

- **사용자 요청:** 아이템화·합성 버튼 제거(사이드바로 이동), 디코·위키는 맨 아래. 순서: 광장, 마이룸, BP 상점, PvP 룸, 문의하기, 구역 스폰 레벨, 위키, 디스코드(4줄). README·위키 명령어 페이지 설명도 맞췄다(`41fb0d7a`). 개발 서버·클라 위키에도 같은 문구를 적용했다.
- **빌드·배포:** 빌드 성공, 테스트 108개 통과, JAR `088E65B2FE48095CD84C752DC97E936334012165C8F047D53336860EEFAAB301`. 개발 서버·클라·`PPakemon`에 교체, 해시 일치.

---

## [2026-10-12] 피츄 화면 배경·입력 차단: InvMove 원인과 대응

- **증상(사용자 스크린샷):** `/피츄` 화면에 블러·어둡게 하기가 없고 핫바가 선명하게 보이며, 화면을 연 채로 움직일 수 있었다.
- **원인(직접 확인):** 1.21.1 `Screen.render`는 `renderBackground`를 먼저 부르므로 우리 코드 문제가 아니었다. 개발·배포 클라이언트에 InvMove 0.9.3이 있고 `config/invmove.json`의 `movement`·`background`의 `unrecognizedScreenDefault`가 true다. InvMove는 처음 보는 화면에서 이동을 허용하고 `BackgroundMixin`으로 배경을 지운다. 일시정지 화면은 배경을 남기지만(`hideOnPause: Show`), 이동은 게임이 실제로 멈출 때(싱글)만 막아서 멀티에서는 `isPauseScreen`으로 해결되지 않는다.
- **대응(`311219d3`):** `InvMoveCompat`가 InvMove의 `Module` 인터페이스를 리플렉션 프록시로 구현해 `InvMove.registerModule`로 등록한다(초기화 순서와 무관하게 모듈 목록이나 대기 목록에 들어감). `PichuScreen`이면 이동 `FORCE_DISABLE`·배경 `FORCE_SHOW`, 그 밖에는 `PASS`. InvMove에 컴파일 의존성을 두지 않았다. 앞으로 새 메뉴 화면을 만들면 같은 모듈에 넣어야 한다.
- **빌드·배포:** 빌드 성공, 테스트 108개 통과, JAR `068A515ED804722CF080ECA0EAA396601A2E1C81881FB0D850DBA2464645EDF2`. 개발 서버·클라·`PPakemon`에 교체, 해시 일치.
- **미검증:** 실게임에서 블러·이동 차단이 실제로 적용되는지 아직 보지 않았다.

---

## [2026-10-12] 광장 안내원 구조 정정·/피츄 도우미 화면 (구현·빌드·개발·PPakemon 배포, 실게임 미검증)

- **사용자 지적:** 안내에 "광장의 리그 터미널을 우클릭"이라고 썼는데, 관장 도전은 리그 안내원에게서 한다. 위키 문구를 확인 없이 믿은 실수다.
- **VPS 광장 구조(직접 확인, 읽기만):** `world/dimensions/jbro_policy/plaza` 엔티티·블록 엔티티를 F 드라이브 사본에서 파싱했다. 도착 지점(0,80,0) 주변에 안내원(`plaza_guide`), 상점 주인(`plaza_shopkeeper`, `/mcc terminal shop`), 간호순(`plaza_nurse_joy`, `healpokemon`), 오박사, 회복장치 3대·PC·홀로 배틀 터미널(2,80,10)이 있다. 북동쪽에 리그 안내원(12,74,-26), 타워 안내원(18,74,-12), 팩토리 안내원(22,74,-28)이 있고, 각각 서버 권한으로 `/mcc terminal league|tower|factory`를 실행한다(타워·팩토리는 챔피언만). 리그·타워·팩토리 터미널 블록은 광장에 없고, 홀로 배틀 터미널 2대(다른 하나 18,74,-21)뿐이다. VPS NPC 대사에는 틀린 안내가 없었다(마이룸 디자이너만 `/room`).
- **정정(`d450e03d`):** 위키 광장(위치별 NPC 표 추가)·리그·타워·팩토리·허브·홈·검색어, 안내 3번(리그 안내원), BP 상점 안내(상점 주인 추가), 타워·팩토리 안내원 안내 1개 추가(33개). 개발 서버 `tips`와 개발 서버·클라 위키에도 같은 수정을 적용했다.
- **/피츄(`df271e65`, 위키 `5020d078`):** 클라이언트 명령 `/피츄`·`/pichu`와 ESC 메뉴 "피츄 도우미". `PichuScreen`은 기본 배경(블러·어둡게), 왼쪽 30%에 Cobblemon `ModelWidget`(정보 화면 비율 66px=2배·−10을 크기에 맞춰 늘림, `shouldFollowCursor`, 매 프레임 `lookStartTime` 갱신으로 커서를 항상 따라봄, 클릭 시 울음소리), 오른쪽에 `CobblemonUiThemedButton` 2열(세로 가운데), 아래 cobblemon-ui 공용 테마 대화창(NPC 대화창과 같은 모양, 이름표 "피츄", 글자 45자/초)이다. 버튼: 광장 가기/나가기, 마이룸 가기/나가기(`myroom`→`room` 루트), 위키, 디스코드(`RequestLinkPayload`), 아이템화 `/poketoitem`, 합성 `/pokefusion`, BP 상점·PvP 룸(`/mcc` 후 `MccHubTabRequest`가 허브가 뜨면 `selectTab`), 문의하기(채팅 `/문의 ` 채움), 구역 스폰 레벨. 클라가 받은 명령 트리에 없는 명령의 버튼은 꺼진다. 호버가 없으면 `PichuLines`가 파티 기절·부상·빈자리, 광장·마이룸, 밤, 비에 맞춘 말과 혼잣말을 7초마다 바꾼다. 말투는 디스코드 피츄처럼 친근한 해요체. 빌드에 cobblemon-ui `compileOnly`를 추가했고 `cobblemon_ui`가 없으면 등록하지 않는다.
- **빌드·배포:** 빌드 성공, 테스트 108개 통과, JAR `0113411794878FD4A832AA998F3B06BCD9FA5A5762BCB1A28F07D48453FB1D18`. 개발 서버·클라·`PPakemon` 프로필(게임 꺼짐 확인)에 교체, 해시 일치. VPS·운영 저장소·`deploy-product`는 건드리지 않았다.
- **미검증:** 실제 화면에서 피츄 모델의 크기·위치, 고개 추적, 버튼 배치, 탭 이동, 대화창 줄바꿈을 보지 않았다(모델 비율은 계산값). VPS에서는 `/구역스폰레벨` 버튼이 꺼지고 나머지는 기존 명령으로 동작할 것으로 본다(미확인).

---

## [2026-10-12] 주기 안내 개편 (구현·빌드·개발 배포, VPS 미적용)

- **사용자 요청:** 주기 안내에 `/구역스폰레벨`·`/포켓내비`·`/마이룸`·`/광장`(ESC 버튼)을 넣고, VPS 채팅·로그에서 유저가 막힌 부분을 바탕으로 안내를 전부 개편.
- **근거(VPS 로그 읽기만):** `logs/*.log.gz`·`latest.log`에서 채팅 1,956줄을 뽑아 분석했다(10-10~10-11). 많이 막힌 곳은 광장 회복·간호순, 관장 위치, 레벨캡·볼 등급, 경험치(직접 처치로는 안 오름), 몬스터볼·규토리, 마이룸 입장·상시활성, `/문의` 내용 누락(`!문의`·`./문의`), 디코 인증, 파티 목록(O), 음악 볼륨이었다. 스타팅 창 다시 여는 키는 채팅 답이 엇갈려(M/Y) 넣지 않았다.
- **확인한 사실:** 레벨캡 16→80(하드 100), 볼 등급은 배지 3·5·8개와 챔피언(`LeagueRank`), 간호순 대사에 `healpokemon`, 리그는 광장 리그 터미널 우클릭, 닉네임은 Styled Nicknames `/nick set`, 디코 질문은 `/피츄`. 야생 레벨은 숫자 대신 `/구역스폰레벨` 안내로 썼다(위키 levels 페이지의 "최소 3 낮다"는 현재 −15~−5 방식과 달라 별도 확인 필요).
- **구현(`3d34027a`):** `PolicyConfig.DEFAULT_TIPS`를 32개로 교체했고, 개발 서버 `config/jbro-policy.json`의 `tips`도 같은 목록으로 바꿨다(다른 키 유지, 원본 백업 `F:/AI/build/plaza-fix-20261011/deployment-backups/keys/jbro-policy.json.before-tips`). 체크리스트의 `tips` 줄을 갱신했다.
- **빌드·배포:** 빌드 성공, 테스트 108개 통과, JAR `23BBF6149E5711C5DB127B0CBB98BB1E52DBAB9ECC68BDBC8D50234A706809DE`. 개발 서버·클라가 꺼진 것을 확인하고 jbro-policy를 교체했으며, 미뤄 둔 ppakemon-client-setup 0.1.21도 개발 클라에 넣었다(해시 일치).
- **VPS 미적용:** VPS `config/jbro-policy.json`은 옛 18개 안내 그대로다. 안내는 서버를 켤 때만 읽고, VPS jbro-policy(`976DDCE9…`)에는 `/광장`·`/마이룸`·`/포켓내비`·Tab·광장 버튼이 없으며, `/구역스폰레벨`은 리그 모드 배포가 필요하다. 반영하려면 VPS jbro-policy·리그 모드 교체, 설정 교체, 재시작(사용자 동의 필요), 배포 클라 갱신이 함께 필요하다.

---

## [2026-10-12] 포켓내비 Tab 단축키·웨이포인트 키 삭제·ESC 광장 버튼 (빌드·PPakemon 배포, 개발 배포 대기, 실게임 미검증)

- **사용자 요청:** 포켓내비 단축키를 jbro-policy에 Tab으로(켜고 끄기), 현재 위치 웨이포인트 생성 단축키 삭제, ESC 메뉴 "빡켓몬 디스코드" 아래 "광장 가기"(광장 안이면 "광장 나가기").
- **Tab 충돌 판단:** Tab은 Cobblemon UI 전투 정보 키다(플레이어 목록은 두 프로필 모두 ` 키). 마크는 한 키를 한 바인딩에만 넘기지만 전투 정보는 `BattleInfoPanel`에서 키 상태를 직접 읽는다. 포켓내비도 `PokenavKey`에서 같은 방식으로 읽어 서로 막지 않게 했다. 전투 중(`CobblemonClient.battle != null`)이나 다른 화면이 열려 있으면 열지 않고, Cobblenav `PokenavScreen`이 열려 있으면 닫는다. 여는 건 서버 `/pokenav` 전송이라 VPS 구버전 서버에서도 동작한다. 키 설정 화면에는 같은 키로 빨갛게 표시된다(위키에 안내).
- **웨이포인트:** `ClientWaypoints`의 B 키 바인딩과 처리 코드를 지웠다. `/waypoint`·`/웨이포인트`와 텔레포트 끄기는 그대로다. 셋업 훅의 해당 해제 규칙도 함께 뺐다.
- **광장 버튼:** 세 번째 버튼이 클라 차원으로 문구를 고르고 `/plaza enter`·`/plaza exit`를 보낸 뒤 메뉴를 닫는다.
- **커밋·빌드:** `2fa7fb5d`, `f0b13bc3`(위키 단축키·명령어·편의 기능 페이지 포함). `f0b13bc3`을 F 드라이브에 풀어 빌드 성공, 테스트 108개 통과. JAR `BBC9397435AACC98BE1C0F333B6A0D7C2CDD9C16A88BCC162C737B30F4FE816D`.
- **배포:** `PPakemon` 프로필(게임 꺼짐 확인)에 교체했고 해시가 같다. 개발 서버(PID 26772, 25566)와 개발 클라(PID 28116)가 실행 중이라 개발 쪽 JAR은 아직 바꾸지 않았다. 위키 설치본(개발 서버·클라)은 같은 수정을 적용해 저장소와 같다. VPS·운영 저장소·`deploy-product`는 건드리지 않았다.

---

## [2026-10-11 13:05] 위키·NPC 대사를 한국어 명령으로 (개발 서버·클라만 반영)

- **사용자 요청:** 위키와 NPC도 한국어 명령으로. 적용 범위는 이전 지시대로 개발 서버만.
- **저장소:** `server-wiki`의 홈·명령어·편의 기능·시작하기·허브·단축키·리그·마이룸·광장 9페이지와 `nav.js` 검색어(광장 마이룸 포켓내비 웨이포인트 추가), `cobblemon-npc/docs/dialogues/extra_room_designer.json` 대사 4줄을 바꿨다. 명령어 페이지 도입부에 영어 명령도 쓸 수 있다는 안내를 넣었다. 바뀐 명령 뒤 조사를 점검해 `켜기</code>은`→`는` 한 곳을 고쳤다.
- **개발 설치본:** 개발 서버·클라 `config/more-cobblemon-contents/wiki`는 CRLF라 Git 원본과 바이트가 달라 파일 복사 대신 같은 치환·수정을 적용했고, 9페이지가 저장소 파일과 같음을 확인했다. 설치본 `nav.js`는 원래 원본과 달라 검색어 치환만 적용했다. 개발 서버 `config/cobblemon_npc/dialogues/extra_room_designer.json`도 같은 치환 후 원본과 JSON이 같다. 원본 백업은 `F:/AI/build/plaza-fix-20261011/deployment-backups/korean-cmd/`.
- **미반영:** 시작 훅의 `startup-assets/server-wiki.zip`(`ONCE`, 해시 고정)과 운영·VPS·배포 클라 위키는 다시 만들거나 바꾸지 않았다. NPC는 서버가 켜질 때 파일을 읽으며, 실행 중 반영(`/npc reload`)과 실게임 표시는 확인하지 않았다.

---

## [2026-10-11 12:45] 광장·마이룸·포켓내비·웨이포인트 한국어 명령 (구현·빌드·개발 배포, 실게임 미검증)

- **사용자 요청:** `/광장`, jbro-policy 안의 `/마이룸`(Simple MyRoom 하위 명령을 일관된 한국어로), `/포켓내비`, `/웨이포인트`. 적용은 개발 서버만(VPS·운영·배포 클라 제외).
- **이름 규칙:** 들어가기/나가기, 켜기/끄기/상태로 통일. 마이룸은 마이룸 메시지의 기존 용어에 맞춰 공개·비공개, 권한허용·권한해제, 차단·차단해제, 내보내기, 상시활성, 스폰설정 [초기화], 방문자, 알림, 리로드(OP). 영어 명령은 모두 유지.
- **구현(`cb583179`):** `MyRoomCommand`는 `simple_myroom`이 있을 때만 등록하고, 실행 시 `myroom`→`room` 순서로 있는 루트를 찾아 `dispatcher.execute("<root> <영어 하위 명령>")`로 넘긴다(권한·규칙·메시지는 모드 그대로). 플레이어 자동완성은 영어 명령의 완성 결과를 그대로 받아 온다. 광장·포켓내비는 같은 동작의 한국어 루트를 추가했고, 웨이포인트는 클라 명령이라 클라 JAR이 필요하다. 기본 팁·한국어 안내 문구를 한국어 명령으로 바꿨다.
- **빌드·배포:** `cb583179`를 F 드라이브에 풀어 `:jbro-policy:build` 성공, 테스트 108개 통과. JAR `3963E08DBAA0B34485F452B383AE339FD182363853E09FCA9BD7379827655600`. 이때 배포 클라(`PPakemon`)가 실행 중이어서 기존 배포 스크립트가 멈췄고, 개발 클라·서버(`cobblemon-dev`, 25566)만 검사하도록 좁혀 개발 서버·클라 JAR을 교체했다(해시 일치, 백업 `F:/AI/build/plaza-fix-20261011/deployment-backups/korean-cmd/`). 개발 서버는 꺼진 상태를 유지했다.
- **개발 서버 설정:** `config/jbro-policy.json`의 옛 명령 팁 4개와 `config/simple-myroom-messages.json`의 `help*` 13개를 한국어 명령으로 바꿨다(원본 백업은 위 폴더의 `config/`). 시작 훅은 이 값들을 검사하지 않는다. 체크리스트에 두 항목을 추가했다.
- **남은 것:** 서버 위키(`server-wiki/index.html`, `pages/commands.html`, `pages/myroom.html`)와 `cobblemon-npc/docs/dialogues/extra_room_designer.json`은 아직 `/room enter`를 안내한다. 실게임 명령 실행·자동완성은 확인하지 않았다.

---

## [2026-10-11 12:20] 배포 클라이언트·릴리스 폴더 누락 보완

- **누락:** VPS에 넣으면서 플레이어용 배포 클라이언트를 갱신하지 않아 사용자 지적을 받았다. VPS(운영)에 반영하면 같은 JAR을 `deploy-product/client`·`server`와 Modrinth `PPakemon` 프로필에도 넣어야 한다.
- **보완:** 게임 미실행을 확인하고 세 곳의 `jbro-policy-0.1.3.jar`(이전 `C3A2443D…`)를 `976DDCE9…`로 교체했고 해시가 일치한다. 백업은 제품 폴더 밖 `F:/AI/build/plaza-fix-20261011/deployment-backups/release/`. 두 `VERSION.txt`의 v0.1 아래에 이번 jbro-policy 변경 한 줄을 적었다. 다른 모드 JAR은 건드리지 않았다.

---

## [2026-10-11 12:12] ESC 메뉴 버튼 개발 서버·VPS 반영 (실게임 미검증)

- **개발 서버:** 사용자 허락 후 PID 30600(25566, `run.bat` 콘솔, `Server console handler` 있음)에 콘솔 Ctrl+C를 보내 정상 종료했다. Java 종료·25566 닫힘·`level.dat` 수정 시각 12:05:48→12:09:29를 확인했다. 서버 JAR을 `976DDCE9…`로 교체해 클라·서버·빌드본 해시가 같다. 사용자가 끄라고만 해서 다시 켜지 않았다.
- **운영 저장소·VPS:** 운영 커밋 `d5b393f3`(정책 JAR만) 푸시·원격 일치. 매니저 update job `920c49fa955f4fac8438d60e073539ef`가 `5c81e613`→`d5b393f3`, 적용 `mods/jbro-policy-0.1.3.jar`만, 백업 `/home/ubuntu/.local/share/ppakemon-backups/manager/20261011-121022-9cedff98`. 12:11:27 `Done`, 25565 LISTEN, tmux `dead=0`, VPS JAR 해시 일치, 봇 명령 15개 등록을 확인했다. mega_showdown·pokebadges 한국어 JSON 파싱 경고는 다른 모드 파일 문제로 이번 변경과 무관하다.
- **미검증:** 버튼 표시·클릭 시 브라우저 열기는 실게임에서 확인하지 않았다. 플레이어 클라이언트에도 새 jbro-policy가 있어야 버튼이 보이며, `deploy-product` 릴리스는 만들지 않았다.

---

## [2026-10-11 03:00] ESC 메뉴 위키·디스코드 버튼 추가 (구현·빌드·클라 배치, 개발 서버 배치 보류, 실게임 미검증)

- **사용자 요청:** ESC 메뉴 왼쪽 위에 "빡켓몬 위키", 그 아래 "빡켓몬 디스코드" 버튼, 두 버튼 너비 동일. 마크 기본 `Button`을 쓴다. 배포는 jbro-policy만.
- **구현(`fbdbc0ae`):** 위키 링크는 플레이어별 토큰이고 초대 링크는 서버 설정이라 클라가 주소를 모른다. 버튼이 C2S `request_link`(wiki/discord)를 보내면 서버가 `WikiApi.linkFor`(MCC 있을 때)나 `MenuLinks.inviteUrl`로 S2C `open_link`를 보내고, 클라가 `Util.getPlatform().openUri`로 바로 연다. 없으면 채팅으로 꺼져 있다고 알린다. 버튼은 `ScreenEvents.AFTER_INIT`에서 `PauseScreen.showsPauseMenu()`일 때만 100x20으로 (4,4)·(4,28)에 붙고, 서버가 패킷을 모르면 비활성화한다. 패킷 타입은 공통 초기화에서 항상 등록한다.
- **빌드:** `fbdbc0ae`를 `git archive`로 F 드라이브에 풀어 `:jbro-policy:build` 성공, 테스트 107개 통과. JAR SHA-256 `976DDCE9D286D387C61D960D611F6E53BADFE7C2F4CC1F60854FD59A331ED494`. 빌드 뒤 main이 `61d42625`로 전진했지만 jbro-policy·빌드 설정·MCC API 차이 0을 확인해 같은 빌드본을 쓴다.
- **배치:** 개발 클라만 교체했고 해시 일치(백업 `F:/AI/build/plaza-fix-20261011/deployment-backups/client-menu/`). 개발 서버는 PID 30600이 25566에서 실행 중이라 교체하지 않았다. 서버 설치본은 `C3A2443D…`로 앞선 `F8DA22E6…`과 달라 다른 세션이 이후 교체한 것으로 보인다. 서버 JAR이 바뀌기 전까지 새 클라에서 두 버튼은 비활성화된다. VPS·운영 저장소는 건드리지 않았다.

---

## [2026-10-11 02:40] 광장 낙하·초대 링크·닉네임 수정 빌드·개발 배포·VPS 반영 (실게임 미검증)

- **빌드:** 공유 체크아웃에 다른 세션의 병합 충돌·미커밋 MCC 변경이 있어, 커밋된 `origin/main`(`c3d471c9`, 수정 `a02069fa` 포함)을 `git archive`로 `F:/AI/build/plaza-fix-20261011/src`에 풀어 빌드했다(워크트리 아님). `GRADLE_USER_HOME=F:/AI/caches/gradle`, 프로젝트 캐시·모듈 출력·TEMP·JVM tmp 모두 F 경로이고 init 스크립트가 F 경로를 검사한다. `:jbro-policy:build` 종료 코드 0(6분 56초), 테스트 102개 모두 통과(새 `inviteUrl` 테스트 포함). JAR SHA-256 `F8DA22E646EE7BDF792151219F4D3854807C9D6C94FE96F8E96DF527BC7096E4`, `jar --validate` 0.
- **개발 배포:** 게임·서버 미실행과 25565/25566 미수신 확인 후 `develop-product/server`·`client`의 `mods/jbro-policy-0.1.3.jar`를 교체했고 두 설치본 해시가 빌드본과 같다. 기존 JAR 백업은 `F:/AI/build/plaza-fix-20261011/deployment-backups/`. 개발 서버는 켜지 않았다.
- **운영 저장소:** `mods/jbro-policy-0.1.3.jar` 교체 커밋 `63638c39`, Tab 닉네임 `config/styled-nicknames.json` `changePlayerListName: true` 커밋 `d40b07c`를 푸시했고 원격과 일치한다.
- **VPS:** 매니저 업데이트 job `1749da72d3494fa99be6dbcaf5d836f7`(02:36 시작)이 정상 종료·백업(`/home/ubuntu/.local/share/ppakemon-backups/manager/20261011-023638-a66d48f3`)·정책 JAR 반영·재기동을 마쳤다. `bp-shop.json`은 보존됐다. 02:37:41 `Done`, 25565 LISTEN, tmux `dead=0`, VPS JAR 해시가 빌드본과 같음을 확인했다. 이 job은 내 업데이트 요청이 사용자 거부로 취소된 직후 시작돼, 사용자가 GUI로 실행한 것으로 보이나 확인하지 못했다.
- **VPS 설정:** `inviteUrl`은 02:33에 이미 `https://discord.gg/HbKxTFeGB`로 들어가 있었다(넣은 주체 미확인). 재기동 뒤 봇 명령 등록 로그를 확인했다. `styled-nicknames.json`은 서버 밖 `~/.local/share/ppakemon-backups/config-edit/20261011-styled-nicknames/`에 백업한 뒤 `changePlayerListName`만 true로 바꾸고 콘솔 `styled-nicknames reload`로 적용했다(`Reloaded config!` 확인). VPS Git은 `d40b07c`보다 한 커밋 뒤지만 파일 내용은 같다.
- **미검증:** 광장 낙하 후 생존, `/디코인증` 초대 링크 표시, 디스코드 `/접속자`·Tab 목록 닉네임 표시는 실게임에서 확인하지 않았다. Tab 목록은 리로드 후 기존 접속자에게 바로 반영되는지 확인하지 않았다(재접속 필요할 수 있음).

---

## [2026-10-11] 광장 낙하 사망·/디코인증 초대 링크·접속자 닉네임 수정 (구현·커밋, 빌드·테스트·배포 미실행)

- **사용자 신고:** 광장에서 떨어지면 죽음, `/디코인증`에 서버 디스코드 링크 없음, 접속자 목록이 닉네임이 아님.
- **광장 낙하 원인(코드 추론, 재현 안 함):** 광장은 데미지를 모두 막고 공허(`minBuildHeight - 8` 아래)로 떨어지면 `goHome`으로 원래 위치에 보낸다. 이때 광장에서 쌓인 낙하 거리(스폰 y=80 기준 약 150블록)를 초기화하지 않아, 돌아간 차원에서 첫 착지 때 낙하 데미지로 죽는 구조였다. `goHome`의 두 순간이동 뒤에 `land()`로 `resetFallDistance`·수직 속도 0·`onGround`를 넣었다.
- **초대 링크:** `DiscordSettings.inviteUrl`(`https://discord.gg/…`·`https://discord.com/invite/…`만 허용, 틀리면 디스코드 설정 전체 무효)을 추가하고, `/디코인증` 코드 아래 눌러서 여는 초대 링크(`discord_link.invite` 번역 키)를 보낸다. 개발 서버 `config/jbro-policy-discord.json`에 `https://discord.gg/HbKxTFeGB`를 넣었다. 운영·VPS 설정은 바꾸지 않았다.
- **접속자 목록:** 디스코드 `/접속자`가 `gameProfile.name` 대신 표시 이름(Styled Nicknames 닉네임)을 보여 준다. 게임 Tab 목록은 이 모드 소관이 아니라 `styled-nicknames.json`의 `changePlayerListName`(기본 false) 때문이어서 개발 서버 설정만 true로 바꾸고 체크리스트에 적었다.
- **검증 경계:** "빌드는 지시할 때만" 결정에 따라 Gradle 컴파일·단위 테스트(새 `inviteUrl` 테스트 포함)·JAR 배치·실게임 확인은 하지 않았다. 개발 서버는 켜지 않았다.

---

## [2026-10-10 20:53] 운영·VPS 사탕 제작 정책 JAR 누락 해소 및 재기동 확인

- **누락 확인·적용:** 사용자 지적 후 개발 정책 JAR SHA-256 `CD50991F3E9C527B1E95A537787401EAC1DA94BF3A58361E8005284D326BB869`와 기존 운영본(`38DD83FA…`)이 달라, 앞선 MCC 배포에서 정책 JAR가 누락된 것을 확인했다. 개발 정책 JAR를 운영에 복사하고 운영 소스 `afb7a750ea1a04244cc277e4c48b668a1043c2f7` 커밋·푸시 후 VPS에도 정책 JAR 1파일을 적용했다(메인 작업자 보고).
- **파일 확인:** VPS 정책 JAR 해시는 위 개발본과 같고, 개발·운영 `mods` 양방향 파일 차이는 0건이며 `world/datapacks`도 서버 생성 `cobblemon-startup-hooks.zip`을 제외하면 차이가 없었다. JAR의 사탕 XS 레시피를 직접 읽어 결과 3개를 확인했다. 실게임 제작 확인과는 구분한다.
- **백업·재기동:** VPS manager job `dc94ab7a10a94ae2bd93834585baaae2`가 정상 저장·종료, 서버 밖 백업, 교체·재기동을 완료했고 `finished=true`·`ok=true`와 HEAD 일치를 확인했다. 원격 백업은 `/home/ubuntu/.local/share/ppakemon-backups/manager/20261010-205201-09ff9716`이다. 기동 로그의 20:53:07 `Done (13.535s)`를 직접 확인했고 manager 포트 확인도 완료했다(메인 작업자 보고).
- **사용자 지시·검증 한계:** 사용자가 추가 종료를 금지한 뒤에는 기존 재기동 완료만 확인하고 추가 종료하지 않았다. 실게임 제작은 확인하지 않았으며 기록 담당자는 검증을 재실행하지 않았다. 이 후속 기록의 커밋·main 병합·푸시는 메인 작업자가 별도로 담당한다.

## [2026-10-10 20:19] 포켓스낵 전포 차단 개발 서버·클라이언트 배포 (빌드·테스트·JAR 배치 확인, 실게임 미검증)

- **후속 요청·main:** 빡대리의 “배포해” 요청에 따라 개발 서버·클라이언트에 배포했다. 원 구현은 main 병합 커밋 `97b842d41a419cae3a9ec02cba38b150393aae03`으로 원격 동기화를 확인했다. 그 커밋의 최초 빌드 후 비교에서 다른 세션의 MCC 상점 소스 변경이 main에 들어온 것을 발견해 최신 `origin/main`의 `3825e2795ecfef5eafc639bce2f65e634b65b88a`로 fast-forward하고 재빌드했다. 최종 소스 HEAD와 `origin/main`의 `works/` 차이 0, main·origin/main 0/0을 확인했다.
- **최종 빌드·테스트:** `:jbro-policy:build`가 1분 25초에 종료 코드 0으로 성공했다. Gradle `test` 태스크는 `SKIPPED`였지만 실제 `unitTest` JUnit 실행에서 101개 시작·101개 성공·실패 0을 확인했다.
- **F 쓰기 경로·실행 오류:** `GRADLE_USER_HOME=F:/AI/caches/gradle`이며 워크트리 전용 `F:/AI/build/snack-275a-20261010` 아래에 project-cache, 모든 root/modules 빌드 출력, `TEMP`·`TMP`·`java.io.tmpdir`(JavaExec·Test 포함), kotlin-project·daemon 경로를 지정·검사했다. 첫 검증용 init의 `gradle.beforeProject`는 configure-on-demand로 미구성된 `:better-battle-presentation`의 기본 C: buildDirectory를 검사해 3분 44초 후 중단했다. 원인 확인 뒤 `allprojects`로 전체 모듈 경로를 먼저 지정해 보완했다. 별도 C: 출력 검사에서 `works/works`를 잘못 조회한 1건도 절대경로로 고쳐 C: 모듈 build 폴더가 없음을 확인했다. 두 오류는 사용자에게 보고했으며 제품 코드 수정은 없었다.
- **산출물·정적 확인:** `F:/AI/build/snack-275a-20261010/modules/jbro-policy/libs/jbro-policy-0.1.3.jar`의 SHA-256은 `CD50991F3E9C527B1E95A537787401EAC1DA94BF3A58361E8005284D326BB869`이다. Java 21 `jar --validate` 종료 코드 0, 빌드본 Mixin JSON 등록, `javap`에서 `legendOf != null`일 때 Boolean false를 `setReturnValue`하는 경로를 확인했다. `LegendPolicy.class`의 `snackMayOffer`·`POKE_SNACK_SPAWN_POKEMON_POST` 제거와 `RareSpawnNotice.class`의 스낵 이벤트 유지를 직접 확인했다.
- **개발 JAR 배치:** 빌드 전·교체 직전 Java 조회에서 서버·게임 실행 없음과 TCP 25565·25566 수신 없음 확인 후 실제 primary 저장소 `C:/Users/박주형/Documents/GitHub/Cobblemon-Mods/develop-product/server/mods/jbro-policy-0.1.3.jar`와 `develop-product/client/mods/jbro-policy-0.1.3.jar`를 교체했다. 두 설치본 SHA-256은 위 빌드본과 일치했다. 기존 JAR는 제품 폴더 밖 `F:/AI/build/snack-275a-20261010/deployment-backups/server/`·`client/`에 백업하고 해시를 확인했다.
- **검증 경계·기록 상태:** 위 빌드·테스트·소스 비교·JAR 검사·배치 결과는 메인 작업자의 직접 확인 보고이며 기록 담당자는 재실행하지 않았다. 운영 서버와 `deploy-product` 릴리스 산출물은 수정하지 않았다. 서버·게임 기동과 실제 스낵 스폰은 미검증이다. 이 후속 기록의 커밋·푸시는 기록 시점에 아직 수행하지 않았으며 메인 작업자가 담당한다.

---

## [2026-10-10 20:01] 포켓스낵 전포 스폰 전면 차단·기존 스낵 전포 코드 제거 (구현·정적 확인, 빌드·배포 미실행)

- **사용자 결정·범위:** 빡대리의 요청으로 포켓스낵에서는 `LegendCatalog`의 103종(전설·환상·전설급 패러독스)을 스폰하지 않는다. 스낵 설치자의 진행도·접속 상태와 무관하게 차단하며 일반 자연 스폰 전설의 소유권·등장 알림은 유지한다.
- **구현:** `PokeSnackLegendFilterMixin`의 `affectSpawnable` HEAD에서 `PokemonSpawnDetail`이고 `LegendPolicy.INSTANCE.legendOf(properties) != null`이면 `false`를 반환한다. `LegendPolicy`의 스낵 전설 소유권·등장 알림용 `POKE_SNACK_SPAWN_POKEMON_POST` 구독, 설치자 조건을 검사하던 `snackMayOffer`와 불필요 import를 제거했다. 일반 울트라비스트·패러독스 스낵 알림을 담당하는 `RareSpawnNotice`는 보존했다. 기록 담당자는 두 구현 파일의 diff를 직접 확인했다.
- **문서:** 모듈 README·`docs/LEGENDARY_SPAWNS.md`와 위키 `pages/legends.html`·`pages/systems.html`에 스낵 차단 정책을 반영하고 기존 스낵 전설 소유권 안내를 제거했다.
- **정적 확인:** 메인 작업자가 실제 Cobblemon 1.8.1 `sources.jar`에서 `PokeSnackBlockEntity` 스포너의 `influences.add(this)`, `affectSpawnable` 시그니처와 `SpawnablePosition.preFilter`의 `allForInfluences` 호출을 확인해 `false`인 후보가 제외되는 경로를 확인했다. Mixin JSON 등록·카탈로그 103항목·`RareSpawnNotice` diff 없음·삭제 참조 및 옛 안내 잔여 없음·`git diff --check` 통과도 메인 작업자 보고 기준이다. 기록 담당자는 이 검증을 재실행하지 않았다.
- **검증·배포 경계:** 기존 “빌드는 지시할 때만” 결정에 따라 Gradle 빌드·단위 테스트(TDD red-green)·JAR 배치·서버 기동·실게임 검증은 수행하지 않았다. 정적 확인을 런타임 차단 성공으로 기록하지 않는다.
- **커밋 상태:** 작업 브랜치는 `codex/block-snack-legends`이며 작업 전 `main`·`origin/main` 0/0 동기화를 메인 작업자가 확인했다. 소스·현재 기능 문서 6파일은 `cab36792`로 커밋했다(메인 작업자 보고). 이 기록 시점에는 이번 변경의 main 병합·푸시를 아직 수행하지 않았으며, 기록의 별도 커밋·main 병합·푸시는 메인 작업자가 담당한다.

---

## [2026-10-10 19:48] DEV Discord 연결 해제·시작 훅 검사 보완

- 빡대리의 명시 요청으로 개발 서버 Discord 연결을 해제했다. DEV의 TCP 25565/25566 LISTEN 및 Fabric 서버 Java 실행이 없음을 확인했으며, Java PID 34828은 JUnit 테스트 프로세스여서 그대로 보존했다.
- `develop-product/server/config/jbro-policy-discord.json`의 `botToken`·`webhookUrl`만 빈 문자열로 바꾸고, `startup-hooks.json`에서 `discord.*` 필수 검사 10개만 제거했다. 다른 채널·역할·연결 기록 설정과 훅의 canonical 값은 원본과 동일함을 비교 확인했다. 두 설치 설정은 Git 제외 상태로 원격에 포함하지 않는다.
- 원본은 제품 폴더 밖 `C:/Users/박주형/AppData/Local/MinecraftServerDeploy/manual-config-backups/dev-discord-20261010-194451-c0295f7d5046433b8bce7c625ccf5b0a`에 백업했고 원문·비밀값은 출력하지 않았다. 개발 서버는 기동하지 않았으며 시작 훅 `-DryRun`은 종료 코드 0(64줄)으로 통과했다.
- 첫 교체 시 PowerShell 7의 `File.Replace(src,dst,$null)`이 빈 경로로 전달되어 `path is empty`로 실패했다. 쓰기 전 원본 두 파일의 SHA 동일을 확인했고, F 드라이브 독립 fixture에서 `[NullString]::Value`로 성공을 확인한 뒤 준비한 파일을 적용했다. 대기 파일은 남지 않았으며 실패와 보완을 사용자에게 보고했다.
- 메인의 직접 확인 보고를 기록했으며 기록 담당자는 검증을 재실행하지 않았다. VPS는 읽기 확인에서 PID 21810·`java`·tmux `dead=0`, `productionBotConfigured=true`, 기존 상태 채널 설정을 유지했다. 운영 VPS 설정 변경이나 서버 재시작은 수행하지 않았다.

---

## [2026-10-10 19:37] DEV 상태 채널 복구·서버 켜짐 알림 실제 전송 확인

- **DEV 비교·단일 필드 복구:** DEV의 기존 상태 채널은 `🟢┃서버-상태`였고 VPS `statusChannelId`는 공백이었다. 기존 피츄 봇 인증을 서버 내부에서만 사용한 Discord GET으로 동일 봇·채널 접근 HTTP 200을 확인한 뒤 VPS의 해당 필드만 DEV 값으로 복구했다. 그 외 모든 설정값은 변경 전후 동일함을 확인했으며 토큰·전체 설정 원문은 출력하지 않았다. 운영 비밀 설정은 Git에서 제외한다.
- **정상 종료·원본 백업:** 정상 저장·종료 후 설정을 반영했고 원본은 서버 밖 `/home/ubuntu/.local/share/ppakemon-backups/discord-config/20261010-193414-ad700f2ba20d435f8b0d3b2b67782578.json`에 권한 0600으로 백업했다.
- **기동·실제 전송:** 새 Java PID `21810`, 19:35:31 fresh `Done (10.524s)`와 게임 포트 기동, 19:35:33 피츄 봇 online·슬래시 7개 등록을 확인했다. Discord 메시지 GET HTTP 200에서 새 프로세스 시작 이후 피츄 봇의 `🟢 서버가 열렸어요!` 메시지(19:35:32 KST)를 확인해 실제 전송을 검증했다. [확인한 알림](https://discord.com/channels/1555187390962860162/1555215001361317938/1558427761826332785)
- **교훈·미확인:** 기존 DEV 설정을 먼저 대조할 수 있었으므로 채널 ID부터 사용자에게 질문한 것은 불필요했다. 일반 운영 업데이트는 공백 설정도 보존하므로 DEV 기존 채널의 누락 여부를 별도로 비교해야 한다. VPS 필드가 공백이 된 경위는 확인하지 못했다. 닫힘 알림을 위한 추가 재기동은 하지 않아 닫힘 메시지 전송은 미검증이다. 위 결과는 메인 작업자의 직접 검증 보고이며 기록 담당자는 재실행하지 않았다.

## [2026-10-10 19:18] 운영 서버 재기동·디스코드 상태 알림 목적지 미설정 확인

- **정상 재기동:** 빡대리가 디스코드 서버 켜짐 알림이 보이지 않아 재기동을 요청했다. VPS의 Minecraft만 `save-all flush` → `stop`으로 종료해 `All dimensions are saved`·기존 PID 종료·TCP 25565 포트 닫힘을 확인했다. 운영체제는 재부팅하지 않았다.
- **기동 확인:** 기존 PID `16097`에서 새 PID `20874`로 기동했다. 새 `Done` 로그 19:16:50과 TCP 25565 LISTEN, 19:16:51 피츄 봇 online·슬래시 명령 7개 등록을 확인했다(메인 작업자 직접 검증 보고).
- **알림 원인:** 비밀값을 출력하지 않고 `config/jbro-policy-discord.json`의 `botConfigured=true`, `statusChannelConfigured=false`(빈 `statusChannelId`), `newsChannelConfigured=false`를 확인했다. 메인 작업자가 `DiscordBot.kt`의 `register`에서 상태 채널 ID가 비면 상태 객체 생성을 생략하고 `showStatus`가 반환함을 확인해 상태 알림 목적지 미지정을 판별했다.
- **미완료·범위:** 운영 설정 수정이나 기타 파일 배포는 하지 않았다. 알림 채널 이름/ID를 사용자에게 질문했고 답변 대기 중이며 실제 켜짐·꺼짐 메시지 전송 성공은 확인하지 않았다. 기록 담당자는 SSH·기동·소스 검증을 재실행하지 않았다.

## [2026-10-10 17:42] 디스코드 인증 설정 적용 확인 (봇·명령·권한 확인, 실제 인증 교환 미검증)

- **현재 적용:** 메인 작업자의 직접 확인 기준으로 VPS `/srv/MinecraftPPakemonServer/config/jbro-policy-discord.json`은 유효하며 수정 시각은 17:20:44, 현재 Java PID `14132`의 시작 시각은 17:24:46이다. 교체한 설정은 현재 실행에 적용돼 있으며 재시작이 필요하지 않다. 기록 담당자는 추가 조회를 하지 않았다.
- **봇·설정:** 17:25:51 로그에서 피츄 봇 online과 slash commands 7개 등록을 확인했다. 인증 역할 설정이 존재하고 `syncNickname`은 true다. 비밀값·역할 ID·원문 설정은 기록하지 않는다.
- **Discord 읽기 확인:** 읽기 전용 GET API로 빡켓몬 서버의 `/verify`와 필수 `code` 옵션 등록을 직접 확인했다. `인증됨` 역할이 존재하며 managed false·봇 역할보다 아래이고, 봇의 `MANAGE_ROLES`·`MANAGE_NICKNAMES` 권한도 true임을 확인했다(메인 작업자 보고).
- **미검증:** 실제 플레이어의 `/디코인증` → `/verify` 코드 교환, 인증 역할 부여, 닉네임 변경은 실행하지 않았다. 설정·명령 등록·권한 확인을 실제 인증 성공으로 해석하지 않는다.

## [2026-10-10 17:09] `/servercmd` 콘솔 명령 실행 추가 (구현·main·빌드·운영 배포·기동·외부 응답 확인, 채팅 실사용 미확인)

- **사용자 결정·범위:** 빡대리가 `/servercmd` 구현·테스트·운영 서버 배포를 승인했다. 개발 서버·클라이언트는 변경하지 않는 결정이며, 구현·기록은 분리 작업트리 `C:/Users/박주형/.codex/worktrees/servercmd/Cobblemon-Mods`의 `jbro-policy`에서 진행했다.
- **구현 확인:** 기록 담당자가 `admin/ConsoleCommandQueue.kt`·`admin/ServerCommand.kt`와 `JbroPolicy.kt` 등록을 직접 읽었다. 현재 실제 OP 권한 4인 플레이어만 접수하며, 실행 틱에서도 접속·권한을 다시 확인한다. 앞의 `/` 한 개는 선택 사항이고 입력은 2,048자 이하·제어 문자 금지다. 대기열 최대 32개, 틱당 최대 4개를 처리하고 서버 종료 시 대기열을 지운다.
- **출력·제약:** 기존 명령 실행 문맥 밖의 서버 틱에서 콘솔 권한으로 실행하며, 실행 중 중첩 `/servercmd` 접수를 거절하는 상태 검사를 둬 `execute`·`function` 경로도 대비했다. 결과·오류는 요청자의 채팅으로 최대 32개 메시지·4,096자까지 캡처한다. 접수 명령의 반환값과 실제 실행 결과는 구분한다. 셸 실행이나 서버 정지 후 재시작은 제공하지 않는다. 명령 인자를 제외하고 호출자·최상위 명령만 정상 실행 로그에 남긴다.
- **문서·현지화 확인:** README 사용법과 한국어·영어 `message.jbro_policy.servercmd.*` 11개씩을 확인하고 양쪽 JSON 파싱을 통과했다. 안내는 `KoreanText.message`의 `translatableWithFallback`을 사용해 새 번역 키가 없는 기존 클라이언트도 문구를 받도록 구현했다. 실제 클라이언트 표시와 명령 연쇄 동작은 아직 검증하지 않았다.
- **TDD·단위 테스트:** 메인 작업자 보고 기준으로 첫 실행은 기존 93개 통과·신규 8개 TODO 실패(총 101개)였다. 구현 후 두 번째 `:jbro-policy:unitTest`는 종료 코드 0·`BUILD SUCCESSFUL`, 101개 전부 통과(기존 93+신규 8)를 확인했다. 기록 담당자는 테스트를 재실행하지 않았다.
- **main·최종 빌드:** 구현 `8e19041b`를 main 병합 커밋 `725dfff042162fe70beba71f7c4fec5693f3bc41`에 반영하고 원격 실제 ref 일치·0/0을 확인했다. 해당 커밋의 분리 소스에서 `:jbro-policy:build`가 종료 코드 0·`BUILD SUCCESSFUL`(5분 18초), 테스트 101개 모두 통과했다. 이후 `main`이 `f8994446`으로 전진했지만 관련 모듈 소스·리소스·빌드 설정·의존 소스의 차이가 0임을 재확인해 같은 빌드본을 사용했다(메인 작업자 보고).
- **빌드 경로·산출물:** 초기 red/green은 C: Gradle 쓰기 금지 규칙이 없던 `0a404f26` 작업트리에서 실행했다. 최신 `b7660a42` 규칙 확인 후 최종 빌드의 캐시·모듈 출력·`TEMP`/`TMP`·JVM tmp는 `F:/AI/build/servercmd-20261010` 아래로, `GRADLE_USER_HOME`은 `F:/AI/caches/gradle`로 지정했다. 산출물은 `F:/AI/build/servercmd-20261010/modules/jbro-policy/libs/jbro-policy-0.1.3.jar`, SHA-256은 `0DDDC4ECBCC74F7A4A9E106A8CB7DAE4B90D26A80150CE25E95F25D554793056`이다. Java 21 `jar --validate` 종료 코드 0과 두 신규 클래스 포함을 확인했다.
- **검증 도구 보완:** 첫 최종 빌드 명령은 PowerShell에서 따옴표 없는 `-Pkotlin.compiler.execution.strategy` 옵션이 분리돼 task not found로 실패했다. 전체 옵션 문자열을 따옴표로 감싼 뒤 성공했다. PATH의 구 JDK `jar`는 major 65를 지원하지 않아 실패했고, `C:/Program Files/Java/jdk-21/bin/jar.exe`를 명시해 검증했다. 두 실패는 호출 옵션·검증 JDK 문제였다.
- **로컬 운영 배포:** `C:/Users/박주형/Documents/GitHub/MinecraftPPakemonServer`의 `mods/jbro-policy-0.1.3.jar`만 교체하고 커밋 `d4e1d01e49329307063bc6b9ca4b4ffc92cc60b9`을 푸시해 원격 0/0을 확인했다. 별도 미커밋 `startup-hooks.json`은 보존했다.
- **VPS 정상 종료·배포·보존:** `/srv/MinecraftPPakemonServer`에서 `save-all flush`의 `Saved game`, `stop`의 모든 차원 `All saved`, 기존 Java PID `10378` 종료와 TCP 25565 포트 닫힘을 확인했다. `/home/ubuntu/.local/share/ppakemon-backups/servercmd-20261010-172444/world-snapshot.tar.gz`와 이전 JAR를 백업했다. fetch 후 들어오는 변경이 policy JAR 1개임을 확인해 fast-forward 병합했다. JAR 교체 전후 config·world·player·plaza·루트 실행 설정 1,224개 파일의 SHA가 같았고 VPS 새 JAR는 위 빌드본 SHA-256과 일치했다.
- **VPS 기동·외부 응답:** 기존 `tmux ppakemon`의 `remain-on-exit`을 이용해 같은 pane을 respawn하고 콘솔을 유지했다. 새 Java PID `14132`, `[17:25:49] Done (12.780s)!`, TCP 25565·UDP 24454 리스닝과 Windows 외부 Minecraft status 응답(version `1.21.1`, protocol `767`) 성공을 확인했다.
- **최종 범위·미확인:** 구현·main 동기화·최종 빌드·로컬 운영/VPS JAR 배치·서버 기동·외부 상태 응답까지 확인했다. 17:28:11 VPS `list`는 접속자 0명으로 실제 플레이어의 `/servercmd` 실행·채팅 결과 표시·실게임은 미확인이다. 개발 서버·클라이언트와 `deploy-product` 릴리스 산출물은 변경하지 않았다. 이번 관리 작업트리는 archive 후 실제 경로 삭제·Git worktree 등록 제거를 확인했고 F 빌드 JAR는 유지하며 해시가 같았다. 빌드·배포·기동·보존·정리 결과는 메인 작업자의 직접 검증 보고이며 기록 담당자는 재실행하지 않았다.

## [2026-10-10 16:47] 사탕 제작 잠금 해제·경험사탕 XS 결과 3개 (구현·리소스 확인, 빌드·배포 미실행)

- **사용자 결정:** 사탕 제작을 잠금 해제하고 스컬크 1개와 벌집 조각 1개로 경험사탕 XS 3개를 만든다. 기존 월드의 내장팩 선택을 유지하기 위해 legacy 내장팩 ID `no_stat_candy_l_xl`는 바꾸지 않는다.
- **구현:** 냄비 XS 레시피와 Create 선택 연동 XS 레시피의 결과만 3개로 오버라이드한다. 나머지 기존 차단 레시피 8파일은 삭제해 Cobblemon 기본 레시피로 복원하며, Create 레시피의 기존 모드 로드 조건은 유지한다. 별도 월드 데이터팩 변경은 이번 작업에 포함하지 않는다.
- **검증:** 메인 작업자 보고 기준으로 두 XS 리소스의 결과 3개와 `git diff --check`를 확인했다. `:jbro-policy:test --tests *PolicyDataTest`는 프로젝트 구성 중 `:better-battle-presentation` 단계의 디스크 공간 부족으로 실패했으며 Kotlin 테스트는 실행되지 않았다. 기록 담당자는 냄비·Create XS JSON의 `count: 3`과 Create 조건 존재만 별도로 확인했다.
- **F 재검증:** 기존 `test` 태스크가 비활성이라 실제 검증 태스크 `unitTest`를 F 드라이브에서 실행했으나, 약 11분 준비 후에도 MCC `compileKotlin` 단계가 계속되어 중단했다. Kotlin 테스트 결과는 미확인이며 통과한 것으로 기록하지 않는다. 검증용으로 시작한 Java 프로세스 3개만 종료했다(메인 작업자 보고).
- **사용자 결정·규칙:** C 드라이브의 Gradle 캐시·데몬 로그·프로젝트 캐시·빌드 출력·임시파일 쓰기를 금지하고 모든 쓰기 경로를 F로 지정·사전 확인하는 필수 규칙을 `AGENTS.md`에 추가했으며 `CLAUDE.md`에 참조를 남겼다.
- **커밋·남은 상태:** 소스는 main 병합 커밋 `582c60d39152cbc25076b9b8860fb3a662da5781`에 반영됐고 `main`·`origin/main`·원격 실제 ref 일치를 확인했다(메인 작업자 보고). JAR 빌드·배치, 서버 기동, 실게임 제작 검증은 수행하지 않았다. 이 후속 기록의 커밋·푸시는 메인 작업자가 별도로 담당한다.

## [2026-10-09 15:21] 마이룸·광장 침대 사용과 침대 스폰 지정 차단 (구현만, 빌드·배치 안 함)

- **사용자 결정:** 각 모드에 따로 차단 코드를 넣지 않고 `jbro-policy` 정책으로 `myroom:rooms`와 `jbro_policy:plaza`에서 침대 사용을 막는다. `simple-myroom`은 수정하지 않는다.
- **구현:** `PlazaProtection.kt`의 `UseBlockCallback`에서 두 차원의 `BedBlock` 우클릭에 `FAIL`을 반환해 수면·침대 스폰 지정·침대 폭발 처리를 막는다. OP도 예외가 없으며 다른 차원은 `PASS`로 기존 처리에 맡긴다. 한국어·영어 안내 문구와 README를 추가했다.
- **정적 확인:** 설치된 `simple-myroom-1.0.0.jar`의 `data/myroom/dimension/rooms.json` 존재와 Fabric API 소스의 콜백 실행 순서(블록 처리 전에 호출, `FAIL`이면 처리 중단)를 메인 작업자가 확인했다. 양쪽 언어 JSON 파싱과 `git diff --check`도 통과했다(메인 작업자 보고).
- **검증 범위:** 기존의 “빌드는 지시할 때만” 사용자 결정에 따라 빌드·테스트·JAR 배치·서버 기동·실게임 검증은 실행하지 않았다. 이미 저장된 스폰을 지우거나 명령어를 통한 스폰 변경까지 막는 기능은 이번 요청에 포함하지 않았다.

## [2026-10-09 14:29] 디스코드 `/피츄 질문` 추가 (구현·빌드·AI 호출·서버 배치 확인, 기동·디스코드 실사용 미확인)

- **동작:** 필수 옵션 `질문`은 1~500자이며 답변은 공개로 보낸다. `anyChannel = true`로 일반 채팅방에서도 사용할 수 있다. Discord user ID별로 채널을 공유하는 5분 쿨다운을 두고, 같은 유저가 답변을 기다리는 중에는 중복 질문을 막는다. AI 실패·빈 답변이면 쿨다운을 해제한다.
- **AI 실행:** 별도 worker에서 최대 2개 질문을 동시에 처리한다. 기존 `inquiry-review`의 `enabled`·`command`·`model`·`timeoutSeconds` 설정을 재사용하되 질문 응답 제한시간은 최대 120초다. agy 전용 `jbro-pichu-chat` 프로필은 `inheritCustomizations: false`, `excludeDefaultComponents: true`, `tools: [finish]`로 제한한다. 질문만 전달하고 서버 로그·유저 기록은 제공하지 않는다.
- **구현 위치:** `DiscordQuestion.kt`, `PichuQuestionAI.kt`, `pichu-chat-agent.md`. Discord 비동기 응답 처리와 한국어 안내 문구를 연결했다.
- **커밋·최종 빌드:** 구현 커밋 `460749ca`를 main에 병합한 `9ea58ba23417eede00885a1ea4429c9a6b3d7997`과 `origin/main`의 같은 해시를 확인했다. 깨끗한 분리 작업트리에 이 병합 커밋을 fast-forward 반영한 소스로 `:jbro-policy:build`를 실행해 단위 테스트 93개 모두 성공, `BUILD SUCCESSFUL`(30초)을 확인했다. `jar --validate` 종료 코드 0과 새 클래스·제한 프로필 포함도 확인했다(메인 작업자 보고).
- **AI 실호출:** Java 검증용 harness에서 실제 `PichuQuestionAI.ask`를 기존 로그인 agy의 `gemini-3.8-flash-low`로 호출해 `SUCCESS`, 비어 있지 않은 피츄 설명 답변, `AI_SMOKE_OK`, 종료 코드 0을 확인했다(메인 작업자 보고). 디스코드 slash 경로를 거친 검증은 아니다.
- **프로필 확인:** `.gemini/config/agents/jbro-pichu-chat/agent.md` 설치와 CLI agent 검색을 확인했다. 초기 flat `agent.md` 경로는 검색되지 않아 폴더 안 `agent.md` 경로로 고쳤다. 로컬 CLI 내장 changelog와 protobuf·YAML 설정 태그에서 `excludeDefaultComponents` 지원을 확인했다. 무해한 sentinel 파일을 읽으라는 질문에는 파일 접근 불가 답변을 받았고 도구 호출은 없었다. CLI `init.tools`는 기본 도구 전체를 표시하므로 제한 프로필의 실제 도구 목록을 증명하는 근거로 쓰지 않는다.
- **채널 권한 확인:** Discord API 읽기 조회에서 `💬┃잡담`(`1555187393236041820`)의 `@everyone`에 `SEND_MESSAGES`·`USE_APPLICATION_COMMANDS`가 허용되고 관련 channel overwrite 거부가 없음을 확인했다. 권한은 변경하지 않았다.
- **검증 도구 보정:** Gradle init task 등록 시점 조정과 Java 인자 CP949 보정은 검증용 scratch에서만 했으며 제품 코드 수정과 무관하다.
- **개발 서버 배치:** 배치 직전 `java`·`javaw` Fabric 서버 프로세스와 25565·25566 수신이 모두 없음을 재확인한 뒤, 실제 primary 저장소의 `develop-product/server/mods/jbro-policy-0.1.3.jar`를 빌드본으로 교체했다. 빌드본·설치본 SHA-256은 모두 `38DD83FA8C39A4956AE7EF2A3722A5798CF26354D210EAB03F9A27391464BA4E`로 일치했다(메인 작업자 보고). 서버 디스코드 기능 범위이므로 개발 클라이언트와 release 산출물은 변경하지 않았다.
- **미확인:** 서버는 기동하지 않았다. 다음 서버 기동 시 slash 명령이 자동 등록되며, 실제 등록·디스코드 호출은 아직 검증하지 않았다.

## [2026-10-08 18:51] 커스텀 레시피(화약·네더의 별) 제거 (구현만, 빌드 안 함)

- **사용자 결정:** 커스텀 레시피는 없앤다. `data/jbro_policy/recipe/`의 `gunpowder_from_coal_and_flint.json`, `nether_star.json`을 지우고 README·위키(`systems.html`, `nav.js`)의 언급도 뺐다.
- **영향:** 바닐라 몹이 나오지 않는 서버라 네더의 별은 그란돈 드롭 말고는 얻을 길이 없다. 특성패치 레시피는 원래 `no_ability_patch` 팩이 막고 있어 BP 상점 전용 그대로다. 화약은 찌리리공·붐볼 드롭으로 얻는다.
- **미확인:** 빌드·배치 안 함(사용자가 배포는 아직이라고 함). 하드 챔피언 배지 아이콘의 네더의 별 텍스처는 레시피와 무관해 그대로 둔다.

## [2026-10-08 13:40] `WildPokemonPolicy.applyWildRolls` 공개 (구현만, 빌드 안 함)

- 리그 모드의 야생 교환꾼이 만든 포켓몬에 야생 개체값 구간과 숨겨진 특성 확률을 걸 수 있게 `@JvmStatic applyWildRolls(pokemon)`을 열었다. 리그는 리플렉션으로 부르므로 **이름과 시그니처를 바꾸지 않는다.** 확률값은 이 모드 설정에만 둔다.

## [2026-10-08 09:20] `/디코인증 linked` 판정 명령 추가 (구현·커밋만, 빌드·배치 안 함)

- **이유:** NPC 대화가 디스코드 연동 여부로 갈라질 방법이 없었다(cobblemon-npc `MEMORY.md`의 기자 NPC).
- **동작:** `/디코인증 linked`, `/discordverify linked`. 연동했으면 1, 아니면 0. 권한 2가 필요해 플레이어에게는 안 보이고, 대화 조건(`cmd:`)은 권한 2로 돌아서 쓸 수 있다. 디스코드 봇 설정이 있을 때만 등록된다.
- **미확인:** 사용자가 빌드는 지시할 때만 하라고 해서 빌드·테스트·배치하지 않았다.

## [2026-10-08 01:30] 전설 볼 거절 수정이 0.1.3 배포에 덮였던 일, 합쳐서 재배포

- **빡대리님 신고:** 수정 뒤에도 전투 중 아르세우스에 마스터볼을 던지면 그대로 멈춘다.
- **원인:** 00:25 Codex 세션이 main에 합치지 않은 `codex/alpha-two-perfect-ivs`(main의 c31bb117 이전 갈래)로 `jbro-policy-0.1.3`을 빌드해 서버·클라이언트에 넣으면서 0.1.2(전설 볼 수정 포함)를 뺐다. 0.1.3에는 `EmptyPokeBallLegendMixin`이 없었다.
- **조치:** 그 브랜치를 main에 병합(24498c5f, MEMORY 충돌만 손으로 해소)하고 0.1.3을 다시 빌드했다(테스트 87개 통과, 믹스인 포함 확인). 서버·게임이 꺼진 상태에서 두 곳에 교체(SHA-256 앞자리 872d7d04), 백업 `develop-product/deployment-backups/20261008-ball-music-trainer-fixes`. 서버는 켜지 않았다. 실게임 미확인.
- **재발 방지:** 다른 갈래에서 빌드한 JAR을 배포하기 전에는 main을 먼저 합친다(MEMORY의 "배포 전 main 병합" 규칙).

## [2026-10-08 00:34] 새 알파 포켓몬의 기본 IV 최소 2개를 31로 보장

- **구현:** 커밋 `71c921ef`, `jbro-policy` 0.1.3. 새로 스폰한 알파는 일반 야생 IV 추첨 뒤 기존 31 개수를 세어, 서로 다른 능력치의 기본 IV가 최소 2개는 31이 되도록 부족한 개수만 올린다. 기존 31이 3개 이상이면 유지하고 나머지 값도 보존한다. 야생 IV 구간 추첨을 꺼도 적용하며, 기존 포켓몬을 일괄 보정하지 않는다.
- **빌드:** 전체 빌드, 테스트 87개, JAR 검증 통과. 빌드 중 C: 공간이 소진되어 실패한 빌드를 멈추고 이 작업트리의 `works/.gradle/loom-cache`만 `F:/AI/Temp/codex-ac66-loom-cache-20261008`로 이동한 뒤 원래 경로에 junction을 두어 약 1GB를 확보했다. 소스·실행 폴더는 이동하지 않았다. `'-Dfabric.loom.ci=true'`는 의존성 소스 준비만 생략했다.
- **개발 배포:** 실제 저장소 `C:/Users/박주형/Documents/GitHub/Cobblemon-Mods`의 `develop-product/server/mods`와 `develop-product/client/mods`에 0.1.3을 설치하고 0.1.2를 제거했다. 양쪽 SHA-256은 `D44EE3F15C92D0A71D0A32210C9A6CBD3C601F30131101557C2175F46584F58A`로 일치한다.
- **서버 기동 확인:** 빡대리님이 정상 종료·교체·재시작을 승인했다. 00:30:04 모든 차원 저장과 이전 프로세스·25566 포트 종료를 확인하고 교체했다. 숨김 `cmd`에서 `run.bat`으로 재시작한 뒤 Java PID 39360의 25566 수신과 `latest.log`의 `jbro_policy` 0.1.3, 00:33:32 `Done (2.040s)`를 확인했다.
- **후속 종료·최종 상태:** 빡대리님의 서버 끄기 지시로 PID 39360은 00:37 모든 차원 저장 후 정상 종료했다. 다른 런처(PID 41084)의 새 Java PID 44172에도 `stop`을 보냈으나, 00:40:19 `Saving worlds` 직후 `ConcurrentModificationException`과 `Exception stopping the server`가 나와 두 번째 종료의 월드 저장 완료는 확인하지 못했다. 최종 `Win32_Process` 조회에서 모든 Fabric 서버 Java와 런처 PID 5592·41084, Java PID 44172가 없고 25566 수신도 없어 서버 꺼짐을 확인했다. 종료 오류 원인과 수정은 검증하지 않았다.
- **남은 검증:** 실제 알파 포획과 IV 확인은 하지 않았다. 소스 커밋은 `codex/alpha-two-perfect-ivs`에 push했고 원격 동기화를 확인했다.

## [2026-10-07 23:55] 전투 중 전설 포획 거절 시 전투가 멈추던 문제 (빌드·테스트 확인, 서버 배치·실게임 미확인)

- **빡대리님 신고:** 이미 잡은 아르세우스와 전투 중 마스터볼을 던지면 "던졌습니다" 로그만 남고 아무것도 못 한다. HUD 카드가 코블몬 기본 타일(볼 아이콘)로 풀린다.
- **원인:** 코블몬 1.8.1 `EmptyPokeBallEntity.onHitEntity`는 전투 안에서 `BattleCaptureAction`을 걸고, 던짐을 알리고, 던진 사람 턴을 넘긴 *다음에* `THROWN_POKEBALL_HIT`을 부른다. 우리가 그 이벤트를 취소하면 볼만 `drop()`되고 `captureFuture`가 끝나지 않아 전투가 영원히 기다렸다. HUD가 기본 타일로 바뀐 것은 cobblemon-ui가 볼 표시 중에는 일부러 코블몬 타일을 쓰기 때문(`BattleOverlayHudMixin`)이고, 볼 상태가 안 풀려 그대로 남은 것.
- **수정:** 거절 검사를 `EmptyPokeBallLegendMixin`(`onHitEntity` HEAD)으로 옮기고 `LegendPolicy.refuseCapture`로 판정한다. 거절되면 이유를 알리고 볼을 돌려준 뒤 코블몬 처리를 건너뛴다(턴도 안 넘어감). 야생이 아닌 포켓몬은 코블몬 메시지에 맡긴다. `THROWN_POKEBALL_HIT` 구독은 지웠다.
- **검증:** `:jbro-policy:build` 테스트 85개 통과, JAR 안에서 `onHitEntity`가 `method_7454`로 매핑된 것 확인. 커밋 c31bb117. 클라이언트에 배치(SHA-256 앞자리 a4c62ca6, 백업 `develop-product/deployment-backups/20261007-legend-ball-battle-hang/`). 서버는 빡대리님이 끈 뒤 같은 JAR로 교체(백업 같은 폴더 `server/`), 다시 켜지는 않았다. 실게임 미확인.

## [2026-10-07 13:00] 등급이 모자란 전설은 스폰·포켓내비 목록에서 뺌

- **빡대리님 결정:** 리그 등급으로 못 잡는 전설은 포켓내비 목록에도 안 뜨게 한다(엔트리 조건과 같은 취급). 전에는 등급이 포획만 막아서, 스폰되고 서버 알림까지 나간 뒤 볼이 거부됐다.
- **구현:** `LegendPolicy.mayMeet`에 `LegendRanks.check(...) == Allowed` 조건 추가. 플레이어 스포너, 포켓내비 스폰표, 포케스낵 후보가 모두 이 함수를 지난다. 등급을 못 읽으면(`Unknown`) 스폰도 막는다. 포획 때 검사는 스폰 뒤 등급 변화 대비로 남겼다. `/spawnpokemonfor`는 `mayMeet`을 안 거치므로 관리자 소환은 그대로.
- **확인:** Cobblenav 2.4.1 `SpawnDataHelper`가 `PlayerSpawner.getSelector().getProbabilities`로 목록을 만드는 것을 바이트코드로 확인. 이 경로가 `affectSpawnable`을 부르는지는 Cobblemon 소스로 따로 보지 않았다(기존 엔트리 조건과 같은 경로라는 전제).
- **함께 발견:** 커밋 c8dbefc9에 `RareSpawnNotice.kt`가 빠져 있어 깨끗한 체크아웃에서는 컴파일이 안 됐다. 원인은 루트 `.gitignore`의 `mods/`가 `works/mods/` 아래 새 파일을 전부 무시한 것. `!works/mods/` 예외를 넣고 빠진 파일(cobblemon-npc 3개 포함)을 3e85ec0a로 커밋했다.
- **검증:** `:jbro-policy:build` 테스트 85개 통과, 커밋 9d7038a6. 서버·클라이언트가 꺼진 상태에서 `jbro-policy-0.1.2.jar`(SHA-256 앞자리 ded8c12e)를 두 곳에 배치, 이전 JAR은 `develop-product/deployment-backups/20261007-legend-rank-spawn/`. 서버는 켜지 않았다. 등급 미달 플레이어의 포켓내비 목록은 실게임 미확인.

## [2026-10-06 08:20] 코라이돈·미라이돈·전설 패러독스를 고대·미래 차원으로, 울비·일반 패러독스 알림 (빌드·테스트 확인, 실게임 미확인)

- **빡대리님 결정:** 울트라비스트와 전설이 아닌 패러독스 14종은 Legend가 아니다(전설 파생이 아니면 풀어 줌). 몇 마리든 잡고 등급 제한도 없지만, 나타나면 알림은 간다. 코라이돈·미라이돈 엔트리는 패러독스 아무거나(고대·미래 구분 없음).
- **스폰:** `legendary_wild_spawns.json`에서 코라이돈은 `cobblemon_dimensions:ancient`의 고대 초원·고대 사막, 미라이돈은 `future`의 미래 평원·강철 산맥으로 옮겼다(오버월드 스폰 없음). 오늘 꺼 뒀던 전설 패러독스 6종을 차원 조건으로 다시 넣었다(굽이치는물결 고대 바다 물 위, 꿰뚫는화염 태고의 화산, 날뛰는우레 고대 초원, 무쇠잎새 네온 숲, 무쇠암석 수정 지대, 무쇠감투 강철 산맥; 레벨·가중치는 예전 값). 103종 모두 스폰.
- **카탈로그:** `LegendCatalog.PARADOXES`(20종)를 코라이돈·미라이돈 엔트리로. 코라이돈 ↔ 전설 패러독스가 고리를 이루므로, 엔트리 순환 테스트를 "Legend가 아닌 포켓몬에서 모든 Legend에 닿는가"로 바꿨다(any/all 의미 반영).
- **알림:** `RareSpawnNotice`가 `ultra_beast`·`paradox` 라벨이면서 Legend가 아닌 포켓몬의 스폰(플레이어 스포너, 포케스낵)을 하늘색으로 알린다. 포케스낵이 두 이벤트를 모두 지날 수 있어 persistentData 플래그로 한 번만 알린다.
- **위키:** `gen_legends.py`가 `PARADOXES` 엔트리와 차원 조건(`dimensions`), cobblemon-dimensions 바이옴 한국어 이름을 읽는다. 전설 페이지 차원 필터에 고대·미래 차원 추가. 끝의 `relative_to(REPO)` 오류(저장소 구조 변경 뒤 생긴 것)도 고쳤다. `legends.js` 재생성.
- **검증:** `:jbro-policy:build` 테스트 85개 통과. 커밋 c8dbefc9. 서버·클라이언트가 꺼진 상태에서 `jbro-policy-0.1.2.jar`(SHA-256 앞자리 e25496b2)를 두 곳에 배치, 이전 JAR은 `develop-product/deployment-backups/20261006-paradox/`. 서버는 켜지 않았다. 차원 조건이 붙은 전설 스폰과 알림은 실게임 미확인.

## [2026-10-05 16:20] 디스코드 `/위키` 개인 링크, `/전적` 분류 선택 (빌드·단위 테스트 확인, 디스코드 실사용 미확인)

- **빡대리님 지적:** 디스코드 `/위키` 링크로 들어가면 "내 정보"가 안 보인다. `/전적`은 한 번에 다 보여 줘서 난잡하니 리그챌린지·배틀팩토리·배틀타워·PvP로 나눈다.
- **원인:** 내 정보는 `/api/me`에 플레이어별 위키 토큰이 있어야 보이는데, 디스코드 `/위키`는 토큰 없는 공용 주소만 줬다.
- **`/위키`:** `/디코인증`으로 연동한 사람에게는 `WikiApi.sharedLinkFor`(MCC에 새로 연 API) 링크를 준다. 기존 `WikiApi.linkFor`는 쓰지 않는다. 오프라인 플레이어면 서버 안 포트(`localhost:8100`)를, 로컬 위키를 띄운 클라이언트면 그 PC의 `localhost` 주소를 주기 때문이다. 링크를 가진 사람은 누구나 그 플레이어 정보를 보므로 답은 모두 본인에게만 보이게(ephemeral) 했다. 연동하지 않은 사람은 공용 주소와 연동 안내를 받는다.
- **`/전적`:** 필수 옵션 `분류`(선택지 네 개)가 `닉네임` 앞에 온다. 고른 콘텐츠 카드만 보여 주고, 제목이 분류를 말하므로 카드 내용은 필드 대신 본문(description)에 넣었다.
- **검증:** `:jbro-policy:test`, `:more-cobblemon-contents:test --tests "*Wiki*"` 통과. 디스코드에서 명령을 실제로 쳐 보지는 않았다. 명령 정의가 바뀌었으니 서버를 다시 켜야 디스코드에 새 옵션이 등록된다.
- **함정:** 이 작업 중 `cobblemon-ui`의 빌드 산출물에서 `CobblemonUiOverlays.kt` 클래스가 통째로 빠졌는데 Gradle은 UP-TO-DATE로 봤다. MCC 컴파일이 `Unresolved reference 'CobblemonUiDialogScreen'`으로 깨지면 `./gradlew :cobblemon-ui:compileKotlin --rerun`으로 다시 컴파일한다.
