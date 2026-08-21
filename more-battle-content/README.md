# Cobblemon: More Battle Content

Adds Battle Tower, Battle Factory, player-versus-player battles, holographic battle terminals, BP progression, and shared battle presentation to Cobblemon.

- Mod ID: `cobblemon_more_battle_content`
- Side: client and server
- Requires: Fabric API, Fabric Language Kotlin, Cobblemon 1.7.3, and Mega Showdown 1.9.3

## Build

Run from the repository root:

```powershell
.\gradlew.bat :more-battle-content:build
```

The JAR is written to `more-battle-content/build/libs`.

Module-specific maintenance utilities are kept in [`tools`](tools/).

## 데이터팩으로 목록 바꾸기

시설별 데이터는 아래 폴더에서 따로 읽습니다. 사용자 정의 폴더 이름은 모두 `mbc-`로 시작합니다.

| 용도 | 경로 | 스키마 |
| --- | --- | --- |
| 배틀타워 트레이너 | `data/<namespace>/mbc-battle-tower/trainers/*.json` | 1 |
| 배틀타워 출전 풀 | `data/<namespace>/mbc-battle-tower/pools/*.json` | 1 |
| 배틀타워 대전 조건 | `data/<namespace>/mbc-battle-tower/encounters/*.json` | 1 |
| 배틀타워 포켓몬 세트 | `data/<namespace>/mbc-battle-tower/pokemon-sets/*.json` | 4 |
| 배틀팩토리 트레이너 | `data/<namespace>/mbc-battle-factory/trainers/*.json` | 1 |
| 배틀팩토리 렌탈 세트 | `data/<namespace>/mbc-battle-factory/rental-sets/*.json` | 4 |
| BP 상점 구매 제한 | `data/<namespace>/mbc-bp-shop/rules/*.json` | 1 |
| BP 상점 상품 | `data/<namespace>/mbc-bp-shop/entries/*.json` | 1 |

타워는 트레이너, 출전 풀, 대전 조건, 포켓몬 세트를 각각 바꿀 수 있습니다. 포켓몬 세트에는
`mechanic_id`를 `mega`, `dynamax`, `tera` 중 하나로 반드시 적어야 하며, 선택한 기믹과
`tera_type`, `dmax_level`, `gmax_factor`가 맞지 않으면 읽지 않습니다. 팩토리도 트레이너와
렌탈 세트가 서로 분리되어 있습니다. BP 상점은 구매 제한 JSON 하나와 상품 JSON 여러 개를
사용하므로 상품 하나만 따로 추가하거나 교체할 수 있습니다.

같은 종류의 JSON은 `/reload` 때 하나의 목록으로 합쳐집니다. 합친 뒤 ID나 상점 정렬 번호가
겹치거나, 다른 폴더를 가리키는 참조가 끊겼거나, 파일 하나라도 형식에 맞지 않으면 그 시설의
변경 전체를 거부하고 마지막 정상 목록을 유지합니다. 기본 파일 하나를 통째로 교체하려면
데이터팩에서 `cobblemon_more_battle_content` 네임스페이스와 같은 상대 경로·파일 이름을 쓰면 됩니다.
