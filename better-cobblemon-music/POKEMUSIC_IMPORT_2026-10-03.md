# pokemusic README 적용 — 1.3.5

2026-10-03. 대상: `cobblemon-dev` 클라이언트. 이 문서는 팩 제작자와 빡대리의 확인용입니다.
Updates: [기반 전투 라인업](MUSIC_LINEUP_2026-10-03.md)의 트레이너·리그 선곡과 공식 팩의 필드·화면 매핑.

사용자 제공 `Downloads/pokemusic/readme.txt`의 항목을 적용했습니다. 사막의 `14 - 48 - Route 111.flac` 표기는 사용자 확인에 따라 **48 - Route 111.flac만** 사용합니다. 기존 곡과 같아도 지정된 원본 30개를 전부 다시 변환했습니다. 8개 안정 ID의 음원을 교체하고 22개 ID를 추가했습니다. 지정되지 않은 기존 음원을 삭제하거나 다른 곡을 옛 이름에 억지로 연결하지 않았습니다.

## 선곡

| 상황 | 지정 원본 | 방식 |
|---|---|---|
| 메인 화면 | 1-02 Introduction / 2-61 Pokémon League (Night) | 2곡 random |
| MCC 기본 허브 | 13 Boutique | 단일 |
| MCC 상점 | 1-24 Poké Mart.mp3 | 단일 |
| 야생 트레이너 / 일반 트레이너 | 1-20 Battle! (Trainer Battle) | 단일 |
| 체육관 / 하드 체육관 | 1-32 Battle! (Gym Leader) | 단일 |
| 사천왕 / 하드 사천왕 | 2-64 Battle! (Elite Four) | 단일 |
| 난천 / 하드 난천 | 2-67 Battle! (Champion) | 상대 ID별 단일 |
| 챔피언 기본값 | README 공란 | 기존 ORAS 유지 |
| 엔드 | 12 Distortion World | 단일 |
| 네더 | 2-58 Stark Mountain | 단일 |
| Deep Dark / 고대 도시 바이옴 | 2-23 Old Chateau / 28 Union Cave | 2곡 random |
| 동굴 바이옴 / 일반 지하 | 1-30 Oreburgh Mine / 11 Lake Caverns [Hidden Track] | 2곡 random |
| 강 | 1-07 Lake / 37 Sealed Chamber | 2곡 random |
| 바다 | 31 Route 47 / 15 Underground Ruins | 2곡 random |
| 늪지 | 26 Road to Reversal Mountain | 단일 |
| 정글 | 1-58 Route 210 | 단일 |
| 설원 | 2-16 Route 205 (Night) | 단일 |
| 산 | 1-39 Route 205 (Day) / 21 Route 3 | 2곡 random |
| 숲 | 1-25 Route 203 (Day) / 18 Viridian Forest | 2곡 random |
| 평원 / 기본 필드 | 2-02 Route 201 (Night) | 단일 |
| 사막 | 48 Route 111 | 단일 |
| 악지 | 40 Abandoned Ship | 단일 |
| 광장 낮 | 24 Route 1 | 시간 조건 |
| 광장 밤 | 25 Pallet Town | 시간 조건 |

2곡 그룹은 개인 기본 `shuffle` 설정과 무관하게 팩에 `selection: "random"`을 명시했습니다. 첫 곡을 전체 후보에서 무작위로 고르며 이후 직전 곡은 피합니다. 따라서 2곡이면 시작 순서가 무작위이고 이후 교대로 재생됩니다. 기존 첫 선택에서 마지막 곡을 제외하던 버그도 수정했습니다.

고대 도시는 구조물 위치를 별도로 추적하지 않고 `minecraft:deep_dark` 바이옴에 연결합니다. 기존 필드의 정확한 바이옴·경로·지하 판정 구조는 유지하며, 벚꽃숲도 새 숲 2곡을 사용합니다. 설원 파일 제목의 `(Night)`는 시간 조건이 아닙니다.

## 낮·밤과 화면

게임 시각 24,000틱 중 13,000 이상 23,000 미만이 밤입니다. 날씨와 PC 시계는 사용하지 않습니다. 실제 `jbro_policy:plaza`와 옛 `cobblemon_policy:plaza` 별칭 모두 연결합니다. 낮·밤 변화는 공용 상황 검사(현재 개인 설정 1초)와 기존 필드 전환 대기(4초)·페이드(각 1초)를 따릅니다.

