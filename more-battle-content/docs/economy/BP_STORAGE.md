# More Battle Content BP 저장 형식 결정

| 항목 | 값 |
|---|---|
| Status | `shared` |
| Effective | 2026-08-18 |
| Updates | [`TERMINAL_AND_BP.md`](../architecture/TERMINAL_AND_BP.md) |
| 주 독자 | 본체 BP·보상·상점·운영 명령 구현 담당자 |

## 1. 목적

본체 BP 경제가 서버 재시작 뒤에도 잔액, 거래 이력과 콘텐츠 보상 멱등성을 유지하기 위한 첫 영구 저장 형식을 확정한다. 기존 Battle Facilities 저장 데이터의 변환 여부는 이 결정에 포함하지 않는다.

## 2. 값 계약

- **MUST:** 신규 플레이어의 초기 잔액은 `0 BP`다.
- **MUST:** 잔액과 요청 금액은 음수가 아닌 `Long` 범위로 유지한다.
- **MUST:** 추가 연산의 `Long` 오버플로, 차감 시 잔액 부족과 음수 설정을 전체 거래 실패로 처리한다.
- **MUST:** 실패한 거래는 잔액, 거래 이력과 SavedData dirty 상태를 바꾸지 않는다.
- **MUST:** 거래 이력 조회는 최신 거래부터 반환한다.
- **MAY:** 한 번의 명령 조회가 반환하는 이력 수에는 서버 안전을 위한 상한을 둘 수 있다. 첫 상한은 100건이다.

## 3. 멱등 거래 계약

- **MUST:** 거래 식별자는 UUID다.
- **MUST:** 멱등 키는 플레이어 UUID와 거래 UUID의 조합이다. 같은 협동 보상 거래 UUID를 여러 참가자에게 사용할 수 있다.
- **MUST:** 같은 플레이어·거래 UUID와 동일한 종류·요청값·출처·사유가 재요청되면 새 변경 없이 최초 거래 결과를 반환한다.
- **MUST:** 같은 플레이어·거래 UUID에 다른 내용이 들어오면 충돌로 거부한다.
- **MUST:** 거래는 종류, 요청값, 변경 전 잔액, 변경 후 잔액, 출처 ID, 사유와 서버 기록 시각을 보존한다.
- **MUST:** 첫 스키마에서 거래 이력과 거래 UUID를 삭제하거나 기간 만료시키지 않는다. 이 정책을 바꾸려면 저장 용량과 중복 지급 안전성을 함께 다루는 후속 결정을 작성한다.

첫 거래 종류는 다음과 같다.

| 종류 | 잔액 동작 |
|---|---|
| `CONTENT_REWARD` | 양수만 추가 |
| `ADMIN_ADD` | 양수만 추가 |
| `ADMIN_REMOVE` | 양수만 차감 |
| `ADMIN_SET` | 음수가 아닌 값으로 설정 |
| `SHOP_PURCHASE` | 양수 비용을 차감. 실제 품목 지급과 카트 원자성은 상점 구현 단계에서 추가 검증 |

## 4. NBT 스키마 1

SavedData 파일 ID는 `cobblemon_more_battle_content_bp`다.

```text
SchemaVersion: 1
Accounts: List<Compound>
  Player: UUID
  Balance: Long
  Transactions: List<Compound>
    Id: UUID
    Kind: String
    RequestedValue: Long
    BalanceBefore: Long
    BalanceAfter: Long
    Source: String
    Reason: String
    RecordedAt: Long
```

- **MUST:** 계정 거래 사슬은 최초 `0`에서 시작하고 각 거래의 `BalanceAfter`가 다음 거래의 `BalanceBefore`와 같아야 한다.
- **MUST:** 마지막 거래의 `BalanceAfter`와 계정 `Balance`가 같아야 한다.
- **MUST:** 같은 플레이어 계정 안에 거래 UUID가 중복돼서는 안 된다.
- **MUST:** 필수 태그 누락과 잘못된 NBT 타입을 기본값이나 빈 목록으로 변환해서는 안 된다.
- **MUST:** 지원하지 않거나 손상된 스키마는 원본 NBT를 보존한 채 BP 읽기·쓰기를 안전 정지한다.
- **MUST:** 잔액과 거래 원장은 같은 SavedData 변경으로 반영한다. 별도 파일에 나눠 부분 저장 상태를 만들지 않는다.

## 5. 명령 계약

- **MUST:** 일반 플레이어는 `/mbc bp`와 `/mbc bp history [count]`로 자신의 잔액과 이력을 조회할 수 있다.
- **MUST:** 다른 플레이어 조회와 `/mbc bp add|remove|set`은 Minecraft 권한 레벨 2 이상만 사용할 수 있다.
- **MUST:** 관리자 변경도 별도 저장 우회 없이 같은 BP 서비스와 거래 원장을 사용한다.
- **MUST:** 관리자 명령 한 번마다 새 거래 UUID를 발급하고 출처는 `cobblemon_more_battle_content:admin_command`로 기록한다.

## 6. 제외 및 기각한 대안

- 기존 BP 잔액과 거래 이력을 자동 변환하지 않는다. 마이그레이션은 원본 데이터 확인과 별도 사용자 결정 뒤에만 추가한다.
- 첫 버전에서 거래 이력을 일정 건수로 자르는 안은 채택하지 않는다. 삭제한 멱등 키로 과거 콘텐츠 보상이 다시 지급될 수 있기 때문이다.
- 잔액과 멱등 원장을 별도 SavedData 파일에 나누는 안은 채택하지 않는다. 저장 중단 시 두 파일이 서로 다른 거래 상태를 가질 수 있기 때문이다.
- 실제 배틀 결과 집계와 BP 지급을 임시 메모리 플래그로 묶지 않는다. 배틀타워 세션 저장을 구현할 때 재시작·부분 실패 복구 계약과 함께 연결한다.
