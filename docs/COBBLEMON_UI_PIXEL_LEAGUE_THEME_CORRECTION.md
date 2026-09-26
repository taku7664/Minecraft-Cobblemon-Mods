# Cobblemon UI 단일 도트 리그 테마 정정

| 항목 | 값 |
| --- | --- |
| Status | `shared` |
| Effective | 2026-09-26 |
| Updates | `COBBLEMON_UI_MAINLINE_THEME_PRESETS_AMENDMENT.md` |
| Does not obsolete | 공용 위젯 의미, 입력·포커스 계약, 현대식 테마 실험 |
| 적용 대상 | `cobblemon-ui-kit`의 도트 UI와 League Challenge 시각 후보 |

## 1. 정정

`hoenn_pixel`, `johto_touch`, `unova_pixel`은 세대별 색·경계 비교용 **팔레트 연구**다. 이 세 프리셋을 도트 UI 구현 완료로 부르면 안 된다(MUST NOT). 동일한 Gallery 배치와 Minecraft 기본 글꼴에 색만 바꾼 결과는 다음 핵심 문법을 증명하지 못한다.

- 픽셀 이중 프레임과 하드 섀도
- 정수 격자 패딩과 평평한 채움
- 선택 커서와 상태별 스프라이트
- 아이콘이 결합된 메뉴 항목
- nearest-neighbor로 표시되는 내장 비트맵 자산

빡대리님이 요구한 것은 세대별 복제 테마 여러 개가 아니라, 여러 Pokémon 도트 게임에서 공통으로 보이는 문법을 한 벌의 재사용 가능한 테마로 만든 것이다. 따라서 최종 도트 후보는 `pixel_league` 하나로 정의한다(MUST).

## 2. `pixel_league` 계약

`pixel_league`는 다음을 제공해야 한다(MUST).

1. `UiBorder.PixelFrame`: 어두운 외곽선, 밝은 안쪽 선, 아래·오른쪽 음영과 바깥 하드 섀도.
2. 셸·패널·버튼의 단색 채움. 도트 외형을 흉내 내기 위한 부드러운 그라데이션을 사용하지 않는다.
3. `FOCUS`와 `SELECTED` 상태에 내장 8×8 선택 커서 스프라이트.
4. `UiIcon` 경로를 실제 리소스로 그리는 버튼 렌더링과 내장 8×8 정보 아이콘.
5. 작은 화면에서도 클릭 영역을 줄이지 않는 별도 정수 패딩·버튼 높이.
6. 상단 도트 패턴과 평평한 제목 막대를 포함한 Gallery 시각 증거.

자산은 다음 경로에 JAR 기본 리소스로 포함한다.

```text
assets/cobblemon_ui_kit/textures/gui/pixel/info.png
assets/cobblemon_ui_kit/textures/gui/pixel/selector.png
```

이 자산은 원작 스프라이트를 복사하지 않고 프로젝트용으로 새로 만든다(MUST). 외부 Visual Pack은 같은 의미 경로를 덮어쓸 수 있지만 입력·포커스·상태 계약을 바꾸면 안 된다(MUST NOT).

## 3. 기존 프리셋의 지위

| 프리셋 | 현행 지위 |
| --- | --- |
| `league_neon` | 기존 호환 기준선 |
| `pixel_league` | 단일 도트 UI 후보 |
| `galar_stadium`, `paldea_portal` | 현대식 평면 UI 비교 후보 |
| `hoenn_pixel`, `johto_touch`, `unova_pixel` | 세대별 팔레트 연구; 최종 도트 테마가 아님 |

세대별 별도 레이아웃과 스킨을 추가로 만드는 것은 현재 요구가 아니다(MUST NOT). 과거 게임 자료는 공통 문법을 추출하는 참고로만 사용한다.

## 4. 개발 실행 경고

Cobblemon 소스의 `SnapshotWarningScreen`은 스냅샷 빌드에서만 초기 화면에 삽입될 수 있다. 이 화면이 나타나면 빠른 월드 입장이 사용자의 확인을 기다리므로 자동 캡처가 멈춘다.

개발 캡처는 `COBBLEMON_UI_KIT_ACCEPT_SNAPSHOT_WARNING=1`을 명시한 경우에만 해당 화면의 **Yes**를 선택할 수 있다(MAY). 이 경로는 “다시 표시하지 않음”을 저장하면 안 되며(MUST NOT), 일반 플레이나 환경변수가 없는 실행에서는 경고를 건드리면 안 된다(MUST NOT).

현재 검증에 사용한 Cobblemon `1.8.1`은 런타임 보고상 `Is Snapshot: false`였으므로 경고 분기 자체는 실제 실행에서 재현되지 않았다. 설정 파싱과 컴파일은 검증했지만, 스냅샷 빌드의 화면 통과를 런타임 검증 완료로 보고하면 안 된다(MUST NOT).

## 5. 실행 증거

개발 월드 `ui-kit-clean`에 `--quickPlaySingleplayer`로 입장해 `pixel_league`의 상단·스크롤 상태를 캡처했다. 렌더, TAB 포커스, 스크롤 소비, 닫기와 클라이언트 정상 종료를 확인했다.

첫 실행은 닫기 뒤 하네스가 한 틱 더 진행해 `UI Kit gallery closed before capture`로 실패했다. `GalleryCaptureLifecycle`을 추가해 완료 상태를 멱등하게 만든 뒤 같은 명령을 다시 실행했고 `BUILD SUCCESSFUL`로 종료했다. 이 실패와 수정은 자동 실행 안정성 증거에 포함한다.

```text
cobblemon-ui-kit/run/screenshots/ui-kit-world-pixel_league-top-427x240.png
cobblemon-ui-kit/run/screenshots/ui-kit-world-pixel_league-scrolled-427x240.png
```

단위·빌드 검증은 29개 테스트 전부 통과했다.
