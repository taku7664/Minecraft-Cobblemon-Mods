# English

## Short description

Improve a base Pokémon's IVs using up to nine PokémonToItem materials from its evolution family, with a preview before fusion.

## Full description

**Keep your favorite Pokémon and improve its IVs.** PokemonFusion is a Fabric addon for Cobblemon that uses Pokémon items created by PokémonToItem. Pick one base Pokémon, add up to nine compatible materials, and preview the result before confirming.

Each of the six IVs takes the highest value among the base and the materials. The base keeps its species, form, nature, ability and other Pokémon data. **Confirming fusion consumes every material Pokémon**, including materials that do not improve the result. Held items from the materials are returned.

### Features

- Open the fusion interface with `/pokefusion`.
- Shift-click Pokémon items to fill the base first, then the materials.
- Material slots expand and stay centered as you add Pokémon.
- Materials that contribute to the final IVs glow inside the interface.
- Check the result before consuming anything.
- Fuse within an evolution family, with form-aware compatibility checks.
- Return inputs when you close the interface without confirming.
- Set command access through Mod Menu or the server configuration file.
- English and Korean interface translations.

### Requirements

Minecraft Java **1.21.1**, Java **21+**, Fabric Loader **0.19.5+**, Fabric API, Fabric Language Kotlin, Cobblemon **1.8.1–1.8.x**, and PokémonToItem **0.2.0+**. Mod Menu is optional.

PokémonToItem 0.2.0 is the directly verified version. Later versions must retain the compatible `PTI_NBT` format. Install the addon on the server for multiplayer; installing it on clients is recommended for translations and the Mod Menu screen. For singleplayer, install it on the client.

### Configuration and item delivery

`config/pokefusion.json` contains `commandPermissionLevel`, an integer from `0` to `4`. The default `0` allows all players. Invalid or unreadable configuration uses level `4` and preserves the original file. Mod Menu changes local/integrated-server settings; dedicated-server settings must be changed on that server and require a restart.

If the inventory is full, excess items drop at the player's location. Items that cannot be added or dropped are kept for another delivery attempt. Saved inputs and undelivered items are recovered on reconnect; recovery depends on Minecraft having saved the player data.

### License

CC0-1.0 for this module's code, documentation and new release icon, to the extent the contributors hold the relevant rights. External projects retain their own licenses and trademarks. This is an unofficial addon.

### AI disclosure

This description and the current icon concept were prepared with AI assistance. The AI-generated icon is a local design concept and must be replaced with human-created artwork before uploading it to a Modrinth project page. The project's broader development history must be reviewed for the appropriate Modrinth content disclosure before publication.

---

# 한국어

## 짧은 설명

같은 진화 계보의 PokemonToItem 재료를 최대 9마리 사용해 베이스 포켓몬의 IV를 높이고, 합성 전에 결과를 확인하세요.

## 본문

**마음에 드는 포켓몬은 그대로, 개체값은 더 높게.** PokemonFusion은 PokemonToItem으로 아이템화한 포켓몬을 사용하는 Cobblemon용 Fabric 애드온입니다. 베이스 1마리와 호환되는 재료 최대 9마리를 넣고, 미리보기를 확인한 뒤 합성을 확정하세요.

6개 능력치의 IV는 베이스와 재료 중 각각 가장 높은 값을 가져옵니다. 베이스의 종·폼·성격·특성 등은 유지됩니다. **합성을 확정하면 결과에 기여하지 않는 재료까지 모두 소모됩니다.** 재료의 지닌 아이템은 반환됩니다.

### 주요 기능

- `/pokefusion`으로 합성 화면 열기
- Shift+클릭으로 베이스부터 재료까지 순서대로 입력
- 재료 수에 맞춰 늘어나고 중앙 정렬되는 입력 칸
- 최종 IV에 기여하는 재료에 화면 내 광택 표시
- 합성 전 결과 미리보기
- 진화 계보와 폼을 함께 구분하는 합성 조건
- 확정하지 않고 화면을 닫으면 입력 아이템 반환
- Mod Menu 또는 서버 설정 파일로 명령어 권한 지정
- 한국어·영어 화면 번역

### 설치와 설정

Minecraft Java **1.21.1**, Java **21 이상**, Fabric Loader **0.19.5 이상**, Fabric API, Fabric Language Kotlin, Cobblemon **1.8.1~1.8.x**, PokemonToItem **0.2.0 이상**이 필요합니다. Mod Menu는 선택 사항입니다.

직접 검증한 PokemonToItem 버전은 0.2.0이며, 이후 버전은 호환되는 `PTI_NBT` 형식을 유지해야 합니다. 멀티플레이에서는 서버에 설치하고 번역과 Mod Menu 화면을 위해 클라이언트에도 설치하는 것을 권장합니다. 싱글플레이에서는 클라이언트에 설치합니다.

`config/pokefusion.json`의 `commandPermissionLevel`은 정수 `0~4`이며 기본값 `0`은 모든 플레이어를 허용합니다. 잘못되거나 읽을 수 없는 설정은 원본을 보존하고 권한 `4`로 실행합니다. Mod Menu는 로컬/통합 서버에 적용되며, 전용 서버는 서버 파일을 변경한 뒤 재시작해야 합니다.

인벤토리가 가득 차면 초과 아이템은 플레이어 위치에 떨어집니다. 인벤토리와 바닥 모두 지급할 수 없으면 보관 후 다시 지급합니다. 재접속 시 저장된 입력과 미지급 아이템을 복구하며, Minecraft에 저장되지 않은 변경까지 복구를 보장하지는 않습니다.

### 라이선스

이 모듈의 소스·문서·새 배포 아이콘은 기여자가 보유한 권리 범위에서 CC0-1.0을 적용합니다. 외부 프로젝트의 라이선스와 상표는 각 권리자에게 남습니다. 비공식 애드온입니다.

### AI 사용 안내

이 설명과 현재 아이콘 시안은 AI를 사용해 준비했습니다. AI 생성 아이콘은 로컬 디자인 시안이며, Modrinth 프로젝트 페이지에 올리기 전에 사람이 직접 제작한 이미지로 교체해야 합니다. 공개 등록 전에는 프로젝트의 전체 제작 이력도 확인해 Modrinth의 AI 사용 고지를 적용해야 합니다.
