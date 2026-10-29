# [2026-10-29 16:00] VPS 비정상 재부팅 후 게임 서버 수동 기동

- **요청·확인:** 서버가 꺼졌다는 제보 후 VPS에 Java 프로세스·25565 LISTEN·기존 tmux 세션이 없음을 확인했다. 사용자가 명시적으로 다시 켜라고 지시했다.
- **기동:** `/srv/MinecraftPPakemonServer/run.sh`를 기존 이름 `ppakemon`의 새 tmux 세션에서 실행했다. 15:56:12(KST) `Done (3.951s)!` 로그, tmux pane_dead=0, Java 실행, 25565 LISTEN을 확인했다. 실제 플레이어 접속까지는 확인하지 않았다. 기존 서버 프로세스를 종료하거나 재시작하지 않았고, 이미 종료된 상태에서 새로 기동했다.
- **종료 원인 범위:** VPS의 직전 부팅 journal 마지막 기록은 14:40:06, 새 부팅 시작은 14:43:54(KST). 이전 로그에 정상 종료·재부팅 명령, OOM kill, kernel panic, watchdog 기록이 없었다. 게임 `latest.log` 마지막 갱신은14:22:59이며 게임 자체의 정상 종료 기록은 없다. 디스크 여유24GB, 재기동 전 메모리 여유 약7.2GiB였다. VPS 전체가 갑자기 재부팅된 것은 확인되나 호스트 측 원인/실행 주체는 확인하지 못했다. 로그의 외부 SSH invalid-user 접속은 인증 전 거절 기록이며 이번 재부팅 원인으로 연결할 근거는 없다.
- **변경 범위:** 게임 설정·모드·월드 파일을 수동 수정하지 않았다. 앞서 설치된 임시 PC 진단의 메모리 구독은 VPS 재부팅으로 종료됐을 수 있으며 재설치하지 않았다.

# [2026-10-28 12:16] PC 포켓몬 소실 조사: PC·파티 불일치와 저장 객체 불일치 흔적

- **요청·범위:** PC 포켓몬이 반복 소실되는 원인 조사. VPS 종료·재시작 금지. 운영 Java PID277928 유지. 제품 코드 수정·빌드·기능 배포는 하지 않았다.
- **백업에서 확인한 손실:** Pokachip5139의 10/21 새벽 PC167개와 10/22 새벽 PC178개를 UUID로 대조하면 기존 개체 중 제거는 원본 이로치 야도뇽 `bb82fa1c-cb84-4974-814c-b0f45f0a640b` 1개, 신규12개, 공통 개체 슬롯 이동0개다. 야도뇽의 Box3/Slot20에 버섯모 `32f5d320-54c1-440a-8ad6-4b7bb6e1a914`가 들어갔다. 이 버섯모는 이전 백업에서는 파티 Slot0에만 있었지만 이후 백업에서는 PC 해당 칸과 파티 Slot0에 동일 UUID로 중복 저장돼 있다. 이후 백업의 PC 파일시각은10/21 21:57:53, 파티는21:46:52다. 현재 PC·파티에는 해당 중복이 없다. 다른 과거 소실까지 같은 원인이라고 확정하지 않는다.
- **저장 경로:** 실제 운영 Cobblemon1.8.1 JAR의 FileBackedPokemonStoreFactory를 분석했다. PC와 파티는 각각 직렬화해 단일 작업 큐에서 별도파일로 저장하며 두 파일을 묶는 트랜잭션이 없다. disconnect는 저장을 큐에 넣고 완료를 기다리지 않고 cache를 제거한다. isCached(store)는 UUID 키 존재만 검사하고 객체 동일성을 검사하지 않는다. 같은 UUID의 다른 PC 객체가 동시에 저장 대상으로 들어갈 여지가 있다. 이것만으로 과거 손실을 재현한 것은 아니다.
- **실행 중 포착:** 11:56:32(KST) 동일 Pokachip PC UUID의 dirty 객체 identity765458718과 cached 객체381096129가 다른 상황을 진단에서 관측했다. 11:56:54에는 dirty 상태가 비었다. 이때 두 객체의 내용 차이는 보관하지 못했고 손실·덮어쓰기는 확인하지 못했다. 이후 조회한 실제 RAM·디스크 PC는 모두261개이며 UUID 집합이 같다. 이는 원본 야도뇽이 현재 PC에 있다는 뜻이 아니다. 앞선 오프라인 지급의 수령 여부는 이번 조사에서 검증하지 않았다.
- **재현·제외 한계:** 실제 storage manager에 등록하지 않은 임의 UUID PC/파티 복사본으로 native swap→NBT 저장/재로드를 실행하면 원본 야도뇽이 보존됐다. 이 과정에서 기존 UUID 조회 index가 남는 현상은 재현했지만 실제 소실과 동일시하지 않는다. 복사본만 사용했고 실제 플레이어·디스크 저장은 하지 않았다. 보관 로그에서 포켓몬 NBT 읽기 실패·구종 오류·이전 저장파일 fallback·PC handler 예외는 찾지 못했다. native PC 이동/방생 자체는 감사로그가 없어 과거 행위를 전부 복원할 수 없다. 다른 모드가 원인이라고 확정하지 못했다.
- **현재 판단:** 동일 개체의 PC·파티 중복과 상대 개체 소실은 저장 불일치의 강한 흔적이다. 한쪽 저장의 실패/되돌림, 오래된 객체의 저장 등 정확한 시작 경로는 아직 미확인이다. 정상 이동만으로 손실이 재현됐다고 보고하지 않는다.
- **임시 추적:** 서버 재시작·클래스 변환/재정의 없이 11:52:24부터 PC·파티 observable의 UUID 변화와 호출경로를 기록한다. 12:10:15부터 dirty/cached가 다른 객체이고 UUID 집합까지 다를 때 두 내용을 별도 진단파일로 남기는 보조 관찰도 설치했다. 실제 포켓몬 데이터 쓰기·기능 처리 변경은 없다. 종료시각2026-10-29 11:52:24(KST), 주 로그8MiB 한도·유한 큐·충돌 payload최대8건, 종료시 구독 해제. 모든 직접 배열변경까지 잡는 완전한 감사는 아니다. 12:15 기준 보조 관찰에서 내용 충돌 payload는 아직 없다.
- **근거 보관:** 로컬 `F:/AI/scratch/pc-loss-investigation-20261028/`의 `cross-store-duplicates.txt`, `pc21-vs-pc22.json`, `isolated-replay.txt`, `ram-vs-disk.json`, 진단 소스/JAR 및 실제 JAR 역컴파일. VPS `/home/ubuntu/.local/share/ppakemon-diagnostics/pc-loss-20261028/`의 `pc-party-events.log`, `cache-state*.txt`, `stale-captures/`. 과거 백업 추출은 앞선 `pokachip-salandit-20261028/all-stores-20261021/`·`all-stores-20261022/` 보존본을 사용했다.

# [2026-10-28 11:22] Pokachip5139 원본 야도뇽 오프라인 지급·귓말 예약 저장 확인

