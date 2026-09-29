# Cobblemon UI 본가 계열 테마 실제 월드 실행 증거

| 항목 | 값 |
| --- | --- |
| Status | `evidence` |
| Captured | 2026-09-26 |
| Verifies | `COBBLEMON_UI_MAINLINE_THEME_PRESETS_AMENDMENT.md`의 같은 월드 Gallery 비교 게이트 |
| 대상 | `cobblemon-ui-kit` 내장 테마 6종 |
| 실행 월드 | 개발 월드 `ui-kit-clean`, `minecraft:overworld` |

## 1. 실행 결과

다음 명령으로 Minecraft의 `--quickPlaySingleplayer` 경로를 사용해 저장된 개발 월드에 실제로 입장했다.

```powershell
$env:COBBLEMON_UI_KIT_CAPTURE_WORLD='1'
$env:COBBLEMON_UI_KIT_CAPTURE_ALL_THEMES='1'
.\gradlew.bat :cobblemon-ui-kit:runClient --no-daemon --configure-on-demand --args="--quickPlaySingleplayer ui-kit-clean"
```

한 번의 월드 세션에서 `league_neon`, `galar_stadium`, `paldea_portal`, `hoenn_pixel`, `johto_touch`, `unova_pixel` 순서로 Gallery를 열었다. 각 프리셋에서 다음 경로가 완료됐다.

- 상단 상태 캡처
- TAB 입력 뒤 `CobblemonUiButton` 포커스 확인
- 스크롤 입력 소비와 하단 상태 캡처
- 화면 닫기

마지막 프리셋을 닫은 뒤에도 플레이어와 월드는 로드된 상태였으며 클라이언트가 정상 종료됐다. 실행은 `BUILD SUCCESSFUL`로 끝났다.

## 2. 산출물

캡처 파일은 빌드 산출물과 같은 로컬 실행 증거이므로 Git에는 넣지 않는다. 논리 화면은 427×240이고 저장 PNG는 Windows 배율을 반영한 854×480이다.

```text
cobblemon-ui-kit/run/screenshots/ui-kit-world-{theme}-top-427x240.png
cobblemon-ui-kit/run/screenshots/ui-kit-world-{theme}-scrolled-427x240.png
cobblemon-ui-kit/run/screenshots/ui-kit-mainline-theme-comparison.png
```

비교 보드는 여섯 상단 캡처 전체를 자르거나 늘리지 않고 3×2로 배치했다. 참고 자료 비교 보드는 다음 로컬 파일이다.

```text
cobblemon-ui-kit/run/reference-images/mainline-ui-reference-board.png
```

## 3. 관찰한 차이

| 프리셋 | 실제 캡처에서 확인한 문법 | League Challenge 적합성 |
| --- | --- | --- |
| `league_neon` | 어두운 남색 셸, 청록 선과 챔퍼 | 기존 호환 기준선 |
| `galar_stadium` | 흰 카드, 청록 선택, 분홍 위험 상태, 제한된 사선 | 리그·랭크·스포츠 시설에 가장 직접적 |
| `paldea_portal` | 강한 파랑 셸, 노랑 선택, 남색 비선택 | 밝고 현대적인 메뉴 후보 |
| `hoenn_pixel` | 올리브 셸, 굵은 안팎 경계, 평평한 상태색 | GBA식 고밀도 정보 화면 후보 |
| `johto_touch` | 밝은 초록 셸, 흰 카드, 주황 기능색 | DS 터치 메뉴에 가까운 가벼운 후보 |
| `unova_pixel` | 검은 셸, 얇은 청록선, 낮은 행과 작은 기능색 | 도트 터미널·고밀도 목록에 가장 직접적 |

`johto_touch`의 초록 셸 위 제목 대비가 낮았던 첫 캡처는 테마의 밝은 경계색을 사용하도록 수정한 뒤 다시 캡처했다. 비교 보드는 수정 후 결과만 사용한다.

## 4. 자동 검증

```powershell
.\gradlew.bat :cobblemon-ui-kit:check :cobblemon-ui-kit:build
```

- JUnit ConsoleLauncher 기준 25개 테스트 발견, 25개 통과
- 프리셋 ID 파싱, 전체 목록, 중복 ID와 모든 버튼 상태·수치 토큰 완전성 검증
- 잘못된 테마 ID의 `league_neon` 폴백 검증
- 전체 테마 캡처 환경 설정의 순회 순서 검증

이 모듈은 Gradle 기본 `test` 태스크를 비활성화하고 별도 `unitTest` 태스크를 `check`에 연결한다. 따라서 위 결과는 `check`가 호출한 실제 JUnit ConsoleLauncher 결과다.

## 5. 증거 경계

이번 실행은 실제 월드 로드, Gallery 렌더, 주입된 키보드 포커스, 스크롤과 닫기를 증명한다. 다음은 아직 증명하지 않는다.

- League Challenge 터미널 블록 우클릭과 서버 패킷 진입
- League Challenge의 실제 화면 조립과 진행 데이터
- 물리 마우스 클릭, 실제 키보드 조작과 화면 읽기 프로그램 음성
- 외부 Visual Pack 또는 자체 비트맵 글꼴·nine-slice 자산
- 독립 Battle UI의 실제 전투 생명주기

최종 League Challenge 기본 프리셋 선택은 이 비교 결과를 본 뒤 빡대리님이 결정한다. 구현자는 현재 기본값인 `league_neon`을 임의로 바꾸면 안 된다.

## 6. 2026-09-26 도트 테마 정정 후속

이 문서의 여섯 프리셋 비교는 팔레트·표면 비교 증거로는 유효하지만 `hoenn_pixel`, `johto_touch`, `unova_pixel`을 완성된 도트 UI라고 증명하지 않는다. 현행 단일 도트 후보와 최신 실제 월드 증거는 `COBBLEMON_UI_PIXEL_LEAGUE_THEME_CORRECTION.md`를 따른다.
