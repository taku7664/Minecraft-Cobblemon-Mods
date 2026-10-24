# More Cobblemon Contents Core

<img src="src/main/resources/assets/more_cobblemon_contents/icon.png" alt="More Cobblemon Contents Core icon" width="128">

Cobblemon에 전투 시설을 추가하는 MCC의 공통 기반 모드입니다. 실제 도전 콘텐츠는 원하는 애드온을 선택해 설치합니다.

## 주요 기능

- 전투 진행과 결과 처리를 공유하는 관리 전투 기반 및 내장 전술 AI
- Battle Points(BP), 보상 상점, 플레이어 전투 기록
- 배틀 허브와 홀로그램 터미널, 콘텐츠별 대시보드
- 애드온과 음악·연출 모드를 위한 공개 연동 API

## 애드온

| 애드온 | 콘텐츠 |
|---|---|
| [Battle Tower](../more-cobblemon-contents-battle-tower/README.md) | 자신의 팀으로 도전하는 연승전 |
| [Battle Factory](../more-cobblemon-contents-battle-factory/README.md) | 대여 포켓몬을 활용하는 도전 |
| [PvP](../more-cobblemon-contents-pvp/README.md) | 플레이어 대전방과 배틀 라운지 |
| [League Challenge](../more-cobblemon-contents-league-challenge/README.md) | 체육관·배지·사천왕·챔피언 진행 |

## 개발자 참고

허브 탭, 대시보드, 음악 연동 API와 운영 명령의 세부 사용법은 [DEVELOPER.md](DEVELOPER.md)에 있습니다.

## 설치

**클라이언트와 서버 양쪽에 설치합니다.** 싱글플레이에서도 같은 클라이언트 모드를 사용합니다.

| 항목 | 요구 사항 |
|---|---|
| Minecraft / Java | Java Edition 1.21.1 / Java 21 이상 |
| 로더 | Fabric Loader 0.19.5 이상 |
| 공통 필수 모드 | Fabric API, Fabric Language Kotlin 1.14.1+kotlin.2.4.20 이상, Cobblemon 1.8.1 이상 / 1.9.0 미만 |
| 본체 필수 모드 | Mega Showdown `1.2.0+1.8.1+1.21.1-release` |
| 선택 연동 | MCC 콘텐츠 애드온, Better Cobblemon Battlecam, Better Cobblemon Music |

1. 서버와 접속할 클라이언트의 `mods` 폴더에 모드 JAR과 필수 모드를 넣습니다. 필요한 지원 라이브러리는 각 의존 모드의 설치 안내를 따릅니다.
2. 본체와 사용하려는 애드온을 같은 구성으로 설치합니다. 애드온은 본체 없이 사용할 수 없습니다.
3. 플레이어는 `/mcc` 또는 콘텐츠의 홀로그램 터미널로 배틀 허브를 엽니다. 서버에서 해당 탭을 허용해야 접근할 수 있습니다.

UI 키트는 본체 JAR에 포함돼 있으므로 MCC용 UI 키트를 별도로 복제해 설치할 필요는 없습니다.

## 설정과 콘텐츠 편집

본체 설정은 서버가 관리합니다. Mod Menu의 본체 설정 화면에서는 이 컴퓨터가 여는 서버의 Better AI 계산 최적화를 켜고 끕니다. 원격 서버에는 서버의 JSON 설정을 사용합니다. 플레이어는 `/mcc` 또는 콘텐츠 터미널로 허브를 엽니다.

