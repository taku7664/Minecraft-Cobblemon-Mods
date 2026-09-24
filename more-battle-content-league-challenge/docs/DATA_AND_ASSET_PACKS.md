# 데이터팩 및 리소스팩 계약

## 1. 목표

League Challenge의 관장, 사천왕, 챔피언, 팀, 규칙, 보상, 외형은 코드에 고정하지 않는다. 기본 신오 리그도 같은 공개 형식으로 제공하여 서버 운영자가 교체할 수 있어야 한다.

이 문서는 아직 구현 전 설계 기준이다. 아래 JSON은 최종 스키마가 아니라 역할 분리 예시다.

## 2. 제안 경로

MBC의 기존 `mbc-` 접두사와 kebab-case 규칙을 따른다.

```text
data/<namespace>/mbc-league-challenge/leagues/*.json
data/<namespace>/mbc-league-challenge/challenges/*.json
data/<namespace>/mbc-league-challenge/trainers/*.json
data/<namespace>/mbc-league-challenge/teams/*.json
data/<namespace>/mbc-league-challenge/rewards/*.json
data/<namespace>/mbc-league-challenge/appearances/*.json
```

클라이언트 표현 자산은 리소스팩에 둔다.

```text
assets/<namespace>/textures/entity/trainers/*.png
assets/<namespace>/textures/gui/portraits/*.png
assets/<namespace>/lang/ko_kr.json
assets/<namespace>/lang/en_us.json
```

서버가 커스텀 외형을 강제하려면 데이터팩과 짝을 이루는 서버 리소스팩을 배포해야 한다.

## 3. 정의 분리

- `league`: 체육관 순서, 사천왕 순서, 챔피언, 기본 진행 규칙을 조합한다.
- `challenge`: 진입 조건, 배틀 형식, 레벨캡, 트레이너·팀·보상·외형 참조를 묶는다.
- `trainer`: 표시 이름, 대사, 분류와 같은 인물 정보를 담는다.
- `team`: 포켓몬, 기술, 특성, 성격, 아이템 등 배틀 구성을 담는다.
- `reward`: 최초 승리와 반복 승리의 보상을 구분한다.
- `appearance`: 스킨 모델과 텍스처, 선택적 초상화를 정의한다.

트레이너, 팀, 외형을 분리하여 같은 인물이 난이도별 팀을 사용하거나 같은 외형을 여러 도전에서 재사용할 수 있게 한다.

## 4. 예시 구조

다음은 의미를 설명하기 위한 예시이며 필드명은 구현 때 확정한다.

```json
{
  "schema_version": 1,
  "id": "example:sinnoh",
  "gyms": [
    "example:oreburgh",
    "example:eterna",
    "example:veilstone",
    "example:pastoria",
    "example:hearthome",
    "example:canalave",
    "example:snowpoint",
    "example:sunyshore"
  ],
  "elite_four": [
    "example:aaron",
    "example:bertha",
    "example:flint",
    "example:lucian"
  ],
  "champion": "example:cynthia"
}
```

```json
{
  "schema_version": 1,
  "id": "example:oreburgh",
  "kind": "gym",
  "trainer": "example:roark",
  "team": "example:roark/default",
  "appearance": "example:roark",
  "badge": "pokebadges:<confirmed-badge-id>",
  "level_cap": 20,
  "reward": "example:oreburgh_first_clear"
}
```

`badge`의 실제 식별자 형식은 선택한 PokeBadges 버전의 공개 API로 다시 확인한 뒤 고정한다. 예시 값을 그대로 구현 계약으로 취급하면 안 된다.

## 5. 검증과 원자적 리로드

리로드는 파일별 부분 적용이 아니라 전체 후보 스냅샷 단위로 처리해야 한다.

1. 모든 정의를 파싱한다.
2. 스키마 버전, 중복 ID, 참조 무결성, 순서, 필수 필드를 검증한다.
3. 리그가 정확히 필요한 도전들을 참조하는지 검증한다.
4. 성공하면 새 스냅샷을 한 번에 게시한다.
5. 실패하면 직전 정상 스냅샷을 유지하고 구체적인 파일·필드 오류를 기록한다.

서버는 클라이언트 리소스팩의 실제 텍스처 존재를 완전히 보증할 수 없다. 누락 자산은 클라이언트에서 안전한 기본 외형으로 대체하고 전투 자체는 중단하지 않는다.

진행 중인 배틀과 사천왕 연속 도전은 시작 시점의 정의 스냅샷을 유지한다. 리로드된 정의는 새 세션부터 적용한다.

## 6. 기본 콘텐츠

초기 기본팩은 포켓몬스터 Pt의 체육관 순서, 뱃지, 사천왕, 챔피언을 기준으로 하되 팀은 현대 Cobblemon 환경에 맞게 별도로 구성한다. 기본팩도 내장 하드코딩이 아니라 위 공개 형식을 사용한다.

기본 외형은 직접 제작하거나 재배포 허가가 확인된 자산만 포함한다. 제3자 게임 원본 스킨을 무단 동봉하지 않는다.

## 7. 안정성 규칙

- 데이터팩에는 원격 URL이나 임의 파일 경로를 허용하지 않는다.
- 모든 사용자 노출 문구는 번역 키로 제공한다.
- 정의에는 `schema_version`을 둔다.
- 스키마를 깨는 변경은 마이그레이션 문서와 함께 새 버전으로 올린다.
- 식별자는 저장 데이터와 외부 애드온 연동에 쓰이므로 출시 후 임의 변경하지 않는다.

## 8. 미확정 항목

- 서버당 활성 리그를 하나만 둘지 여러 리그를 동시에 허용할지
- 데이터팩이 레벨캡 숫자를 직접 갖는지 별도 진행 규칙이 계산하는지
- 난이도별 팀 변형의 최종 스키마
- PokeBadges 뱃지 식별자의 정확한 형식
