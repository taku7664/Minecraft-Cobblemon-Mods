# Better AI Router 판단 품질 모드 후속 결정

| 항목 | 값 |
|---|---|
| Status | `shared` |
| Effective | 2026-08-18 |
| Updates | `../architecture/BRAIN_OWNERSHIP.md` §§2, 4, 6과 `../architecture/DESIGN.md` 외부 모델 설정 |
| Scope | OpenRouter 판단 요청의 추론 effort, 출력 토큰 상한과 설정 읽기 호환 |
| 주 독자 | 빡대리님과 Better AI 설정·OpenRouter 구현 담당자 |

## 1. 판단 소유권

- `QUALITY`, `BALANCED`, `ECONOMY`는 Router 요청의 추론 예산만 바꿔야 한다(MUST).
- 세 모드 모두 합법 후보가 둘 이상인 모든 판단 요청을 Router에 보내야 한다(MUST).
- 모드 때문에 후보, 공개 상태, 계산 사실, 전략, 기억 또는 최근 사건을 삭제·정렬·요약해서는 안 된다(MUST NOT).
- 합법 후보가 하나뿐이면 기존 계약대로 외부 호출 없이 그 행동을 반환할 수 있다(MAY).
- Router 실패 시에만 로컬 Brain으로 넘어가는 폴백 순서는 바뀌지 않는다(MUST).

## 2. 모드별 요청

| 모드 | `reasoning.effort` | `max_tokens` | 기본값 |
|---|---:|---:|---:|
| `QUALITY` | `high` | 8,192 | 예 |
| `BALANCED` | `medium` | 4,096 | 아니오 |
| `ECONOMY` | `low` | 2,048 | 아니오 |

- reasoning을 사용하더라도 내부 추론 텍스트를 응답에 포함하지 않도록 `reasoning.exclude = true`를 보내야 한다(SHOULD).
- 모델이 reasoning effort 선택을 지원한다고 공식 모델 메타데이터로 확인된 경우에만 `reasoning`을 보내야 한다(MUST).
- 지원하지 않거나 메타데이터가 아직 없거나 조회에 실패한 경우에는 `reasoning`만 생략하고 같은 Router 판단 요청을 즉시 보내야 한다(MUST). 메타데이터 조회를 기다리느라 판단을 지연하거나 로컬 Brain으로 넘겨서는 안 된다(MUST NOT).
- 모델 메타데이터가 `temperature`를 지원하지 않는다고 명시하면 해당 파라미터를 보내서는 안 된다(MUST NOT).
- reasoning을 보낼 때는 구조화 출력 설정과 무관하게 `provider.require_parameters = true`로 지원 제공자만 허용해야 한다(MUST).

## 3. 모델 메타데이터와 보안

- 공식 `openrouter.ai` 또는 그 하위 도메인의 채팅 엔드포인트에서만 `/api/v1/model/{author}/{slug}` 조회 주소를 유도해야 한다(MUST).
- 사용자 지정 호스트에서는 메타데이터 주소를 추측하거나 API 키를 다른 호스트로 전달해서는 안 된다(MUST NOT).
- 메타데이터 조회에는 API 키가 필요하지 않으므로 Authorization 헤더를 보내지 않아야 한다(MUST).
- 메타데이터 조회는 서버 초기화 때 비동기로 시작하며 Router 판단 요청의 제한 시간에 포함하거나 선행 조건으로 삼아서는 안 된다(MUST NOT).
- 단위 테스트는 HTTP 메타데이터 전송 구현을 명시적으로 주입하지 않는 한 외부 네트워크를 시작해서는 안 된다(MUST NOT).

## 4. 설정 스키마 2

- 새 설정 파일은 `schemaVersion: 2`와 `decisionMode: "QUALITY"`를 써야 한다(MUST).
- 허용 값은 `QUALITY`, `BALANCED`, `ECONOMY`뿐이다(MUST).
- 기존 스키마 1은 `QUALITY`로 읽되 디스크 파일을 자동으로 다시 쓰지 않아야 한다(MUST).
- 스키마 1의 `maximumCallsPerBattle`은 읽기 호환만 유지하고 실행에 사용하거나 새 스키마 2 파일에 쓰지 않아야 한다(MUST NOT).
- 알 수 없는 스키마나 모드는 설정 오류로 처리해 외부 Brain을 등록하지 않고 로컬 폴백을 유지해야 한다(MUST).

## 5. 검증 계약

- 세 모드에서 동일한 공개 입력의 프롬프트 본문이 같고 추론 effort와 출력 토큰 상한만 다른지 검증해야 한다(MUST).
- 세 모드 각각 연속된 복수 후보 요청을 모두 Router에 보내는지 검증해야 한다(MUST).
- reasoning 미지원·메타데이터 미확정·조회 실패가 Router 호출 자체를 막지 않는지 검증해야 한다(MUST).
- 스키마 1 파일이 `QUALITY`로 읽힌 뒤 바이트 수준으로 다시 쓰이지 않는지 검증해야 한다(MUST).
- 새 기본 설정에 API 키가 비어 있고 `maximumCallsPerBattle`이 쓰이지 않는지 검증해야 한다(MUST).

## 6. 채택하지 않은 방식

- `ECONOMY`에서 Router 호출 횟수를 줄이는 방식은 Router 판단 소유권을 깨므로 채택하지 않았다.
- `ECONOMY`에서 사건 기록이나 후보 설명을 줄이는 방식은 비용 차이가 모델 사고 예산이 아니라 정보 격차가 되므로 채택하지 않았다.
- 모델 이름 문자열에 따라 reasoning 지원을 하드코딩하는 방식은 모델과 별칭이 바뀔 때 쉽게 낡으므로 채택하지 않았다.
- 첫 판단을 메타데이터 응답까지 대기시키는 방식은 10초 판단 제한과 장애 격리를 해치므로 채택하지 않았다.
- 사용자 지정 엔드포인트에서 OpenRouter 모델 API 주소를 추측하는 방식은 호환성과 자격 증명 경계를 보장할 수 없어 채택하지 않았다.