- `config/more-cobblemon-contents/hub_tabs.json`: `/mcc`와 각 터미널에 표시할 탭, 허브 명령어 권한.
- `config/more-cobblemon-contents/wiki.json`: 서버 위키 연동 설정.
- `config/more-cobblemon-contents/betterai-optimizations.json`: `single`와 `double` 객체에서 각 최적화를 독립적으로 켜고 끕니다. 모든 기본값은 `false`입니다. Mod Menu에서도 싱글·더블을 따로 설정합니다. 다음 새 전투부터 읽으며 진행 중인 전투는 시작할 때의 설정을 유지합니다. 잘못된 파일은 덮어쓰지 않고 새 전투의 최적화를 끕니다. 재시작이나 `/reload`는 필요 없습니다. 구형 최상위 옵션은 별도 모드 설정이 없는 모드에 이전과 같이 적용되며, 저장하면 두 모드로 분리됩니다.
  - `simpleStatusMatchups`: 1번 상태기 후속 대면 단순화입니다. 기존 대면의 대표 공격을 고정하고, 피해 배율·평균 피해·필요 타격 수로 사용 전·후를 모두 계산합니다. 후속 기술 재선택·정밀 피해·장기 KO 분포를 생략하므로 점수와 선택이 달라질 수 있습니다.
  - `simpleActingChance`: 2번 행동 가능 확률 단순화입니다. 기존 피해 표를 입력으로 상대 슬롯마다 낮은 피해·높은 피해 두 대표 구간을 만들고, 두 구간의 합산 KO를 간단식으로 계산합니다. 공격 대면 재생성·상대 선택의 전체 조합 전개·64단계 합산 적분을 생략합니다. 최대 한 마리의 상대 다이맥스, 선공·명중·타깃·비공격 선택을 반영하지만 기술별 피해·타깃의 상관관계는 근사합니다.
  - 위 두 옵션은 싱글·더블별 기본 `false`이고 Mod Menu에 각각 표시합니다. 현재 행동의 랭크 평가식은 유지합니다. 전후 계산식·측정·판단 변화는 [1·2번 단순화 검수](docs/BETTERAI_FORMULA_REPLACEMENT_20261024.md)에 있습니다. 이전 두 기술 최적화 키는 JSON 호환용으로 기존 동작을 유지하며 새 근사를 자동으로 켜지 않습니다.
  - `firstHitActingChance`: 행동 가능 확률에서 예측한 공격의 첫 피격 자료만 만듭니다. 이미 만든 장기 자료는 공유하며, 예측 불명·중복 기술 후보·수치 경계·진화 대면은 기존 장기 경로를 유지합니다. 현재 행동의 랭크 평가는 유지합니다. 싱글·더블 각각 기본 `false`입니다.
  - `skipUnusedStatusMatchups`: 평가할 변화기 효과가 없을 때 피격 후·더블 파트너 대면 재계산을 생략합니다. 평가 대상 판정과 점수식은 기존 경로를 공유합니다. 싱글·더블 각각 기본 `false`입니다.
  - `simpleFixedHpReactions`: 독립적인 생구 HP 반응을 간단 계산합니다. 복합 반응·반동사 등은 기존 계산을 유지합니다.
  - `simplePoisonEvaluation`: 독·맹독의 장기 대면 평가를 단기 HP·KO 평가로 바꿉니다. 각 모드의 `poisonEvaluationTurns`는 1~8턴이며 기본값은 2입니다.
  - `simpleHpMatchups`: HP만 바뀐 판에서 HP와 무관한 기존 피해를 재사용하고 새 남은 HP 상한을 적용합니다. 이때 전술 계산·피해 롤 계산을 생략합니다. HP 의존 효과, 스크립트 기술, 연속기 등은 기존 경로를 사용합니다. KO 누적표·교환 승률·랭크 점수식은 유지합니다.
  - `simpleForecastMatchups`: 미래 스윕·스윕 저지의 대면을 간단 계산합니다. 처음 준비한 피해 롤에 실제 위력·공격·방어·보정 배율의 비율을 적용하고, 평균 피해와 필요 타격 수로 장기 교환을 추정합니다. 미래 판마다 기술 후보·정밀 피해·KO 누적표를 다시 만들지 않습니다. 현재 행동의 랭크 평가와 직접 대면 계산은 기존 정밀 경로를 유지합니다. 근사 때문에 미래 점수와 최종 선택은 달라질 수 있습니다.

예를 들어 `{"single":{},"double":{"simpleForecastMatchups":true}}`는 더블에서만 미래 대면 간단 계산을 켭니다. 점수와 선택이 달라질 수 있는 실험 옵션이며, 비교 범위와 결과는 [계산 최적화 검토](docs/BETTERAI_OPTIMIZATION_REVIEW.md)를 따릅니다.

- `config/more-cobblemon-contents/bp-shop.json`: BP 상점 전체를 관리하는 단일 JSON입니다. 최초 로드 때 기본 파일을 생성하고 이후에는 이 파일만 읽습니다.
  - `entries`: 판매 품목 목록. 품목을 추가·삭제하거나 `price_bp`를 바꾸고 `/reload`하면 적용됩니다. 삭제한 품목은 재시작해도 돌아오지 않으며, `[]`는 빈 상점입니다.
  - `categories`, `shopkeeper`, `limits`: 분류 순서·상점 외형·구매 제한도 같은 파일에서 관리합니다. 품목이 없는 분류는 표시하지 않습니다.
  - 잘못된 JSON은 기존 정상 상점을 유지하고 오류를 기록합니다. 파일 자체를 삭제하면 기본 파일을 다시 생성합니다.
  - 기본값은 볼·경험사탕·이상한사탕·학습장치·PP에이드 계열·포인트업·PP맥스·민트를 제외합니다. 노력치 드링크 6종은 각 3 BP, 개체값 증가 사탕 6종은 각 10 BP, 감소 사탕 6종은 각 1 BP, 마법이 부여된 황금 사과는 120 BP로 판매합니다.

