# Music 1.3.14 — 포켓몬챔피언스 PvP 곡 복원

2026-10-04. 대상: 빡대리와 Music 모드 유지보수자.

Updates: `MUSIC_ASSET_PRUNING_2026-10-03.md`의 PvP 음원 제거·트레이너 기본곡 대체 방침만 변경합니다. 그 문서의 다른 정리 범위는 유지합니다.

## 재생 계약

- 공식 팩은 기존 `pokemon_champions_arena_battle.ogg` 한 곡을 다시 포함해야 합니다(MUST).
- 일반 PvP 및 `more_cobblemon_contents:pvp` 전투의 기본곡은 `아레나 배틀 (PC)`여야 합니다(MUST).
- 배틀타워·배틀팩토리는 기존 트레이너 기본곡을 유지해야 합니다(MUST).
- 삭제된 다른 PvP 음원 3개와 그 이벤트·플레이리스트는 복원하지 않아야 합니다(MUST).
- 개인 덮어쓰기 매핑은 바꾸지 않습니다. 사용자가 설정한 매핑이 있다면 기본곡보다 우선합니다.

이전 PvP 4곡을 모두 되살리는 방식은 요청 범위를 넓히므로 사용하지 않습니다. 기존 4곡 플레이리스트 ID를 재사용하면 누락된 3곡을 참조하게 되므로, 자동 생성되는 단일 곡 플레이리스트 ID를 사용합니다.

## 검증 기준

`OfficialMusicLineupTest`, `OfficialPokemusicMappingTest`, `ApprovedMusicAssetsTest`가 곡명·일반/MCC PvP 매핑·공식 팩 음원 집합을 검사합니다. 빌드와 실제 클라이언트 적용 결과는 별도로 확인해야 합니다.
