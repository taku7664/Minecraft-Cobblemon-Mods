# Better Cobblemon Music 작업 기록

## [2026-10-11 02:47] 차원곡 복구와 공식 BGM·경고음 파일 음량 조정

- 빡대리님 요청대로 현재 `main` 작업본에 울트라스페이스·고대·미래 차원(`cobblemon_dimensions:ultra_space`/`ancient`/`future`)의 `울트라데저트` 매핑과 OGG를 이식했다. 이전 브랜치 `71714d25`의 직접 cherry-pick은 충돌로 중단하고 현재 매핑을 보존했다.
- 공식 팩의 BGM 88곡과 저체력 경고음 1개 OGG를 각각 직전 파일 대비 0.5배로 조정했다. 타격음 3개는 변경하지 않았다. `tools/scale_all_bgm_and_alert.py`와 `resource-pack/all-bgm-alert-volume-2026-10-11.json`으로 변환 전후 해시, 44.1kHz·Vorbis 형식, 길이와 RMS를 검증했다. 실측 RMS 감소폭은 -5.93~-6.065dB이며, 재실행 시 중복 감쇠를 차단한다.
- F 드라이브의 검증 작업 폴더에서 `:better-cobblemon-music:build`가 성공하고 JUnit 178/178개가 통과했다. 이는 소스·빌드 검증이며 실게임 청취 결과가 아니다.
- 이 기록 시점에 `main` 커밋·`origin/main` 푸시, 개발 클라이언트 JAR·ZIP 배치, 실게임 재생·청취는 아직 완료되지 않았다.

## [2026-10-10 17:16] 태그 기반 바이옴 음악 개발 클라이언트 적용

- 공식 바이옴 판정 변경 커밋 `a35d6c76f49ee04f9b1bec2f9cdd8571d66f03c8`이 빌드 전에 `main`과 `origin/main`에 동일하게 반영된 것을 확인했다. 이후 같은 저장소의 다른 작업으로 현재 HEAD가 더 진행됐으므로 이 SHA는 이번 Music 변경의 기준 커밋이다.
- `:better-cobblemon-music:build --configure-on-demand` 성공, Music 테스트 176/176 통과. 공식 매핑의 `path:` 규칙은 0개이고, `#c:is_swamp`는 늪지 `120번도로` 곡으로 연결된다. `#minecraft:is_deep_ocean`은 깊은 바다 전용곡에 연결하고, `minecraft:cherry_grove`는 산 태그보다 먼저 숲 곡을 선택하도록 배치했다.
- `cobblemon-dev` 게임이 실행 중이지 않은 상태에서 빌드본 Music 1.3.18 JAR과 선택된 음악 리소스팩 ZIP을 개발 클라이언트에 교체했다. 압축 해제된 같은 이름의 팩 폴더에서는 변경된 생성 카탈로그만 교체했다. `options.txt`는 이미 그 ZIP을 선택하고 있어 수정하지 않았다.
- 빌드본과 설치본 SHA-256이 각각 일치한다: JAR `F8184B6843014E2097039A4AAF44ABB29411DEBC2DC48B9F83EADBBC0CBF9595`, ZIP `E3E2071B104B8492F1CC24F8576AF5B67E00A750A90FB9525B7BADE2960406C3`. 실게임 재생·청취는 아직 확인하지 않았다. 아래 17:10 항목의 미배포 표기는 당시 상태다.

## [2026-10-10 17:10] 공식 음악팩 바이옴 판정을 태그·정확한 ID로 전환

- 빡대리님은 공식 기본팩의 `biomePathContains`만 제거하고 실제 바이옴 태그와 필요한 정확한 ID로 선곡하되, 개인 `overrides.json`의 경로명 부분 일치 기능은 호환성을 위해 유지하기로 결정하셨다. 공식 `catalog-layout.json`의 경로명 규칙 17개와 `path:` 우선순위 항목을 없애고 동굴·강·바다·늪지·정글·설원·산·숲·평원·사막·악지에 태그·ID 규칙을 지정했다. 별도 곡을 쓰는 `minecraft:deep_ocean`은 정확한 ID를 유지하고, `terralith:deep_warm_ocean`은 일반 바다로 지정했다.
- 설치된 Terralith 자료에서 `terralith:ice_marsh`가 `#c:is_swamp`와 설원 계열 태그에 모두 속하는 것을 확인했다. 공식 규칙은 늪지를 설원보다 먼저 평가하므로 얼음 습지는 늪지 곡을 고른다. 이름에 `swamp`가 없어도 태그로 판정해야 한다는 회귀 사례다.
- Music `unitTest` 176개 통과(`--configure-on-demand`). 처음 일반 실행은 같은 작업공간의 별도 MCC Gradle 프로세스가 캐시 잠금을 점유해 진행되지 않았고, 위 옵션으로 검증했다. 이는 코드·카탈로그 검증이며 실게임에서의 청취 증거는 아니다.
- 이 기록 시점에는 변경분의 커밋·`main` 푸시·음악팩 빌드·개발 클라이언트 적용을 아직 확인하지 않았다. 이후 단계의 결과는 별도로 확인해 기록해야 한다.

