# 마이룸 음악 추가 — 1.3.6

2026-10-03. 대상 독자: 빡대리와 Music 팩 유지보수자.
Updates: [1.3.5 선곡](POKEMUSIC_IMPORT_2026-10-03.md)에 마이룸 항목을 추가합니다. 기존 선곡은 유지합니다.

## 적용

- 사용자 제공 원본: `Downloads/pokemusic/43 - Eterna Forest.flac`.
- 음원: `assets/cobleserver/sounds/music/field/myroom/eterna_forest.ogg`.
- 기본 매핑: `mappings.field.dimensions`의 `myroom:rooms` → `cobleserver:track/field/myroom/eterna_forest`.
- 실제 차원 ID는 `simple-myroom/.../room/RoomDimensions.java`에서 확인했습니다. 마이룸이나 서버 모드 자체를 수정하지 않았습니다.
- 낮·밤·바이옴·지하 여부와 무관하게 마이룸 전체에서 이 곡을 사용합니다. 전투 → 화면 → 필드 우선순위, 필드 전환 대기·페이드·개인 음량은 유지합니다.
- Mod Menu → 필드 음악 매핑의 차원 항목에 `myroom:rooms`가 기존 카탈로그 기반 UI로 표시됩니다. 기존 한국어·영어 차원 분류 이름을 사용하며 새 UI 코드나 개인 overrides는 필요하지 않습니다.

## 변환

`minecraft-audio-ogg` 스킬의 재사용 도구로 Vorbis quality 4, 스테레오 44.1kHz, 158초 음원으로 변환했습니다. 비디오·커버·상속 메타데이터는 제거하고 원본 음량을 유지했습니다. 정규화·게인 보정·트리밍은 하지 않았습니다.

20,556,550바이트 → 2,574,490바이트, 17,982,060바이트 감소. 원본은 보존했습니다. 단일 Vorbis 스트림·채널·샘플률·길이 확인과 전체 FFmpeg 디코딩 검사를 통과했습니다. [변환 및 해시 기록](resource-pack/import-myroom-2026-10-03.json).

## 검증·배포

- 마이룸 매핑이 없는 상태에서 재현 테스트가 기본 필드 선택으로 실패하는 것을 확인한 후 매핑을 추가했습니다. 낮·밤 × 바이옴 3개 × 지상/지하 12개 조합에서 마이룸 곡이 선택되며 일반 숲 음악은 바뀌지 않는 것을 검사합니다.
- `:better-cobblemon-music:build` 139/139 테스트 통과. OGG 변환 도구 5/5 테스트 통과.
- 1.3.5 ZIP와 새 ZIP를 비교했습니다. 새 파일은 마이룸 OGG 하나, 바뀐 파일은 `sounds.json`과 기본 카탈로그뿐이며 기존 오디오 115개는 동일합니다. 카탈로그도 새 트랙·단일곡 플레이리스트·마이룸 차원 매핑만 추가했습니다. ZIP 전 항목 CRC와 BGM `stream: true` 검사를 통과했습니다.
- 전체 BGM 112개, 오디오 OGG 116개. ZIP 크기 278,951,062바이트.
- Minecraft 클라이언트가 종료된 상태에서 `C:\Users\박주형\AppData\Roaming\ModrinthApp\profiles\cobblemon-dev`에 1.3.6 JAR와 ZIP를 배포했습니다. 활성 설치 Music JAR은 하나이며 `options.txt`에서 음악팩 선택만 1.3.5 → 1.3.6으로 바꿨습니다.
- 빌드·설치 SHA-256 일치: JAR `1dc429ab277dafd0fcdf2eace37c0594c41f936603e92281a707bc609bf17b9d`, ZIP `41a4f8f0e928757ed5323592593bc13d2eb1879947951126edf40d92368818f5`.
- 개인 `settings.json`·`overrides.json`은 변경하지 않았고, 배포 전 백업과 해시 일치를 확인했습니다. 서버와 다른 모드는 변경하지 않았습니다.
- 이전 JAR·options·개인 설정은 `deployment-backups/music-myroom-1.3.6-20261003`에 보존했습니다. 이전 ZIP는 클라이언트에 비활성 상태로 남겨 두었습니다. 복구 시 Minecraft를 종료하고 옛 JAR와 음악팩 선택만 되돌리세요.

실제 마이룸 진입 후 음악 청취와 설정 화면의 표시 여부는 아직 확인하지 않았습니다. 이는 소스·팩·설치 검사와 별도의 게임 검증입니다.
