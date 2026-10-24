# 랭크업 선택 아레나 50판 검토 — 2026-10-24

일부 랭크업 선택에서 공격을 포기한 손해가 확인됐다. 다만 전부 랭크업 식의 과대평가로 묶을 수 없다. 이미 세트 읽기로 확인한 공격을 상대 예측에서 빠뜨리는 공통 코드 문제가 1장면에서 재현됐고, 다른 장면에는 미공개 견제기·도구와 지정된 커스텀 성격의 가정 차이가 있었다. 랭크업 횟수에 따른 일괄 감점을 권하지 않는다.

## 실행 범위

- 소스 `3d85ab735e58d09af4b62f44bddc1bec827a42e3`, 새 시드925173. 일반 교체/유턴 랭크 손실 포함. 두 근사 옵션 OFF, 보스 대 보스, 탐색 없음, 미리보기 있음.
- 새 렌탈3마리 조합12판 + 렌탈6마리8판 + 리그6마리 새 조합15판 + 혼합 전술 싱글5판 + 더블10판. 등장 종·폼 이름243개. 이전60판의 같은 팀 짝/시드를 다시 돌린 결과가 아니다.
- 요청한50입력 모두 COMPLETE, 1,449판단, 불법 선택0. 3병렬 첫 실행에서42번이 JVM 메모리 부족으로 중단돼 원본 실패를 보존하고 같은 입력을 새 JVM에서 단독 재실행했다. 본 보고서의42번은 `retry42/battle-42`다. 나머지는 `battles/battle-N`이다. 초기 실패를 지우거나50/50 첫 실행 성공으로 보고하지 않는다.
- 아레나용 중간JAR만F에 생성했다. SHA-256 `81ab6a49ef159bfa86382819fb9ff79342bbfcc99863435db9a148e788cd4174`. 게임용 remapJar 생성·개발/운영 설치·서버/클라이언트 재시작은 하지 않았다. 공용 휴대폰 실행부도 변경하지 않았다.

## 무엇을 비교했나

- `setupChosen` 숫자만 집계하지 않았다. 실제 기술/컴포넌트와 선언 효과를 읽어 자기 랭크가 오르는 변화기를 분류했다. 더블은 파트너 행동을 유지하고 랭크업 슬롯만 공격으로 교체했다. 다이맥스 기술은 `maxMoveId`를 읽는다.
- 랭크업 선택65회(32판), 싱글64회·더블1회. 실제 기술 실행51회, 선택했으나 실행되지 않은 경우14회다. 실행되지 않았다는 이유만으로 과사용으로 세지 않았다.
- 65장면 모두에서 점수가 높은 공격 대안을 최대3개씩, 총173개 골랐다. 네이티브 판의 같은 순간을 복제하고 상대의 실제 선택은 유지했다. 원래 행동 재생의 전체 공개 로그는50/50 일치했다.
- 원래 난수 재생에 더해, 일반 공격의 급소를 제외하고 명중 조건을 고정한 진단에서 피해 난수16개를 각각 대조했다. 공격 대안 비교2,768쌍, 분기 오류0. 이는 명중·급소·부가효과를 독립적으로 전수 조사한 승률 실험이 아니며, 특히 두 진영의 피해 난수를 같은 인덱스로 묶었다. 고정한 상대 선택이 AI가 사전에 확실히 알 수 있었던 행동이라는 뜻도 아니다.
- 내부 랭크업 식 `((pressure+speed)×survival×holds−hpCost)×effectChance`754건을 수치 대조했고 오차0이다. 점수가 있는1,381판단의 비유한 값/보스 최고점 선택 불일치0. 나머지68판단은 합법 후보가 하나뿐이라 stages가 없다(교체58·기술5·pass 포함5).

## 실제 상대 행동 조건에서 공격이 더 나았던 5장면

