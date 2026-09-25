# League Challenge 문서 색인

- 상태: `planning baseline`
- 대상: 기획자, MBC 본체 구현자, League Challenge 구현자, 데이터·리소스팩 제작자
- 최종 갱신: 2026-09-25

## 문서 목록

| 문서 | 역할 |
| --- | --- |
| [DECISIONS.md](DECISIONS.md) | 지금까지 채택·보류·기각된 결정을 한곳에서 확인한다. |
| [PRODUCT_SCOPE.md](PRODUCT_SCOPE.md) | 제품 목표, 비목표, 용어와 모듈 경계를 정의한다. |
| [PROGRESSION_AND_CONTENT.md](PROGRESSION_AND_CONTENT.md) | 뱃지, 볼 계급, 레벨캡, 체육관, 사천왕, 챔피언과 후반 해금을 정의한다. |
| [DATA_AND_ASSET_PACKS.md](DATA_AND_ASSET_PACKS.md) | 교체 가능한 리그 콘텐츠의 데이터팩·리소스팩 계약을 정의한다. |
| [TERMINAL_GUI_AND_BATTLE_FLOW.md](TERMINAL_GUI_AND_BATTLE_FLOW.md) | 별도 터미널, GUI, 관장전과 리그 연전의 사용자 흐름을 정의한다. |
| [GUI_FRAMEWORK_AND_RESOURCE_PACK.md](GUI_FRAMEWORK_AND_RESOURCE_PACK.md) | MbcUI 첫 사용처, GUI 우선 수직 단면, 내장 테마와 선택형 외부 리소스팩 계획을 정의한다. |
| [EXTERNAL_INTEGRATIONS.md](EXTERNAL_INTEGRATIONS.md) | PokeBadges와 외부 레벨캡 제공자 연동 및 장애 처리를 정의한다. |
| [TRAINER_APPEARANCES.md](TRAINER_APPEARANCES.md) | 관장·사천왕·챔피언 스킨, 초상화와 홀로그램 렌더링을 정의한다. |
| [MBC_CORE_CHANGES.md](MBC_CORE_CHANGES.md) | MBC가 독립성을 유지하면서 애드온을 지원하기 위해 필요한 공개 API와 잠금 지점을 정의한다. |
| [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) | 선행 작업, 구현 단계, 검증 범위와 완료 조건을 정리한다. |

## 문서 우선순위

문서가 서로 다르게 읽힐 때는 다음 순서를 적용한다.

1. 사용자의 최신 명시적 결정
2. `DECISIONS.md`의 `채택` 항목
3. 각 세부 문서의 MUST/MUST NOT 계약
4. `방향 채택` 또는 `제안` 항목
5. 예시 JSON과 설명용 수치

예시 값은 계약이 아니다. 특히 레벨캡 수치와 기본 상대 팀은 별도 확정 전까지 구현 상수로
박아서는 안 된다(MUST NOT).

## 규범 용어

- **MUST**: 진행도 무결성, 상호운용성 또는 우회 방지를 위해 반드시 지킨다.
- **MUST NOT**: 진행도·보상·호환성을 깨뜨리므로 금지한다.
- **SHOULD**: 특별한 근거가 없다면 지킨다.
- **SHOULD NOT**: 특별한 근거가 없다면 피한다.
- **MAY**: 팩 제작자나 서버 운영자가 선택할 수 있다.