- **지시·상태:** 빡대리님이 지급과 귓말 안내를 명시했다. 11:18:36 `list`에서 접속자0명·Pokachip 조회 No entity 확인. 기존 `PendingItems`와 `OperatorWhisper`의 다음 접속 전달 경로를 사용했으며 즉시 인벤토리 수령/귓말 열람으로 보고하지 않는다.
- **지급 내용·중복 방지:** 원본 `bb82fa1c-cb84-4974-814c-b0f45f0a640b`의 48레벨·수컷·이로치 야도뇽 NBT를 사용했다. 현재 저장본과 기존 지급 대기열에 같은 UUID가 없는지 확인하고, 서버 스레드에서도 오프라인 상태·기존 대기열 중복을 재확인했다. `cobblemon:pokemon_item={species:"cobblemon:salandit",aspects:["shiny","male"]}` 표시 정보와 `cobblemon_to_item` 버전1 원본 전체 NBT를 가진 아이템 한 개를 기존 PendingItems API로 등록했다. 클래스 재정의/변환이나 제품 코드/JAR 교체는 없다.
- **귓말:** 기존 OperatorWhisper API로 “분실 신고하신 이로치 야도뇽을 10월 21일 백업 원본으로 복구해 지급했습니다. 48레벨 수컷이며 원래 개체값·성격·특성·기술을 유지했습니다. 포켓몬 아이템을 손에 들고 우클릭해 복원해 주세요. 소지품이 가득 찼다면 발밑을 확인해 주세요.”를 본인 UUID에 예약했다. 다음 접속 때 운영자 귓말 형식으로 전달된다.
- **저장 검증:** 11:21:42 `save-all flush`의 Saved the game 확인. `world/data/jbro_policy_pending_items.dat`의 본인 큐에 원본 UUID가 정확히1개이며 포켓몬 NBT의 자료형·값 전체가 10/21 백업 원본과 동일함을 검증했다. `jbro_policy_operator_whispers.dat`에도 안내 문구1개가 저장됐다. 서버 Java PID277928·pane_dead=0 유지, 종료·재시작·강제 접속 조작 없음.
- **근거·미확인:** `F:/AI/scratch/pokachip-salandit-20261028/{offline-grant-result.txt,offline-grant-saved-verification.txt,grant-item.snbt,whisper-message.txt}`. 실제 다음 접속 지급·귓말 수신·아이템의 파티 복원은 아직 확인하지 않았다. 인벤토리가 가득 차면 기존 PendingItems 구현에 따라 발밑에 떨어진다. 소실 원인은 이번 지급으로 해결됐다고 주장하지 않는다.

# [2026-10-28 00:35] Pokachip5139 이로치 야도뇽 원본 확보 (지급 전)

- **추가 단서:** 빡대리님이 마지막 보관 시점을 지난주로 추정했다. 10/21 21:04:06 Pokachip의 `[Salandit]`, 21:04:21~23 `수컷이라 / 못씀`을 확인했다. 제보의 수컷 야도뇽과 특징은 일치하나 채팅 로그만으로 동일 UUID·이로치 여부는 확인할 수 없다. 바로 뒤 연결 종료 로그가 있지만 소실 원인으로 단정하지 않는다.
- **원본 발견:** Drive `snapshot-20261020T190026Z-f6_rxlev`(10/21 새벽)에서 본인 PC 현재/이전 파일의 `Box3/Slot20`에 `bb82fa1c-cb84-4974-814c-b0f45f0a640b`, `cobblemon:salandit`, Shiny=1·MALE·48레벨·성급(hasty)·부식(corrosion)·퀵볼의 원본을 찾았다. 원래 트레이너 UUID도 Pokachip과 일치한다. 체/공/방/특공/특방/속 IV는21/10/16/0/31/16, 기술은 화염방사·용의파동·베놈쇼크·불태우기다. 이 백업의 본인 PC/파티/playerdata 현재·이전 6개를 모두 보관했다.
- **보관·일치:** 로컬 `F:/AI/scratch/pokachip-salandit-20261028/original-pc-20261021.dat`와 VPS 진단 보관본의 SHA-256은 `5fd99fad3bd5853c022f4889340257f08913059dac910505fa7ed00d60062005`로 같다. 자료형을 유지한 원본 포켓몬 SNBT도 같은 로컬 폴더 `original-salandit.snbt`에 보관했다. VPS 원본 파일들은 `/home/ubuntu/.local/share/ppakemon-diagnostics/pokachip-salandit-20261028/all-stores-20261021/`에 있다.
- **추가 대조:** 10/22·10/27 새벽 백업 각각 본인 PC/파티/playerdata 현재·이전 6개에도 두 종이 없음을 확인했다. 10/22 추출본도 `all-stores-20261022/`에 보관했다. 현재 전체 플레이어 PC/파티·playerdata의 현재/이전 및 배낭61개 저장본에서 원본 UUID의 16바이트·문자열 모두 부재를 확인했다. 모든 월드 상자·드롭 엔티티를 조회한 것은 아니다.
- **상태·미확인:** 원본 확보까지 완료했으며 복구 지급·플레이어 파일 변경은 하지 않았다. 정확한 소실 시점·원인은 여전히 미확인이다. 서버 Java PID277928·pane_dead=0 유지, 종료·재시작 없음. 제품 코드·빌드·테스트는 실행하지 않았다. 앞선 00:22의 원본 미확보 상태는 이번 발견으로 갱신한다.

# [2026-10-28 00:22] Pokachip5139 이로치 야도뇽 분실 조사 (원본 미확보)

- **대상·제보:** 계정 `Pokachip5139`, UUID `fe24bf6c-1069-4648-8807-e167e55f198f`. 00:12:01 게임 채팅에서 박스의 이로치 야도뇽이 사라졌다고 했고, 직전 수컷이라고 설명했다. 마지막으로 본 시각은 기억하지 못한다고 답했다. 10/21 21:04:06 `[Salandit]` 채팅 기록은 있지만 그 로그만으로 이로치 여부·UUID는 식별하지 못한다.
- **현재 확인:** 저장된 본인 PC/파티와 각 이전 파일, playerdata의 현재/이전 파일에 salandit/salazzle 없음. 실제 인벤토리 조회도 해당 종·포켓몬 모델 아이템0개. 클래스 변환·재정의 없는 임시 진단으로 실행 중 PC260마리·파티6마리에 두 종이 없음을 재확인했다. 배낭 저장본에도 두 종이 없고, 다른 계정 포함 현재 PC/파티20개 파일에서도 원래 트레이너가 본인인 이로치 두 종을 찾지 못했다. 원래 트레이너 조건이 맞지 않는 개체의 이동까지 배제한 것은 아니다.
- **백업 확인:** 로컬 보관 전체 백업 `snapshot-20261026T190007Z-a_1g9t1t`(10/27 새벽)의 본인 PC 현재/이전 저장본에도 없음. Drive `snapshot-20261021T190025Z-t5ri54dd`(10/22 새벽)를 스트리밍해 본인 PC 현재/이전 파일(파일 저장시각10/21 21:57:53)에서도 두 종이 없음을 확인했다. 두 백업의 파티·인벤토리 및 나머지 날짜 전체 백업은 이번 조사에서 열지 않았다. 따라서 원본이 모든 백업에서 없다고 단정하지 않는다.
- **결과·범위:** 원본 UUID/NBT와 소실 원인은 아직 미확인이다. 원본 복구·새 개체 지급·포획/진화 조작·설정/플레이어 파일 변경·서버 종료/재시작은 하지 않았다. VPS Java PID277928 유지. 진단 및 클라우드 읽기는 서버 밖 경로를 사용했고 제품 코드·Gradle/JUnit은 변경/실행하지 않았다.
- **근거:** 로컬 `F:/AI/scratch/pokachip-salandit-20261028/{live-query.txt,backup-pc-scan.txt,cloud-20261022-pc-scan.txt}` 및 진단 소스/JAR. VPS 실행 중 조회 결과는 `/home/ubuntu/.local/share/ppakemon-diagnostics/legend-20261025/salandit-live-20261028.txt`에 있다. 다음 조사는 백업의 파티/아이템까지 확인하거나 마지막 실제 보관 시점을 좁혀야 하며, 조회 캐시에만 남은 개체를 저장 원본으로 취급하지 않는다.

# [2026-10-26 21:47] Park_JH 에이스번 기존 아이템 Alpha 한 필드 복구 (VPS 무중단)