서버 설정과 데이터팩을 수정한 뒤 관리자 권한으로 `/reload`를 사용합니다. `/mcc`의 기본 허브 접근 권한은 0이며, 운영 명령은 권한 2를 요구합니다. `/mcc bp`와 `/mcc bp history [count]`로 자신의 BP를 조회할 수 있습니다.

## BP·상점 거래 복구 기록

서버 보상 코드가 `BattlePointService.apply`/`BattlePointRewards.award`로 지급하는 콘텐츠 BP와 기본 BP 상점은 월드의 `data/mcc-compensation-v1/`에 원거래 번호·수령자·정확한 금액·상품·처리 상태를 기록합니다. 디스코드 운영자 직접 지급과 ADMIN 계열 조정은 자동 복구 권리로 만들지 않습니다. 잔액·완료 내역은 기존 BP SavedData를 사용하며 원거래 기록은 문의 종결 후에도 보존합니다.

- 정상 상점 구매는 인벤토리 공간을 먼저 검사한 뒤 원거래를 기록하고 차감·전달합니다. 이미 차감됐지만 전달을 시작하지 않은 구매만 복구할 수 있습니다. 복구에는 현재 카탈로그 대신 결제 당시 상품·수량을 사용합니다.
- 재문의·정상/비정상 서버 재시작에도 같은 원거래로 새 지급을 만들지 않습니다. BP·아이템 변경 전에 원거래 상태를 파일에 강제 기록하며, 처리 도중 중단돼 결과가 불명확하면 재시도 대신 관리자 확인으로 남깁니다. BP 저장 내역과 완료 기록이 어긋나도 자동 지급하지 않습니다.
- 이후 운영자 BP 조정은 거래 내역 순서로 검사합니다. 기록 손상·삭제·저장 실패·부분 전달·원거래 번호 충돌도 자동 복구를 막습니다. 월드/저장소의 임의 롤백·수동 파일 삭제는 정상 복구 절차가 아닙니다.
- `jbro-policy`의 피츄 문의에서 원거래 검증 API를 호출하며 `autoResolve`가 자동 처리를 제어합니다. 문의에 거래 UUID가 없고 후보가 여러 건이면 관리자에게 넘깁니다. 차감이 없는 구매 문의로 새 결제를 진행하지 않습니다. 아이템을 받은 뒤 잃어버린 경우와 기능 설치 전의 사건은 자동 복구하지 않습니다.
- 공개 연동 API는 `MccCompensation`입니다. 조회·복구는 서버 스레드에서 호출하고, 원래 보상 금액이나 상품을 호출자가 지정하는 API는 제공하지 않습니다. 제어 옵션은 피츄 문의의 기존 Mod Menu 설정 화면에서 편집합니다.


수동 아이템 보상(`/give` 등) 전에는 OP 권한 2로 `/bp recoveryhold <플레이어UUID> <원거래UUID>`를 실행해 보류 성공을 확인합니다. 이후 자동 복구는 해당 거래를 다시 실행하지 않습니다. 외부 모드·명령으로 한 수동 아이템 지급은 BP 내역에 남지 않으므로 이 절차 없이 자동으로 감지할 수 없습니다. BP 운영자 조정은 원거래 이후 내역을 확인해 자동 차단합니다.

## 문제 해결

- 모드가 로드되지 않으면 클라이언트·서버의 모드 구성과 필수 의존성을 확인하세요.
- 허브에 콘텐츠가 보이지 않으면 해당 애드온 설치 여부와 본체의 `hub_tabs.json` 설정을 확인하세요.
- 데이터팩을 수정한 뒤 문제가 생기면 서버 로그에서 잘못된 콘텐츠 정의를 확인하세요. 모드 JAR을 직접 수정하는 대신 서버 데이터팩을 사용합니다.

## 라이선스와 배포 설명

이 모드의 자체 코드와 아이콘은 [MIT License](LICENSE)로 제공합니다. 의존 모드와 내장 라이브러리는 각각의 라이선스를 따릅니다.

내장 `cobblemon-ui`는 별도 저작권·라이선스 고지를 유지합니다. 원본 Cobblemon Extended Battle UI의 MIT 고지는 내장 UI JAR의 `THIRD_PARTY_LICENSE_CobblemonExtendedBattleUI`에 포함돼 있습니다. 자세한 고지는 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)에 있습니다.

공개 배포 페이지용 영문 설명은 [MODRINTH.md](MODRINTH.md)에 있습니다. [소스](https://github.com/taku7664/Minecraft-Cobblemon-Mods/tree/main/works/mods/more-cobblemon-contents) · [문제 신고](https://github.com/taku7664/Minecraft-Cobblemon-Mods/issues)
