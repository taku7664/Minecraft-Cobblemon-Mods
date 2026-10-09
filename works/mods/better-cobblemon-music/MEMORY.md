# Better Cobblemon Music 작업 기록

## [2026-10-09 18:25] 정글 곡 교체 소스 완료, 묶음 배포 대기

- 빡대리님이 `120 Southern Jungle [0096].mp3`를 정글 곡으로 지정했다. 기존 `field/jungle/route_210.ogg`를 제거하고 `field/jungle/southern_jungle.ogg`로 교체했으며, `#minecraft:is_jungle`과 `path:jungle` 매핑을 모두 새 곡으로 바꿨다. 표시 이름은 확인된 한국어 명칭인 `남쪽 정글`이다. 과거 가져오기 기록 JSON은 당시 기록이므로 수정하지 않았다.
- `minecraft-audio-ogg`로 MP3를 44.1kHz 스테레오·단일 스트림 Vorbis quality 4로 변환하고 전체 디코딩을 확인했다. 결과 87초·1,208,958바이트, SHA-256 `C5C56DB75348D7B484A96AF4313EC2E0CE8904D8A9BE2107FC9EFB605820F851`.
- 변경 전 관련 통합 테스트는 새 곡·매핑 기대값에서 2건 실패했고, 변경 후 17개 모두 통과했다. 리소스팩 ZIP 생성 성공. ZIP에는 새 OGG가 있고 기존 OGG가 없으며 카탈로그에 새 곡 참조 6곳·기존 참조 0곳이다. 생성 ZIP SHA-256 `83F6E0C620AC581AE59D0BF66D19E2194EDCB09475C048B3DF9B269043DD8466`.
- **개발 클라이언트에는 아직 설치하지 않았다.** 빡대리님이 나머지 곡 변경을 모아 한 번에 교체하자고 하셨다. 실게임 재생은 확인하지 않았다. 이후 묶음 변경이 추가되면 이 ZIP 해시는 폐기하고 최종 `main`에서 다시 생성해야 한다.

## [2026-10-09 18:18] 필드 음악 교체 묶음 소스 완료, 클라이언트 적용 대기

- 요청된 묶음: 일반 바다는 `47번도로`만, `minecraft:deep_ocean`은 `땅밑유적`만 선곡한다. 강의 `고시의 석실`과 동굴의 `호수의 공동`은 제외하고 사용하지 않는 OGG도 제거했다. 악지는 `송화산 (외부)`, 늪지는 `120번도로`, 마이룸은 `입지호수 근처` 낮·밤, 설원은 `216번도로 (밤)`으로 교체했다. 산의 `205번도로 (낮)`은 유지했다.
- 소스: 악지 교체는 `b3a43e99`, 나머지 묶음은 `85335c7f`로 `origin/main`에 푸시했다. 앞선 두 미적용 메모는 이 소스 변경으로 반영됐다.
- 음원: 제공된 OGG 세 곡은 Vorbis 단일 스트림과 전체 디코딩을 확인해 재인코딩 없이 복사했다. `35 - Route 216 (Night).mp3`는 `minecraft-audio-ogg` 도구로 44.1kHz 스테레오 Vorbis(quality 4)로 변환했다. 이 PC의 Python `pip`가 깨져 있어 전역 설치는 건드리지 않고, PyPI의 `imageio-ffmpeg` 0.6.0 wheel 해시를 확인해 `build/audio-tools`의 FFmpeg를 사용했다.
- 검증: 관련 통합 테스트 17개 통과. `main`에서 생성한 `better-cobblemon-music-resourcepack-1.3.18.zip`의 SHA-256은 `6C1009284355FB85CDE362AE53FA1352789576453B161E247D3C3C066D392B2F`이며, 생성 카탈로그 87곡과 새 음원 포함·옛 음원 제외를 확인했다. 변경 전 전체 `unitTest`는 이번 작업과 무관한 모드 설명문 기대값 1건이 실패했다. 변경 후 전체 테스트는 실행하지 않았다.
- 배포: 빡대리님이 추가 변경을 모아 한 번에 교체하자고 하셔서 **개발 클라이언트 ZIP/폴더는 아직 교체하지 않았다**. 실게임 재생도 확인하지 않았다. 추가 요청이 끝나면 현재 `main` 소스에서 팩을 다시 확인한 뒤 한 번에 적용할 것.

## [2026-10-09 17:59] `sealed_chamber`의 river 선곡 제외 요청 (당시 미적용)

- 빡대리님 요청: `field/river/sealed_chamber.ogg`가 일반 `river`에서 나오지 않도록 한다. 앞선 요청과 마찬가지로 이번에는 메모만 남긴다.
- 확인: `resource-pack/catalog-layout.json`에서 곡 `better_cobblemon_music:field/river/sealed_chamber`가 현재 `better_cobblemon_music:field_river` 재생목록에 들어 있다.
- 카탈로그·OGG 파일·클라이언트 설정은 변경하지 않았고, 빌드·배포·실게임 확인도 하지 않았다. 곡 파일 삭제나 다른 환경으로의 재배정은 요청되지 않았다.

## [2026-10-09 17:58] `underground_ruins`의 deep_ocean 선곡 요청 (당시 미적용)

- 빡대리님 요청: 현재 일반 `ocean`에 들어 있는 `field/ocean/underground_ruins.ogg`를 `deep_ocean`에서 나오도록 변경한다.
- 확인: `resource-pack/catalog-layout.json`에서 곡 `better_cobblemon_music:field/ocean/underground_ruins`는 현재 `better_cobblemon_music:field_ocean` 재생목록에 있으며, `#minecraft:is_ocean`과 `ocean` 경로가 이 목록을 사용한다. 지정한 개발 클라이언트의 1.3.18 리소스팩에 해당 OGG 파일이 존재한다.
- 이번에는 요청만 기록했다. 카탈로그·음원·클라이언트 설정은 변경하지 않았고, 빌드·배포·실게임 확인도 하지 않았다. 구현 시 일반 바다와 깊은 바다의 매핑을 분리하고 이 곡이 일반 바다에서는 제외되는지 확인할 것.
