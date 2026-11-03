# Cobblemon: Battle Speed

PvE 배틀의 실제 진행을 1·2·3·4배속으로 빠르게 합니다. 애니메이션만 빨라지는 게 아니라, 서버가 메시지마다 기다리는 시간 자체를 줄입니다. 서버와 클라이언트 양쪽에 넣는 모드입니다.

- 대상: 플레이어 대 야생, 플레이어 대 NPC 배틀. PvP는 기본값에서 항상 1배속입니다.
- 플레이어마다 자기 배속을 고르며, 선택은 월드에 저장됩니다(`world/data/cobblemon_battle_speed_players.json`).
- 배틀 도중에 바꾸면 그다음 대기부터 바로 적용됩니다.
- 의존 모드: Cobblemon 1.8.1, Fabric API, Fabric Language Kotlin.

## 명령어

| 명령어 | 설명 | 기본 권한 |
|---|---|---|
| `/battlespeed` | 지금 배속 확인 | 0 (누구나) |
| `/battlespeed <단계>` | 배속 변경 (1부터 `speeds` 개수까지) | 0 |
| `/battlespeed reload` | 설정 JSON 다시 불러오기. 진행 중인 배틀에도 바로 반영 | 2 (OP) |

명령어 이름은 영어입니다. 마인크래프트 명령어 파서가 한글 이름을 받지 않습니다.

## 설정 (`config/cobblemon_battle_speed.json`)

파일이 없으면 처음 실행할 때 기본값으로 만듭니다. 빠진 항목은 기본값을 쓰고, 범위를 벗어난 값은 범위 안으로 맞춥니다. 파일이 깨져 있으면 `/battlespeed reload`가 실패를 알리고 이전 설정을 그대로 씁니다.

```json
{
  "enabled": true,
  "speeds": [1.0, 2.0, 3.0, 4.0],
  "defaultLevel": 1,
  "battles": { "wild": true, "npc": true, "pvp": false },
  "multiplePlayers": "slowest",
  "apply": {
    "messageWaits":     { "enabled": true, "multiplier": 1.0 },
    "moveEffects":      { "enabled": true, "multiplier": 1.0 },
    "battleTimers":     { "enabled": true, "multiplier": 1.0 },
    "clientAnimations": { "enabled": true, "multiplier": 1.0 }
  },
  "skipMoveEffectsFromLevel": 0,
  "permissions": { "change": 0, "reload": 2 }
}
```

| 항목 | 뜻 |
|---|---|
| `enabled` | `false`면 모든 배틀이 1배속 |
| `speeds` | 단계별 배속. 첫 값이 1단계. 개수가 곧 단계 수이며, 값은 0.1~20으로 맞춤 |
| `defaultLevel` | 아직 고르지 않은 플레이어의 단계 |
| `battles.wild` / `battles.npc` | 야생 / NPC 배틀에 배속 적용 여부 |
| `battles.pvp` | `true`면 PvP에도 적용. 이때는 양쪽 중 가장 느린 선택을 따름 |
| `multiplePlayers` | 같은 편에 플레이어가 여럿일 때. `slowest`는 가장 느린 선택, `normal`은 1배속 |
| `apply.messageWaits` | 배틀 메시지 뒤의 고정 대기(Mega Showdown 연출 대기 포함) |
| `apply.moveEffects` | 기술 연출 안의 일시정지·딜레이 |
| `apply.battleTimers` | 그 밖의 배틀 타이머: 내보내기 간격, 기절 대기, 울음소리 |
| `apply.clientAnimations` | 클라이언트 체력바·HUD 타일 이동 애니메이션 |
| `apply.*.multiplier` | 그 부분만 단계 배속에 추가로 곱함. 1배속 배틀은 항상 1배속 |
| `skipMoveEffectsFromLevel` | 이 단계부터 기술 연출을 아예 건너뜀. `0`은 건너뛰지 않음 |
| `permissions.change` / `permissions.reload` | 각 명령어에 필요한 권한 레벨(0~4) |

안내 문구는 `assets/cobblemon_battle_speed/lang/ko_kr.json`, `en_us.json`에 있어서 리소스팩으로 바꿀 수 있습니다.

## 빨라지지 않는 것

- 몬스터볼 비행, 회수, 상대에게 돌진하는 동작처럼 실제로 움직이는 엔티티는 속도를 바꿀 수 없습니다. 기다리는 시간만 줄어서, 고배속에서는 동작이 끝나기 전에 다음 진행이 시작될 수 있습니다. 거슬리면 `skipMoveEffectsFromLevel`로 연출을 건너뛰게 합니다.
- 기술 선택 시간 같은 플레이어 입력 대기는 그대로입니다.
- 클라이언트에 이 모드가 없어도 서버 진행은 빨라지지만, 체력바는 원래 속도로 줄어서 화면이 진행을 못 따라갑니다.