아래5장면은 급소 제외·상대 공격 명중 조건의16개 피해 난수 모두에서 랭크업 후 자신이 쓰러졌고, 같은 기믹 사용 여부의 공격 대안은 더 많은 상대 HP를 깎았다. 이 조건에서 공격을 포기한 손해는 확인된다. 그러나 이것만으로 불확실성을 포함한 사전 기대값 판단까지 모두 버그라고 단정하지 않는다. 번호는 파일의0기준 battle 번호다.

| 판·턴·진영 | 대면 | 랭크업 점수 | 공격 후보 점수 | 실제 판 대조와 구분 |
|---|---|---:|---:|---|
|14·14·p1|툰베어→깨비물거미|칼춤6.61|고드름떨구기1.67|칼춤 후 덤벼들기에 사망. 공격하면 상대 최대HP의29.2~35.4%를 깎고 사망한다. 실제 속도102>94지만 커스텀 성격에서 상대 속도가103이 되어 AI는 후공으로 본다. 지정된 가정의 한계이며 실제 성격을 읽도록 바꿀 근거로 쓰지 않는다.|
|17·3·p2|리자몽→하마돈|용의춤46.50|플레어드라이브26.27|용의춤 후 스톤에지에 사망. 공격은 피해와 화상 가능성을 남긴다. 스톤에지는 당시 미공개였고 AI의 예측 목록은 지진·엄청난힘이었다. 숨은 견제기 위험을 놓친 사례다.|
|19·8·p1|보만다→가이오가|용의춤71.63|더블윙47.66|용의춤 후 냉동빔에 사망. 공격하면 턴말 기준 상대HP38.1~46.0%를 깎고 사망한다. 예측 목록은 근원의파동·명상·공개 번개·추정10만볼트로, 냉동빔이 없었다.|
|22·2·p1|한카리아스→마릴리|칼춤80.90|지진77.76|칼춤 후 치근거리기에 사망하며 까칠한피부만 발동. 지진이면 그 피해까지 합쳐 상대HP94.3~100%를 깎는다. 실제 상대는 천하장사+구애머리띠였고 AI의 가정 피해 최대치는 현재HP의92.6%였다. 미공개 특성/도구 위험과 식 자체를 구분한다.|
|33·8·p1|대왕끼리참→리자몽|칼춤126.06|기습27.09|칼춤을 쓰기 전에 화염방사로 사망. 기습은 상대HP52.2~61.9%를 깎고 사망한다. 이 화염방사는 보스 세트 읽기로 이미 CONFIRMED였다. 아래 공통 코드 문제를 재현했다.|

## 확인한 코드 원인: 다이맥스 기술 이력이 기본 기술 칸을 잠식

`LocalOpponentIntentPredictor.inferredAttacks`는 `open = 4 - known.size`로 읽은 기술을 더 넣을지 정한다.33번 리자몽의 known 목록은 `gmaxwildfire, maxquake, maxairstream, hurricane, solarbeam`이었다. 변환 기술 이력이 기본 기술3칸으로 세어져 open이 음수가 되면서, 추론 슬롯에 `CONFIRMED/DIFFICULTY_SET_READ`로 있는 `flamethrower`도 실제 상대 행동 예측에 들어가지 않았다. 기록된 `actingHit`는 솔라빔·폭풍뿐이고, 칼춤 행동 가능성은 사실상1이었다.

진단용 F 하네스에서 다이맥스 기술을 기본 칸 수에 세지 않도록 입력의 해당 이력만 제외해 비교했다. 커스텀 성격·실제 세트·랭크업 식은 변경하지 않았다.

| 같은 재구성 입력의 진단 | 칼춤 | 기습 | 칼춤 행동 가능성 | 선택 |
|---|---:|---:|---:|---|
|원래 목록|126.058823|27.584853|약1|칼춤|
|다이맥스 이력을 칸 수에서 제외|21.078704|27.584853|0.167213|기습|