- **요청·확인:** 빡대리가 실수로 합성 소모한 것 같은 우두머리 에이스번 복구를 요청했다. 실제로는 UUID 40d00c87-c525-48ea-bb27-a2ddfb204e68가 21:36:39 합성 결과에도 유지되고 현재 인벤토리 Slot31의 66레벨 아이템으로 남았다. Alpha 필드만 없고 우두머리 마크·표시 정보가 남아 있었다. 별도 신규 개체 지급은 필요하지 않았다. 당일 04시 전체 백업의 본인 playerdata·party·PC에는 해당 에이스번이 없었으므로 그 백업으로 원본을 복원했다고 쓰지 않는다.
- **복구:** 21:43:38 count1·Slot31·종·원본 UUID가 일치하고 Alpha=true가 아닌 조건에서만 item modify entity Park_JH inventory.22의 인라인 set_custom_data로 cobblemon_to_item.pokemon.Alpha=1b를 재귀 병합했다. 실행 직전 원본 SNBT와 실행 후 SNBT·콘솔 결과는 서버 밖 /home/ubuntu/.local/share/ppakemon-backups/manual/20261026-parkjh-cinderace-alpha에 보관했다. Slot31은 핫바 다음 소지품 영역의 inventory.22이며 슬롯 번호 체계를 혼동하지 않는다.
- **검증:** 콘솔 교체 성공과 직후 Alpha=1b를 확인했다. SNBT를 구조로 파싱하여 before와 after에서 Alpha 추가 하나만 다르고 UUID·레벨66·IV·기술·수량·컴포넌트 전부가 같음을 확인했다. F:/AI/scratch/ppakemon-cinderace-20261026-117d/restoration.json, verify_restoration.py, verification.json에 근거를 남겼다. 실제 파티 복원·후속 디스크 저장·사용자 화면은 직접 확인하지 않았다. 새 지급·다른 개체/월드 파일 덮어쓰기·모드/설정 수정·서버 재시작은 없다. tmux pane %0 PID277928·dead0이 전후 동일하다.
- **원인 조사:** 운영 JAR의 실제 class를 읽어 합성 브리지의 인스턴스 loadFromNBT가 Cobblemon copyFrom의 Alpha 누락을 거치는 것을 확인했다. installed-classes.json, actual-classes, Pokemon-installed.txt, PokeFusionCobblemonBridge-installed.txt에 근거가 있다. 모드 수정·빌드·테스트·배포는 하지 않았으며 현 합성 경로는 재발 가능성이 있다. 사용자에게 결과 확정을 멈추라고 안내했다.

# [2026-10-25 00:45] 찌르꼬 지급 아이템 표시 정보 누락 수정·파티 복원 확인

- **잘못·원인:** 앞선 수동 지급은 포켓몬 NBT만 포함하고 `PokemonItem.from`이 설정하는 `cobblemon:pokemon_item` 컴포넌트를 빠뜨렸다. 서버 인벤토리에는 원본 UUID의 아이템 한 개가 있었지만 사용자 캡처에서는 해당 칸이 비어 보였다. 서버 데이터만 근거로 칸을 안내한 것은 불충분했다.
- **확인·수정:** 설치된 Cobblemon 1.8.1 JAR의 `PokemonItem.Companion.from`과 컴포넌트 코덱을 읽어 species/aspects 필드를 확인했다. 00:44:04 현재 `Slot:25`의 수량 1·원본 UUID를 확인하고, 이 조건이 그대로인 경우에만 `inventory.16`의 기존 한 개를 원본 포켓몬 NBT와 `cobblemon:pokemon_item={species:"cobblemon:starly",aspects:["shiny","male"]}`를 가진 아이템으로 교체했다. 추가 개체는 지급하지 않았다. 00:44:29 `[Starly]` 교체 성공을 확인했다.
- **결과:** 00:44:42 `[pokemon-transfer]`에서 원본 UUID `f9faa7fa-a729-468f-aa59-4c4e60d27bc8`의 찌르꼬가 `cursor`에서 `party[2]`로 복원된 `outcome=success`를 확인했다. 수정 뒤 사용자 화면과 이후 디스크 저장은 직접 확인하지 않았다. VPS 재시작·모드/설정 변경·빌드·테스트는 없었다.
- **재발 방지:** 수동 지급 전 설치 버전의 정상 아이템 생성 경로에 필요한 전체 컴포넌트를 확인한다. 서버 저장 데이터와 실제 사용자 화면을 구분해 보고한다.

# [2026-10-25 00:37] Park_JH 이로치 찌르꼬 원본 아이템 지급 (VPS 무중단)

- **확인:** 00:27:03 파티 2번의 찌르꼬 `f9faa7fa-a729-468f-aa59-4c4e60d27bc8`가 커서로 아이템화된 뒤 00:27:19 `Java heap space`로 서버가 다운됐다. 종료 처리도 같은 오류로 실패했고 00:28:00 watchdog 보고서가 남았다. 구체적인 메모리 점유 원인은 이 작업에서 확정하지 않았다.
- **원본:** Park_JH PC 저장본 `.dat.old`의 `Box1/Slot8`에 동일 UUID·75레벨·이로치·수컷 찌르꼬의 전체 NBT가 남았다. 이를 `F:/AI/scratch/ppakemon-starly-20261025-0034/Park_JH-pcstore.dat.old`에 보관했고 VPS 원본과 SHA-256 `dde8820bb497e3643162cf1d44e6adeffcc09c8bd2b4fda75226bae632e922b7` 일치를 확인했다.
- **지급:** 사용자 지시로 현재 파티·PC·플레이어 저장본의 UUID 부재와 실제 인벤토리를 재확인한 뒤, 원본 NBT의 자료형까지 유지한 `cobblemon_to_item` 버전 1 포켓몬 아이템 한 개를 콘솔로 지급했다. 00:36:41 지급 성공과 00:36:43 실제 인벤토리 `Slot:25`의 수량 1·원본 UUID·전체 포켓몬 NBT를 확인했다. 파티 복원 조작과 이후 디스크 저장은 아직 확인하지 않았다.
- **범위:** 서버 종료·재시작·모드/설정 변경·월드 파일 덮어쓰기는 하지 않았다. 서버는 기존 Java PID `260207`로 지급했으며, 관련 소스 수정·빌드·테스트는 없다.

# [2026-10-18 02:24] 교체 망키의 파티 복원·진화 저장 확인

- 앞선 UUID 교체 뒤 읽기 전용으로 저장 데이터를 재조회했다. 새 UUID의 개체가 파티 `Slot0`에서 `cobblemon:primeape`, 레벨 56으로 저장돼 있어 아이템이 복원된 사실을 확인했다. 새 UUID는 인벤토리·PC에 없었고 기존 UUID도 파티·PC·인벤토리에서 검출되지 않았다. 실제 플레이 장면은 보지 않았으며 진화 조작은 이 작업에서 하지 않았다.
- VPS tmux Java PID `141095`·`pane_dead=0`을 다시 확인했다. 이 추가 확인은 저장 파일과 프로세스 상태를 읽기만 했고 서버 종료·재시작·모드 배포는 하지 않았다.

# [2026-10-18 02:14] Kim_gabsoo 합성 망키 아이템 UUID 교체 (VPS 무중단)

- **사용자 범위:** 먼저 플레이어 아이템을 복구하고, 모드 수정본은 개발 클라이언트에만 적용한다. VPS/운영 배포 파일 변경과 서버 종료·재시작은 금지했다.
- **교체:** 콘솔 `data get entity`로 기존 망키 아이템의 UUID·슬롯·전체 컴포넌트를 확인했다. 해당 UUID·종류·수량·슬롯이 그대로일 때만 `execute if data ... run item replace entity ...`로 새 UUID의 아이템 한 개로 교체했다. 원본 및 교체 결과는 서버 밖 `/home/ubuntu/.local/share/ppakemon-backups/manual/20261018-kim-gabsoo-mankey-f7de4263/`에 보관했다.
- **검증:** 교체 전후 SNBT를 필드 순서와 무관하게 비교해 `pokemon.UUID` 한 필드만 달라졌음을 확인했다. 레벨 56·암컷·6V를 포함한 나머지 데이터는 동일하다. 기존 UUID 아이템은 인벤토리에 없으며, 교체 직전 저장된 파티·PC에 기존 UUID 실물이 없어 PC 포켓몬은 삭제하지 않았다. 캐시만 남은 것은 저장 파일의 실물과 구분한다.
- **시도 기록:** 첫 명령은 아이템 컴포넌트 키에 따옴표를 써 파싱 단계에서 거부되어 원본이 유지됐다. 키 표기를 고친 한 번의 교체가 성공했다. 교체 후 원문 비교 실패는 NBT 필드 순서 변화였고 구조 비교로 UUID 외 동일함을 확인했다.
- **가동·미확인:** tmux pane `%0`의 Java PID `141095`가 전후 동일하며 `pane_dead=0`이다. 모드·설정·서버 실행 상태는 바꾸지 않았다. 플레이어가 새 아이템을 실제 복원했는지는 확인하지 않았다.

