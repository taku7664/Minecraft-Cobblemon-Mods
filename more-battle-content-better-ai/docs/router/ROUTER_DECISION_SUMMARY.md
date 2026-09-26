# Better AI Router 공개 판단 근거 로그 후속 결정

| 항목 | 값 |
|---|---|
| Status | `shared` |
| Effective | 2026-08-19 |
| Updates | `../behavior/HUMANLIKE.md`, `../architecture/BRAIN_OWNERSHIP.md` |
| Scope | OpenRouter 응답의 선택형 공개 근거 한 문장, 서버 운영 로그와 서버 전용 JSONL 장기 기록 |
| 주 독자 | 빡대리님과 Better AI 서버 운영·구현 담당자 |

## 1. 설정과 요청 계약

- 서버 전용 `openrouter.json`은 `logDecisionSummary` Boolean을 받을 수 있어야 한다(MUST).
- 키가 없거나 기존 스키마 1·2 파일에서 생략되면 `false`여야 한다(MUST). 새 안전 기본 파일도 `false`를 기록해야 한다(MUST).
- `false`이면 Router 요청 지시와 응답 JSON Schema에서 `decisionSummary`를 완전히 빼야 한다(MUST). 숨긴 문장을 계속 생성해 토큰을 소비해서는 안 된다(MUST NOT).
- `true`이면 응답 JSON Schema에 필수 문자열 `decisionSummary`를 추가하고, 선택한 `actionId`의 결정적인 공개 전투 사실만 한 문장으로 요청해야 한다(MUST).

## 2. 실행과 로그 경계

- `decisionSummary`는 숨은 사고과정이나 원시 chain-of-thought가 아니다(MUST NOT). 공개 보드·공개 사건·서버 계산 사실 중 선택에 결정적이었던 항목을 짧게 요약한 운영 진단이다(MUST).
- 실행은 기존처럼 서버 검증을 통과한 `actionId`만 사용해야 한다(MUST). 요약은 합법성, 점수, 행동 선택, 계획, 기억, 폴백과 보상을 바꾸어서는 안 된다(MUST NOT).
- 모델 문장은 개행·제어·format 문자를 제거하고 공백을 정규화한 뒤 최대 240자로 잘라야 한다(MUST).
- 유효한 Router 행동이 선택되고 설정이 `true`일 때만 서버 콘솔과 `logs/latest.log`에 `[MBC Better AI] Router decision summary` 접두어로 한 줄을 기록해야 한다(MUST).
- 로그는 배틀 ID, 턴, 서버 발급 행동 ID, 난도와 정제된 요약만 포함할 수 있다(MAY). API 키, Authorization 헤더, 요청 JSON, 전체 프롬프트, 원시 응답 본문과 예외 메시지는 포함해서는 안 된다(MUST NOT).
- 요약이 없거나 정제 뒤 비면 `<unavailable>`로 표시할 수 있으며, 이미 합법인 행동을 실패 처리해서는 안 된다(MUST NOT).

## 3. 이전 자유 텍스트 금지 조항의 갱신

2026-08-19부터 `../behavior/HUMANLIKE.md`의 모델 자유 텍스트 로그 금지는 원시 자유 응답과 숨은 사고과정에 계속 적용한다. 다만 이 문서의 좁은 공개 근거 한 문장은 서버가 명시적으로 요청하고 길이·문자를 제한한 구조화 필드이므로 예외다. 이 예외를 원시 응답이나 클라이언트·채팅 출력 허용으로 넓혀 해석해서는 안 된다(MUST NOT).

## 4. 채택하지 않은 대안

- OpenRouter의 원시 reasoning이나 전체 응답을 기록하는 방식은 비공개 추론·프롬프트·운영 정보가 섞일 수 있어 채택하지 않았다.
- 설정이 `false`인데 문장을 생성한 뒤 로그만 버리는 방식은 불필요한 토큰과 지연을 만들므로 채택하지 않았다.
- 첫 구현에서 플레이어 채팅이나 클라이언트 GUI로 보내는 방식은 다음 행동 계획과 서버 내부 진단을 노출할 수 있어 채택하지 않았다.

