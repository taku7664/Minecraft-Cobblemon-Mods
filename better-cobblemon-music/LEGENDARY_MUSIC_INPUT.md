# 전설·환상·울트라비스트 음악 전달 목록

## 음악을 주는 방법

가장 편한 방법은 이 모듈의 `music-input/` 폴더에 아래 폴더 구조 그대로 음원을 넣는 것입니다. 폴더 전체를 ZIP으로 묶어 전달할 때도 ZIP을 열자마자 `battle/` 폴더가 보이게 해 주세요.

```text
better-cobblemon-music/music-input/
└─ battle/
   ├─ legendary/
   ├─ ultra_beast/
   └─ wild/
```

- 이미 OGG라면 아래 목표 파일명 그대로 넣어 주세요.
- MP3, WAV, FLAC, M4A만 있다면 같은 목표 이름으로 넣어도 됩니다. 코사원이 Ogg/Vorbis로 변환하면서 확장자를 `.ogg`로 맞춥니다.
- 유튜브 주소 대신 빡대리께서 사용 권한을 가진 실제 음원 파일을 주세요.
- 짧게 잘린 미리듣기가 아니라 처음부터 끝까지 들어 있는 원곡 파일이 필요합니다.
- 파일명을 임의로 번역하거나 대문자로 바꾸지 말아 주세요. 이름이 다르면 자동으로 인식되지 않습니다.
- 음원 파일은 `.gitignore`에 등록되어 있으므로 실수로 Git에 커밋되지 않습니다.

음원을 모두 넣은 뒤 코사원에게 알려 주시면 다음 순서로 처리합니다.

1. 파일별 실제 오디오 형식과 재생 시간을 검사합니다.
2. 필요한 파일만 Ogg/Vorbis로 변환합니다.
3. 개발 클라의 `config/better_cobblemon_music/music/`에 배포합니다.
4. `/bcm reload`와 실제 전투로 선곡을 확인합니다.

## 필요한 파일

총 48개입니다. `교체`는 개발 클라에 같은 이름의 구 파일이 있지만 새 원본을 받아 바꿀 항목이고, `필요`는 현재 목표 이름의 파일이 없는 항목입니다.

### 전설·환상 전용 44개

| 상태 | 목표 파일명 | 적용 대상 |
|---|---|---|
| 필요 | `b2w2_black_white_kyurem_battle.ogg` | 블랙큐레무, 화이트큐레무 |
| 필요 | `bw_kyurem_battle.ogg` | 큐레무 |
| 필요 | `bw_legendary_pokemon_battle.ogg` | 비크티니, 성검사, 토네로스·볼트로스·랜드로스, 케르디오, 메로엣타, 게노세크트 |
| 필요 | `bw_reshiram_battle.ogg` | 레시라무 |
| 필요 | `bw_zekrom_battle.ogg` | 제크로무 |
| 교체 | `dppt_arceus_battle.ogg` | 아르세우스 |
| 교체 | `dppt_azelf_mesprit_uxie_battle.ogg` | 유크시, 엠라이트, 아그놈 |
| 필요 | `dppt_dialga_palkia_battle.ogg` | 디아루가, 펄기아 |
| 교체 | `dppt_giratina_battle.ogg` | 기라티나 |
| 필요 | `dppt_legendary_pokemon_battle.ogg` | 히드런, 레지기가스, 다크라이, 쉐이미 |
| 필요 | `emerald_mew_battle.ogg` | 뮤 |
| 필요 | `frlg_deoxys_battle.ogg` | 테오키스 |
| 필요 | `frlg_legendary_pokemon_battle.ogg` | 관동 삼새 |
| 필요 | `frlg_mewtwo_battle.ogg` | 뮤츠 |
| 필요 | `generic_legendary_battle.ogg` | 우라오스 계열, 자루도 및 미지정 전설 대체곡 |
| 교체 | `hgss_entei_battle.ogg` | 앤테이 |
| 교체 | `hgss_ho-oh_battle.ogg` | 칠색조 |
| 교체 | `hgss_lugia_battle.ogg` | 루기아 |
| 필요 | `hgss_raikou_battle.ogg` | 라이코 |
| 교체 | `hgss_suicune_battle.ogg` | 스이쿤 |
| 필요 | `oras_primal_reversion_battle.ogg` | 원시그란돈, 원시가이오가 |
| 필요 | `pla_origin_dialga_palkia_battle.ogg` | 오리진 디아루가·펄기아 |
| 필요 | `pla_origin_giratina_battle.ogg` | 오리진 기라티나 |
| 교체 | `rse_regirock_regice_registeel_battle.ogg` | 레지락, 레지아이스, 레지스틸 |
| 필요 | `rse_super_ancient_pokemon_battle.ogg` | 그란돈, 가이오가, 레쿠쟈 |
| 필요 | `sm_solgaleo_lunala_necrozma_battle.ogg` | 솔가레오, 루나아라, 네크로즈마 및 알로라 환상 계열 |
| 필요 | `sm_tapu_battle.ogg` | 카푸 4종 |
| 필요 | `sv_koraidon_miraidon_battle.ogg` | 코라이돈, 미라이돈 |
| 필요 | `sv_loyal_three_battle.ogg` | 조타구, 이야후, 기로치 |
| 필요 | `sv_ogerpon_battle.ogg` | 오거폰 |
| 필요 | `sv_pecharunt_battle.ogg` | 복숭악동 |
| 필요 | `sv_stellar_terapagos_battle.ogg` | 스텔라 테라파고스 |
| 필요 | `sv_terapagos_battle.ogg` | 테라파고스 |
| 필요 | `sv_treasures_of_ruin_battle.ogg` | 총지엔, 파오젠, 딩루, 위유이 |
| 필요 | `swsh_calyrex_battle.ogg` | 버드렉스 |
| 필요 | `swsh_eternatus_battle.ogg` | 무한다이노 |
| 필요 | `swsh_galarian_legendary_birds_battle.ogg` | 가라르 삼새 |
| 필요 | `swsh_glastrier_spectrier_battle.ogg` | 블리자포스, 레이스포스 |
| 필요 | `swsh_king_of_bountiful_harvests_battle.ogg` | 백마·흑마 버드렉스 |
| 필요 | `swsh_legendary_giants_battle.ogg` | 레지에레키, 레지드래고 |
| 필요 | `swsh_zacian_zamazenta_battle.ogg` | 자시안, 자마젠타 |
| 필요 | `usum_dusk_mane_dawn_wings_necrozma_battle.ogg` | 황혼의 갈기·새벽의 날개 네크로즈마 |
| 필요 | `usum_ultra_necrozma_battle.ogg` | 울트라네크로즈마 |
| 필요 | `xy_xerneas_yveltal_zygarde_battle.ogg` | 제르네아스, 이벨타르, 지가르데 및 칼로스 환상 계열 |

