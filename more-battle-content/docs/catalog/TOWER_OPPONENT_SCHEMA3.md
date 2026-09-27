# 배틀타워 상대 카탈로그 스키마 3·72세트 쓰기 계획

> **2026-09-27 정리 메모:** 기믹별 필드 규칙(1절)은 유효하지만 18프로필·72세트 수치와 `mbc_core.json` 경로는 현재 데이터가 아니다. 현재 타워 데이터는 트레이너 120명·포켓몬 세트 스키마 4이며, 이를 정의한 결정 문서는 아직 없다. 상세는 [인덱스의 불일치 표](../README.md#현재-코드와-다른-조항)를 본다.

| 항목 | 값 |
|---|---|
| Status | `shared` |
| Effective | 2026-08-18 |
| Updates | [`TOWER_OPPONENT_DATA.md`](TOWER_OPPONENT_DATA.md)의 실제 기믹별 데이터 게이트 |
| 작성일 | 2026-08-18 |
| 주 독자 | 빡대리님과 카탈로그 구현 담당자 |
| 적용 대상 | 내장 `mbc_core.json`과 한·영 트레이너 이름 |

빡대리님이 2026-08-18에 아래 전체 값을 승인했다. 내장 `mbc_core.json`과 한·영 언어 파일은 이 문서의 스키마 3·18프로필·72세트 계약을 적용한다.

## 1. 스키마 3 형식

스키마 3 세트는 기존 필드에 다음 필드를 조건부로 추가한다.

```json
{
  "schema_version": 3,
  "sets": [
    {
      "set_id": "tera_single_low_meowscarada",
      "set_tier": 1,
      "species_id": "cobblemon:meowscarada",
      "form_id": null,
      "ability_id": "cobblemon:protean",
      "nature_id": "cobblemon:jolly",
      "held_item_id": "cobblemon:choice_band",
      "tera_type": "grass",
      "moves": ["cobblemon:flowertrick", "cobblemon:knockoff", "cobblemon:playrough", "cobblemon:uturn"],
      "ivs": {"hp": 15, "attack": 15, "defense": 15, "special_attack": 15, "special_defense": 15, "speed": 15},
      "evs": {"hp": 0, "attack": 0, "defense": 0, "special_attack": 0, "special_defense": 0, "speed": 0}
    }
  ]
}
```

- `TERA` 프로필의 모든 세트는 표준 18타입 중 하나인 `tera_type`을 MUST로 가진다.
- `DYNAMAX` 프로필의 모든 세트는 `dmax_level: 10`과 Boolean `gmax_factor`를 MUST로 가진다.
- `gmax_factor: true`는 원작에서 거다이맥스가 가능하고 Mega Showdown `1.9.3+1.7.3+1.21.1` 로컬 JAR에서 전용 자산이 확인된 종에만 쓴다.
- `MEGA` 프로필 세트는 위 세 필드를 쓰지 않고 해당 종의 정확한 메가스톤을 지닌다.
- 서로 다른 기믹 속성을 한 세트에 섞지 않는다.
- 72세트 모두 `form_id: null`을 사용한다. 특정 폼 전용 기술을 기본 폼에 우회 적용하지 않는다.

## 2. 공통 능력치 규칙

| 풀 | `set_tier` | IV | EV |
|---|---:|---|---|
| `*_low_*` | 1 | 여섯 능력치 모두 15 | 여섯 능력치 모두 0 |
| `*_high_*` | 2 | 여섯 능력치 모두 20 | 아래 표의 `EV 252` 능력치 하나에 252, 나머지 0 |

상위 풀을 랭크 3 티어 보스도 재사용한다. 보스의 차이는 별도 복제 세트나 숨은 보정이 아니라 `ai_skill: 4`와 보스 진행 규칙에서 만든다.

## 3. 프로필 18개

표의 `이름`은 `en_us / ko_kr` 순서다. 표시 키는 모두 `trainer.cobblemon_more_battle_content.<profile_id>`다.

| profile_id | 이름 | rank_ids | format | kind | mechanic | weight | ai_skill | theme | set_pool |
|---|---|---|---|---|---|---:|---:|---|---|
| `mega_single_regular_low` | Keystone Initiate / 키스톤 입문자 | 1, 2 | single | regular | mega | 40 | 1 | `mega_single_low` | `mega_single_low_*` 6개 |
| `mega_single_regular_high` | Mega Vanguard / 메가 선봉장 | 2, 3 | single | regular | mega | 100 | 2 | `mega_single_high` | `mega_single_high_*` 6개 |
| `mega_single_tier_boss` | Mega Grandmaster / 메가 그랜드마스터 | 3 | single | tier_boss | mega | 100 | 4 | `mega_single_boss` | `mega_single_high_*` 6개 재사용 |
| `mega_double_regular_low` | Bond Coordinator / 유대 조율사 | 1, 2 | double | regular | mega | 40 | 1 | `mega_double_low` | `mega_double_low_*` 6개 |
| `mega_double_regular_high` | Mega Synergist / 메가 연계가 | 2, 3 | double | regular | mega | 100 | 2 | `mega_double_high` | `mega_double_high_*` 6개 |
| `mega_double_tier_boss` | Mega Duo Master / 메가 더블 마스터 | 3 | double | tier_boss | mega | 100 | 4 | `mega_double_boss` | `mega_double_high_*` 6개 재사용 |
| `dynamax_single_regular_low` | Power Spot Challenger / 파워스폿 도전자 | 1, 2 | single | regular | dynamax | 40 | 1 | `dynamax_single_low` | `dynamax_single_low_*` 6개 |
| `dynamax_single_regular_high` | Dynamax Ace / 다이맥스 에이스 | 2, 3 | single | regular | dynamax | 100 | 2 | `dynamax_single_high` | `dynamax_single_high_*` 6개 |
| `dynamax_single_tier_boss` | Max Master / 맥스 마스터 | 3 | single | tier_boss | dynamax | 100 | 4 | `dynamax_single_boss` | `dynamax_single_high_*` 6개 재사용 |
| `dynamax_double_regular_low` | Max Team Builder / 맥스 팀 설계자 | 1, 2 | double | regular | dynamax | 40 | 1 | `dynamax_double_low` | `dynamax_double_low_*` 6개 |
| `dynamax_double_regular_high` | Dynamax Commander / 다이맥스 지휘관 | 2, 3 | double | regular | dynamax | 100 | 2 | `dynamax_double_high` | `dynamax_double_high_*` 6개 |
| `dynamax_double_tier_boss` | Galar Tower Monarch / 가라르 타워 군주 | 3 | double | tier_boss | dynamax | 100 | 4 | `dynamax_double_boss` | `dynamax_double_high_*` 6개 재사용 |
| `tera_single_regular_low` | Tera Apprentice / 테라 입문자 | 1, 2 | single | regular | tera | 40 | 1 | `tera_single_low` | `tera_single_low_*` 6개 |
| `tera_single_regular_high` | Tera Specialist / 테라 전문가 | 2, 3 | single | regular | tera | 100 | 2 | `tera_single_high` | `tera_single_high_*` 6개 |
| `tera_single_tier_boss` | Paldea Top Champion / 팔데아 최상위 챔피언 | 3 | single | tier_boss | tera | 100 | 4 | `tera_single_boss` | `tera_single_high_*` 6개 재사용 |
| `tera_double_regular_low` | Crystal Tactician / 결정 전술가 | 1, 2 | double | regular | tera | 40 | 1 | `tera_double_low` | `tera_double_low_*` 6개 |
| `tera_double_regular_high` | Tera Field Director / 테라 필드 지휘자 | 2, 3 | double | regular | tera | 100 | 2 | `tera_double_high` | `tera_double_high_*` 6개 |
| `tera_double_tier_boss` | Tera Crown Master / 테라 크라운 마스터 | 3 | double | tier_boss | tera | 100 | 4 | `tera_double_boss` | `tera_double_high_*` 6개 재사용 |

## 4. 메가진화 세트 24개

모든 ID는 표 안에서 생략한 네임스페이스를 붙여 저장한다. 종·특성·성격·기술은 `cobblemon:`, 도구는 `mega_showdown:`이다. `EV 252`가 `-`인 행은 하위 풀이다.

| set_id | 종 | 특성 | 성격 | 메가스톤 | 기술 4개 | EV 252 |
|---|---|---|---|---|---|---|
| `mega_single_low_absol` | absol | superluck | jolly | absolite | knockoff, suckerpunch, playrough, swordsdance | - |
| `mega_single_low_ampharos` | ampharos | static | modest | ampharosite | thunderbolt, dragonpulse, focusblast, voltswitch | - |
| `mega_single_low_banette` | banette | frisk | adamant | banettite | shadowclaw, suckerpunch, willowisp, knockoff | - |
| `mega_single_low_abomasnow` | abomasnow | snowwarning | quiet | abomasite | blizzard, gigadrain, earthpower, iceshard | - |
| `mega_single_low_audino` | audino | regenerator | bold | audinite | dazzlinggleam, wish, protect, healpulse | - |
| `mega_single_low_pidgeot` | pidgeot | keeneye | timid | pidgeotite | hurricane, heatwave, roost, uturn | - |
| `mega_single_high_garchomp` | garchomp | roughskin | jolly | garchompite | earthquake, dragonclaw, stoneedge, swordsdance | attack |
| `mega_single_high_gengar` | gengar | cursedbody | timid | gengarite | shadowball, sludgebomb, focusblast, destinybond | special_attack |
| `mega_single_high_metagross` | metagross | clearbody | adamant | metagrossite | meteormash, zenheadbutt, earthquake, bulletpunch | attack |
| `mega_single_high_salamence` | salamence | intimidate | jolly | salamencite | doubleedge, earthquake, dragondance, roost | attack |
| `mega_single_high_tyranitar` | tyranitar | sandstream | adamant | tyranitarite | stoneedge, crunch, earthquake, dragondance | attack |
| `mega_single_high_scizor` | scizor | technician | adamant | scizorite | bulletpunch, uturn, closecombat, swordsdance | attack |
| `mega_double_low_manectric` | manectric | lightningrod | timid | manectite | thunderbolt, voltswitch, snarl, protect | - |
| `mega_double_low_altaria` | altaria | naturalcure | modest | altarianite | hypervoice, moonblast, tailwind, protect | - |
| `mega_double_low_camerupt` | camerupt | solidrock | quiet | cameruptite | heatwave, earthpower, rockslide, protect | - |
| `mega_double_low_gallade` | gallade | justified | jolly | galladite | closecombat, psychocut, wideguard, protect | - |
| `mega_double_low_slowbro` | slowbro | oblivious | quiet | slowbronite | scald, psychic, trickroom, protect | - |
| `mega_double_low_kangaskhan` | kangaskhan | scrappy | jolly | kangaskhanite | fakeout, doubleedge, suckerpunch, protect | - |
| `mega_double_high_charizard` | charizard | blaze | timid | charizardite_y | heatwave, airslash, solarbeam, protect | special_attack |
| `mega_double_high_venusaur` | venusaur | chlorophyll | modest | venusaurite | gigadrain, sludgebomb, sleeppowder, protect | special_attack |
| `mega_double_high_blastoise` | blastoise | torrent | modest | blastoisinite | waterpulse, icebeam, darkpulse, protect | special_attack |
| `mega_double_high_mawile` | mawile | intimidate | adamant | mawilite | playrough, ironhead, suckerpunch, protect | attack |
| `mega_double_high_lucario` | lucario | innerfocus | jolly | lucarionite | closecombat, meteormash, extremespeed, protect | attack |
| `mega_double_high_aerodactyl` | aerodactyl | pressure | jolly | aerodactylite | rockslide, aerialace, tailwind, protect | attack |

## 5. 다이맥스 세트 24개

종·특성·성격·도구·기술은 모두 `cobblemon:` 네임스페이스다. 모든 행은 `dmax_level: 10`을 사용한다.

| set_id | 종 | 특성 | 성격 | 도구 | 기술 4개 | gmax | EV 252 |
|---|---|---|---|---|---|---|---|
| `dynamax_single_low_corviknight` | corviknight | mirrorarmor | adamant | sharp_beak | bravebird, ironhead, bodypress, roost | true | - |
| `dynamax_single_low_drednaw` | drednaw | strongjaw | adamant | mystic_water | liquidation, crunch, rockslide, swordsdance | true | - |
| `dynamax_single_low_orbeetle` | orbeetle | frisk | modest | wise_glasses | psychic, bugbuzz, energyball, calmmind | true | - |
| `dynamax_single_low_sandaconda` | sandaconda | shedskin | impish | soft_sand | earthquake, rockslide, glare, coil | true | - |
| `dynamax_single_low_toxtricity` | toxtricity | punkrock | modest | throat_spray | overdrive, sludgebomb, boomburst, voltswitch | true | - |
| `dynamax_single_low_alcremie` | alcremie | sweetveil | calm | leftovers | dazzlinggleam, mysticalfire, energyball, recover | true | - |
| `dynamax_single_high_dragapult` | dragapult | infiltrator | timid | life_orb | dracometeor, shadowball, flamethrower, uturn | false | special_attack |
| `dynamax_single_high_excadrill` | excadrill | moldbreaker | jolly | focus_sash | earthquake, ironhead, rockslide, swordsdance | false | attack |
| `dynamax_single_high_mimikyu` | mimikyu | disguise | adamant | spell_tag | playrough, shadowclaw, shadowsneak, swordsdance | false | attack |
| `dynamax_single_high_hydreigon` | hydreigon | levitate | modest | choice_specs | dracometeor, darkpulse, flashcannon, flamethrower | false | special_attack |
| `dynamax_single_high_togekiss` | togekiss | serenegrace | timid | leftovers | airslash, dazzlinggleam, thunderwave, roost | false | special_attack |
| `dynamax_single_high_conkeldurr` | conkeldurr | guts | adamant | assault_vest | drainpunch, machpunch, knockoff, icepunch | false | attack |
| `dynamax_double_low_pelipper` | pelipper | drizzle | timid | damp_rock | scald, hurricane, tailwind, protect | false | - |
| `dynamax_double_low_ludicolo` | ludicolo | swiftswim | modest | life_orb | muddywater, gigadrain, icebeam, fakeout | false | - |
| `dynamax_double_low_arcanine` | arcanine | intimidate | careful | sitrus_berry | heatwave, snarl, willowisp, protect | false | - |
| `dynamax_double_low_gastrodon` | gastrodon | stormdrain | modest | leftovers | muddywater, earthpower, icebeam, protect | false | - |
| `dynamax_double_low_raichu` | raichu | lightningrod | timid | focus_sash | thunderbolt, nuzzle, fakeout, protect | false | - |
| `dynamax_double_low_ferrothorn` | ferrothorn | ironbarbs | relaxed | rocky_helmet | powerwhip, gyroball, leechseed, protect | false | - |
| `dynamax_double_high_coalossal` | coalossal | steamengine | modest | weakness_policy | heatwave, powergem, solarbeam, protect | true | special_attack |
| `dynamax_double_high_rillaboom` | rillaboom | grassysurge | adamant | miracle_seed | grassyglide, woodhammer, fakeout, highhorsepower | true | attack |
| `dynamax_double_high_indeedee` | indeedee | psychicsurge | bold | terrain_extender | psychic, allyswitch, helpinghand, protect | false | hp |
| `dynamax_double_high_duraludon` | duraludon | stalwart | modest | assault_vest | dragonpulse, flashcannon, thunderbolt, snarl | true | special_attack |
| `dynamax_double_high_copperajah` | copperajah | sheerforce | adamant | life_orb | ironhead, highhorsepower, rockslide, powerwhip | true | attack |
| `dynamax_double_high_snorlax` | snorlax | gluttony | careful | figy_berry | facade, darkestlariat, highhorsepower, protect | true | hp |

## 6. 테라스탈 세트 24개

종·특성·성격·도구·기술은 모두 `cobblemon:` 네임스페이스다.

| set_id | 종 | 특성 | 성격 | 도구 | 기술 4개 | tera_type | EV 252 |
|---|---|---|---|---|---|---|---|
| `tera_single_low_meowscarada` | meowscarada | protean | jolly | choice_band | flowertrick, knockoff, playrough, uturn | grass | - |
| `tera_single_low_ceruledge` | ceruledge | flashfire | adamant | focus_sash | bitterblade, shadowclaw, closecombat, swordsdance | fire | - |
| `tera_single_low_clodsire` | clodsire | unaware | careful | leftovers | earthquake, poisonjab, recover, toxic | water | - |
| `tera_single_low_kilowattrel` | kilowattrel | competitive | timid | choice_specs | thunderbolt, hurricane, voltswitch, airslash | electric | - |
| `tera_single_low_baxcalibur` | baxcalibur | thermalexchange | adamant | loaded_dice | iciclespear, glaiverush, earthquake, dragondance | dragon | - |
| `tera_single_low_tinkaton` | tinkaton | moldbreaker | adamant | assault_vest | gigatonhammer, playrough, knockoff, fakeout | fairy | - |
| `tera_single_high_kingambit` | kingambit | supremeoverlord | adamant | black_glasses | kowtowcleave, ironhead, suckerpunch, swordsdance | dark | attack |
| `tera_single_high_gholdengo` | gholdengo | goodasgold | timid | air_balloon | makeitrain, shadowball, focusblast, nastyplot | steel | special_attack |
| `tera_single_high_dragonite` | dragonite | multiscale | adamant | weakness_policy | extremespeed, dragonclaw, firepunch, dragondance | normal | attack |
| `tera_single_high_volcarona` | volcarona | flamebody | timid | heavy_duty_boots | fierydance, bugbuzz, gigadrain, quiverdance | grass | special_attack |
| `tera_single_high_garganacl` | garganacl | purifyingsalt | careful | leftovers | saltcure, bodypress, recover, irondefense | ghost | hp |
| `tera_single_high_azumarill` | azumarill | hugepower | adamant | sitrus_berry | aquajet, playrough, liquidation, bellydrum | water | attack |
| `tera_double_low_murkrow` | murkrow | prankster | careful | eviolite | foulplay, tailwind, taunt, quash | ghost | - |
| `tera_double_low_garchomp` | garchomp | roughskin | jolly | clear_amulet | earthquake, dragonclaw, rockslide, protect | ground | - |
| `tera_double_low_armarouge` | armarouge | flashfire | modest | weakness_policy | armorcannon, expandingforce, wideguard, protect | grass | - |
| `tera_double_low_farigiraf` | farigiraf | armortail | quiet | safety_goggles | psychic, hypervoice, trickroom, helpinghand | fairy | - |
| `tera_double_low_amoonguss` | amoonguss | regenerator | calm | rocky_helmet | ragepowder, spore, pollenpuff, protect | water | - |
| `tera_double_low_sylveon` | sylveon | pixilate | modest | throat_spray | hypervoice, dazzlinggleam, helpinghand, protect | fire | - |
| `tera_double_high_fluttermane` | fluttermane | protosynthesis | timid | choice_specs | moonblast, shadowball, dazzlinggleam, powergem | fairy | special_attack |
| `tera_double_high_ironhands` | ironhands | quarkdrive | adamant | assault_vest | fakeout, wildcharge, drainpunch, heavyslam | grass | attack |
| `tera_double_high_incineroar` | incineroar | intimidate | careful | sitrus_berry | fakeout, flareblitz, knockoff, partingshot | ghost | hp |
| `tera_double_high_rillaboom` | rillaboom | grassysurge | adamant | miracle_seed | grassyglide, woodhammer, fakeout, protect | fire | attack |
| `tera_double_high_gholdengo` | gholdengo | goodasgold | modest | metal_coat | makeitrain, shadowball, nastyplot, protect | steel | special_attack |
| `tera_double_high_dragonite` | dragonite | innerfocus | adamant | lum_berry | extremespeed, icespinner, stompingtantrum, protect | normal | attack |

## 7. 적용 검증 게이트

실제 JSON과 언어 파일은 다음을 모두 통과해야 한다.

1. 스키마 3 로더가 18개 프로필과 72개 고유 세트를 읽어야 한다.
2. 기믹·형식·랭크·상대 종류별 조회 결과가 위 표와 일치해야 한다.
3. 각 6세트 풀은 종족과 지닌도구가 중복되지 않고 싱글 3마리·더블 4마리 합법 팀을 만들 수 있어야 한다.
4. 모든 종·특성·기술·Cobblemon 도구는 로컬 Cobblemon `1.7.3` 데이터에 존재해야 한다.
5. 모든 메가스톤과 `gmax_factor: true` 대상 자산은 로컬 Mega Showdown `1.9.3+1.7.3+1.21.1` JAR에 존재해야 한다.
6. 본체 전체 단위 테스트, Better AI 단위 테스트, clean build, JDK 21 JAR 검증을 각각 수행해야 한다.
7. 서버 배치와 실제 게임 검증은 별도 승인·별도 상태로 남긴다.

2026-08-18 적용 검증에서 프로필 18개, 고유 세트 72개, 프로필당 6세트, 풀별 종·지닌도구 중복 0건을 확인했다. 로컬 Cobblemon `1.7.3` JAR의 종·특성·성격·기술·도구와 Mega Showdown `1.9.3+1.7.3+1.21.1` JAR의 메가스톤·거다이맥스 11종 모델 대조도 누락 0건으로 통과했다. 본체 205개와 Better AI 13개, 합계 218개 테스트 및 clean build를 통과했으며 최종 JAR 안에도 스키마 3·18프로필·72세트가 포함됐다. 실제 게임 런타임은 검증하지 않았다.

## 8. 이번 계획에서 버린 대안

- `Centiskorch`, `Hatterene`, `Grimmsnarl`, `Lapras`의 `gmax_factor: true`: 원작에서는 가능하지만 현재 필수 Mega Showdown JAR에서 해당 전용 자산을 확인하지 못해 제외했다.
- 암컷 Indeedee의 `Follow Me`: 암컷 폼 전용 기술인데 현재 카탈로그의 `form_id` 계약과 기본 폼 생성을 섞어 우회하지 않기 위해 기본 폼이 배우는 `Ally Switch`를 사용한다.
- 세트마다 서로 다른 임의 IV·EV: 초기 밸런스 원인을 추적하기 어렵게 만들므로 기존 승인 카탈로그의 하위 15/0, 상위 20/252 단계를 유지한다.
- 보스 전용 36세트 추가: 보스는 상위 풀 재사용으로 확정했으므로 72세트 밖의 숨은 중복 데이터를 만들지 않는다.
