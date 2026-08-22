# Cobblemon Custom Species

Cobblemon 포켓몬의 종족값, 특성 후보, 기술 습득 목록을 JSON 파일 하나로 바꾸는 Fabric 애드온입니다. 서버를 재시작하지 않아도 Minecraft의 `/reload` 명령으로 새 설정을 적용할 수 있습니다.

이 모드는 **개체값(IV)** 을 바꾸는 모드가 아닙니다. 포켓몬 종 전체에 공통으로 적용되는 **종족값** 을 바꿉니다.

## 설치

전용 서버에서 사용한다면 애드온 JAR를 서버의 `mods` 폴더에 넣고 서버를 한 번 실행합니다. 멀티플레이 클라이언트에는 이 애드온을 따로 설치하지 않아도 됩니다. 적용된 종 데이터는 접속 중인 클라이언트에 서버가 다시 전송합니다.

싱글플레이에서 사용한다면 통합 서버도 함께 실행되므로 사용 중인 Minecraft 프로필의 `mods` 폴더에 JAR를 넣어야 합니다.

필요한 환경은 다음과 같습니다.

- Minecraft 1.21.1
- Fabric Loader 0.19.3 이상
- Fabric API
- Fabric Language Kotlin 1.13.11+kotlin.2.3.21 이상
- Cobblemon 1.7.3 이상
- Java 21 이상

## 빠른 사용법

1. 애드온을 설치한 뒤 서버나 싱글플레이 월드를 한 번 실행합니다.
2. 아래 파일이 자동으로 만들어졌는지 확인합니다.

   `config/cobblemon-custom-species/species-overrides.json`

3. 서버를 끄지 않고 이 파일을 원하는 내용으로 수정합니다.
4. 게임이나 서버 콘솔에서 `/reload`를 실행합니다.
5. `/customspecies status`를 실행해 마지막 적용 결과와 실제 설정 파일 경로를 확인합니다.

`/customspecies status`는 권한 레벨 2 이상인 관리자만 사용할 수 있습니다.

## 설정 예시

```json
{
  "schema": 1,
  "overrides": [
    {
      "species": "cobblemon:charizard",
      "form": "base",
      "base_stats": {
        "attack": 100,
        "special_attack": 120
      },
      "abilities": {
        "add": ["h:toughclaws"],
        "remove": ["h:solarpower"]
      },
      "moves": {
        "add": ["tm:scaleshot", "72:blastburn"],
        "remove": ["tm:toxic"],
        "remove_moves": ["growl"]
      }
    },
    {
      "species": "cobblemon:rotom",
      "form": "wash",
      "base_stats": {
        "speed": 99
      },
      "moves": {
        "add": ["tm:hydropump"]
      }
    }
  ]
}
```

각 `overrides` 항목에는 바꿀 내용만 적으면 됩니다. 위 예시는 다음처럼 적용됩니다.

- 기본 리자몽의 공격과 특수공격 종족값을 변경합니다.
- 리자몽의 특성 후보와 기술 습득 목록을 추가하거나 삭제합니다.
- 워시로토무에만 스피드 종족값과 기술 항목을 추가합니다.

## 대상 포켓몬과 폼 지정

| 항목 | 뜻 | 예시 |
| --- | --- | --- |
| `species` | 포켓몬의 네임스페이스가 포함된 ID | `cobblemon:charizard` |
| `form: "base"` | 기본 폼만 적용 | 기본 리자몽 |
| `form: "wash"` | 해당 Showdown 폼 ID에만 적용 | 워시로토무 |
| `form: "alola"` | 해당 리전 폼에만 적용 | 알로라 폼 |
| `form: "*"` | 해당 포켓몬의 기본 폼과 모든 폼에 적용 | 모든 로토무 폼 |

`form`을 생략하면 `base`로 처리됩니다. 폼 이름은 Cobblemon이 사용하는 소문자 Showdown 폼 ID를 적어야 합니다.

같은 `species`와 같은 `form` 조합은 파일 안에 두 번 적을 수 없습니다. 다만 `*`와 개별 폼을 함께 사용하면 대상이 겹칠 수 있습니다. 이 경우 `overrides` 배열에서 **아래쪽에 적은 항목이 위쪽 결과에 이어서 적용**됩니다. 의도한 경우가 아니라면 같은 포켓몬에 `*`와 개별 폼을 섞지 않는 편이 안전합니다.

## 종족값 변경

`base_stats`에는 바꿀 종족값만 적습니다. 나머지 종족값은 그대로 유지됩니다.

| JSON 항목 | 종족값 |
| --- | --- |
| `hp` | HP |
| `attack` | 공격 |
| `defence` | 방어 |
| `special_attack` | 특수공격 |
| `special_defence` | 특수방어 |
| `speed` | 스피드 |

