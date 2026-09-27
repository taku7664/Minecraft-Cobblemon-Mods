# Shadow 외형과 NPC 표시 이름 분리

| 항목 | 값 |
|---|---|
| Status | `applied` |
| 작성일 | 2026-08-19 |
| 적용일 | 2026-08-19 |
| Updates | [`PLAIN_NPC_NAMES.md`](PLAIN_NPC_NAMES.md) |
| 대상 | 배틀타워·배틀팩토리 Shadow 트레이너의 외형 프로필과 이름표 |
| 승인 | 빡대리님 승인 완료 |

## 1. 정정 이유

Shadow 트레이너는 도전자 자신의 `GameProfile`을 복제해 같은 스킨과 장비 외형을 사용한다. 기존 렌더러는 같은 프로필의 플레이어 이름까지 표시 이름으로 취급할 수 있었으므로, 일반 NPC 이름을 도입해도 Shadow에서 도전자 이름이 남는 경로가 있었다.

## 2. 계약

- Shadow 모델은 로컬 플레이어의 UUID와 프로필명을 계속 사용해 같은 스킨을 유지해야 한다(MUST).
- Shadow 이름표는 현재 `battleId`가 같은 Cobblemon 클라이언트 전투의 상대 액터 `displayName`을 사용해야 한다(MUST).
- 다른 전투나 상대 액터를 찾을 수 없는 상태에서는 잘못된 이름표를 표시하면 안 된다(MUST NOT).
- 이름표 변경을 위해 Shadow 표시 패킷의 형식을 확장하거나 서버 프로토콜을 바꾸면 안 된다(MUST NOT).
- 배틀타워·배틀팩토리 전투창과 Shadow 이름표는 같은 상대 액터 이름을 사용해야 한다(MUST).

## 3. 구현 결과

- `ShadowTrainerProjection`의 `profileId/profileName`과 기존 패킷 형식은 변경하지 않았다.
- 렌더 시점마다 동일한 `battleId`의 양 진영을 확인하고, 로컬 플레이어가 속하지 않은 진영의 첫 상대 액터 이름을 Shadow의 `customName`으로 지정한다.
- 이름표가 존재할 때만 표시하고, Shadow 모델·스킨·위치·홀로그램 색은 변경하지 않았다.

## 4. 검증

- 상대 이름 선택기가 없는 상태에서 실패하는 테스트를 먼저 확인했다.
- 플레이어 외형 프로필 `Park_JH`가 유지되면서 상대 액터 번역 이름만 선택되는 테스트와 다른 전투 이름 차단 테스트가 통과했다.
- 최신 통합 작업 사본의 본체 479개 테스트, 캐시 없는 clean build와 JDK 21 `jar --validate`가 통과했다.
- 제품 JAR SHA-256은 `91879284F0C06BDBB8ABD46E94D7AF5C742D8BD4579666DD93BADEF5864FA586`이다.
- 종료 상태의 `cobblemon-dev` 클라이언트에 배치했고 이전 `77A0B271DF7181B0E8982044907258D82C5BC46B9206BCDD7345A1C4ECEC8177`은 `mod-backups/20260819-1601-before-shadow-opponent-name/`에 보존했다.
- 서버 PID 34908과 서버 JAR은 변경하지 않았다. 실제 전투에서 스킨·이름표를 함께 보는 검증은 남았다.
