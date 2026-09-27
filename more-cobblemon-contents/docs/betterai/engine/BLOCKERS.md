# 엔진 작업 중 막힌 부분

작업하다 막힌 항목을 남겨 두고 다른 작업부터 진행한 기록이다. 해결하면 항목을 지운다.

## Showdown 동작

- **교체 요청에서 Showdown의 자동 선택이 오류를 낸다.** `side.chooseSwitch()`를 인자 없이 부르면 `slotText.length`에서 멈춘다. 심판 도구는 첫 번째 교체 가능한 포켓몬을 직접 지정해서 피한다. 실게임은 항상 대상을 지정하므로 영향이 없다.

## 게임 연결

- **실게임 검증 전이다.** 네이티브 탐색은 기본으로 엔진 작업자를 쓴다. 개발 서버에 배포한 뒤 실제 판단 시간과 탐색 깊이, Cobblemon 종족을 덮어쓴 도감이 쓰이는지(`AI engine dex overlaid with N Cobblemon species` 로그)를 확인해야 한다.
- **수제 계산기 동결 해제**는 아직 하지 않았다. 엔진이 게임에서 제대로 판단하는지 확인한 뒤, 수제 계산기를 엔진 호출로 바꿀지 결정한다.

## 검증

- 전수 검사의 일반 전투에서 발동 조건이 까다로운 효과는 한 번도 발동하지 않을 수 있다. 이런 항목은 "옮긴 코드가 있고 넣어 본 전투에서 달라지지 않았다"는 뜻으로만 통과한다. 사용률 상위 효과 일부는 `EngineTriggerParityTest`로 발동시켜 확인했고, 대회 형식 전투 120전과 실제 경기 재현도 통과한다. 나머지 항목도 사용률 순으로 전용 시나리오를 늘려 가야 한다.
- 지옥찌르기가 다른 기술이 불러낸 소리 기술을 막는 경우는 중력과 같은 코드 경로지만, 아직 전용 시나리오로 발동시켜 보지 않았다.
- 전수 검사는 Showdown 도감에 없는 Z-A 메가진화(Clefable-Mega 등)의 메가진화를 건너뛴다. 게임에서는 Cobblemon 종족 데이터로 이 종족을 만들지만, 테스트에는 Cobblemon 레지스트리가 없다.

## 정리한 항목

- **Z-A 메가진화 종족과 데이터팩 종족**: Cobblemon은 실제 전투에서 Showdown 도감을 자기 종족 데이터로 바꿔 등록한다(`receiveSpeciesData`). 엔진도 게임 안에서는 같은 데이터(`PokemonSpecies.allShowdownSpecies`)를 읽어 도감에 덮어쓴다. Showdown의 `new Species(data)`를 옮긴 `ShowdownSpeciesData`는 도감 전체와 합성 사례 1427건에서 Showdown과 같다(`EngineSpeciesDataTest`).
- **타워·PvP 전투 규칙**: 두 콘텐츠 모두 Cobblemon의 `GEN_9_SINGLES`/`GEN_9_DOUBLES`(레벨 조정 없음)를 쓴다. 이 형식의 규칙(`Obtainable`, `+Past`, `+Unobtainable`)에는 팀 검증 훅(`onValidateTeam`, `onChangeSet`)만 있어서 전투 중 동작은 규칙 없는 엔진과 같다.
- **연결 상태(`linkedStatus`) 이름**: Showdown과 Mega Showdown의 모든 호출이 문자열 `"trapper"`를 넘긴다. 이름과 id가 같아서 원본과 엔진이 같은 상태를 찾는다.
- **코트체인지의 난전 분기**: 네 진영 전투에서만 쓰인다. Cobblemon에는 난전 형식이 없고 MCC도 쓰지 않으므로 옮길 대상이 아니다.