## [2026-10-10 07:05] 전투 BGM 재생 음량 조정팩 개발 클라이언트 적용

- `main` HEAD와 `origin/main`이 모두 `d97dc9c67516572ad0f19b818f2ac5d2badb5531`이며, 팩 빌드 기준 `0e92a0d7` 이후 현재 HEAD까지 Music 관련 경로 변경은 없다. 게임 프로세스 0개를 확인한 뒤 개발 클라이언트의 선택된 1.3.18 리소스팩 ZIP을 기존 SHA-256 `AFBB27FC33390357E91F37DDA2BEC38CA30D1C8220EED2EBBE40441D19BF8DC8`에서 빌드본 SHA-256 `F571FED8FF06727EC3FA6E39612D6E2513B2E8F666690C80F8DC6652CE9FE34E`로 교체했고, 설치본 해시가 빌드본과 같다.
- 같은 이름의 압축 해제 폴더는 배포 직전 94개 파일 중 `sounds.json`만 빌드본과 달랐다. 이 파일만 교체했고 설치 파일 SHA-256은 `ED0E10070A916602BBC0BEED1776BE6A29C9262FCFFB32B2DFC8880C27BC19D2`다. `options.txt`·개인 설정·모드 JAR·OGG는 변경하지 않았다.
- 설치와 파일 해시만 확인했다. 실게임 청취는 아직 검증하지 않았다. 아래 05:35 항목의 미배포 표기는 당시 상태다.

## [2026-10-10 05:35] 음악 슬라이더 100%에 맞춘 전투 BGM 재생 음량 조정

- 결정·소스: 빡대리님은 Minecraft 음악 슬라이더를 71.3028%에서 100%로 올리면 필드곡은 적당하지만 전투곡은 크다고 확인하셨다. 공식 팩의 `catalog-layout.json`에 `battleSoundVolume: 0.713`을 추가하고, `MusicResourcePackBuildTool`이 `sounds.json`의 `music.track.battle.*` 61개에만 `volume: 0.713`을 생성하도록 했다. OGG는 다시 인코딩하지 않는다.
- 빌드 검증: 원본 OGG 87개와 생성본의 해시가 모두 같고, 필드곡·효과음에는 `volume` 값이 없다. ZIP SHA-256은 `F571FED8FF06727EC3FA6E39612D6E2513B2E8F666690C80F8DC6652CE9FE34E`(189,477,253바이트). 처음에는 기존 `BetterCobblemonMusicModuleContractTest`가 낡은 설명문과 비교해 1개 실패했으나, 현재 메타데이터의 핵심 문구를 검사하도록 별도로 고쳤다. 이후 `:better-cobblemon-music:unitTest` 전체 175개 통과.
- 배포·실게임: 개발 게임이 실행 중이라 설치본은 교체하지 않았다. 실제 청취도 확인하지 않았다.

## [2026-10-09 20:56] 전투 BGM 감쇠팩 개발 클라이언트 적용

- `746e4639`의 Music 소스·리소스가 `main`과 `origin/main`에 올라간 것을 확인했다. 이 소스와 동일한 빌드 ZIP SHA-256은 `AFBB27FC33390357E91F37DDA2BEC38CA30D1C8220EED2EBBE40441D19BF8DC8`이다.
- `cobblemon-dev` 게임 프로세스가 없을 때 선택된 `better-cobblemon-music-resourcepack-1.3.18.zip`을 교체했다. 설치 ZIP과 빌드 ZIP의 SHA-256이 같다. 같은 이름의 압축 해제 폴더도 갱신했고, 94개 파일이 생성본과 각각 SHA-256이 일치한다. `options.txt`는 이미 해당 ZIP을 선택하고 있어 수정하지 않았다.
- 모드 JAR·개인 `settings.json`/`overrides.json`·다른 팩·서버·릴리스 제품은 변경하지 않았다. 게임을 실행하거나 실제 청취는 하지 않았다.

