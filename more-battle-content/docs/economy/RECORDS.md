# More Battle Content 기록·리더보드 집계 저장 결정

> **2026-09-27 정리 메모:** 2절의 랭크 지표(`current_rank`·`highest_rank` 등)는 타워 연승 전환 뒤 타워 진행에 쓰이지 않는다. 상세는 [인덱스의 불일치 표](../README.md#현재-코드와-다른-조항)를 본다.

| 항목 | 값 |
|---|---|
| Status | `shared` |
| Effective | 2026-08-18 |
| Updates | [`DESIGN.md`](../architecture/DESIGN.md), [`CONTENT_SCOPE.md`](../architecture/CONTENT_SCOPE.md) |
| 결정자 | 빡대리님 |
| 주 독자 | 본체 기록·콘텐츠·리더보드 구현 담당자 |

## 1. 목적

본체가 리더보드 순위를 계산하고 플레이어의 콘텐츠 기록을 표시하는 데 필요한 누적 집계만 저장한다. 경기별 감사 원장이나 진행 중 세션 복구는 이 저장소의 목적이 아니다.

## 2. 저장 계약

- **MUST:** 기록은 플레이어 UUID, 콘텐츠 ID와 형식 ID 조합별로 분리한다.
- **MUST:** 전체 승리 수, 전체 패배 수, 현재 연승과 최고 연승을 저장한다.
- **MUST:** 배틀타워 현재 랭크·랭크 진행도처럼 현재 상태를 나타내는 콘텐츠별 진행 지표를 저장할 수 있어야 한다.
- **MUST:** 최고 랭크, 최고 층, 최고 점수처럼 리더보드 정렬에 쓰는 콘텐츠별 최고 지표를 저장할 수 있어야 한다.
- **MUST:** 싱글과 더블 기록을 서로 섞지 않는다.
- **SHOULD:** 콘텐츠별 숫자 지표는 안정된 소문자 식별자와 음수가 아닌 정수 값으로 저장한다.
- **MUST:** 지원하지 않거나 손상된 스키마를 현재 버전 형식으로 덮어쓰지 않는다. 해당 저장소의 갱신을 중단하고 원본 데이터를 보존한다.
- **MAY:** 이후 콘텐츠가 별도 최고 지표를 추가할 수 있다. 새 지표는 기존 지표의 의미를 바꾸지 않는다.

현재 구현의 저장 파일 ID는 `cobblemon_more_battle_content_records`, 스키마 버전은 `1`이다. 각 레코드는 다음 집계만 가진다.

```text
Player UUID
Content ID
Format ID
TotalWins / TotalLosses
CurrentWinStreak / BestWinStreak
ProgressMetrics: metric ID -> non-negative long
BestMetrics: metric ID -> non-negative long
```

기본 지표 ID는 `current_rank`, `rank_progress`, `highest_rank`, `current_floor`, `highest_floor`, `best_score`다.

## 3. 저장하지 않는 정보

- **MUST NOT:** 리더보드 집계 저장소에 경기별 결과 이력이나 전체 행동 기록을 보존하지 않는다.
- **MUST NOT:** 진행 중 배틀 세션, 팀 스냅샷 또는 재시작 후 세션 복구 정보를 넣지 않는다.
- **MUST NOT:** 초기 자유 대전 PvP에 시즌·레이팅 데이터를 미리 추가하지 않는다.
- **MUST NOT:** 리더보드 집계만을 위해 영구 정산 ID 원장을 만들지 않는다.

이 결정은 BP의 멱등 거래 원장, 보상 중복 방지 또는 콘텐츠 자체의 필수 진행 저장을 제거하지 않는다. 해당 데이터는 기록·리더보드 집계와 별도 책임으로 유지한다.

## 4. 아직 포함하지 않는 범위

- 리더보드 정렬 기준과 동점 순위 규칙
- 네트워크 동기화와 화면
- 기존 모드 기록 마이그레이션
- 실제 배틀 종료 서비스와 집계 저장소 연결

## 5. 기각한 대안

경기별 결과 원장과 세션 복구 데이터를 리더보드 저장소에 함께 두는 안은 채택하지 않는다. 빡대리님이 요구한 범위는 승패·연승·진행도·최고 기록을 이용한 순위 산출이며, 경기 재현이나 세션 복구가 아니기 때문이다.