# [2026-10-14 19:18] 관리 도구의 월드 백업을 Google Drive로 전송

- **문제:** 예약된 서버 전체 Google Drive 백업과 별도로 VPS 관리 도구가 업데이트·커밋마다 압축하지 않은 `world-snapshot.tar`를 VPS의 `~/.local/share/ppakemon-backups/manager/`에 누적했다. 2026-10-14 확인 시 이 폴더는 약 24.6GB로 VPS 디스크 사용량의 주원인이었다. 사용자는 월드 백업을 Google Drive로 보내라고 지시한 상태였으므로 관리 도구 경로도 포함해야 한다.
- **구현:** 관리 작업이 끝나고 서버가 다시 켜진 후 백업 폴더 전체를 `gdrive:VPS-backups/PPakemon/manager/<백업 ID>/`에 `rclone copy`로 전송하고 `rclone check --checksum --one-way` 성공 시에만 VPS 사본을 지운다. 전송·검증 실패 시 VPS 원본을 남기고 GUI에 경고한다. Drive 인증은 VPS의 기존 root 전용 `/var/lib/ppakemon-gdrive/rclone.conf`를 사용하며 비밀값은 기록하거나 설치본에 복사하지 않는다.
- **검증:** Python 전체 `unittest` 25개 중 24개 통과, 1개 건너뜀. Drive 검증 성공 시 로컬 정리, 실패 시 보존, 커밋 작업의 서버 재기동 후 업로드 순서를 테스트했다. 후속 점검에서 전송 진행 상태 기록 실패가 성공한 작업의 롤백을 유발하지 않도록 수정하고 관련 4개 테스트를 통과했다. 실제 VPS에서 새 `_archive_backup`에 작은 검증 파일을 넣어 Drive 일치 확인과 VPS 사본 정리를 확인했고 검증용 Drive 폴더도 제거했다. 실제 GUI 업데이트·커밋의 전체 경로는 서버 중단 동의가 없어 실행하지 않았다.
- **기존 백업 이전·정리:** 관리 도구 백업 26개 폴더의 파일 618개, 총 24,569,278,857바이트를 Drive로 업로드했다. `rclone check --checksum --one-way`를 두 번 실행해 차이 0개와 618개 일치를 확인한 뒤, 관리 작업 잠금 안에서 VPS의 해당 26개 폴더만 정리했다. 디스크 사용률 81%→29%, 백업 루트 4KiB, 서버 25565 LISTEN 확인. 예약된 서버 전체 백업이 유지하는 최신 로컬 스냅샷은 별도 기능으로 그대로 있다. 운영 서버 종료·재시작 없음.
- **설치·Git:** 두 수정 커밋 `4d2e4f3d`, `a90634af`를 `main`에 병합·`origin/main`에 푸시했다. PC 설치본의 `remote_manager.py`·`vps-manager.ps1` 해시가 `main`과 같고 연결 설정 파일은 보존했다.

# [2026-10-11 14:22] VPS 월드 스폰을 초원으로 이동, Chunky 재시작

- **사용자 지시:** 시작 지형이 초원이 아니어서 바꾸고 Chunky도 다시. 코드 확인: 첫 접속은 바닐라 월드 스폰, 광장 `goHome`은 귀환점 → 리스폰 지점 → `sharedSpawnPos` 순서(`Plaza.kt:99`).
- **후보 탐색:** `locate biome minecraft:plains`를 여러 위치에서 실행. 옛 스폰(대략 90,0) 근처 (864,48)은 좁은 띠. 사용자가 직접 보고 E (-1240,-664)를 골랐다.
- **적용:** `positioned over motion_blocking_no_leaves`로 `setworldspawn` → `-1240, 64, -664`(밑 블록 흙). 플레이어 데이터는 2명뿐이고 신규 1명 포함 3명 모두 오버월드에 접속 중이어서 공지 후 `spawnpoint @a`·`tp` (`Yoon_SSU_`는 usercache에만 있고 데이터 없음). 차원 확인용 `say`가 채팅에 한 번씩 보였다.
- **Chunky:** 옛 작업 cancel·confirm 후 `chunky spawn`, 반경 3000 재시작(이미 만든 청크는 건너뜀, 처음 90cps). 체인 스크립트 재실행. 주의: `pkill -f chunky-chain.sh`는 SSH 명령 줄 자체를 죽인다. `pgrep -f "^bash ./chunky-chain"`을 쓴다.
- **범위:** VPS만. 개발 서버 월드 스폰은 바꾸지 않았다.

# [2026-10-11 14:01] VPS 광장 커밋·푸시, 설정 변경 정체 확인

- **사용자 지시:** "VPS 깃 상황 보고 광장차원 같은 건 커밋&푸시". LFS 오류는 이미 풀려 `git status`가 정상이었다.
- **겹침 처리:** 업데이트가 `dimensions.html`·Chunky JAR(직접 올린 사본)과 겹친다며 멈췄다. 둘 다 `origin/main`과 바이트가 같은 걸 확인하고 JAR은 `~/.local/share/ppakemon-backups/manual-wiki-20261011/`로 옮기고 위키는 `git checkout`한 뒤 업데이트(`2c9ae7a`, 백업 `…/manager/20261011-135800-91be3c3a`). 관리 도구는 같은 내용의 겹침도 충돌로 본다.
- **커밋:** 광장 12개(`data/raids.dat`, `entities` 4, `poi` 1, `region` 6, 새 `r.1.-1`·`r.1.0` 포함)를 관리 도구 commit job으로 `82d487d` 푸시(백업 `…/manager/20261011-135856-387b1fd7`). 로컬 운영 저장소도 ff로 맞췄다.
- **커밋하지 않은 변경(의미 없음):** `peaceful_hunger-server.toml`·`ferritecore`·`forgeconfigapiport`·`voicechat` 2개는 CRLF→LF만 바뀜(값 동일, 리눅스에서 모드가 다시 씀). `worldedit.properties`는 타임스탬프 주석만. `server.properties`는 기존 값 변경 없이 마크가 기본 키를 채워 넣은 것. `cobblemon-startup-hooks.zip`은 생성물이라 보존. `config/chunky/`, `sophisticatedcore-client.toml`은 런타임 생성물.
- **개발 서버 광장 동기화:** 사용자 지시로 개발 서버(꺼진 상태) `world/dimensions/jbro_policy/plaza`를 운영 저장소 `82d487d` 광장으로 미러링, 트리 해시 일치. 기존본 백업 `develop-product/deployment-backups/20261011-dev-plaza-sync/`. `jbro_policy_plaza_biome.dat`는 원래 같았다.
- **Chunky:** 재시작 두 번으로 체인 스크립트가 "server gone"으로 멈췄다. `chunky continue`로 15.7%부터 이어졌고 스크립트를 다시 띄웠다. 광장 `r.0.0` 두 파일은 재기동 직후 다시 바뀐다(정상 drift).

# [2026-10-11 13:35] VPS Chunky 프리젠 시작

- **사용자 결정:** 오버월드 스폰 기준 반경 3000(정사각형), 고대·미래·울트라스페이스(`cobblemon_dimensions:ancient`/`future`/`ultra_space`) 각각 중심 0,0 반경 1000. 접속자 0명일 때 시작.
- **실행:** 13:33 오버월드 시작. 초반 12~22cps, Chunky ETA 2~3시간. 나머지 세 차원은 VPS `~/.local/share/chunky-chain.sh`(로그 `chunky-chain.log`)가 `Task finished for <world>` 로그를 보고 tmux 콘솔로 차례로 시작한다. 서버가 꺼지거나 `Task cancelled`가 나오면 스크립트는 멈춘다. `chunky pause`/`continue`는 그대로 이어진다.
- **시작 시점:** 디스크 38GB 여유, `world/region` 22MB. 완료 여부·최종 용량·틱 영향은 아직 확인하지 않았다.

