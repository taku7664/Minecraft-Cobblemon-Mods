# BGM 표시 곡명 한국어 대조

2026-10-03. 대상: 빡대리와 음악팩 유지보수자.
Updates: [곡명 입력 문서](TRACK_TITLES_2026-10-03.md)의 영어 초기값. 입력 구조와 재생 동작은 변경하지 않습니다.

## 적용 범위와 확인 수준

[`resource-pack/catalog-layout.json`](resource-pack/catalog-layout.json)의 `trackTitles` 112개를 한국어 표시명으로 교체했습니다. 빡대리의 요청에 따라 게임명은 `전투! 루기아 (HG·SS)`처럼 영문 약자로 덧붙였습니다. 필드곡의 낮·밤 표시는 원곡의 버전을 뜻하며, 현재 Minecraft 시간이나 바이옴의 이름을 뜻하지 않습니다.

표시용 약자: `G·S`(Gold/Silver), `C`(Crystal), `HG·SS`(HeartGold/SoulSilver), `R·S`(Ruby/Sapphire), `FR·LG`(FireRed/LeafGreen), `D·P`(Diamond/Pearl), `Pt`(Platinum), `B·W`(Black/White), `X·Y`, `OR·AS`(Omega Ruby/Alpha Sapphire), `S·M`(Sun/Moon), `Sw·Sh`(Sword/Shield), `BD·SP`(Brilliant Diamond/Shining Pearl), `S·V`(Scarlet/Violet), `PLA`(Pokémon Legends: Arceus), `PLZA`(Pokémon Legends: Z-A), `PC`(Pokémon Champions), `PMD2`(Pokémon Mystery Dungeon 탐험대). 이 약자는 이번 팩의 표시 규칙이며 공식 약자 인증을 뜻하지 않습니다.

