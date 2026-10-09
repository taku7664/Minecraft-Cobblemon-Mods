# Better Cobblemon Music 작업 기록

## [2026-10-09 17:59] `sealed_chamber`의 river 선곡 제외 요청 (미적용)

- 빡대리님 요청: `field/river/sealed_chamber.ogg`가 일반 `river`에서 나오지 않도록 한다. 앞선 요청과 마찬가지로 이번에는 메모만 남긴다.
- 확인: `resource-pack/catalog-layout.json`에서 곡 `better_cobblemon_music:field/river/sealed_chamber`가 현재 `better_cobblemon_music:field_river` 재생목록에 들어 있다.
- 카탈로그·OGG 파일·클라이언트 설정은 변경하지 않았고, 빌드·배포·실게임 확인도 하지 않았다. 곡 파일 삭제나 다른 환경으로의 재배정은 요청되지 않았다.

## [2026-10-09 17:58] `underground_ruins`의 deep_ocean 선곡 요청 (미적용)

- 빡대리님 요청: 현재 일반 `ocean`에 들어 있는 `field/ocean/underground_ruins.ogg`를 `deep_ocean`에서 나오도록 변경한다.
- 확인: `resource-pack/catalog-layout.json`에서 곡 `better_cobblemon_music:field/ocean/underground_ruins`는 현재 `better_cobblemon_music:field_ocean` 재생목록에 있으며, `#minecraft:is_ocean`과 `ocean` 경로가 이 목록을 사용한다. 지정한 개발 클라이언트의 1.3.18 리소스팩에 해당 OGG 파일이 존재한다.
- 이번에는 요청만 기록했다. 카탈로그·음원·클라이언트 설정은 변경하지 않았고, 빌드·배포·실게임 확인도 하지 않았다. 구현 시 일반 바다와 깊은 바다의 매핑을 분리하고 이 곡이 일반 바다에서는 제외되는지 확인할 것.
