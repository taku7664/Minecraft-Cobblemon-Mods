# MCC 배틀팩토리·배틀타워 미진화체 전수 검사

이 문서는 수정 전 검사 기록입니다. 현재 수정 결과는 [2026-10-13 후속 기록](EVOLUTION_FIX_2026-10-13.md)을 참조하세요. 원본 소스 링크는 검사 당시 커밋에 고정했습니다.

검사·기록 시각: 2026-10-12 23:40 KST. 검사한 소스: `715e9b6e36baa58f59930a1185830aaaf17b543f`.

## 결론과 판단 경계

배틀팩토리와 배틀타워 양쪽에 **진화가 더 가능한 포켓몬**이 있습니다. 코드로 의도적인 예외가 확인된 세트를 제외하면 팩토리 11종·폼 44세트, 타워 5종 30세트입니다. 팩토리의 스라크·발바로 8세트는 진화 전 자체를 채용할 전투상 이유가 있어 별도 분류했습니다. 이를 제외한 **팩토리 9종·폼 36세트와 타워 5종 30세트가 수정 검토 대상**입니다.

관찰한 사실은 진화 가능 여부와 현재 세트·도구·선택 경로입니다. 명시적 예외 근거가 없다는 사실만으로 제작자의 의도까지 증명할 수는 없습니다. 아래의 수정 검토 대상은 누락 가능성이 있는 목록이며, 전부 채용 오류라고 확정한 목록은 아닙니다.

## 전수 검사 범위와 방법

| 항목 | 배틀팩토리 | 배틀타워 |
|---|---:|---:|
| 세트 JSON 파일 | 416 | 260 |
| 전체 세트 | 2,004 | 1,931 |
| 세트에 사용된 종·폼 | 501 | 313 |
| 트레이너 정의 | 162 | 131 |
| 진화 가능한 종·폼 / 세트 | 14 / 56 | 7 / 40 |
| 코드로 확인된 의도적 예외 | 3종 / 12세트 | 2종 / 10세트 |
| 명시적 예외 근거 없는 세트 | 11종·폼 / 44세트 | 5종 / 30세트 |
| 채용 의도 확인을 유보한 스라크·발바로 | 2종 / 8세트 | 0 |
| 수정 검토 대상 | 9종·폼 / 36세트 | 5종 / 30세트 |

각 세트의 `species_id`와 `form_id`를 Cobblemon 1.8.1의 종 데이터 1,025개에 대조했습니다. 폼 자체의 `evolutions`가 있으면 그 값을 사용하고, 없으면 기본 종의 값을 사용했습니다. Cobblemon에 내장된 Showdown 종 도감의 `evos`와 독립 대조한 결과, 종·폼을 찾지 못한 세트와 진화 여부 판정 불일치는 각각 0건이었습니다. 단순히 이름이나 세대만 보고 추정하지 않았습니다.

개발 서버에 설치된 전체 모드 JAR도 확인했습니다. 두 풀에 쓰인 종의 합집합 442종에 관련된 `species`·`species_additions` 93리소스는 모두 MegaShowdown에 있었고, 사용된 폼의 진화 목록은 기본 Cobblemon과 차이가 없었습니다. 피카츄·두랄루돈을 덮는 종 데이터도 진화 목록이 같았습니다. 로컬 운영 서버는 후보종에 관련된 같은 MegaShowdown 파일을 확인했으며, 전체 93건 전수 대조는 개발 설치본에 한정합니다.

타워는 트레이너 131개뿐 아니라 대진(`encounters`) 48개와 풀(`pools`) 12개를 따라 2,568개의 트레이너·대진 조합을 해석했습니다. 고정 로스터가 있는 경우와 풀에서 뽑는 경우를 구분했습니다. 이는 정적 데이터의 선택 가능 경로 확인이며, 게임에서 실제로 뽑힌 포켓몬을 관찰한 검증은 아닙니다.

## 의도적인 미진화 예외