# [2026-10-11 13:28] 광장 LFS 캐시 복구, 관리 도구 상태 조회 정상화

- **확인·범위:** 사용자 요청으로 아래 Chunky 작업에서 확인한 LFS 실패를 복구했다. VPS `.git/lfs/objects/6e/ca/6eca08c85ffc8e979deb161dd17b8d91f81ef062f78fb66229408c0344d659ef`가 0바이트였고, 정상 임시 파일 `.git/lfs/tmp/2568781714`는 24,576바이트·SHA-256이 해당 객체 ID와 일치했다.
- **복구:** 관리 작업 `operation.lock`을 획득하고 손상 캐시와 검증한 복구 원본을 `/home/ubuntu/.local/share/ppakemon-backups/lfs-repair/20261011-132718-3109177d/`에 백업한 뒤 캐시만 원자 교체했다.
- **보존:** 광장 원본 `world/dimensions/jbro_policy/plaza/entities/r.-1.-1.mca` 내용, HEAD, index 내용과 staged 목록이 작업 전후 동일했다. 이 복구 작업에서는 서버 재시작·월드 변경·운영 커밋·푸시를 하지 않았다.
- **검증:** `git lfs clean`·`git status` 성공, 실제 설치된 `PPakemonVpsManager/remote_manager.py`의 `dispatch(status)` 성공. 서버 실행 중(Java PID 4160), 변경 23개·제외 0개·ahead 0·behind 1, VPS HEAD `54f9d763291bdb0b76011694e20454bda730277e`를 확인했다.
- **한계:** 0바이트 캐시가 처음 생긴 원인은 미확인이다. 복구·검증 결과는 메인 작업자의 직접 확인 보고이며 기록 담당자는 SSH나 GUI 조작을 재실행하지 않았다.

# [2026-10-11 13:27] Chunky 1.4.23 추가, VPS는 JAR 직접 업로드 후 재시작

- **사용자 지시:** "청키 추가해줘. VPS서버 재부팅도" + "청키만 적용". 재부팅은 마크 서버 재시작으로 확인받았다.
- **JAR:** Modrinth `Chunky-Fabric-1.4.23.jar`(1.21.1 Fabric 최신, 의존은 Fabric API뿐). SHA-512가 Modrinth 값과 일치, SHA-256 `3412b170…`. 서버 전용이라 클라이언트에는 넣지 않았다. `deploy-product/server`에도 넣었다(해시 일치). 이 폴더는 VPS와 동기화하는 곳이고, 지금 자체 모드만 있는 건 동기화를 미뤄 둔 상태일 뿐이다(사용자 정정). `VERSION.txt` 항목은 추가하지 않았다.
- **배치:** 개발 서버 `mods`(서버 꺼진 상태, 켜지 않음), 운영 저장소 커밋 `060509e`(Chunky만, LFS 업로드, `origin/main` 일치). 시작 훅 `mod_compatibility`는 목록에 없는 JAR을 지우지 않는다.
- **VPS 업데이트 불가:** `plan`이 git 단계에서 실패한다. 광장 `world/dimensions/jbro_policy/plaza/entities/r.-1.-1.mca`의 LFS clean filter가 기존 객체 `6eca08c8…`와 내용 불일치(`Files don't match`)로 죽는다. 이번 작업 전부터 있던 문제이고 고치지 않았다. GUI의 상태/업데이트 검사도 같은 이유로 실패할 것이다.
- **VPS 적용:** JAR을 `scp`로 `mods`에 올려 해시 일치 확인. 접속자 1명(`Park_JH`)은 사용자 확인 후 진행. 관리 도구 stop job(13:25:36 `All dimensions are saved`) → start job, 새 PID 4160, 13:26:24 `Done (4.770s)`, 모드 목록에 `chunky 1.4.23`, 25565 LISTEN·외부 TCP 성공, 콘솔 `chunky progress` → `No tasks running.` VPS git은 HEAD `54f9d76`이고 Chunky는 미추적 파일로 남는다. LFS 문제를 고친 뒤 업데이트하면 같은 내용의 파일을 받게 된다(그때 충돌 여부는 미확인).
- **미확인:** 프리젠 실행, 실게임 접속.

# [2026-10-11 12:17] 업데이트 때 startup-hooks.json을 저장소 버전으로

- **문제:** `preserve()`가 `startup-hooks.json`을 보존해 VPS의 위키 해시·모드 버전 규칙이 갱신되지 않았다. 기록상 VPS 해시는 옛 값이고 `.startup-hooks-state.json`의 ONCE 표시 덕에만 기동했는데, 이 상태 파일은 백업에도 없었다.
- **수정:** `25df5a43`. `startup-hooks.json`은 저장소 버전을 받는다. VPS에서 직접 고친 상태면 기존 충돌 검사가 업데이트를 멈춘다(새 테스트). 설정 백업에 `.startup-hooks-state.json`을 넣었다. 테스트 22개 통과(1개 원래 skip).
- **설치:** 사용자 지시로 `explorer.exe`에서 띄운 bat으로 앱 밖에서 `install-vps-manager.ps1`을 실행했다(MSIX 가상화 회피). 실제 `%LOCALAPPDATA%/PPakemonVpsManager`의 두 파일 해시가 원본과 같고 `settings.json`은 그대로이며, 앱 가상 경로에는 사본이 없다.
- **VPS 확인(SSH 읽기 전용):** `simulation-distance=6`, `view-distance=10`(이미 6이었음). VPS `startup-hooks.json`은 위키 해시만 옛 `df5c…`로 직접 수정돼 있었고 zip은 `90ff…`였다. 상태 파일의 `wiki:df5c…` ONCE 표시 덕에만 기동하던 상태였다. 이대로면 새 관리 도구의 업데이트가 충돌로 멈추므로 `git checkout -- startup-hooks.json`으로 저장소 버전(`90ff…`)으로 되돌렸다. 원본 백업 `/home/ubuntu/.local/share/ppakemon-backups/manager/manual-hooks-20261011-122347/`. 다음 기동 때 위키 ONCE가 한 번 더 돌며 없는 파일만 채운다. VPS HEAD는 `d5b393f`로 원격보다 뒤다(업데이트는 하지 않음).

# [2026-10-11 19:50] `/피츄` 답변 실패 원인: 서버 PATH에 agy 없음 → `/usr/local/bin/agy` 추가 (재시작 없음)

- **증상(사용자 스크린샷):** 19:03·19:11 `/피츄`가 "지금은 답을 만들지 못했어요"만 반환.
- **원인(직접 확인):** 실패 시각에 `~/.gemini/antigravity-cli/log/`에 agy 실행 로그가 없어 agy가 실행되지도 않았다. 현재 서버 pane(PID 18830)은 이 세션이 띄운 프로세스가 아니라 이후 누군가 `./run.sh`로 다시 켠 것이고(`pane_start_command=./run.sh`), 그 프로세스 PATH에 `~/.local/bin`이 없어 `InquiryReviewSettings.executable()`이 agy를 찾지 못했다. 같은 이유로 문의 검토도 실패했을 것이다. agy 자체·전용 프로필(앞뒤 공백을 자르면 JAR 원본과 같음)·위키 프롬프트는 정상이었다. 배포 JAR로 만든 실제 프롬프트 두 개를 VPS agy에 보내 각각 7~8초에 `SUCCESS`, 도구 호출 없음, 울트라홀 질문에 챔피언 조건을 정확히 답하는 것을 확인했다.
- **조치:** 사용자가 VPS를 끄지 말라고 해서, 서버가 질문마다 PATH를 다시 뒤진다는 점을 이용해 `/usr/local/bin/agy`(`exec /home/ubuntu/.local/bin/agy "$@"`, 755)를 sudo로 추가했다. 서버와 같은 PATH(`env -i`)에서 `command -v agy`가 이 파일을 찾고 `agy --version` 1.3.3을 확인했다. 디스코드에서 실제 `/피츄` 성공은 아직 확인하지 않았다.
- **재발 주의:** VPS는 `./run.sh`로 직접 켜도 이제 agy를 찾는다. 다만 피츄·문의 검토 실패 원인이 로그에 남지 않는 코드라 원인 추적이 어렵다(다음 개선 후보: 실패 사유 WARN 로그, `executable()`에 `~/.local/bin` 후보 추가).