## 5. 검증 계약

- 기본값·기존 파일 누락은 `false`, 명시한 `true`는 활성화되는 설정 테스트가 있어야 한다(MUST).
- 비활성 요청에는 필드와 지시가 없고 활성 요청에는 둘 다 있는 스키마 테스트가 있어야 한다(MUST).
- 개행·제어문자 제거, 240자 상한과 비밀값 미기록 검사가 있어야 한다(MUST).
- 실제 모델 문장은 배포 후 비용이 발생하는 전투 턴에서 별도로 확인해야 한다(SHOULD).

## 6. 장기 조회 JSONL 기록

2026-08-19 후속 결정(구 `BETTER_AI_ROUTER_DECISION_JOURNAL_DECISION.md`)을 이 문서에 병합했다.

### 6.1 목적과 활성 조건

- `logDecisionSummary=true`일 때 합법 Router 판단의 공개 근거를 `logs/mbc-better-ai-router-decisions.jsonl`에 계속 추가해야 한다(MUST).
- 설정이 `false`이면 기존 결정처럼 Router 요청에서 `decisionSummary`를 제거하고 JSONL 파일도 만들거나 갱신해서는 안 된다(MUST NOT).
- 이 파일은 서버 운영자가 전투 뒤 판단 문장을 모아서 보는 진단 기록이며 클라이언트·채팅으로 전송하지 않는다(MUST NOT).

### 6.2 파일 형식

- UTF-8 JSON Lines 형식을 사용하고 Router 판단 하나를 JSON 객체 한 줄로 기록해야 한다(MUST).
- 각 줄은 `timestamp`, `battle`, `turn`, `actionId`, `difficulty`, `summary`만 기록해야 한다(MUST).
- 기존 파일을 덮어쓰지 않고 append해야 하며 서버 재기동 뒤에도 이전 줄을 유지해야 한다(MUST).
- 모델 문장은 기존 계약과 같이 제어·format 문자와 개행을 제거하고 공백을 정규화한 뒤 240자로 제한해야 한다(MUST).
- API 키, Authorization 헤더, 요청 JSON, 프롬프트, 원시 응답과 예외 메시지는 기록해서는 안 된다(MUST NOT).

### 6.3 판단 비간섭 계약

- 서버가 검증한 `BattleDecision` future를 먼저 완료한 뒤 JSONL 기록을 시도해야 한다(MUST).
- 파일 기록은 Router 판단 실행기와 분리된 전용 단일 스레드 실행기에서 수행해야 한다(MUST).
- 디렉터리 생성·파일 열기·append가 실패해도 이미 합법인 행동, 기억, 폴백과 배틀 결과를 바꾸어서는 안 된다(MUST NOT).
- 기록 실패 로그는 예외 타입만 포함할 수 있고 예외 메시지는 포함해서는 안 된다(MUST NOT).

### 6.4 채택하지 않은 대안

- 10초마다 메모리 버퍼를 일괄 저장하는 방식은 서버 종료 시 마지막 묶음을 잃을 수 있어 채택하지 않았다.
- `latest.log`만 사후 파싱하는 방식은 다른 서버 로그와 섞이고 재기동별 파일을 찾아야 하므로 전용 장기 조회 경로로 채택하지 않았다.
- 원시 모델 응답 전체 저장은 공개 근거 한 문장 경계를 깨므로 금지한다.

### 6.5 검증 계약

- 두 판단을 기록하면 두 JSON 줄이 순서대로 남는 테스트가 있어야 한다(MUST).
- 개행·제어문자 정제와 필드 값을 JSON 파서로 다시 읽는 테스트가 있어야 한다(MUST).
- 파일 append 실패가 합법 Router 행동을 실패로 바꾸지 않는 테스트가 있어야 한다(MUST).
- 파일 append가 멈춰도 완료된 Router 행동 반환을 막지 않는 테스트가 있어야 한다(MUST).
- 서버 기동 때 활성 파일 경로가 로그에 나타나는지 확인해야 한다(MUST).
- 실제 JSON 한 줄 생성은 비용이 발생하는 Router 성공 턴 뒤 별도로 확인해야 한다(SHOULD).