위 44개 파일은 모두 `music-input/battle/legendary/`에 넣습니다.

### 울트라비스트 1개

| 상태 | 목표 파일명 | 적용 대상 |
|---|---|---|
| 필요 | `sm_ultra_beast_battle.ogg` | 울트라비스트 전체 |

이 파일은 `music-input/battle/ultra_beast/`에 넣습니다. 현재 있는 `alola_ultra_beast_battle.ogg`는 구 파일명이므로 재사용하지 않습니다.

### 야생 전투곡을 사용하는 전설·환상 3개

| 상태 | 목표 파일명 | 적용 대상 |
|---|---|---|
| 필요 | `hoenn_wild_pokemon_battle.ogg` | 라티아스, 라티오스, 지라치 |
| 교체 | `johto_wild_pokemon_battle.ogg` | 세레비 |
| 필요 | `sinnoh_wild_pokemon_battle.ogg` | 크레세리아, 마나피, 피오네 |

이 3개 파일은 `music-input/battle/wild/`에 넣습니다.

## 완성 폴더 트리

```text
music-input/
└─ battle/
   ├─ legendary/
   │  ├─ b2w2_black_white_kyurem_battle.ogg
   │  ├─ bw_kyurem_battle.ogg
   │  ├─ bw_legendary_pokemon_battle.ogg
   │  ├─ bw_reshiram_battle.ogg
   │  ├─ bw_zekrom_battle.ogg
   │  ├─ dppt_arceus_battle.ogg
   │  ├─ dppt_azelf_mesprit_uxie_battle.ogg
   │  ├─ dppt_dialga_palkia_battle.ogg
   │  ├─ dppt_giratina_battle.ogg
   │  ├─ dppt_legendary_pokemon_battle.ogg
   │  ├─ emerald_mew_battle.ogg
   │  ├─ frlg_deoxys_battle.ogg
   │  ├─ frlg_legendary_pokemon_battle.ogg
   │  ├─ frlg_mewtwo_battle.ogg
   │  ├─ generic_legendary_battle.ogg
   │  ├─ hgss_entei_battle.ogg
   │  ├─ hgss_ho-oh_battle.ogg
   │  ├─ hgss_lugia_battle.ogg
   │  ├─ hgss_raikou_battle.ogg
   │  ├─ hgss_suicune_battle.ogg
   │  ├─ oras_primal_reversion_battle.ogg
   │  ├─ pla_origin_dialga_palkia_battle.ogg
   │  ├─ pla_origin_giratina_battle.ogg
   │  ├─ rse_regirock_regice_registeel_battle.ogg
   │  ├─ rse_super_ancient_pokemon_battle.ogg
   │  ├─ sm_solgaleo_lunala_necrozma_battle.ogg
   │  ├─ sm_tapu_battle.ogg
   │  ├─ sv_koraidon_miraidon_battle.ogg
   │  ├─ sv_loyal_three_battle.ogg
   │  ├─ sv_ogerpon_battle.ogg
   │  ├─ sv_pecharunt_battle.ogg
   │  ├─ sv_stellar_terapagos_battle.ogg
   │  ├─ sv_terapagos_battle.ogg
   │  ├─ sv_treasures_of_ruin_battle.ogg
   │  ├─ swsh_calyrex_battle.ogg
   │  ├─ swsh_eternatus_battle.ogg
   │  ├─ swsh_galarian_legendary_birds_battle.ogg
   │  ├─ swsh_glastrier_spectrier_battle.ogg
   │  ├─ swsh_king_of_bountiful_harvests_battle.ogg
   │  ├─ swsh_legendary_giants_battle.ogg
   │  ├─ swsh_zacian_zamazenta_battle.ogg
   │  ├─ usum_dusk_mane_dawn_wings_necrozma_battle.ogg
   │  ├─ usum_ultra_necrozma_battle.ogg
   │  └─ xy_xerneas_yveltal_zygarde_battle.ogg
   ├─ ultra_beast/
   │  └─ sm_ultra_beast_battle.ogg
   └─ wild/
      ├─ hoenn_wild_pokemon_battle.ogg
      ├─ johto_wild_pokemon_battle.ogg
      └─ sinnoh_wild_pokemon_battle.ogg
```

이 목록의 경로는 `src/main/resources/assets/better_cobblemon_music/config_defaults/music.json`의 현재 기본 선곡 규칙과 일치해야 합니다. 선곡 규칙이 바뀌면 이 문서도 함께 갱신해야 합니다.