Mod Menu → 필드 음악 매핑에 `차원 · 낮`, `차원 · 밤` 항목을 추가했습니다. 다른 설정을 저장해도 개인 시간 매핑을 보존합니다. 시간별 매핑이 공통 차원 매핑보다 우선하므로 광장의 개인 곡을 바꿀 때도 낮·밤 항목을 각각 수정하세요.

메인 화면은 `minecraft:title`, MCC 기본은 `more_cobblemon_contents:hub`, 상점은 `more_cobblemon_contents:hub/shop`입니다. 난천은 `league_challenge/champion/cynthia`, 하드 난천은 `league_challenge/hard_champion/cynthia_hard`의 구체적인 상대 키가 기본 챔피언보다 우선합니다. RCT NPC 역할 탐색은 추가하지 않습니다.

새 시간 속성을 모르는 옛 JAR는 이 ZIP를 읽을 수 없으므로 1.3.5 JAR와 ZIP를 함께 배포해야 합니다(MUST). 새 JAR는 기존 팩과 개인 설정을 계속 읽을 수 있습니다. 상세 계약·inspect 근거는 [메뉴 및 낮·밤 계약](../docs/MUSIC_MENU_AND_DAY_NIGHT_2026-10-03.md)에 있습니다.

## 아직 지정되지 않은 것

| 원본 또는 상황 | 현재 처리 |
|---|---|
| 챔피언 기본곡 | 사용자 README가 비어 있음. 사용자 지정 전까지 ORAS 유지 |
| 52 - Battle Tower.flac | README `메모장(적용X)`에 있으므로 미적용 |
| 14 - Santalune Forest.flac | 사막은 Route 111만이라는 사용자 답변에 따라 미지정 |
| 25 - Poké Mart.flac | 상점은 README가 지정한 MP3를 사용; FLAC는 미지정 |
| 1-12. Obtained an Item!.mp3 | 이벤트 매핑 요청 없음 |
| 1-16. Pokémon Center (Day).mp3 | 화면·장소 매핑 요청 없음 |
| low-health-critical-health-pokemon.mp3 | 1.3.3에서 이미 적용한 경고음 유지; 미작업이 아님 |
| 해변 | README 지정 없음. PMD Beach at Dusk 유지 |
| 배틀타워·팩토리 실제 전투 | 이번 전용곡 지정 없음. 기존 트레이너 곡 ID 유지; 같은 ID의 음원이 새 MP3로 교체됨 |
| 일반 야생 / PvP / 전설·환상·울트라비스트 / 알파 | 이번 변경 대상 아님. 이전 선곡·알파 식별·전용곡 우선 유지 |

원본 36개 중 새로 변환한 것은 30개이며, 남은 6개 중 경고음 1개는 이미 적용되어 있습니다. 모든 원본 파일은 보존했습니다.

## 변환 및 검증

- `minecraft-audio-ogg` 스킬의 재사용 변환 도구 사용: Vorbis quality 4, 스테레오 44.1kHz, 비디오·커버·상속 메타데이터 제거. 정규화·게인·트리밍 없음.
- 원본 합계 232,503,964바이트 → OGG 합계 42,900,463바이트. 189,603,501바이트(약 81.55%) 감소. 성공적인 디코딩 검사는 청취 품질 평가가 아닙니다.
- [입력/트랙 ID manifest](resource-pack/import-pokemusic-2026-10-03.json), [파일별 SHA-256 및 변환 보고서](resource-pack/import-pokemusic-report-2026-10-03.json).
- 전체 BGM은 111개. 타격음 3개와 기존 빨피 경고음 1개를 합쳐 OGG 115개입니다. 사용하지 않는 옛 트랙은 개인 매핑 ID 호환을 위해 보존합니다.
- `:better-cobblemon-music:build` 138/138 테스트 통과, 변환 도구 5/5 테스트 통과. 새 30개 파일이 생성 팩에 들어갔는지 해시로 검사하며 루기아·경고음 보존도 회귀 검사합니다.
- 실제 게임에서 음악 청취·설정 화면 표시는 별도 확인이 필요합니다. 설치 파일과 선택 상태는 [배포 기록](POKEMUSIC_DEPLOYMENT_2026-10-03.md)에서 따로 확인합니다.
