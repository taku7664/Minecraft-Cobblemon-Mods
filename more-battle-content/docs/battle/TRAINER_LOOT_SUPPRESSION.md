# MBC 관리 트레이너 포켓몬 드롭 억제 정정

> **2026-09-27 정리 메모:** 3절의 합성 `PlayerPartyStore` 방식은 2026-08-19 05:03에 ArmorStand 소유 앵커 방식으로 교체됐다. 상세는 [인덱스의 불일치 표](../README.md#현재-코드와-다른-조항)를 본다.

| 항목 | 값 |
|---|---|
| Status | `shared` |
| Effective | 2026-08-19 |
| Updates | [`REWARD_AND_HELD_ITEM.md`](REWARD_AND_HELD_ITEM.md) 1절과 2절 |
| 주 독자 | MBC 전투 런타임과 Cobblemon 1.7.3 호환 계층 구현 담당자 |

## 1. 정정된 계약

- MBC가 생성한 배틀타워·배틀팩토리 상대 포켓몬은 야생 포켓몬이 아니라 가상 트레이너 소유 포켓몬으로 취급해야 한다(MUST).
- 위 상대 포켓몬이 쓰러질 때 종족 드롭 테이블 아이템과 지닌 도구를 포함한 일반 Cobblemon 사망 드롭을 생성해서는 안 된다(MUST NOT).
- MBC 관리 전투의 일반 처치 경험치 억제와 BP·진행 보상은 그대로 유지해야 한다(MUST).
- 일반 야생 포켓몬과 MBC가 관리하지 않는 전투의 경험치·드롭 규칙은 변경해서는 안 된다(MUST NOT).

## 2. 발견된 원인

기존 안전 계층은 생성한 상대 포켓몬을 일반 `PartyStore`에 넣어 `Pokemon.isWild()`만 거짓으로 만들었다. 그러나 Cobblemon 1.7.3의 `Pokemon.getOwnerUUID()`는 `PlayerPartyStore`, `NPCPartyStore` 또는 `PCStore`만 소유 저장소로 인정한다. 일반 `PartyStore`에서는 소유자 UUID가 계속 `null`이었다.

Cobblemon의 `PokemonServerDelegate.doDeathDrops()`는 소유자 UUID와 소유 엔티티가 모두 없으면 지닌 도구와 종족 드롭 테이블을 처리한다. 따라서 `canDropHeldItem=false`와 일반 `PartyStore`만으로는 최종 사망 드롭을 막지 못했다.

## 3. 적용 방식

- 생성 상대의 `originalPokemon`과 `effectedPokemon`을 각각 합성 소유자 UUID를 가진 `PlayerPartyStore`에 연결한다(MUST).
- 두 복제본의 `heldItemVisible=false`와 `canDropHeldItem=false`도 유지한다(MUST).
- 실제 플레이어 저장소, 실제 NPC 저장소 또는 월드 영속 데이터에는 합성 로스터를 등록하지 않는다(MUST NOT).
- 드롭 방지는 포켓몬의 네이티브 소유 상태로 보장하며 전투 종료 뒤 틱 폴링이나 아이템 엔티티 사후 삭제에 의존하지 않는다(MUST NOT).

## 4. 기각한 대안

- `doPokemonLoot` 게임 규칙을 전역으로 끄는 방식은 일반 야생 포켓몬 드롭까지 제거하므로 기각했다.
- 주변 아이템 엔티티를 전투 종료 뒤 삭제하는 방식은 다른 플레이어의 정상 아이템까지 지울 수 있고 이미 지급된 인벤토리 보상을 되돌리지 못하므로 기각했다.
- `canDropHeldItem=false`만 유지하는 방식은 Cobblemon 1.7.3의 최종 사망 드롭 경계를 막지 못하므로 기각했다.

## 5. 검증 상태

- `PlayerPartyStore`가 합성 소유자 UUID를 보존하고 지닌 도구 표시·드롭 플래그도 함께 끄는 회귀 테스트 2개가 통과했다.
- Cobblemon 1.7.3 바이트코드에서 일반 `PartyStore`는 `getOwnerUUID() == null`, `PlayerPartyStore`는 지정한 플레이어 UUID를 반환하며 `doDeathDrops()`는 소유자 UUID가 있으면 드롭 처리를 건너뛰는 것을 교차 확인했다.
- 캐시 없이 본체를 다시 컴파일하고 `unitTest` 대상 클래스 2개, 배포용 리맵 JAR 빌드와 JDK 21 `jar --validate`를 통과했다. 제품 JAR SHA-256은 `8199140CE4D10CC7EB9D0BC210935AE54731BC74B14C798726B83D2E1F56C64F`다.
- 전체 본체 `unitTest`는 426개 중 424개가 통과했으며, 병행 GUI 작업의 `FactoryPlayLayoutTest` 2개가 실패해 전체 성공으로 기록하지 않는다. 드롭 안전 계층 테스트는 실패하지 않았다.
- 새 JAR은 서버·클라이언트에 배치하지 않았고 서버를 재기동하지 않았다. 실제 배틀타워·배틀팩토리 전투의 종족·지닌 도구 드롭 억제는 아직 게임 안에서 검증하지 않았다.