# [2026-10-11 05:00] 디스코드 역할 초기화, 개발 서버도 같은 방식으로 초기화

- **디스코드 역할:** 사용자 지시("디코 역할도 전부 초기화"). 봇은 서버 멤버 인텐트가 꺼져 있어 멤버 목록을 못 본다(403). `GET /guilds/{id}/roles/member-counts`로 관리 역할(인증됨 + 등급 5종, 개발·VPS 설정 합침) 보유 인원을 셌더니 인증됨 1명(서버 주인)·등급 0명이었다. VPS 연동 백업의 계정 1개는 이미 서버를 나간 상태. 주인의 인증됨 역할을 `DELETE`(204)한 뒤 다시 세어 모두 0명을 확인했다. 운영자 역할은 건드리지 않았다. 닉네임은 바꾸지 않았다.
- **개발 서버 초기화:** 사용자가 VPS와 같은 상태로 맞추라고 해서 `develop-product/server/world`도 광장·`level.dat`·데이터팩·`serverconfig`·시작 훅 저장소만 남기고 38개 항목을 `develop-product/deployment-backups/20261011-dev-world-reset/world/`로 옮겼다(BP·전설 기록·JourneyMap 잔여 포함). 광장+`level.dat` 해시 전후 동일. 사용자 요청으로 `explorer.exe`를 통해 `run.bat`로 기동해 04:45:43 `Done (10.639s)`, 포트 25566 확인. 개발 서버는 켜진 채로 두었다.

# [2026-10-11 04:32] VPS 광장 외 월드·유저 데이터 초기화, 초대 링크·상점·정책 JAR 반영

- **사용자 지시:** "VPS 서버 광장을 제외한 모든 차원 초기화. 유저데이터도. 내꺼까지. 전부."
- **초기화(직접 확인):** 접속자 0명 확인 → `TmuxRuntime.stop()` → 삭제 대신 `/home/ubuntu/.local/share/ppakemon-backups/manager/20261011-043020-world-reset/world/`로 33개 이동: 오버월드(`region`·`entities`·`poi`), `DIM-1`, `DIM1`, `dimensions/cobblemon_dimensions`·`myroom`·`more_cobblemon_contents`(PvP 라운지, 경기장은 필요할 때 생성), 플레이어 폴더(`playerdata`·`advancements`·`stats`·`cobblemonplayerdata`·`cobblenav`·`player-mod-data`·`pokedex`·`pokemon`·`tm_moves`), `world/data`의 모든 SavedData(리그 진행·디스코드 연동·첫 접속 키트·광장 복귀점·PvP 기록·위키 토큰·마이룸·차원 진입점·스코어보드 등).
- **유지:** 광장 차원, `data/jbro_policy_plaza_biome.dat`, `data/command_storage_cobblemon_startup.dat`, `datapacks`, `serverconfig`, `level.dat`(같은 시드·게임룰·데이터팩 활성 목록). 광장 파일+`level.dat` 해시가 작업 전후 같았다(`1ef15ea9…`). 시드는 바꾸지 않았으므로 지형은 같은 시드로 다시 생성된다.
- **기동:** 새 PID 46302, 04:32:05 `Done (26.807s)`, 스폰 영역 새로 생성, 봇 online·명령 15개, 25565 LISTEN, 외부 TCP 성공, 광장 `time query` 응답, 데이터팩 30개 활성(초기화 전과 같음).
- **같은 정지 구간에 함께 적용(04:25~04:27):** `jbro-policy-discord.json` `inviteUrl`→`https://discord.gg/rcvmf3txDE`(만료 없음, API로 `expires_at=null` 확인), 상점 `bp-shop.json`을 운영 저장소의 95품목(`0646c05e`)으로 교체(사용자 결정, 기동 로그 `95 entries`), 정책 JAR `C3A2443D…`(첫 접속 초대 링크) 업데이트 job, 이후 오박사·기자 대사. 백업 `…/20261011-042551-invite-shop`, `…/20261011-042604-b33d870a`. VPS HEAD `5c81e61`.
- **남는 것:** 디스코드에서 이미 받은 등급 역할은 연동 데이터가 지워져도 디스코드 쪽에 남는다. 유저는 `/디코인증`을 다시 해야 한다.

# [2026-10-11 04:14] VPS 시뮬레이션 거리 6 적용, deploy-product 갱신

- **시뮬레이션 거리:** 사용자 결정으로 VPS `server.properties`의 `simulation-distance`만 10→6(운영 저장소 `b08deda`와 같은 값). 원본 백업 `/home/ubuntu/.local/share/ppakemon-backups/manager/20261011-041156-simulation-distance/`, 다른 줄은 변경 전후 동일. 접속자 0명 확인 후 `TmuxRuntime.stop()`·`start()`로 재기동: 새 PID 43843, 04:13:06 `Done (9.469s)`, 25565 LISTEN, 봇 online·명령 15개. `view-distance=10`은 그대로. 체감 성능 변화는 측정하지 않았다.
- **deploy-product:** 앞 항목에서 "운영 클라·서버 갱신"에 포함되는데 빠뜨려 사용자 지적을 받았다. `deploy-product/client`에 `PPakemon`의 자체 모드 JAR 14개, `deploy-product/server`에 운영 서버의 자체 모드 JAR 9개를 넣고 바이트 대조(차이 0). 리소스팩 ZIP·셰이더는 규칙(JAR과 `VERSION.txt`만)에 따라 넣지 않았고, `VERSION.txt` 버전 항목은 버전 번호를 사용자가 정해야 해서 추가하지 않았다.
- **설정 보존 주의(VPS):** VPS 업데이트는 기존 `config/` 파일을 보존한다. 지금 VPS의 `config/cobblemon_npc/dialogues/plaza_oak.json`은 운영 저장소보다 새 대사(VPS에서 직접 수정, 미커밋), `config/more-cobblemon-contents/bp-shop.json`은 기록상 VPS에 반영하지 않기로 한 로컬 조정과 다르다. 둘 다 건드리지 않았다.

# [2026-10-11 04:06] 개발→운영 클라·서버 갱신, 운영 커밋 `0f71835`, VPS 반영·기동 확인

