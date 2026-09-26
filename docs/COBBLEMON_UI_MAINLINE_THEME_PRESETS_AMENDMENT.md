# Cobblemon UI 본가 계열 테마 프리셋 후속 결정

| 항목 | 값 |
| --- | --- |
| Status | `shared` |
| Effective | 2026-09-26 |
| Updates | `COBBLEMON_UI_SURFACE_STYLE_AMENDMENT.md` |
| Does not obsolete | 공용 표면·입력 계약, 내장 코드 폴백과 선택형 Visual Pack 경계 |
| 적용 대상 | `cobblemon-ui-kit`의 내장 테마 프리셋과 Component Gallery |
| 주 독자 | 공용 위젯·테마·League Challenge 화면을 구현하는 개발자 |

## 1. 수정 이유

표면 계약은 직사각형 반복 문제를 해결했지만, 현재 기본 테마 한 개만으로는 화면 성격을 비교하거나 League Challenge의 최종 시각 방향을 고를 수 없다. 또한 Pokémon 본가 UI도 세대마다 같은 문법을 쓰지 않는다. GBA, Nintendo DS 초기, Nintendo DS 후기와 현대 Switch 화면을 하나의 “포켓몬풍” 테마로 섞으면 어느 세대와도 닮지 않은 결과가 된다.

따라서 공용 위젯 API는 그대로 유지하면서, 세대별로 관찰한 시각 문법을 독립된 내장 프리셋으로 제공해야 한다(MUST). 프리셋은 본가 자산이나 로고를 복제하지 않고 색 역할, 프레임 두께, 모서리와 상태 대비만 재해석해야 한다(MUST).

## 2. 조사 자료와 관찰

아래 자료의 게임 화면을 실제로 나란히 비교했다. Nintendo 공식 매뉴얼과 공식 게임 페이지를 주 근거로 쓰고, 공식 자료에서 큰 화면을 제공하지 않은 GBA 게임은 보조 캡처를 함께 사용했다.

