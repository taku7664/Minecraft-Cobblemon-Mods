# 알파포켓몬 전투 음악 — 1.3.2

Updates: [2026-10-03 라인업](MUSIC_LINEUP_2026-10-03.md). 50곡 선곡과 루기아 유지 정책은 바꾸지 않고, 전달 폴더의 기타 보스 2곡을 추가합니다.

## 판정과 선곡

- **MUST:** 상대편의 현재 활성 포켓몬에 서버가 전달한 정확한 `alpha` aspect가 있고 야생 전투일 때만 알파 분류를 추가합니다. 크기·레벨·이름·붉은 눈 효과만으로 추정하지 않습니다. NPC·PvP는 적용하지 않습니다.
- **MUST:** 사용자께서 선택한 대로 포켓몬별 전용곡을 우선합니다. 전체 선곡 순서는 포켓몬 규칙 → 콘텐츠 키 → 알파 → 울트라비스트 → 전설 분류 → 전투 종류 기본곡입니다.
- **MUST:** 알파곡 매핑이 없으면 기존 울트라비스트·전설·전투 종류 선곡을 따릅니다. 선택한 알파곡에서 재생할 트랙을 찾지 못하면 종류별 기본곡으로 돌아갑니다. 기존 알파 매핑 없는 팩도 정상적으로 읽습니다.
- **MAY:** 공식 팩의 `mappings.battle.alpha` 또는 사용자 `overrides.json`의 `battle.alpha`를 다른 플레이리스트 ID로 바꿀 수 있습니다. Mod Menu → 전투 매핑 → `알파 포켓몬 전투`에서 선택할 수 있습니다.

기본 플레이리스트 `cobleserver:battle_alpha`는 `PLA Battle- Boss`와 `SV Battle! (Leader Pokémon)` 두 곡입니다. 현재 사용자 설정인 `shuffle`을 따릅니다. 이것은 원작 주인·왕·여왕 포켓몬 분류를 구현한 것이 아니라 Cobblemon의 알파 분류에 두 보스 음원을 배정한 것입니다.

## 판정 근거와 대안

Cobblemon 1.8.1의 실제 의존성 소스를 확인했습니다.

- `PokemonAspects.kt`의 `ALPHA_ASPECT`는 `pokemon.isAlpha`가 참일 때 `alpha`를 제공합니다.
- `BattleInitializePacket.ActiveBattlePokemonDTO.fromPokemon`은 속성 문자열에는 종·성별·폼·색이 다름만 추출하지만 `exposed.aspects`를 별도로 전송합니다. 따라서 전투 중 `properties.isAlpha`만 확인하는 방식은 누락될 수 있어 사용하지 않습니다.
- `ClientBattlePokemon.state.currentAspects`는 수신한 aspect 집합이며 `updateAspects`도 같은 상태를 갱신합니다. Music은 기존 공용 상황 검사에서 현재 상대 활성 슬롯을 읽습니다. 별도 월드 엔티티 탐색이나 고빈도 타이머는 추가하지 않습니다.
- 미수신 활성 슬롯은 기존처럼 건너뜁니다. 매 검사에서 분류를 새로 모으므로 상대 교체·전투 종료 뒤 알파 분류를 계속 보관하지 않습니다. 외형상 커다란 일반 포켓몬을 알파로 판단하는 방식은 오탐 때문에 제외했습니다.

## 배포와 호환

- **MUST:** JAR과 공식 ZIP 모두 1.3.2로 설치합니다. 1.3.1 JAR은 새 `alpha` 매핑 항목을 읽지 못하므로 새 ZIP만 설치하지 않습니다.
- 음원 가져오기 목록: [import-alpha-2026-10-03.json](resource-pack/import-alpha-2026-10-03.json). 기존에 설치한 `minecraft-audio-ogg` 스킬로 품질 4·최대 44.1kHz·음원 스트림만 변환합니다.
- 개인 설정은 그대로 유지합니다. 알파 분류 자체의 ON/OFF 기능은 이 변경에 포함하지 않습니다. 다른 곡을 원하면 매핑을 변경할 수 있습니다.
- 테스트는 알파 판정, NPC·PvP 제외, 종 규칙 우선, 없는 매핑의 폴백, 알파 매핑 저장·복원 및 실제 공식 팩의 두 곡 참조를 검사합니다. 실제 게임에서의 전투 재생과 설정 화면 조작은 별도로 확인합니다.
