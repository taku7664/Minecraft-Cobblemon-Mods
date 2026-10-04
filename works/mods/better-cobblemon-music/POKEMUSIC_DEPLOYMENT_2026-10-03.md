# Music 1.3.5 클라이언트 배포 기록

2026-10-03. [선곡·미지정 목록](POKEMUSIC_IMPORT_2026-10-03.md).

## 결과

- 대상: `C:\Users\박주형\AppData\Roaming\ModrinthApp\profiles\cobblemon-dev`.
- Minecraft 클라이언트 프로세스가 없는 상태에서 설치했습니다. 실행 중인 별도 서버는 중단하거나 수정하지 않았습니다.
- `mods`에는 `better-cobblemon-music-1.3.5.jar` 한 개만 설치되어 있습니다.
- `resourcepacks/cobleserver-music-resourcepack-1.3.5.zip`을 복사하고 `options.txt` 선택 항목을 1.3.4 → 1.3.5로 바꿨습니다. 다른 리소스팩 선택과 순서는 배포 직전 목록을 유지했습니다.
- 옛 1.3.4 JAR은 아래 백업으로 이동했습니다. 옛 ZIP는 비활성 상태로 보존했습니다.
- 서버·MCC·정책 모드 파일은 변경하지 않았습니다. 설치된 클라이언트 MCC JAR에서 `MccClientContext.current`, `MccClientState.getHubTab`, `MccBattleTag.getStage/getOpponentId`의 존재를 `javap`으로 확인했습니다. 이는 바이너리 API 확인이며 실제 서버의 태그 전달을 관찰한 결과는 아닙니다.

## 파일 검증

| 파일 | SHA-256 |
|---|---|
| 1.3.5 JAR | `38e0db45cc31e831d1b96a90dcd7f6ce4f5d55acc43d75c0c023b20f6df1253f` |
| 1.3.5 음악 ZIP | `89f7b2fd319cc40dedf997e369f9b6638fb6c0fd0bdbc3d57e4df017b023c608` |
| 개인 settings.json — 변경 없음 | `955012e15fc330acc63eb377a86cf539267a674c582d8824ee44d87a2fe22337` |
| 개인 overrides.json — 변경 없음 | `c6f30a5d298d8b65cc3069274d6e46facea629dac32aa312b8e4d96db31625c9` |

빌드 산출물과 설치 파일의 SHA-256이 각각 일치합니다. ZIP 크기는 **276,437,633바이트 = 276.44MB / 263.63MiB**입니다.

설치되어 있던 1.3.4 ZIP와 새 ZIP의 일반 파일을 전부 비교했습니다. 바뀐 파일은 지정 음원 8개 + `sounds.json` + 기본 카탈로그 2개, 추가 파일은 지정 음원 22개이며 삭제 파일은 없습니다. 추가 디렉터리 항목은 음악 파일 변경과 별도로 취급했습니다. 기존 오디오 85개는 바이트 단위로 동일합니다. ZIP 전 항목 CRC 검사도 통과했습니다.

전체 115 OGG 중 BGM은 111개, 타격음은 3개, 빨피 경고음은 1개입니다. 모든 BGM 이벤트에 `stream: true`가 있습니다. 전체 소스 빌드의 138개 단위·통합 테스트와 OGG 도구의 5개 회귀 테스트가 통과했습니다.

현재 개인 설정은 공용 검사 1초, 필드 대기 4초, 페이드 인/아웃 각 1초, 기본 음악 음량 1.0, 타격음 0.5, 빨피 경고음 0.2입니다. 기본 selection은 shuffle 그대로이며 README의 두 곡 그룹만 명시적 random으로 지정했습니다.

## 백업·복구

백업: `C:\Users\박주형\Documents\GitHub\Cobblemon-Mods\deployment-backups\music-pokemusic-1.3.5-20261003`.

보관 파일: 옛 1.3.4 JAR, 배포 직전 `options.txt`, 개인 `settings.json`, `overrides.json`.

복구할 경우 Minecraft를 종료하고 백업 JAR을 되돌린 뒤, `options.txt`의 음악팩 선택만 보존된 1.3.4 ZIP로 변경하세요. 개인 설정은 이번에 바꾸지 않았으므로 되돌릴 필요가 없습니다. 전체 options 백업 복구는 이후 다른 설정을 바꿨을 때 그 변경도 없애므로 피하는 것이 좋습니다.

## 검증 범위

확인: 소스 빌드·선곡 해석·시간 경계·레거시 호환·파일 저장/재독해·변환 디코딩·팩 구조/CRC·클라이언트 설치 및 선택·개인 설정 보존.

아직 확인하지 않음: 실제 게임에서 메인 화면/MCC/광장 음악 청취, 서버별 난천 태그 전달, 낮·밤 설정 항목의 실제 UI 표시. 게임을 자동 실행하지 않았습니다. 다음 실행에서 이 부분을 확인할 수 있습니다.