각 값에는 1부터 999까지의 정수만 사용할 수 있습니다.

```json
"base_stats": {
  "hp": 90,
  "defence": 110,
  "special_defence": 110
}
```

## 특성 후보 변경

특성 이름은 Cobblemon 종 데이터에서 사용하는 표기를 그대로 적습니다. 예를 들어 일반 특성과 숨겨진 특성의 표기가 다를 수 있으므로, 기존 Cobblemon 종 JSON의 특성 항목을 기준으로 작성하는 것이 가장 안전합니다.

### 기존 목록에 추가하거나 삭제하기

```json
"abilities": {
  "add": ["h:toughclaws"],
  "remove": ["h:solarpower"]
}
```

- `add`: 기존 특성 후보에 추가합니다.
- `remove`: 정확히 일치하는 특성 후보를 삭제합니다.

### 특성 후보 전체 교체하기

```json
"abilities": {
  "replace": ["r:runaway", "h:anticipation"]
}
```

`replace`를 사용하면 기존 특성 후보를 전부 지우고 작성한 목록으로 교체합니다. `replace`는 `add` 또는 `remove`와 함께 사용할 수 없으며, 결과 특성 목록을 비워 둘 수도 없습니다.

## 기술 습득 목록 변경

### 습득 방식까지 지정해 추가하거나 삭제하기

```json
"moves": {
  "add": ["tm:surf", "egg:wish", "50:hydropump"],
  "remove": ["tm:toxic"]
}
```

- `add`: 해당 습득 방식으로 기술을 추가합니다.
- `remove`: 습득 방식과 기술이 모두 정확히 일치하는 항목만 삭제합니다.

사용할 수 있는 습득 방식은 다음과 같습니다.

- 레벨 습득: `1:growl`, `50:hydropump`처럼 0부터 100까지의 레벨을 숫자로 작성
- 알 기술: `egg:wish`
- 기술머신: `tm:surf`
- 가르침 기술: `tutor:dracometeor`
- 기타 Cobblemon 방식: `legacy`, `special`, `evolution`, `form_change`

### 습득 방식과 관계없이 기술 전체 삭제하기

```json
"moves": {
  "remove_moves": ["growl", "toxic"]
}
```

`remove_moves`는 레벨, 기술머신, 알 기술 같은 습득 방식을 따지지 않고 해당 기술의 모든 습득 항목을 삭제합니다.

`remove_moves`에 기술 이름을 잘못 적으면 설정 전체가 거부되는 대신 일치하는 기술이 없어 아무것도 삭제되지 않습니다. 적용 후 실제 결과를 꼭 확인해야 합니다.

## 변경을 전부 되돌리기

`overrides`를 빈 배열로 바꾸고 `/reload`를 실행하면 이 애드온이 적용하던 변경을 모두 해제하고, 각 대상이 처음 변경되기 전의 값으로 돌립니다.

```json
{
  "schema": 1,
  "overrides": []
}
```

## 적용 실패 시 동작

이 애드온은 파일 전체를 먼저 확인한 뒤 한 번에 적용합니다. JSON 문법, 포켓몬이나 폼 ID, 추가·삭제할 기술 항목, 특성 표기 등에 문제가 있으면 일부만 적용하지 않고 **새 설정 전체를 거부**합니다. 이때 직전에 정상 적용된 설정은 그대로 유지됩니다.

오류 원인은 서버 로그와 `/customspecies status`에서 확인할 수 있습니다.

자주 틀리는 부분은 다음과 같습니다.

- 최상단 `schema` 값이 `1`이 아님
- `species`에 `cobblemon:` 같은 네임스페이스를 빼먹음
- 존재하지 않는 포켓몬, 폼, 기술 또는 특성을 작성함
- 정해진 이름이 아닌 JSON 항목을 추가하거나 철자를 틀림
- 같은 `species`와 `form` 조합을 두 번 작성함
- `abilities.replace`와 `abilities.add` 또는 `abilities.remove`를 같이 사용함
- 아무 변경도 없는 빈 override 항목을 작성함
- 종족값에 1 미만, 999 초과 또는 소수가 들어감

## 이미 존재하는 포켓몬에 관한 주의사항

종 데이터가 바뀌더라도 이미 존재하는 포켓몬이 현재 기억한 기술, 벤치에 보관한 기술, 현재 특성, 개체값은 자동으로 다시 뽑히거나 교체되지 않습니다. 이 애드온은 포켓몬 개체를 일괄 수정하는 도구가 아니라 Cobblemon의 종 데이터와 앞으로 참조할 후보 목록을 바꾸는 도구입니다.

설정 파일은 여러 개가 아니라 `species-overrides.json` 하나뿐이며, `schema`는 설정 형식의 버전을 확인하기 위한 값입니다. 현재는 반드시 `1`을 사용해야 합니다.