화염방사가 예측 목록에 복귀하며 KO1=1, 예측 확률0.753253을 갖는다. 이 재구성에서 원실행의 일부 공격 점수(기습 원기록27.089808)는 완전히 같지 않다. 위 표는 같은 재구성 환경의 두 진단끼리 비교한 값이며 원실행 전체의 비트 동일 재생이라고 쓰지 않는다. 칼춤 원기록126.058823과 원 진단은 일치한다. 제품 코드는 수정하지 않았다.

우선 수정 방향은 다이맥스 기술 이력과 실제 기본 기술 슬롯을 분리하고, 이미 확인한 기본 기술이 칸 수 때문에 누락되지 않게 하는 것이다. 상대 의도·행동 가능성·랭크업 생존 평가가 같은 유효 목록을 읽는지도 같이 확인해야 한다. 랭크업 점수에 고정 감점을 붙여 가릴 문제가 아니다.

## 여러 번 썼다고 과사용으로 세지 않은 사례

- 11번8턴 레지스틸: 방어+2에서 철벽38.97점, 바디프레스−15.64점. 상대 트래피더는 체력38%였지만 즉시 바디프레스를 쓰면 트래피더를 잡는 동시에 울퉁불퉁멧으로 레지스틸도 쓰러졌다. 철벽 선택을 단순히 '잡을 수 있는데 또 쌓음'으로 오판하지 않았다. 전체 전투에서 이 선택이 최적임을 입증한 것은 아니다.
- 38번4턴 대왕끼리참: 공격+2에서 칼춤157.26점, 도각참61.70점. 상대 너트령의 철가시·울퉁불퉁멧·씨뿌리기가 있다. 명중 고정 중간 피해 난수에서 칼춤 뒤114HP, 즉시 도각참 뒤55HP였다. 즉시 피해량만으로 재랭크업을 불필요하다고 할 수 없다.
- 32번11턴 잠만보: 저주1.91점 대10만마력0.51점. 상대 선행 다이스틸에 저주도 공격도 실행 전에 쓰러졌다. 낮은 점수의 랭크업을 골랐다는 사실과, 랭크업 대신 공격했으면 이득이었다는 주장은 다르다.

## 남은 범위와 결과 해석

- 5장면은 실제 선택 조건에서 공격이 더 유리했던 장면 수이며 전체 AI의 보편적 과사용률이 아니다. 나머지60장면의 전투 전체 최적성을 증명하지 않았다. 특히 더블 랭크업 표본은1회뿐이다.
- 다이맥스 칸 수 누락1건은 기존 정보만으로 피할 수 있는 원인이 확인됐다. 나머지의 숨은 견제기/구애 도구/커스텀 성격 차이는 사전 가정과 실제 결과 차이를 분리해 추가 검토할 문제다.
- `|-fail|`12건·`|-immune|`17건을 확인했다. 실패는 보호류6·기습4·클리어바디 하락 차단1·대상 없음1이며 랭크업 기술 자체의 실패 로그는 없었다. 면역에는 교체로 바뀐 대상과 더블 지진의 아군 비행/부유가 섞여 있다. 공격 면역 판단의 전부를 정당화한 검수는 아니다.
- 이번에는 아레나·진단·보고만 수행했다. 새 단위 검사나 소스 수정·설치·실게임 검증은 하지 않았다. 성능 개선/승률 변화 측정으로도 쓰지 않는다.

## 산출물

F 루트: `F:/AI/builds/cobblemon-mods-1b39-entry-healblock/setup-audit-20261024-3d85ab735/`

- `run-metadata.json`, `pairs.json`, `battles/`, `retry42/`: 버전·설정·팀·원시 판단·실제 공개 로그.42번 원 실패는 보존했다.
- `counterfactual-controlled/battle-N/{summary.json,probes.jsonl}`: 원래 로그 일치와 상대 행동을 고정한 공격 분기. `baseline`/`result`는 원난수, `controlledBaseline`/`controlled`는 진단 조건이다.
- `setup-inventory.json`, `controlled-summary.json`, `formula-and-execution.json`, `fail-immune-review.json`: 집계·5장면 조건·산술 대조·실패/면역 목록.
- `max-slot-diagnostic.json`, `SetupCauseProbe.kt`: 다이맥스 칸 수 진단 전후. 별도 하네스이며 제품 수정이 아니다.
- 진단 스크립트는 `F:/AI/betterai-tools/{prepare-setup-audit-20261024.py,audit-setup-counterfactual-20261024.py,summarize-controlled-setup-20261024.py,inventory-setup-audit-20261024.py,verify-setup-audit-20261024.py}`다.

