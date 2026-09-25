# League 홈 UI 백엔드 스파이크 결과

- 상태: `non-normative evidence record`
- 실행일: 2026-09-25
- 대상: GUI 백엔드를 결정할 빡대리님과 후속 구현자
- fixture: `badges_3`, `long_disabled`
- 런타임: Minecraft 1.21.1, Fabric Loader 0.19.5, owo-lib 0.12.15.4+1.21

이 문서는 코드 드로잉과 owo 후보를 같은 fixture로 Minecraft 1.21.1 클라이언트의 **타이틀 파노라마 위**에서 실행한 결과를 기록한다. 실제 월드에 들어간 플레이 검증, 터미널 진입 검증 또는 백엔드 최종 채택 기록이 아니며, [GUI_FRAMEWORK_AND_RESOURCE_PACK.md](GUI_FRAMEWORK_AND_RESOURCE_PACK.md)의 LGUI-3 승인 조건을 대체하지 않는다.

## 캡처

### 논리 해상도 640×360 · 영어/한국어

- 코드 드로잉: [영어 원본](assets/ui-spike/league-code-badges_3-en_us-640x360.png) · [한국어 원본](assets/ui-spike/league-code-badges_3-ko_kr-640x360.png)
- owo 후보: [영어 원본](assets/ui-spike/league-owo-badges_3-en_us-640x360.png) · [한국어 원본](assets/ui-spike/league-owo-badges_3-ko_kr-640x360.png)

네 파일을 원본 크기로 비교했다. `badges_3` fixture에서 제목, 계급, 뱃지 경로, 다음 도전, 시설 상태와 행동 버튼이 화면 안에 유지됐다. 이 결과는 아직 긴 관장명·보상명 fixture의 안전성을 증명하지 않는다.

### 논리 해상도 427×240

[코드 드로잉 원본](assets/ui-spike/league-code-badges_3-427x240.png) · [owo 원본](assets/ui-spike/league-owo-badges_3-427x240.png)

![427×240 코드 드로잉과 owo 비교](assets/ui-spike/league-code-vs-owo-427x240.png)

긴 관장명·보상명, 잠긴 행동과 3D 트레이너 슬롯을 함께 넣은 `long_disabled`도 같은 크기로 비교했다.

- 코드 드로잉: [영어 원본](assets/ui-spike/league-code-long_disabled-en_us-427x240.png) · [한국어 원본](assets/ui-spike/league-code-long_disabled-ko_kr-427x240.png)
- owo 후보: [영어 원본](assets/ui-spike/league-owo-long_disabled-en_us-427x240.png) · [한국어 원본](assets/ui-spike/league-owo-long_disabled-ko_kr-427x240.png)

네 파일을 원본 크기로 비교했다. 영어 4줄·한국어 3줄 관장명 뒤에 보상문이 겹치지 않았고, 모델은 초상화 안쪽 scissor 경계에 머물렀다. 잠금 버튼은 짧은 화면 라벨을 사용하되 전체 이유를 내레이션으로 유지했다.

### 논리 해상도 320×240

[코드 드로잉 원본](assets/ui-spike/league-code-badges_3-320x240.png) · [owo 원본](assets/ui-spike/league-owo-badges_3-320x240.png)

![320×240 코드 드로잉과 owo 비교](assets/ui-spike/league-code-vs-owo-320x240.png)

## 관찰한 사실