- **범위:** 사용자 요청으로 위키 반영, 개발↔운영 클라이언트·서버 대조 갱신, 운영 저장소 커밋·푸시, VPS Pull 적용. 통합 패쳐 모듈(`develop-product/tools/patcher`)을 화면 없이 `Get-*Plan`/`Invoke-*`로 불렀다.
- **JAR 출처 확인:** 바뀐 JAR은 모두 모듈 기록의 main 빌드본과 해시가 같고, 빌드 커밋 이후 모듈 소스·빌드 설정이 현재 main과 같아 재빌드하지 않았다(정책 `F5DB967F`/main `4ff986e4`, 차원 `e05339ec`/`1347320d`, PvP `f049a4b1`/`1d7da204`, 셋업 0.1.14 `7F46B560`/`7d51ad67`, 음악 `234E1F34`·팩 `5FF78087`/`670e3e24`, 리그 `DCDA43CF`/`5611b7e7`).
- **로컬 운영 서버:** 게임·서버 미실행, 25565/25566 미수신 확인 후 30개 적용(JAR 3, 위키 25, 위키 zip, `startup-hooks.json` 위키 해시), 재검사 남은 차이 0. 백업 `%LOCALAPPDATA%\MinecraftServerDeploy\6fddb4c310db0ebb\history\20261011-035710-bad1a866`.
- **운영 클라이언트 `PPakemon`:** 28개 적용(정책·차원·PvP·리그·음악 JAR과 음악 팩 교체, 셋업 0.1.13→0.1.14, 위키 20), 남은 차이 0. 위키 원본은 공유 작업 트리 대신 main을 푼 `F:/AI/build/pichu-wiki-20261011/src/server-wiki`. 백업 `%LOCALAPPDATA%\MinecraftClientPatch\2943A7F97E95FBB9\history\20261011-035953-a3058630`.
- **운영 저장소:** `0f71835` 커밋·푸시, LFS 4개 업로드, `origin/main`·`ls-remote` 일치. 패쳐·작업 기록은 포함하지 않았다.
- **VPS:** 업데이트 전 HEAD `1a1981a`, 직접 수정 파일은 보존 대상 설정뿐이라 충돌 없음. VPS `startup-hooks.json`은 보존되므로 위키 해시가 옛 값(`df5c5f26…`)이고, 그 해시의 ONCE 표시가 `.startup-hooks-state.json`에 있어 위키 훅이 해시 검사 전에 건너뛴다는 것을 먼저 확인했다. GUI와 같은 detached update job(`56cb225e…`)으로 29개 적용, `server.properties`·`startup-hooks.json` 보존, 백업 `/home/ubuntu/.local/share/ppakemon-backups/manager/20261011-040335-ebbf3f11`. 독립 확인: HEAD `0f71835`, 새 PID 42487, 04:04:43 `Done (14.959s)`, TCP 25565 LISTEN, 봇 online·명령 15개, JAR·zip·위키 해시가 로컬 운영본과 일치, Windows에서 공인 25565 TCP 연결 성공.
- **남은 것:** 운영 커밋 `b08deda`(시뮬레이션 거리 10→6)는 `server.properties`라 VPS에서 보존돼 VPS는 여전히 10이다(이번에 바꾸지 않음). 실게임 접속·디스코드 `/피츄` 실제 답변은 확인하지 않았다. `deploy-product` 릴리스 폴더는 건드리지 않았다.

# [2026-10-10 22:42] VPS 60초 JFR 성능 측정·CPU 부하 확인

- 메인 직접 측정 보고: 22:29:54 온라인 3명을 확인한 뒤 PID `26662`를 Java 21 내장 `jdk.jcmd` 모듈(`java -m`, 별도 jcmd/jfr 실행 파일 없음)로 22:31:44~22:32:44 KST JFR profile 측정했다. pidstat/iostat는 22:32:20~22:33:04의 1초 45표본이다. 사용자 후속 시점은 1명이지만 60초 기록은 이미 수집했다. 코드·배포·설정·재시작 없이 JFR 자동 종료와 recording 없음, tmux `dead=0`·동일 PID 유지를 확인했다. 기록 담당자는 재측정하지 않았다.
- ServerTickTime 59표본은 평균 38.681ms·p95 50.348ms·최대 93.148ms·50ms 초과 4개이며, 22:32:17에는 2145ms/42ticks 지연 로그가 있었다. Xeon Gold 6230 2vCPU에서 프로세스 CPU 평균 197.846%는 전체 용량의 98.9%(2코어 최대 200%), JFR machine 평균 99.226%, steal 평균 1.034%였다. CPU 합계에는 C2 JIT compiler 평균 71.834%가 포함되며 프로파일 자체의 JIT 영향과 청크 탐험 워밍업을 분리 측정하지 못했으므로 평상시 부하로 일반화하지 않는다.
- CPU 샘플 5114개 중 WorkerMain 2904개(56.8%)는 NoiseChunkGenerator/Aquifer/Perlin 지형 생성, Server 2147개 중 PokemonEntity 507개(23.6%)·Spawner 166개(7.7%)였다(inclusive 집계는 중첩 가능). ChunkGeneration 3532단계·서로 다른 좌표 1090개는 완성 청크 수가 아니며 full 267단계의 최대 3216ms는 비동기·동시 실행을 포함한 wall duration이다. Server thread 평균 `%wait` 63.061%는 CPU 스케줄링 대기이며 디스크 I/O wait가 아니다.
- RAM 7931MiB, Xms2G/Xmx4G에서 초기 RSS 약 3.22GiB·available 3733MiB·swap 0·memory PSI 0이었다. Heap 최대 2531MiB, GC 후 최대 2468.8MiB로 전체 메모리 압박은 관찰하지 않았다. GC pause 21개 총 986.369ms/60초·최대 85.064ms이며 concurrent G1 old 7초는 정지 7초가 아니다. 디스크 평균 write await 0.898ms·fsync 1.26ms·I/O wait 0.033%로 이 구간의 주병목 근거는 CPU·새 청크 생성과 시뮬레이션 부하 및 JIT/GC 경쟁이다.
- `view-distance=10`, `simulation-distance=10`은 유지했다. RAM 증설보다 먼저 6/6 등 거리 축소를 별도 동일 조건 A/B로 비교하자고 제안했으며 실제 설정 변경·성능 개선·10명 수용 여부는 검증하지 않았다. 이동 되돌림·새 지역 표시 지연은 사용자 보고이며 순간 상태와 지속 프로파일을 구분한다.
- 원본은 `/home/ubuntu/.local/share/ppakemon-profiles/20261010-223144-a7a41970/server-60s.jfr`(3,612,396bytes·0600), 로컬은 `F:/AI/scratch/ppakemon-profile-20261010-223144/`의 JFR와 `analysis-summary.json`으로 보존한다. 전체 stack 64의 allocation/threadpark를 JSON으로 펼쳐 scratch 3.13GB와 분석 지연을 만들었고 로컬 Python 분석은 exit 0으로 완료됐다. 변환 방식으로 시간을 끌었다고 사용자에게 정정했다. 해당 로컬 폴더의 `events.json`(3,132,493,877bytes) 삭제가 자동 승인 검토에서 두 번 `blocked by policy`로 거부돼 해당 방식을 중단했으며 파일은 남아 있다(세부 사유 미제공). 다음 대량 JFR은 binary consumer 또는 좁은 event subset·제한된 stack으로 분석한다.

# [2026-10-10 20:09] 실제 VPS 기능 업데이트·명시적 기동·외부 연결과 시작 알림 확인