## 65개 선택별 점수 목록

공격 후보는 네이티브 분기에 넣은 대안 중 최고 원점수다. 컴포넌트 변경 외 파트너 행동을 유지했고, 기믹이 다르면 명시했다. 점수 차이만으로 정답을 판정하지 않는다. 아래 '조건부 손해'는 위5장면이며, '기록 대조'는 전투 전체 최적성 미확정이다.

|판|턴|진영·슬롯|사용자|랭크업|점수|공격 대안|점수|검토|
|---:|---:|---|---|---|---:|---|---:|---|
|0|2|p1·0|plusle|nastyplot|6.351|alluringvoice|2.048|기록 대조|
|0|5|p1·0|raichu|nastyplot|7.218|thunderbolt|4.803|기록 대조|
|2|6|p2·0|braviary|bulkup|4.434|closecombat|2.080|기록 대조|
|3|3|p2·0|overqwil|swordsdance|57.475|crunch|24.943|기록 대조|
|3|4|p2·0|lilligant|quiverdance|119.149|pollenpuff|97.233|기록 대조|
|4|2|p2·0|haxorus|swordsdance|95.702|maxwyrmwind [dynamax]|24.634|기록 대조|
|5|3|p1·0|groudon|swordsdance|73.148|heatcrash|62.541|기록 대조|
|7|1|p1·0|leavanny|swordsdance|94.177|lunge|80.306|기록 대조|
|7|3|p1·0|scrafty|bulkup|4.652|knockoff|1.880|기록 대조|
|9|2|p1·0|landorus|nastyplot|148.712|earthpower|78.627|기록 대조|
|10|11|p2·0|manaphy|tailglow|178.066|hydropump|26.920|기록 대조|
|11|1|p1·0|dodrio|swordsdance|106.404|bravebird|61.988|기록 대조|
|11|6|p2·0|registeel|irondefense|119.997|bodypress|25.617|기록 대조|
|11|8|p2·0|registeel|irondefense|38.970|bodypress|-15.638|기록 대조|
|12|3|p2·0|ninetalesalola|nastyplot|105.954|moonblast|36.739|기록 대조|
|12|22|p2·0|samurott|swordsdance|134.884|megahorn|55.690|기록 대조|
|13|5|p1·0|zamazenta|irondefense|46.957|bodypress|28.931|기록 대조|
|13|5|p2·0|suicune|calmmind|86.146|scald|24.516|기록 대조|
|13|6|p1·0|zamazenta|irondefense|72.128|bodypress|50.193|기록 대조|
|14|7|p2·0|deoxysspeed|nastyplot|40.700|focusblast|16.397|기록 대조|
|14|11|p2·0|krookodile|bulkup|85.797|knockoff|42.832|기록 대조|
|14|14|p1·0|beartic|swordsdance|6.607|iciclecrash|1.672|조건부 손해|
|17|1|p2·0|ceruledge|swordsdance|98.978|poltergeist|56.184|기록 대조|
|17|3|p2·0|charizard|dragondance|46.499|flareblitz|26.271|조건부 손해|
|17|7|p2·0|ceruledge|swordsdance|67.319|poltergeist|49.474|기록 대조|
|17|11|p2·0|toxicroak|swordsdance|117.971|closecombat|55.539|기록 대조|
|18|16|p2·0|scizor|swordsdance|54.956|bugbite|26.158|기록 대조|
|19|8|p1·0|salamence|dragondance|71.628|dualwingbeat|47.658|조건부 손해|
|19|15|p2·0|banette|swordsdance|56.444|poltergeist|20.098|기록 대조|
|19|16|p2·0|banette|swordsdance|76.509|poltergeist|74.563|기록 대조|
|20|1|p1·0|garchomp|swordsdance|88.427|earthquake [mega]|63.534|기록 대조|
|20|6|p2·0|landorus|nastyplot|96.458|sludgebomb|15.596|기록 대조|
|20|10|p2·0|landorus|nastyplot|99.182|sludgebomb|16.210|기록 대조|
|20|13|p2·0|excadrillmega|swordsdance|69.034|earthquake|34.766|기록 대조|
|20|18|p1·0|garchompmega|swordsdance|110.279|scaleshot|94.791|기록 대조|
|20|18|p2·0|landorus|nastyplot|94.189|earthpower|56.111|기록 대조|
|22|2|p1·0|garchomp|swordsdance|80.902|earthquake|77.758|조건부 손해|
|24|2|p2·0|tyranitar|dragondance|52.977|crunch [mega]|33.899|기록 대조|
|24|7|p1·0|excadrillmega|swordsdance|79.847|earthquake|54.595|기록 대조|
|25|2|p2·0|annihilape|bulkup|42.681|ragefist|30.889|기록 대조|
|26|11|p2·0|tyranitar|dragondance|9.734|crunch [mega]|3.543|기록 대조|
|27|7|p2·0|excadrillmega|swordsdance|138.592|rockslide|21.606|기록 대조|
|28|7|p2·0|alakazam|nastyplot|114.858|shadowball|33.134|기록 대조|
|28|14|p1·0|garchomp|swordsdance|108.396|earthquake [mega]|81.899|기록 대조|
|29|4|p1·0|alakazam|nastyplot|134.393|psychic|65.569|기록 대조|
|31|5|p1·0|baxcalibur|dragondance|68.261|earthquake|38.890|기록 대조|
|32|10|p2·0|snorlax|curse|53.145|highhorsepower|21.630|기록 대조|
|32|11|p2·0|snorlax|curse|1.906|highhorsepower|0.513|기록 대조|
|33|8|p1·0|kingambit|swordsdance|126.059|kowtowcleave|46.769|조건부 손해|
|34|6|p2·0|landorus|nastyplot|107.053|earthpower|97.759|기록 대조|
|36|4|p2·0|hatterene|calmmind|7.727|mysticalfire|5.764|기록 대조|
|36|5|p1·0|sinistcha|calmmind|118.669|shadowball|68.500|기록 대조|
|36|6|p2·0|hatterene|calmmind|64.126|mysticalfire|28.728|기록 대조|
|36|10|p1·0|annihilape|bulkup|25.691|ragefist|21.713|기록 대조|
|37|4|p1·0|garganacl|irondefense|3.959|saltcure|2.294|기록 대조|
|37|5|p2·0|dragonite|dragondance|111.602|earthquake|84.567|기록 대조|
|38|3|p1·0|kingambit|swordsdance|73.161|kowtowcleave|17.722|기록 대조|
|38|4|p1·0|kingambit|swordsdance|157.255|kowtowcleave|61.700|기록 대조|
|39|19|p2·0|hatterene|calmmind|75.009|psyshock|59.100|기록 대조|
|39|22|p2·0|annihilape|bulkup|36.111|drainpunch|31.736|기록 대조|
|39|23|p2·0|annihilape|bulkup|36.111|drainpunch|30.643|기록 대조|
|39|26|p1·0|garganacl|irondefense|28.317|saltcure|25.343|기록 대조|
|39|26|p2·0|annihilape|bulkup|136.997|drainpunch|105.683|기록 대조|
|39|28|p1·0|garganacl|irondefense|35.321|saltcure|7.582|기록 대조|
|43|8|p1·0|dragonite|dragondance|293.000|earthquake|284.640|기록 대조|