| 항목 | 코드 드로잉 | owo 후보 |
| --- | --- | --- |
| 정보 구조 | 계급, 레벨캡, 뱃지, 다음 도전, 시설 상태와 행동 버튼 표시 | 동일 |
| 320×240 배치 | 세로형으로 전환하며 캡처 범위 안에 유지 | 동일 |
| 427×240 배치 | 뱃지와 다음 도전을 좌우로 배치 | 동일 |
| 배경 | 흐려진 Minecraft 파노라마와 외곽 그림자 유지 | 불투명한 검은 루트 배경 |
| 버튼 | 1픽셀 외곽선 포함 | 현재 flat renderer에는 외곽선 없음 |
| 기본 위젯 외형 | 사용하지 않음 | 사용하지 않고 사용자 정의 surface와 renderer 적용 |
| 640×360 한영 | `en_us`, `ko_kr` 모두 잘림 없이 표시 | `en_us`, `ko_kr` 모두 잘림 없이 표시 |
| 427×240 긴 문자열 | 줄 수를 측정해 관장명 다음에 보상문 배치 | 동일한 배치 계산을 사용하며 겹침 없음 |
| 잠긴 행동 | TAB 포커스와 ENTER 설명은 허용하지만 행동은 전달하지 않음 | 동일 |
| 3D 슬롯 | 바닐라 Steve fixture 모델이 초상화 내부에 표시 | 초상화 컴포넌트 surface 단계에서 같은 렌더러로 표시 |
| 키보드 행동 | TAB 포커스 후 ENTER로 행동 전달 | TAB 포커스 후 ENTER로 행동 전달 |
| 내레이션 | 포커스한 버튼의 제목·사용 안내 생성 | MBC 소유 전달 어댑터를 추가한 뒤 동일 안내 생성 |
| 닫기 | 화면 `onClose` 경로로 닫힘 | 화면 `onClose` 경로로 닫힘 |

따라서 owo를 사용하면 JEI 같은 기본 위젯 외형으로 고정된다는 가설은 이 스파이크에서 반례가 나왔다. owo 컴포넌트를 사용하면서도 League 테마를 코드 드로잉과 거의 같은 형태로 표현할 수 있었다.

그러나 두 후보가 거의 같은 형태라는 사실은 공용 위젯 프레임워크의 성공 증거가 아니다. 두 구현 모두 행동 버튼에 고정 폭을 주고, 같은 Minecraft 글자 크기와 유사한 직사각형 표면을 반복했다. 내용 폭·크기·variant·아이콘·보조 문구를 가진 버튼 계열, 목록·탭·콤보박스 같은 일관된 위젯 인터페이스와 교체 가능한 테마 객체는 아직 구현하거나 비교하지 않았다.

코드 드로잉 첫 캡처에서는 사용자 정의 내용 전체가 흐려졌다. 화면이 직접 `renderBackground`를 호출한 뒤 `Screen.render`를 다시 호출해 배경 처리가 중복된 것이 원인이었다. 버튼만 직접 렌더링하도록 고친 뒤 위 비교 캡처를 다시 만들었다.

owo의 기본 `OwoUIAdapter`는 자신을 바닐라 화면의 포커스 대상으로 노출하지만 내레이션 우선순위와 내용을 제공하지 않았다. 내부 버튼은 내레이션을 만들 수 있어도 화면 밖으로 전달되지 않는 구조였다. 스파이크에는 포커스된 내부 `NarratableEntry`의 우선순위와 내용을 전달하는 `LeagueNarratingOwoAdapter`를 추가했다. 따라서 owo를 채택하면 이 전달 계층을 Cobblemon UI 툴킷의 비공개 백엔드가 소유해야 한다(MUST).

바닐라와 owo 모두 버튼 자체를 비활성화하면 TAB 포커스 대상에서 빠져 잠금 이유를 키보드로 확인할 수 없었다. 따라서 잠긴 행동은 포커스와 ENTER 설명은 받되 실제 행동 전달을 거부하는 별도 권한 상태로 구현했다. 버튼에는 `Locked`/`잠김`만 표시하고 전체 이유는 내레이션과 하단 상태문으로 제공한다. Owo 고정 폭 버튼은 긴 라벨을 자동으로 자르지 않고 밖으로 그렸기 때문에 이 짧은 시각 라벨 분리는 레이아웃 안전에도 필요했다.

처음에는 Owo 화면의 전체 렌더가 끝난 뒤 3D 모델을 덧그렸고, 검증 플래그는 참이었지만 실제 캡처에는 모델이 보이지 않았다. 모델 렌더를 초상화 컴포넌트의 `Surface` 단계로 옮긴 뒤 실제 픽셀에서 표시되는 것을 확인했다. 모델 렌더러는 두 백엔드가 공유하며 초상화 안쪽에서 scissor를 열고 `finally`에서 반드시 닫는다. 현재 자산은 렌더·클리핑 검증용 바닐라 Steve일 뿐이며, 리소스팩 관장 스킨이나 `default`/`slim` 선택을 검증한 증거는 아니다.