[팩토리 카탈로그 생성기](https://github.com/taku7664/Minecraft-Cobblemon-Mods/blob/715e9b6e36baa58f59930a1185830aaaf17b543f/works/mods/more-cobblemon-contents/tools/generate_factory_catalog.mjs)의 `fixedItem`은 럭키·폴리곤2에 진화의휘석(`eviolite`), 피카츄에 전기구슬(`light_ball`)을 명시적으로 고정합니다. 해당 세트의 실제 도구도 일치합니다.

| 종 | 근거 | 팩토리 | 타워 |
|---|---|---:|---:|
| 럭키 | 진화의휘석 고정 | 4 | 8 |
| 폴리곤2 | 진화의휘석 고정 | 4 | 0 |
| 피카츄 | 전기구슬 고정 | 4 | 2 |

이 22세트는 이번 수정 검토 대상에서 제외했습니다. 다른 미진화 후보에는 진화의휘석이 없었습니다.

## 수정 검토 대상 전체 목록

진화 후 이름은 Cobblemon 1.8.1의 한국어 언어 데이터에서 확인했습니다. 침바루는 기본 폼이 아니라 **히스이 폼**입니다.

| 현재 종·폼 | 진화 후 | 팩토리 세트 | 타워 세트 |
|---|---|---:|---:|
| 과미르 | 과미드라 | 4 | 8 |
| 노고치 | 노고고치 | 4 | 6 |
| 두랄루돈 | 브리두라스 | 4 | 6 |
| 키링키 | 키키링 | 4 | 8 |
| 토쇠골 | 노보청 | 4 | 0 |
| 무우마 | 무우마직 | 4 | 2 |
| 침바루(히스이) | 장침바루 | 4 | 0 |
| 코뿌리 | 거대코뿌리 | 4 | 0 |
| 링곰 | 다투곰 | 4 | 0 |
| **합계** | | **36** | **30** |

### 팩토리 세트 ID와 파일

아래의 `{1,2,3,4}`는 네 개의 정확한 ID를 줄여 적은 표기입니다. 예를 들어 `dipplin_preset_{1,2,3,4}`는 `dipplin_preset_1`부터 `dipplin_preset_4`까지입니다.

| 세트 ID | 파일 |
|---|---|
| `dipplin_preset_{1,2,3,4}` | [cobblemon_dipplin.json](https://github.com/taku7664/Minecraft-Cobblemon-Mods/blob/715e9b6e36baa58f59930a1185830aaaf17b543f/works/mods/more-cobblemon-contents-battle-factory/src/main/resources/data/more_cobblemon_contents/mcc-battle-factory/rental-sets/cobblemon_dipplin.json) |
| `dunsparce_preset_{1,2,3,4}` | [cobblemon_dunsparce.json](https://github.com/taku7664/Minecraft-Cobblemon-Mods/blob/715e9b6e36baa58f59930a1185830aaaf17b543f/works/mods/more-cobblemon-contents-battle-factory/src/main/resources/data/more_cobblemon_contents/mcc-battle-factory/rental-sets/cobblemon_dunsparce.json) |
| `duraludon_preset_{1,2,3,4}` | [cobblemon_duraludon.json](https://github.com/taku7664/Minecraft-Cobblemon-Mods/blob/715e9b6e36baa58f59930a1185830aaaf17b543f/works/mods/more-cobblemon-contents-battle-factory/src/main/resources/data/more_cobblemon_contents/mcc-battle-factory/rental-sets/cobblemon_duraludon.json) |
| `girafarig_preset_{1,2,3,4}` | [cobblemon_girafarig.json](https://github.com/taku7664/Minecraft-Cobblemon-Mods/blob/715e9b6e36baa58f59930a1185830aaaf17b543f/works/mods/more-cobblemon-contents-battle-factory/src/main/resources/data/more_cobblemon_contents/mcc-battle-factory/rental-sets/cobblemon_girafarig.json) |
| `gurdurr_preset_{1,2,3,4}` | [cobblemon_gurdurr.json](https://github.com/taku7664/Minecraft-Cobblemon-Mods/blob/715e9b6e36baa58f59930a1185830aaaf17b543f/works/mods/more-cobblemon-contents-battle-factory/src/main/resources/data/more_cobblemon_contents/mcc-battle-factory/rental-sets/cobblemon_gurdurr.json) |
| `misdreavus_preset_{1,2,3,4}` | [cobblemon_misdreavus.json](https://github.com/taku7664/Minecraft-Cobblemon-Mods/blob/715e9b6e36baa58f59930a1185830aaaf17b543f/works/mods/more-cobblemon-contents-battle-factory/src/main/resources/data/more_cobblemon_contents/mcc-battle-factory/rental-sets/cobblemon_misdreavus.json) |
| `qwilfishhisui_preset_{1,2,3,4}` | [cobblemon_qwilfish.json](https://github.com/taku7664/Minecraft-Cobblemon-Mods/blob/715e9b6e36baa58f59930a1185830aaaf17b543f/works/mods/more-cobblemon-contents-battle-factory/src/main/resources/data/more_cobblemon_contents/mcc-battle-factory/rental-sets/cobblemon_qwilfish.json) |
| `rhydon_preset_{1,2,3,4}` | [cobblemon_rhydon.json](https://github.com/taku7664/Minecraft-Cobblemon-Mods/blob/715e9b6e36baa58f59930a1185830aaaf17b543f/works/mods/more-cobblemon-contents-battle-factory/src/main/resources/data/more_cobblemon_contents/mcc-battle-factory/rental-sets/cobblemon_rhydon.json) |
| `ursaring_preset_{1,2,3,4}` | [cobblemon_ursaring.json](https://github.com/taku7664/Minecraft-Cobblemon-Mods/blob/715e9b6e36baa58f59930a1185830aaaf17b543f/works/mods/more-cobblemon-contents-battle-factory/src/main/resources/data/more_cobblemon_contents/mcc-battle-factory/rental-sets/cobblemon_ursaring.json) |

### 타워 세트 ID와 파일

각 행은 기믹과 티어의 모든 조합입니다. 예를 들어 과미르는 `generated_dynamax_tier1_dipplin`부터 `generated_dynamax_tier4_dipplin`, `generated_tera_tier1_dipplin`부터 `generated_tera_tier4_dipplin`까지 8세트입니다.

| ID 형식 | 기믹 | 티어 | 파일 |
|---|---|---|---|
| `generated_<기믹>_tier<티어>_dipplin` | `dynamax`, `tera` | 1, 2, 3, 4 | [cobblemon_dipplin.json](https://github.com/taku7664/Minecraft-Cobblemon-Mods/blob/715e9b6e36baa58f59930a1185830aaaf17b543f/works/mods/more-cobblemon-contents-battle-tower/src/main/resources/data/more_cobblemon_contents/mcc-battle-tower/pokemon-sets/cobblemon_dipplin.json) |
| `generated_<기믹>_tier<티어>_dunsparce` | `dynamax`, `tera` | 2, 3, 4 | [cobblemon_dunsparce.json](https://github.com/taku7664/Minecraft-Cobblemon-Mods/blob/715e9b6e36baa58f59930a1185830aaaf17b543f/works/mods/more-cobblemon-contents-battle-tower/src/main/resources/data/more_cobblemon_contents/mcc-battle-tower/pokemon-sets/cobblemon_dunsparce.json) |
| `generated_<기믹>_tier<티어>_duraludon` | `dynamax`, `tera` | 2, 3, 4 | [cobblemon_duraludon.json](https://github.com/taku7664/Minecraft-Cobblemon-Mods/blob/715e9b6e36baa58f59930a1185830aaaf17b543f/works/mods/more-cobblemon-contents-battle-tower/src/main/resources/data/more_cobblemon_contents/mcc-battle-tower/pokemon-sets/cobblemon_duraludon.json) |
| `generated_<기믹>_tier<티어>_girafarig` | `dynamax`, `tera` | 1, 2, 3, 4 | [cobblemon_girafarig.json](https://github.com/taku7664/Minecraft-Cobblemon-Mods/blob/715e9b6e36baa58f59930a1185830aaaf17b543f/works/mods/more-cobblemon-contents-battle-tower/src/main/resources/data/more_cobblemon_contents/mcc-battle-tower/pokemon-sets/cobblemon_girafarig.json) |
| `generated_<기믹>_tier<티어>_misdreavus` | `dynamax`, `tera` | 1 | [cobblemon_misdreavus.json](https://github.com/taku7664/Minecraft-Cobblemon-Mods/blob/715e9b6e36baa58f59930a1185830aaaf17b543f/works/mods/more-cobblemon-contents-battle-tower/src/main/resources/data/more_cobblemon_contents/mcc-battle-tower/pokemon-sets/cobblemon_misdreavus.json) |

## 오류로 확정하지 않은 팩토리 8세트

| 종 | 세트 / 파일 | 판정 유보 근거 |
|---|---|---|
| 스라크 | `scyther_preset_{1,2,3,4}` / [cobblemon_scyther.json](https://github.com/taku7664/Minecraft-Cobblemon-Mods/blob/715e9b6e36baa58f59930a1185830aaaf17b543f/works/mods/more-cobblemon-contents-battle-factory/src/main/resources/data/more_cobblemon_contents/mcc-battle-factory/rental-sets/cobblemon_scyther.json) | 스라크·핫삼·사마자르는 종족값 합계가 모두 500이고 스피드는 각각 105·65·85입니다. 진화하면 전투 성질이 달라지므로 단순히 상위 종으로 교체할 대상이라고 확정할 수 없습니다. |
| 발바로 | `vigoroth_preset_{1,2,3,4}` / [cobblemon_vigoroth.json](https://github.com/taku7664/Minecraft-Cobblemon-Mods/blob/715e9b6e36baa58f59930a1185830aaaf17b543f/works/mods/more-cobblemon-contents-battle-factory/src/main/resources/data/more_cobblemon_contents/mcc-battle-factory/rental-sets/cobblemon_vigoroth.json) | 네 세트 모두 의기양양(`vitalspirit`)·벌크업(`bulkup`)·태만함(`slackoff`)을 사용합니다. 진화 후 게을킹의 특성은 게으름(`truant`)이므로 같은 전술을 그대로 비교할 수 없습니다. |

두 종 모두 진화 가능한 미진화체라는 사실은 확인했습니다. 다만 의도적인 채용이었다는 명시적 근거는 확인하지 못했습니다. 타워 풀에는 두 종의 세트가 없습니다.

## 트레이너 풀 영향

타워의 수정 검토 대상 30세트는 일반 트레이너 정의 120개 중 **112명**에게 선택될 수 있습니다. ID는 `trainer_001`~`trainer_104`, `trainer_113`~`trainer_120`입니다. 모든 112명이 30세트를 전부 사용한다는 뜻은 아니며, 각 트레이너가 속한 기믹·티어 풀에 따라 가능한 세트가 다릅니다.

`trainer_105`~`trainer_112`의 메가 보스와 챔피언 11명의 고정 로스터에는 해당 30세트가 없습니다. 트레이너 JSON에 종 이름이 직접 쓰이지 않은 경우에도 공통 풀을 통해 선택되므로, 트레이너 파일에서 종 이름만 검색하는 방법으로는 이 영향을 찾을 수 없습니다.

## 원인과 수정 시 검토할 점

팩토리 생성기의 `indexCobblemonSpecies`·`addSpecies`는 구현 여부와 폼 등을 확인하지만 진화 여부를 필터링하지 않습니다. `fixedItem`에서 명시적으로 처리하는 미진화 예외는 럭키·폴리곤2·피카츄 세 종입니다. 팩토리·타워의 카탈로그 로더에도 진화 가능 여부를 검사하는 로직이 없습니다. 따라서 현재 데이터가 검사를 통과하는 경로는 확인됐습니다. 다만 타워 각 세트가 어떤 과거 생성 단계에서 들어왔는지까지 확정한 것은 아닙니다.

수정한다면 두 애드온이 각자의 세트·풀을 소유하므로 두 모듈을 함께 검토해야 합니다. 후보를 풀에서 제외할지, 진화체에 맞는 특성·기술·도구·티어로 다시 구성할지는 결정되지 않았습니다. **이는 빡대리가 채용 정책을 정할 때 검토할 선택지이며, 이번 검사에서 확정하거나 구현한 정책이 아닙니다.** 종 ID만 진화체로 바꾸면 특성·기술·밸런스의 유효성이 달라질 수 있어 별도 검증이 필요합니다. 스라크·발바로는 채용 의도를 확인한 뒤 결정할 대상입니다.

## 설치본 대조와 미확인 범위

소스의 카탈로그 JSON 전체를 로컬 개발 서버와 로컬 운영 서버의 설치 JAR 내부 파일에 바이트 단위로 대조했습니다. 두 서버 모두 팩토리 578파일·타워 451파일이 동일했고, 빠진 파일·변경된 파일·추가 파일이 없었습니다. 두 로컬 서버의 `world/datapacks/`에서도 해당 시설 카탈로그를 덮는 파일을 찾지 못했습니다.

| 로컬 대상 | 경로 |
|---|---|
| 개발 서버 | `C:/Users/박주형/Documents/GitHub/Cobblemon-Mods/develop-product/server` |
| 운영 서버 | `C:/Users/박주형/Documents/GitHub/MinecraftPPakemonServer` |

| 설치 JAR | 개발·로컬 운영 공통 SHA-256 |
|---|---|
| `more-cobblemon-contents-battle-factory-0.1.0.jar` | `5c6cd913bc5f2024c477fdc903c84a7013f2be61185535177dc0ee18bf42b1e7` |
| `more-cobblemon-contents-battle-tower-0.1.0.jar` | `77a5fed33f161bb81faccf0ae13baa61baea436771cbf3830d38c6349b09f873` |

확인한 것은 디스크에 설치된 데이터입니다. 실행 중인 서버가 실제로 로드한 데이터, VPS의 설치 파일, 실게임 상대·대여 선택과 진화 상태는 확인하지 않았습니다. 새 빌드·카탈로그 수정·JAR 배치·서버 종료 또는 재시작은 하지 않았습니다.

검사 원본과 검사 스크립트는 세션 임시 경로 `F:/AI/Temp/mcc-evolution-audit-20261012/`에 있으며, 이 보고서는 그 전수 검사 결과를 저장소에 남긴 기록입니다.