| 계열 | 자료 | 관찰한 반복 규칙 | 프리셋 반영 |
| --- | --- | --- | --- |
| FireRed/LeafGreen, Emerald | [Nintendo의 FireRed/LeafGreen 소개](https://www.nintendo.com/us/whatsnew/pokemon-firered-version-and-pokemon-leafgreen-version-are-coming-to-nintendo-switch-in-multiple-languages/), [FireRed 전투 보조 캡처](https://cdn.shopify.com/s/files/1/0916/9970/8282/files/POKEMON_1.jpg), [Emerald 파티 보조 캡처](https://gamefabrique.com/screenshots2/gba/pokemon-emerald-20.big.jpg) | 굵은 어두운 외곽선과 밝은 안쪽 선, 아래·오른쪽 그림자, 큰 선택 화살표, 상태에 따른 행 색 전환 | `hoenn_pixel` |
| Diamond/Pearl/Platinum | [Diamond 공식 매뉴얼](https://csassets.nintendo.com/noaext/image/private/t_KA_PDF/DS_Pokemon_Diamond?_a=DATAg1AAZAA0), [Platinum 공식 매뉴얼](https://csassets.nintendo.com/noaext/image/private/t_KA_PDF/DS_Pokemon_Platinum?_a=DATAg1AAZAA0) | 밝은 카드, 얇은 청록·파랑 경계, 작은 아이콘과 높은 정보 밀도, 단색에 가까운 표면 | `johto_touch`의 밝은 카드 계층에 반영 |
| HeartGold/SoulSilver | [SoulSilver 공식 매뉴얼](https://csassets.nintendo.com/noaext/image/private/t_KA_PDF/DS_Pokemon_SoulSilver), [HGSS 메뉴 보조 캡처](https://cdn.mos.cms.futurecdn.net/fb65eb33b5defc2209e65ef5dd7bc138.jpg) | 아이콘이 있는 카드형 명령, 초록·주황·빨강 기능색, 직사각형 안쪽 카드와 둥근 외곽 그룹 | `johto_touch` |
| Black 2/White 2 | [Black 2 공식 매뉴얼](https://csassets.nintendo.com/noaext/image/private/t_KA_PDF/DS_pokemon_black_2?_a=DATAg1AAZAA0) | 거의 검은 2열 타일, 1픽셀 회색·청록 경계, 낮은 행 높이, 작은 기능색 아이콘 | `unova_pixel` |
| Sword/Shield | [Battle Stadium 공식 화면](https://swordshield.pokemon.com/en-us/gameplay/pokemon-battle-stadium/), [공식 캡처](https://swordshield.pokemon.com/assets/img/screenshots/8_16_battle_1.jpg), [Y-Comm 공식 화면](https://swordshield.pokemon.com/en-us/gameplay/y-comm/) | 청록 헤더, 흰 본문 카드, 노랑 진행 강조, 차콜 메시지 패널, 스포츠 방송 그래픽에 가까운 사선 | `galar_stadium` |
| Scarlet/Violet | [Poké Portal 공식 설명](https://scarletviolet.pokemon.com/en-gb/news/pokemon_go_connect/), [공식 캡처](https://scarletviolet.pokemon.com/_images/news/feb_27/go_p09_01_en.jpg) | 강한 파랑 바탕, 노랑 선택 행, 남색 비선택 행, 흰 글자와 굵은 선택 대비 | `paldea_portal` |

이 관찰은 각 게임의 전체 UI를 복제했다는 뜻이 아니다(MUST NOT). 표에 적은 반복 규칙만 프레임워크 토큰으로 옮긴다.

## 3. 내장 프리셋 계약

현재 원본 테마를 포함해 다음 프리셋을 제공해야 한다(MUST).

| ID | 역할 | 셸·패널 | 버튼·선택 |
| --- | --- | --- | --- |
| `league_neon` | 현행 Cobblemon UI Kit 기준선 | 어두운 남색 그라데이션과 전방향 챔퍼 | 청록 Primary, 보라 Selected, 역할별 서로 다른 챔퍼 |
| `galar_stadium` | League Challenge의 현대 스포츠형 후보 | 어두운 청록 셸, 흰 카드와 차콜 보조 패널 | 청록·분홍·노랑 기능색, 제한된 사선 모서리 |
| `paldea_portal` | 밝고 강한 현대 메뉴 후보 | 파랑 셸과 남색 정보 패널 | 노랑 Selected/Primary, 남색 비선택 행, 굵은 색 대비 |
| `hoenn_pixel` | GBA식 고밀도 정보 후보 | 올리브 배경, 청회색 카드, 굵은 이중 경계 인상 | 평평한 색, 밝은 안쪽 선, 상태별 행 색 전환 |
| `johto_touch` | DS 터치 메뉴·아이콘 카드 후보 | 밝은 회백색 본문과 초록 셸, 기능별 색 카드 | 직사각형 카드, 초록·주황·빨강 역할색, 얇은 경계 |
| `unova_pixel` | BW2식 정밀·어두운 터미널 후보 | 검정에 가까운 셸과 패널 | 낮은 대비 타일, 1픽셀 청록·회색선, Selected만 강한 색 |

- `league_neon`은 호환 기준선으로 유지해야 한다(MUST).
- 최종 League Challenge 기본 테마는 실제 월드 비교 뒤 빡대리님이 선택한다(MUST). 구현자가 임의로 교체하면 안 된다(MUST NOT).
- 모든 프리셋은 같은 의미 버튼 종류와 상태 집합을 완전하게 제공해야 한다(MUST).
- 프리셋 전환은 `UiThemeRegistry`에 완성된 불변 스냅샷 하나를 설치하는 방식이어야 한다(MUST).
- 프리셋마다 버튼 높이와 최소 클릭 영역을 줄여 도트게임 외형을 흉내 내면 안 된다(MUST NOT). 도트 인상은 표면과 색으로 만들고 접근 가능한 입력 크기는 유지한다.
- 글꼴 파일이나 본가 아이콘은 첫 프리셋 범위가 아니다(MUST NOT). 폰트 축척과 패딩 토큰은 사용할 수 있다(MAY).

## 4. 명시적 제외

`Pokémon Legends: Arceus`와 히스이 계열의 붓글씨, 종이·먹 질감, 베이지·갈색 휴대 메뉴 문법은 이번 후보에서 제외한다(MUST NOT). League Challenge의 현대 터미널·랭크·시설 문맥과 맞지 않는다는 빡대리님의 결정이다.

도트게임을 참고한다는 이유로 다음도 가져오면 안 된다(MUST NOT).

- 원본 게임의 스프라이트, 아이콘, 프레임 텍스처 또는 로고
- 해상도 한계 때문에 생긴 작은 클릭 영역
- 언어별 글자 폭을 무시한 고정 텍스트 폭
- 모든 패널에 같은 세대 장식을 과도하게 반복하는 처리

## 5. 코드와 리소스팩 경계

첫 프리셋은 코드만으로 모양·색·채움·테두리를 렌더해야 한다(MUST). 이는 외부 리소스팩이 없어도 MBC, League Challenge와 독립 소비 모드가 같은 의미 계약으로 동작하게 하기 위함이다.

후속 내장 자산 또는 선택형 Visual Pack은 픽셀 패턴, 미세 노이즈, nine-slice 프레임과 자체 아이콘을 공급할 수 있다(MAY). 자산 팩은 프리셋 ID와 의미 토큰을 덮어쓸 수 있지만 입력 행동, 포커스, 내레이션, 상태 의미를 바꾸면 안 된다(MUST NOT).

## 6. Component Gallery와 검증 게이트

1. 프리셋 ID 파싱, 전체 목록, 중복 ID와 모든 상태 완전성을 순수 단위 테스트로 검증해야 한다(MUST).
2. Gallery는 현재 프리셋 이름과 전환 수단을 노출해야 한다(MUST).
3. 같은 화면·같은 해상도·같은 월드 상태에서 각 프리셋을 캡처해야 한다(MUST).
4. 캡처는 셸, Primary, Secondary, Danger, Ghost, Icon과 상태 행을 모두 포함해야 한다(MUST).
5. 구현 후 캡처를 자료와 나란히 두고, “원본과 같다”가 아니라 표의 어떤 규칙이 보이는지 기록해야 한다(MUST).
6. 소스·빌드·스크린샷은 실제 League 화면 소비나 마우스·키보드·화면 읽기 프로그램 검증을 대신하지 않는다(MUST NOT).

## 7. 보류한 결정

| 결정자 | 대상 | 보류 이유와 시점 |
| --- | --- | --- |
| 빡대리님 | League Challenge의 최종 기본 프리셋 | 같은 월드 Gallery 캡처를 비교한 뒤 선택 |
| 빡대리님 | 어떤 프리셋을 별도 Visual Pack으로 승격할지 | 코드 프리셋으로 계층·가독성을 먼저 검증한 뒤 선택 |
| 구현 담당자 + 빡대리님 | 도트 전용 비트맵 폰트·nine-slice 자산 | 한·영 글자 누락, 라이선스와 리소스팩 폴백 계획을 제시한 뒤 결정 |

## 8. 기각한 대안

| 대안 | 상태 | 이유 |
| --- | --- | --- |
| 아르세우스풍을 후보에 포함 | 기각 | League 터미널과 맞지 않고 사용자 결정과 충돌한다. |
| 모든 도트 세대를 하나의 레트로 테마로 통합 | 기각 | GBA의 굵은 이중 프레임, HGSS의 아이콘 카드, BW2의 검은 타일 차이를 잃는다. |
| 본가 캡처의 색을 그대로 추출해 복제 | 기각 | 화면·상태 문맥이 다르고 자산 복제 문제도 생긴다. 색 역할과 대비 규칙만 재해석한다. |
| 먼저 리소스팩을 만들고 위젯 계약을 맞춤 | 기각 | 소비 모드별 입력·상태 계약이 다시 갈라진다. 코드 프리셋을 기준으로 자산을 후속 적용한다. |
