# Rounding-Block — 배포 페이지 문구

## English summary

Rounded block edges, connected corners and water contacts. Client-side visuals that preserve Minecraft’s block grid and collision.

## English description

**Give Minecraft terrain softer edges without changing how the world plays.**

Rounding-Block rounds exposed edges of full blocks, slabs and stairs. Neighboring shapes are taken into account at their shared corners, and small water surfaces fill the gaps where water meets rounded blocks. The block grid, collision and saved world data stay unchanged.

### Features

- Adjustable edge radius and curve segments.
- Rounded connections between supported blocks, slabs and stairs.
- Water contacts that follow the existing fluid renderer.
- Native settings screen with English and Korean labels.
- Client commands for viewing, editing and reloading settings.
- Original-model fallback for unsupported models.

### Requirements

Minecraft **1.21.1**, **Fabric Loader 0.19.3+**, **Fabric API** and **Java 21+**. Install on the client; the server does not need this mod. Cobblemon and additional settings mods are not required.

Run `/roundingblock config` in a world to open settings. If Mod Menu is installed, its configuration button opens the same native screen. Cloth Config is not needed.

The default radius is **0.09375 blocks**, with **3 curve segments**. More segments produce smoother curves and increase rendering work. You can also edit `config/rounding-block.json`, then run `/roundingblock reload`.

Custom models and rendering combinations can behave differently. Unsupported models use their original appearance. Compatibility and performance with a particular resource pack, Sodium or Iris setup should be checked in-game.

Licensed under **MIT**. The project icon is an illustration, not a gameplay screenshot.

## 한국어 요약

블록·반블록·계단의 모서리를 둥글게 그리는 클라이언트 모드입니다. 블록 격자와 충돌 판정을 유지하며 이웃 블록과 물 접촉면을 연결합니다.

## 한국어 설명

**플레이 방식은 유지하고, 지형의 모서리만 부드럽게 바꿉니다.**

Rounding-Block은 전체 블록·반블록·계단에서 드러난 모서리를 둥글게 그립니다. 이웃 블록의 형상을 고려해 접점을 처리하고, 물과 둥근 블록 사이의 빈 공간에는 수면을 이어 그립니다. 블록 격자, 충돌 판정, 월드 저장 데이터는 바꾸지 않습니다.

Minecraft **1.21.1**, **Fabric Loader 0.19.3 이상**, **Fabric API**, **Java 21 이상**이 필요합니다. 클라이언트에만 설치하며 서버·Cobblemon·추가 설정 모드는 필요하지 않습니다.

월드에서 `/roundingblock config`로 기본 설정 화면을 열 수 있습니다. Mod Menu가 있다면 모드 목록에서도 같은 화면을 열 수 있고, Cloth Config는 필요하지 않습니다. 한국어와 영어를 지원합니다.

기본 라운딩 반경은 **0.09375 블록**, 곡면 분할 수는 **3**입니다. 분할 수가 높을수록 부드러워지는 대신 렌더링 비용이 늘어납니다. `config/rounding-block.json`을 직접 편집한 뒤 `/roundingblock reload`로 적용할 수도 있습니다.

지원하지 않는 모델은 원래 외형으로 그립니다. 개별 리소스팩·Sodium·Iris 조합의 외형과 성능은 게임에서 확인해야 합니다.

라이선스는 **MIT**입니다. 아이콘은 소개용 그림이며 실제 게임 화면이 아닙니다.