입력 검증은 타이틀 화면 개발 하네스가 실제 클라이언트의 `Screen.keyPressed` 경로에 TAB과 ENTER를 주입하고, 포커스 대상·내레이션 항목·행동 전달 상태를 검사했다. Minecraft의 `Screen.keyPressed(TAB)`은 포커스를 옮겨도 `false`를 반환하므로 반환값이 아니라 실제 포커스 결과를 판정했다. 이는 물리 키보드·마우스 조작이나 화면 읽기 프로그램의 음성 출력을 사람이 확인한 결과가 아니며, `client.level`과 `client.player`도 없는 상태였다.

두 한국어 실행에서 Cobblemon 자체 `cobblemon:lang/ko_kr.json`의 834행 부근이 잘못되어 해당 외부 언어 파일을 건너뛰었다는 경고가 발생했다. League와 MBC의 언어 파일 오류는 아니었고 League 한국어 화면과 내레이션은 정상 작동했다. 배포 조합에서는 Cobblemon 한국어 파일 제공 출처를 별도로 확인해야 한다.

정적 계약 테스트에서도 MBC의 `en_us`·`ko_kr` 574개 키와 League의 33개 키가 각각 일치하고, 빈 값·영문 번들의 한글 혼입·포맷 자리표시자 형식 불일치가 없음을 확인했다. MBC 번역값 수정은 필요하지 않았다.

## 현재 판단

이 결과만으로 자체 렌더러와 owo 중 선두 후보를 정하지 않는다(MUST NOT). owo가 커스텀 표면을 막지 않는다는 점은 확인했지만, 두 구현 모두 사용자가 지적한 넓은 고정 폭 버튼·동일한 글자 크기·단일 버튼 문법을 유지했다. 다음 항목을 검증한 뒤에만 백엔드와 위젯 API를 결정한다(MUST).

- 공용 Component Gallery의 버튼 계열, 목록, 탭, 스크롤, 상태와 테마 교체
- 실제 월드에서 터미널 블록 우클릭 → 서버 패킷 → League 화면 열기
- 물리 마우스 입력과 화면 읽기 프로그램의 실제 음성 출력
- 리소스팩 사용자 정의 관장 스킨과 `default`/`slim` 모델
- 프레임 시간과 입력 지연

## 임시 구현 경계

현재 `LeagueChallengeOwoSpikeScreen`은 비교를 위해 League 모듈에서 owo를 직접 참조한다. 이는 개발 환경 명령과 자동 캡처에만 쓰는 일회성 예외다. 제품 구조에서는 League가 owo 타입을 참조하면 안 된다(MUST NOT).

LGUI-3에 들어가기 전에 다음 중 하나를 수행해야 한다(MUST).

1. owo를 채택하면 렌더러 소유권을 Cobblemon UI 툴킷 안으로 옮기고 League는 백엔드 비종속 계약만 사용한다.
2. owo를 기각하면 `LeagueChallengeOwoSpikeScreen`, 관련 명령과 의존성을 제거한다.

이 예외가 남은 JAR은 제품 배포 대상으로 취급하지 않는다.

## 재현 방법

다음 명령은 실제 개발 월드에 들어간 뒤 수동 검증용으로 사용할 수 있지만, 이 문서의 캡처를 만들 때는 사용하지 않았다.

```text
/mbc-league-ui badges_3
/mbc-league-ui-owo badges_3
```

자동 캡처는 프로세스 환경 변수로 백엔드와 fixture를 고른다.

```text
MBC_LEAGUE_CAPTURE_BACKEND=code|owo
MBC_LEAGUE_CAPTURE_FIXTURE=badges_3|long_disabled
MBC_LEAGUE_CAPTURE_LOCALE=en_us|ko_kr
MBC_LEAGUE_CAPTURE_GUI_SCALE=1..4
```

캡처 하네스는 개발 환경에서만 설치된다. 타이틀 화면에서 지정한 언어와 GUI 배율을 적용한 뒤 fixture 화면을 열고, 주입한 키보드 포커스·내레이션·ENTER 행동을 검사한다. 20틱 뒤 `run/screenshots`에 PNG를 저장하고 닫기 경로를 확인한 다음 클라이언트를 종료한다. 이 경로는 실제 월드와 터미널·서버 패킷을 거치지 않는다.
