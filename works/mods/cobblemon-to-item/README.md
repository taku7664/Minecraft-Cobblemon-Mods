# Cobblemon To Item

포켓몬의 전체 저장 데이터를 아이템으로 옮기고 다시 파티 또는 PC로 복원하는 Fabric 모드입니다. Cobblemon의 포켓몬 모델 아이템을 사용하며 외부 PokemonToItem은 필요하지 않습니다.

## 사용법

- `/poke2item <파티 번호>`: 1~6번 엔트리의 포켓몬을 아이템화합니다. 인벤토리·퀵슬롯이 모두 가득 차면 플레이어 위치에 떨어뜨립니다. 마지막 한 마리도 아이템화할 수 있습니다.
- 손에 들고 우클릭: 가장 낮은 번호의 빈 엔트리로 복원합니다. 파티가 가득 차면 PC로 보내며, PC에도 넣을 수 없으면 아이템을 유지합니다.
- 일반 인벤토리 화면에서 포켓몬 아이템을 Shift+클릭: 인벤토리와 퀵슬롯 모두 가장 낮은 번호의 빈 엔트리로 복원합니다. 파티가 가득 차면 아이템을 유지하며 PC로 보내지 않습니다.
- 상자·합성·크리에이티브 등 다른 화면은 이 모드가 Shift+클릭을 가로채지 않습니다. 기존 화면의 동작을 따릅니다.
- Cobblemon Party Sidebar 0.2.0+: 엔트리 슬롯을 클릭하는 즉시 아이템화하여 커서에 듭니다. 빈 엔트리에 놓으면 복원하고, 포켓몬이 있는 엔트리에 놓으면 그 포켓몬을 아이템화하여 커서에 드는 방식으로 교환합니다. 인벤토리 슬롯에는 일반 아이템처럼 놓습니다. Shift+클릭은 인벤토리·퀵슬롯으로 바로 보내며, 빈 칸이 없으면 거부합니다. 일반 클릭 집기는 커서가 비어 있으면 인벤토리가 가득 차도 가능합니다. I키와 별도 모델 드래그·파티 재정렬 패킷은 사용하지 않습니다.

모든 입출고는 서버에서 배틀·생존·관전자·권한을 검사합니다. 동작 중인 포켓몬은 아이템화하지 않습니다. UUID와 수량을 검사해 변경된 슬롯 요청 및 이미 자신의 파티·PC에 있는 포켓몬의 복원을 거부합니다. 중복 여부는 UUID 조회 캐시가 아니라 실제 파티·PC 슬롯을 확인하므로, PC와 파티를 맞바꾼 뒤 캐시에만 남은 포켓몬 때문에 복원이 거부되지 않습니다. 손상되거나 지원하지 않는 저장 데이터는 소비하지 않습니다. 저장·지급에 실패하면 이동을 되돌립니다. 갑작스러운 프로세스 종료와 월드 저장 파일 자체의 장애까지 복구한다고 보장하지는 않습니다.

## 툴팁과 공개 API

상세 툴팁의 구현과 번역은 이 모드가 소유합니다. Sidebar의 기존 상세 툴팁을 이관했으며 Sidebar와 아이템이 같은 구현을 호출합니다. 첫 줄은 `[잡은 볼 아이콘] 레벨 이름 성별`이며, 이로치는 이름과 이름 뒤의 `★`를 노란색으로 표시합니다. HP, 성격·특성, Cobblemon 자체 XS/S/M/L/XL 단계, 우두머리 여부, 기술·PP 및 개체값·노력치 표를 표시합니다. 개체값이 31(V)인 숫자와 노력치가 252인 숫자는 녹색입니다. 크기는 흑청색 세로 막대 5개 중 XS부터 XL까지 1~5개를 푸른색으로 채우고 뒤에 `(XS)` 같은 단계 문자를 붙입니다. 기술 목록 위·아래에는 회색 구분선을 표시합니다. 하단 조작 안내는 표시하지 않으며 아이템의 추가 설명은 유지합니다.

공개 Kotlin API(클라이언트용 API를 서버에서 불러오지 마세요):

```kotlin
// 일반/서버: 외부 모드는 전용 저장 형식을 직접 조립하지 않고 이 API를 사용합니다.
PokemonItemData.create(pokemon, registryAccess)
PokemonItemData.pokemonTag(stack)
PokemonItemData.putPokemon(rootTag, pokemonTag)
PokemonTransfers.itemize(player, slot, pokemon.uuid, targetMenuSlot, dropOverflow = false)
PokemonTransfers.restoreInventory(player, menuSlot, pokemonUuid)
PokemonTransfers.pickup(player, partySlot, pokemonUuid)
PokemonTransfers.restoreCursor(player, partySlot, pokemonUuid, expectedTargetUuidOrNull)
PokemonTransfers.restoreHeld(player, heldStack)

// 클라이언트: 파티 Pokemon 또는 저장된 ItemStack으로 같은 툴팁을 표시합니다.
PokemonTooltip.render(graphics, pokemon, mouseX, mouseY)
PokemonTooltip.renderItemTooltip(graphics, stack, mouseX, mouseY)
```

`PokemonItemData`·`PokemonTransfers`는 `jbro.cobblemon.toitem.api`, `PokemonTooltip`은 `jbro.cobblemon.toitem.api.client`에 있습니다. 전송 API는 서버 스레드에서 호출해야 합니다. 사이드바 GUI 경로는 일반 인벤토리와 합성 메뉴에서 실행합니다. 인벤토리 아이템의 Shift 복원은 일반 인벤토리 메뉴에서만 실행합니다. 명령 경로만 `dropOverflow = true`를 사용합니다.

아이템 데이터는 `custom_data.cobblemon_to_item`의 `version: 1`, `pokemon: <전체 Cobblemon NBT>`로 저장합니다. 알 수 없는 버전은 읽지 않습니다. 기존 `PTI_NBT` 아이템 마이그레이션은 이 모드에 포함하지 않으며, 추후 `jbro-policy`의 서버 데이터 일괄 변환 작업으로 처리합니다. 마이그레이션 전에 구버전 아이템을 새 아이템으로 취급하지 않습니다.

## 설치와 설정

Minecraft 1.21.1, Java 21, Fabric Loader 0.19.5+, Fabric API, Fabric Language Kotlin 1.14.1+kotlin.2.4.20+, Cobblemon 1.8.1~1.8.x가 필요합니다. 서버와 클라이언트에 모두 설치합니다. Mod Menu는 선택 사항입니다. 외부 PokemonToItem을 함께 설치할 필요가 없습니다.

`config/cobblemon_to_item.json`의 초기 값:

```json
{"commandPermissionLevel":0,"showTooltip":true}
```

`commandPermissionLevel`은 명령·입출고의 공통 최소 권한(0~4)이고 `showTooltip`은 클라이언트 상세 툴팁 표시입니다. Mod Menu에서 변경할 수 있습니다. 멀티플레이의 이동 권한은 서버 설정을 따르며 클라이언트 설정으로 바꿀 수 없습니다. 권한 설정 변경 뒤 명령 목록 갱신에는 재접속이 필요합니다. 서버 파일 수정은 다음 기동부터 적용합니다. 설정을 읽거나 생성하지 못하면 원본을 보존하고 권한 4로 실행합니다. UI 저장 실패 시 기존 실행 값을 유지합니다.

빌드는 `works`에서 `:cobblemon-to-item:build`, 단위 검증은 `:cobblemon-to-item:unitTest`입니다. 저장소의 F 드라이브 Gradle 경로 규칙을 먼저 적용해야 합니다. 라이선스는 MIT입니다.