- **요청·실행 경로:** 빡대리의 “일단 됐고 지금 VPS에서 풀 받아서 다시 실행” 요청에 따라 GUI 개선은 보류하고 기존 backend SSH detached update RPC(job `fff625150e444d47b142e2d6ef65cf59`)를 실행했다. 사전 조회용 일회성 `Manager.plan()` 호출은 실제 함수명 `plan_update`와 달라 실패했으나 서버 변경 없이 `dispatch(plan)`으로 정정했으며 프로그램 코드 오류는 아니었다. 프로그램 소스 변경·빌드는 하지 않았다. 아래 결과는 메인 작업자의 직접 검증 보고이며 기록 담당자는 SSH 검증을 재실행하지 않았다.
- **원격 반영·해시:** 운영 HEAD를 `6f0ea241f139d73bd7fcb92300516be315a0d121`에서 `e654112385fa5246fc10c4f88ecb68dad8c8f717`로 반영했고 실제 GitHub `origin/main`의 `ls-remote`와 일치했다. 적용한 6개 파일은 `config/cobblemon_npc/dialogues/plaza_nurse_joy.json`, `mods/cobblemon-npc-0.1.0.jar`, `mods/jbro-policy-0.1.3.jar`, `mods/more-cobblemon-contents-0.1.0.jar`, `mods/more-cobblemon-contents-league-challenge-0.1.0.jar`, `startup-assets/server-wiki.zip`이다. 기동 후 모두 커밋된 운영 main 실제 파일과 SHA-256이 일치했다.
- **보존·백업:** 원격에서 변경된 보호 대상 `startup-hooks.json`, `world/dimensions/jbro_policy/plaza/entities/r.0.0.mca`와 기존 설정·월드·플레이어를 보존했다. 서버 밖 `/home/ubuntu/.local/share/ppakemon-backups/manager/20261010-200140-6ad76003`에 광장을 포함한 `world-snapshot.tar`(126 members)와 `settings-snapshot.tar`를 백업했다. 비밀 Discord 설정과 시작 훅의 JSON 값, `run.sh`·`run.bat` 바이트가 백업과 동일함을 확인했으며 비밀값은 기록하지 않는다.
- **기동 보고 정정:** 서버는 업데이트(20:01:40) 전에 이미 19:54:29 `save-all flush`·`stop`, 19:54:30 `All dimensions are saved`, 19:54:31 pane `dead`(status 0)로 종료됐다. 업데이트는 원래 실행 상태 유지 계약에 따라 기동하지 않았다. 메인 작업자가 재기동 완료로 먼저 보고했던 판단을 독립 상태 확인으로 정정하고, 요청 이행을 위해 `TmuxRuntime.start`를 명시적으로 호출했다.
- **실제 기동·연결:** 최종 `tmux ppakemon:0.0 dead=0`, PID `23732`, cmd `java`를 확인했다. `/srv/MinecraftPPakemonServer`의 새 로그에서 20:07:55 `Done (13.943s)`, 20:07:57 voice UDP 24454 시작을 확인했다. `ss`로 TCP 25565·UDP 24454 모두 같은 PID의 Java 리스닝을 확인했고 Windows에서 공인 TCP 25565 직접 연결에 성공했다.
- **Discord 실제 알림:** 20:07:57 피츄 봇 online과 slash commands 7개 등록을 확인했다. Discord 읽기 전용 GET에서 새 시작 알림 1개(2026-10-10 20:07:56.966 KST)를 확인했고 해당 메시지 ID의 직접 GET에서도 `bot=true`, 내용 `🟢 서버가 열렸어요!`를 확인했다: [서버 켜짐 메시지](https://discord.com/channels/1555187390962860162/1555215001361317938/1558435917059063898).
- **재발 방지·검증 한계:** update 성공은 서버 기동 증명이 아니다. 업데이트 전 stopped 상태이면 유지되므로 “다시 실행” 요청은 explicit start와 새 `Done`·프로세스·포트·외부 TCP를 확인한 뒤 완료로 보고한다. 상태를 조회하지 않은 가정으로 기동 완료를 보고하지 않는다. 실제 게임 입장·플레이와 GUI 버튼 직접 클릭은 검증하지 않았다.

# [2026-10-10 18:50] VPS 관리 GUI main 반영·로컬 설치·실행·작업트리 정리 완료

- **main 반영:** 구현 커밋 `1a9d175e825c5fa7e19863104deb7cb00cdded14`를 main `e8e6ebf96d33282f6baa023d75a22403106f1818`에 병합·푸시하고 원격 `ls-remote` 일치와 ahead/behind 0/0을 확인했다. 커밋된 main의 설치기로 설치했다(메인 작업자 보고).
- **설치·해시:** `C:/Users/박주형/AppData/Local/PPakemonVpsManager`에 설치하고 `C:/Users/박주형/Desktop/빡케몬 VPS 관리.lnk`를 생성했다. 설치본 SHA-256은 GUI `9F5498CE5DBDE23068671089FA0583F879941541F0C310E4F3479AD5DB946520`, backend `CFB05A459865503177BCF6248DD2224FEED5A6112E0CC27F3714F2D21EEAC7E0`으로 각각 main 소스와 일치했다. SSH 키는 기존 경로만 참조했으며 내용을 읽거나 복사하지 않았다.
- **설치본 검증:** 설치본 `-Probe`로 실제 VPS HEAD `6f0ea24`, 서버 실행 중, 변경 14개·미푸시 0·미반영 0·제외 0을 조회했다. 바로가기의 Target/Arguments를 사용해 GUI를 실제 실행했고 PID `23624`, MainWindowTitle `빡케몬 VPS 관리`와 실행 유지를 확인했다.
- **작업트리 정리:** 작업용 `vps-manager` 관리 작업트리를 앱 `archive_worktree`로 아카이브했다. 실제 경로 `Test-Path=false`와 Git worktree 목록에서 제외된 것을 확인해 정리를 완료했으며 주 작업폴더는 유지했다.
- **검증 한계:** 실제 운영 파일 선택 커밋·업데이트 적용·종료 버튼 실행은 하지 않았다. 테스트 Git fixture와 상태 조회·기존 실행 중 서버의 start Probe 검증 범위는 그대로이며 실제 운영 파일 변경 성공으로 해석하지 않는다. 설치·동기화·해시·GUI·정리 결과는 메인 작업자의 직접 검증 보고이고 기록 담당자는 재실행하지 않았다.

# [2026-10-10 18:42] VPS 관리 GUI 구현·최종 fixture 검증 (main 반영·설치·실제 운영 변경 미완료)

- **사용자 결정·구현:** Windows 바탕화면에서 개발 폴더 패쳐처럼 GUI로 관리하며 광장 전용이 아닌 JSON·properties 등 변경 파일을 직접 선택해 커밋·푸시한다. Windows PowerShell 7 WinForms와 Python SSH RPC로 상태·변경 목록, 업데이트 검사·기능 적용, 선택 파일 커밋·푸시, 기존 커밋 푸시, 서버 시작·종료, 기존 콘솔, 로컬 연결 설정을 구현했다(메인 작업자 보고).
- **업데이트·보존:** 기능 파일만 반영하고 기존 config 값·루트 properties·run scripts·startup hooks·world·plaza·player와 생성된 `cobblemon-startup-hooks.zip`을 보존한다. 정상 저장·종료 확인 후 월드 전체와 설정을 서버 밖에 백업하며 자동 복구는 교체한 기능 파일만 되돌린다. 원래 실행 중이었던 경우에만 재기동한다. SSH 끊김에 대비한 detached 작업자·job ID 재조회와 원격 `flock`을 사용한다.
- **선택 커밋 정책:** 선택한 파일 이외의 변경은 보존하고 비밀값·일반 월드·플레이어 기록은 제외한다. 푸시가 실패한 커밋은 남겨 기존 커밋 푸시로 재시도한다. 이 동작과 보존·실패 처리는 테스트용 Git fixtures에서 검증한 범위다.
- **최종 검증:** 처음에는 backend 부재로 red import failure를 확인한 뒤 구현했다. VPS의 격리된 임시 Git fixture에서 Linux 최종 테스트 21개 모두 통과했다(1.512초). Windows 기존 20개 중 19개 통과·symlink 권한 1개 skip에 추가 LFS 실제 파일·손상 거부 테스트도 통과해 총 21개 중 20개 통과·1개 skip이다. 메인 작업자가 `RenderUiPath` 화면을 실제 이미지로 확인했고 PowerShell Parser 오류 0을 확인했다.
- **실제 SSH Probe·연결 분리:** GUI와 동일한 SSH 경로의 상태 Probe에서 커밋 `6f0ea24`·실행 중 상태·변경 파일 14개를 조회했고, 이미 실행 중인 서버를 대상으로 start job 생성·완료·결과 조회도 성공했다. 서버는 `tmux dead=0`, PID `16097`, cmd `java`로 변경 없이 유지됨을 확인했다. 부모 SSH 종료 후 별도 SSH로 detached 시험 작업의 진행 중→완료를 확인했다. 시험 dispatch는 3초 대기 후 메시지만 반환해 운영 파일을 변경하지 않았으며 해당 시험 job의 정확한 파일만 정리했다.
- **설치·남은 작업:** 설치 위치는 `%LOCALAPPDATA%/PPakemonVpsManager`, 바탕화면 바로가기는 `빡케몬 VPS 관리.lnk`로 계획했으며 아직 설치하지 않았다. 운영 저장소에는 관리 프로그램을 설치하지 않는다. main 커밋·푸시, 설치본 해시 확인, 바로가기 생성, 작업트리 정리가 아직 남아 있다.
- **검증 한계:** 새 GUI의 실제 운영 업데이트·선택 파일 커밋/푸시·종료 버튼 적용은 실행하지 않았다. fixture 통과와 읽기/시작 Probe를 실제 운영 파일 변경 완료로 해석하지 않는다. 이 기록은 메인 작업자의 구현·직접 검증 보고와 모듈 README를 근거로 하며 기록 담당자는 테스트·GUI·SSH 검증을 재실행하지 않았다.