사용자가 제시한 [나무위키 OST 분류](https://namu.wiki/w/분류:포켓몬스터/OST)는 직접 열지 못했습니다. [같은 분류의 나무모에 미러](https://namu.moe/w/분류:포켓몬스터/OST)에서 연결된 작품별 OST의 한국어·영어·일본어 제목을 대조했습니다. 지명은 포켓몬 위키의 다국어 표도 대조했습니다. 미러는 원문 최신 상태와 일치한다고 보증할 수 없습니다.

여기서 '대조'는 문헌의 제목·한국어 고유명과 입력 파일명/태그를 비교했다는 뜻입니다. 모든 OGG를 들어서 원곡·리믹스·편곡을 식별한 결과도, 모든 제목이 공식 한국어 OST 발매명이라는 주장도 아닙니다. 위키의 한국어 번역과 게임의 한국어 고유명을 사용한 표시명입니다.

## 파일 ID를 그대로 번역하면 틀리는 사례

| 원본 제목 또는 기존 ID | 표시명 | 대조 근거 |
|---|---|---|
| Eterna Forest | 영원의숲 | 신오 OST의 Eterna Forest와 다국어 지명표. OST 미러의 띄어쓰기와 다르므로 게임 지명 표기를 선택 |
| Stark Mountain | 하드마운틴 | 신오 OST의 한국어·영어 제목 |
| Old Chateau | 숲의 양옥집 | 신오 OST와 다국어 지명표 |
| Oreburgh Mine | 무쇠탄갱 | 신오 OST |
| Lake Caverns | 호수의 공동 | 신오 OST |
| Underground Ruins | 땅밑유적 | 블랙 2·화이트 2 OST. '지하유적'으로 임의 직역하지 않음 |
| Sealed Chamber | 고시의 석실 | 루비·사파이어 OST와 다국어 지명표 |
| Road to Reversal Mountain | 리버스마운틴으로 가는 길 | 블랙 2·화이트 2 OST |
| Poké Mart | 프렌들리숍 | 신오 OST |
| `pla_boss_battle` / 입력 파일 `1-25. Battle- Boss.mp3` | 승부: 우두머리 포켓몬 | 아르세우스 OST 25번의 일본어·영어·한국어 제목 |
| `sv_leader_pokemon_battle` / 입력 파일 `2-15. Battle! (Leader Pokémon).mp3` | 전투! 주인 포켓몬 | 스칼렛·바이올렛 OST의 Battle! (Titan Pokémon) |
| `oras_groudon_kyogre_battle` | 전투! 초고대 포켓몬 | 입력 FLAC의 TITLE=Battle! (Super-Ancient Pokémon), ALBUM=Omega Ruby / Alpha Sapphire와 해당 OST 목록. '원시회귀'는 별개의 곡이므로 사용하지 않음 |
| `sv_stellar_terapagos_battle` | 전투! 제로의 비보 테라파고스 | 스칼렛·바이올렛 OST의 해당 전투곡 |
| `gs_beasts_battle` | 전투! 라이코·앤테이·스이쿤 (C) | 금·은 OST 문서의 크리스탈 한정곡 목록. 파일 앨범 태그에 Gold / Silver라고 적힌 것만으로 출시 작품을 확정하지 않음 |

원본 FLAC 태그로 `3번도로`, `1번도로`, `태초마을`, `연결동굴`, `47번도로`는 하트골드·소울실버 앨범임을 확인했습니다. `고시의 석실`과 `버려진배`는 루비·사파이어, `영원의숲`은 디아루가·펄기아 앨범으로 적혀 있습니다. 최신 가져오기 목록의 ID는 그대로 유지합니다.

## 곡의 공식 제목으로 확정하지 않은 표시명

아래 항목은 기존 ID 또는 영문명의 의미를 설명하는 한국어 표시명입니다. 확인되지 않은 작품별 OST 제목을 새로 지어 확정하지 않았습니다.

| 트랙 ID의 끝부분 | 표시명/처리 | 남은 확인 |
|---|---|---|
| `oras_dialga_palkia_battle` | 디아루가·펄기아 전투 (OR·AS) | 포켓몬 이름은 신오 OST에서 대조. 이 파일의 정확한 원곡·편곡판과 ORAS 수록 제목은 확인하지 못함 |
| `oras_reshiram_zekrom_battle` | 레시라무·제크로무 전투 (OR·AS) | 포켓몬 이름은 블랙·화이트 OST에서 대조. 이 파일의 정확한 원곡·편곡판과 ORAS 수록 제목은 확인하지 못함 |
| `za_darkrai_battle` | 다크라이 전투 (PLZA) | 기존 ID의 설명명. 연결된 Z-A OST 미러에 해당 곡의 한국어 제목이 없어 확정하지 못함 |
| `pokemon_champions_arena_battle` | 아레나 배틀 (PC) | 기존 ID의 설명명. 해당 음원에 대응하는 공식 한국어 곡명을 확인하지 못함 |
| `swsh_elite_leader_battle` | 포켓몬 배틀 (Sw·Sh) | '상위 관장' 또는 '파이널 토너먼트'라고 추정해 제목을 확정하지 않음. 유지보수자가 원본 파일/출처를 찾아 정확한 곡을 식별한 뒤 title만 갱신 가능 |
| `pmd_beach_at_dusk` 2개 | 해질녘 해변에서 (PMD2) | On the Beach at Dusk의 영문명을 대조하고 뜻을 번역. 공식 한국어 OST 표기는 확인하지 못함 |
| `johto_dark_cave`, `johto_ilex_forest`, `alola_lush_jungle` | 어둠의 동굴, 너도밤나무숲, 셰이드정글 | 한국어 지명을 다국어 표에서 대조한 장소 기반 표시명. OST가 다른 장소와 음악을 공유하거나 별도 제목을 쓰는지까지 식별하지 않음 |
| `unova_route_4`, `jubilife_city` | 4번도로, 축복시티 | 계절/낮·밤 버전이 기존 ID에 없으므로 확인되지 않은 세부 버전을 덧붙이지 않음 |

## 대조한 자료

아래 작품별 링크는 제시된 OST 분류에서 따라간 미러 문서입니다. 곡명 전체를 복제하지 않고 이번 팩의 대응 이름만 편집했습니다.

- [디아루가·펄기아와 Pt 기라티나 OST](https://namu.moe/w/포켓몬스터DP%20디아루가·펄기아/슈퍼%20뮤직%20컬렉션)
- [브릴리언트 다이아몬드·샤이닝 펄 OST](https://namu.moe/w/포켓몬스터%20브릴리언트%20다이아몬드·샤이닝%20펄/OST)
- [하트골드·소울실버 OST](https://namu.moe/w/포켓몬스터%20하트골드·소울실버/슈퍼%20뮤직%20컬렉션), [금·은과 크리스탈 OST](https://namu.moe/w/포켓몬스터%20금·은/OST)
- [루비·사파이어 OST](https://namu.moe/w/포켓몬스터%20루비·사파이어/슈퍼%20뮤직%20컬렉션), [오메가루비·알파사파이어 OST](https://namu.moe/w/포켓몬스터%20오메가루비·알파사파이어/슈퍼%20뮤직%20컬렉션)
- [파이어레드·리프그린 OST](https://namu.moe/w/포켓몬스터%20파이어레드·리프그린/슈퍼%20뮤직%20컬렉션)
- [블랙·화이트 OST](https://namu.moe/w/포켓몬스터%20블랙·화이트/슈퍼%20뮤직%20컬렉션), [블랙 2·화이트 2 OST](https://namu.moe/w/포켓몬스터%20블랙%202·화이트%202/슈퍼%20뮤직%20컬렉션)
- [X·Y OST](https://namu.moe/w/포켓몬스터%20X·Y/슈퍼%20뮤직%20컬렉션), [썬·문 OST](https://namu.moe/w/포켓몬스터%20썬·문/슈퍼%20뮤직%20컬렉션)
- [소드·실드 OST](https://namu.moe/w/포켓몬스터%20소드·실드/슈퍼%20뮤직%20컬렉션), [스칼렛·바이올렛 OST](https://namu.moe/w/포켓몬스터%20스칼렛·바이올렛/슈퍼%20뮤직%20컬렉션)
- [레전즈 아르세우스 OST](https://namu.moe/w/Pokémon%20LEGENDS%20아르세우스/슈퍼%20뮤직%20컬렉션), [레전즈 Z-A OST](https://namu.moe/w/Pokémon%20LEGENDS%20Z-A/OST)
- [다국어 지명표](https://bulbapedia.bulbagarden.net/wiki/List_of_locations_in_other_languages), [영원의숲의 곡명·한국어 지명](https://bulbapedia.bulbagarden.net/wiki/Eterna_Forest)
- [불가사의 던전 탐험대 OST 영문 목록](https://mysterydungeonwiki.com/wiki/Meta:Pokémon_Mystery_Dungeon:_Explorers_of_Time_and_Darkness_Soundtrack)

## 유지보수와 검증

- 곡명 수정은 `trackTitles` 값만 바꿔야 합니다(MUST). ID·OGG 경로·플레이리스트·매핑·음량은 제목 변경의 대상이 아닙니다.
- 새로운 고유명은 영문 원곡명과 한국어 게임명/지명을 대조하는 것을 권장합니다(SHOULD). 확인하지 못했으면 설명명과 확정 곡명을 구분합니다.
- 공식 표시명에서 게임 구분을 넣을 때는 긴 게임명 대신 위 영문 약자를 사용하는 것을 권장합니다(SHOULD). 개인 확장팩의 이름은 `track-titles.json`에서 자유롭게 편집할 수 있습니다(MAY).

회귀 테스트는 실제 공식 팩 생성 → 카탈로그 파싱 → 재생 이벤트 제목 컴파일을 거쳐 112곡 전부에 한글이 있는지, 긴 게임명이 남지 않았는지 확인하고, 주요 오역 위험 항목과 약자를 고정값으로 검사합니다. 테스트가 번역의 사실성을 판정하는 것은 아닙니다. 사실 대조는 위 자료와 입력 태그 비교이며, 테스트는 그 결과가 잘못 되돌아가거나 전달 중 깨지는 것을 검출합니다.
