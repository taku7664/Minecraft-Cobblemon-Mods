# Better Cobblemon Music 설정 스키마 2

| 항목 | 값 |
|---|---|
| Status | `shared` |
| Effective | 2026-08-23 |
| Updates | `RELIABILITY_UPDATE_2026-08-22.md` §6 |
| 대상 | `music.json`의 필드 바이옴 규칙과 플레이리스트 |
| 주 독자 | 설정 작성자와 이후 구현·검증 담당자 |

## 1. 호환 계약

- 파서는 스키마 1과 스키마 2를 모두 읽어야 **MUST** 한다.
- 기존 사용자 `music.json`을 자동으로 수정하거나 덮어써서는 안 된다 **MUST NOT**.
- 새 설치에 생성하는 기본 설정은 스키마 2여야 **MUST** 한다.
- 공개 `FieldMusicConfig` Java 형태와 선곡 우선순위는 이번 변경에서 바꾸지 않는다 **MUST NOT**.

## 2. 스키마 2 필드 구조

- `field.biomes`는 정확한 바이옴 ID를 키로 갖는 객체여야 **MUST** 한다. 태그를 이 객체에 섞어서는 안 된다 **MUST NOT**.
- `field.biomeTags`는 `{ "tag", "playlist" }` 항목의 배열이어야 **MUST** 한다.
- `field.biomePathContains`는 `{ "contains", "playlist" }` 항목의 배열이어야 **MUST** 한다.
- 두 배열은 위에서 아래로 평가하며 먼저 맞은 규칙을 선택해야 **MUST** 한다.
- 같은 태그 또는 경로 조각을 배열에 중복해서는 안 된다 **MUST NOT**.

## 3. 복수 음원

- 모든 `playlist` 값과 정확한 바이옴 값은 단일 `.ogg` 문자열, `.ogg` 문자열 배열, 상세 플레이리스트 객체를 허용해야 **MUST** 한다.
- 상세 객체는 `selection`, `volume`, `betweenTracksSeconds`, `tracks`를 사용할 수 있다 **MAY**.
- `selection`은 `shuffle`, `random`, `sequential` 중 하나여야 **MUST** 한다.
- 같은 바이옴에 머무는 동안 현재 곡이 끝나면 해당 플레이리스트에서 다음 곡을 골라야 **MUST** 한다. 여러 곡을 동시에 겹쳐 재생하는 기능으로 해석해서는 안 된다 **MUST NOT**.

## 4. 내부 변환과 되돌리기

- 파서는 스키마 2의 순서형 배열을 기존 순서 보존 맵으로 변환할 수 있다 **MAY**. 외부 Java 모델을 넓히는 것은 요구하지 않는다.
- 문제 발생 시 기본 설정을 스키마 1 형식으로 되돌리고 스키마 2 파서 분기를 제거할 수 있어야 **SHOULD** 한다. 사용자 스키마 1 파일에는 별도 역마이그레이션이 필요하지 않다.
