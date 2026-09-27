# MBC 루트 명령·타워/팩토리 승리 BP 후속 결정

> **2026-09-27 정리 메모:** 1절과 달리 권한 2 전용 `tower`·`factory`·`test` 하위 명령이 있다. 2절의 타워 2 BP는 연승 구간별 1~4 BP(+보스 5 BP)로 바뀌었다. 상세는 [인덱스의 불일치 표](../README.md#현재-코드와-다른-조항)를 본다.

| 항목 | 값 |
|---|---|
| Status | `shared` |
| Effective | 2026-08-19 |
| Updates | [`TERMINAL_AND_BP.md`](TERMINAL_AND_BP.md), `MORE_BATTLE_CONTENT_BP_REWARD_AND_SHOP_DATA_WRITE_PLAN.md`(2026-09-27 삭제, `MEMORY.md` 참고) |
| Obsoletes | `/mbc open`과 `/mbc status|start|resume|abandon|spectate|pvp|factory` 공개 명령 트리 |
| 주 독자 | 빡대리님과 MBC 본체 구현·운영 담당자 |

## 1. 명령 계약

- 일반 플레이어가 `/mbc`를 실행하면 공통 MBC GUI를 MUST 연다.
- `/mbc` 루트의 공개 자식은 `bp`만 MUST 남긴다.
- `/mbc open`, 공통 콘텐츠 `status/start/resume/abandon`, 원격 `spectate`, 구형 `pvp`와 `factory` 하위 명령은 등록하지 않아야 한다(MUST NOT).
- `/mbc`, `/mbc bp`, `/mbc bp history`는 별도 운영자 권한을 요구하지 않아야 한다(MUST NOT).
- 타인 BP 조회·이력과 `/mbc bp add|remove|set`은 Minecraft 권한 레벨 2 이상만 허용해야 한다(MUST).
- GUI·패킷은 제거된 명령 문자열을 조립하거나 안내해서는 안 된다(MUST NOT). 구형 클라이언트용 PvP 안내도 `/mbc` 진입만 안내해야 한다(MUST).

## 2. 승리 BP 계약

- 배틀타워와 배틀팩토리의 명시적 플레이어 승리는 각각 2 BP를 MUST 지급한다.
- 패배, 무승부, 강제 종료와 중복 완료 콜백은 BP를 지급하지 않아야 한다(MUST NOT).
- Cobblemon 전투 UUID를 BP 거래 UUID로 사용해 같은 전투의 완료 재시도를 멱등 처리해야 한다(MUST).
- 거래 종류는 `CONTENT_REWARD`, source는 각 콘텐츠 ID, reason은 `<content_id>_win`이어야 한다(MUST).
- BP 정산은 전투 진행 기록 커밋보다 먼저 시도해야 한다(SHOULD). 이후 기록 저장이 실패해 재시도되더라도 이미 적용된 거래는 `ALREADY_APPLIED`로 받아 중복 지급을 막는다.
- 정산 뒤 서버는 열린 MBC 화면의 공통 BP 헤더를 다시 전송해야 한다(MUST).
- 타워 승급 보너스와 팩토리 연승·라운드 보너스는 이번 결정에 포함하지 않는다. 별도 후속 결정 전에는 일반 승리 2 BP만 지급한다(MUST).

## 3. 채택하지 않은 대안

- `/mbc open`을 별칭으로 남기는 안은 진입 명령을 하나로 통일한다는 목적과 충돌해 채택하지 않았다.
- GUI 뒤에 구형 Factory/PvP 명령 빌더를 숨겨 남기는 안은 죽은 공개 표면과 번역을 계속 유지하므로 채택하지 않았다.
- 기록 저장 뒤 BP를 정산하는 순서는 BP 저장 실패 때 완료된 전투를 안전하게 다시 처리하기 어려워 채택하지 않았다.

## 4. 구현·검증 상태

- 2026-08-19 현재 타워·팩토리 승리 2 BP, 전투 UUID 멱등 정산, BP 헤더 갱신과 축소된 명령 트리가 구현됐다.
- 본체 480개 테스트와 clean build가 성공했고 JDK 21 JAR 검증이 통과했다.
- 제품 JAR SHA-256은 `7ABFB8F763A3D3D969090CB34B2C597FA77EE223984D25D5B843CA18ED8D4591`이다.
- 2026-08-19 16:42 제품 JAR을 `dev-server`와 종료 상태의 `cobblemon-dev`에 배치해 양쪽 SHA-256을 제품 해시와 일치시켰다. 서버 Java PID 17536이 `127.0.0.1:25566`과 `[16:41:59] Done (1.634s)`에 도달했다. 실제 승리 후 `+2 BP`, 저장 파일 생성, 명령 노출과 권한 거부는 런타임 검증이 남아 있다.