## [2026-10-09 20:53] 공식 전투 BGM 61곡의 음원 음량 조정

- 빡대리님 요청대로 공식 팩 `sounds/music/battle/`의 야생·트레이너·체육관·사천왕·챔피언·PvP·보스·울트라비스트·전설 BGM 61곡을 각각 기존 실측 RMS 음량의 약 60%로 낮췄다. 필드·메뉴 BGM과 `sounds/battle/`의 타격음·저체력 경고음은 변경하지 않았다. 전설곡은 이전 1.3배 증폭본을 입력으로 삼아 기존 곡별 차이를 보존했다.
- 재사용 가능한 `tools/scale_battle_volume.py`를 추가했다. 61개 음원과 카탈로그 곡명 집합이 정확히 같은지, 기존 전설·PvP 음원 해시가 승인본인지 먼저 확인한다. 변환 결과를 임시 `build/` 폴더에서 Vorbis 단일 스트림·44.1kHz·채널·길이·전체 디코딩·실측 RMS 감소폭으로 검증한 뒤에만 소스 파일을 교체하며, 보고서와 현재 파일 해시가 맞으면 재실행해도 다시 줄이지 않는다. 일부 OGG는 입력 시간표 때문에 FFmpeg가 중단돼 `asetpts=N/SR/TB`를 적용했다. 울트라비스트 1곡은 Vorbis 재인코딩 후 음량 차이가 목표보다 커 입력 이득을 추가 보정했다.
- `resource-pack/battle-volume-2026-10-09.json`에 61곡의 변환 전후 해시·RMS·입력 이득을 기록했다. 전곡 실측 감소폭은 -4.567~-4.322dB(목표 -4.437dB)이며, 총 OGG 크기는 168,133,466→145,102,824바이트다. 과거 `legendary-gain-2026-10-04.json`은 당시 기록이므로 덮어쓰지 않았다.
- 앞선 필드곡 교체 때문에 오래된 가져오기 목록을 기대하던 `ApprovedMusicAssetsTest`를 현재 승인 카탈로그의 87곡 기준으로 고쳤다. 관련 테스트 19개 통과, 팩 ZIP 생성 성공, ZIP 안 전투곡 61개 모두 보고서 해시와 일치한다. 빌드 ZIP SHA-256 `AFBB27FC33390357E91F37DDA2BEC38CA30D1C8220EED2EBBE40441D19BF8DC8`(189,477,238바이트).
- 이 단계에서는 소스·테스트·ZIP 빌드만 확인했다. 개발 클라이언트 배치와 실게임 청취는 아직 확인하지 않았다. 모드 JAR 코드는 바꾸지 않았다.

## [2026-10-09 18:36] 묶음 필드 음악 개발 클라이언트 적용

- 빡대리님이 앞서 모아 둔 필드 음악 변경의 클라이언트 교체를 요청했다. `08fbcd57`을 포함한 `main`·`origin/main`이 동기화됐고, Music 소스·리소스·빌드 설정은 검증 ZIP 생성 이후 바뀌지 않아 기존 빌드본을 재사용했다.
- 개발 클라이언트 `cobblemon-dev`에서 게임 프로세스가 없는 것을 확인한 뒤 선택된 `better-cobblemon-music-resourcepack-1.3.18.zip`을 교체했다. 설치 ZIP과 빌드 ZIP SHA-256이 모두 `83F6E0C620AC581AE59D0BF66D19E2194EDCB09475C048B3DF9B269043DD8466`이다. `options.txt`는 이미 해당 ZIP을 선택하고 있어 수정하지 않았다.
- 같은 이름의 압축 해제 리소스팩 폴더도 생성본으로 갱신했다. 이전 팩에만 있던 곡 7개를 정확히 지정해 휴지통으로 옮겼으며, 최종 폴더의 94개 파일이 생성본과 각각 SHA-256이 같다. 일반 삭제 명령은 도구 안전 정책에 차단되어 이 방식으로 정리했다. 다른 리소스팩·설정 파일·모드 JAR은 변경하지 않았다.
- 이전 작업의 관련 통합 테스트 17개 통과와 ZIP 생성 성공은 유지된다. 이번 교체 후 실게임 재생은 아직 확인하지 않았다.

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
