# League 홈 UI 백엔드 스파이크 결과

- 상태: `non-normative evidence record`
- 실행일: 2026-09-25
- 대상: GUI 백엔드를 결정할 빡대리님과 후속 구현자
- fixture: `badges_3`
- 런타임: Minecraft 1.21.1, Fabric Loader 0.19.5, owo-lib 0.12.15.4+1.21

이 문서는 코드 드로잉과 owo 후보를 같은 fixture로 실제 Minecraft 클라이언트에서 실행한 결과를 기록한다. 백엔드 최종 채택 기록이 아니며, [GUI_FRAMEWORK_AND_RESOURCE_PACK.md](GUI_FRAMEWORK_AND_RESOURCE_PACK.md)의 LGUI-2 승인 조건을 대체하지 않는다.

## 캡처

### 논리 해상도 640×360 · 영어/한국어

- 코드 드로잉: [영어 원본](assets/ui-spike/league-code-badges_3-en_us-640x360.png) · [한국어 원본](assets/ui-spike/league-code-badges_3-ko_kr-640x360.png)
- owo 후보: [영어 원본](assets/ui-spike/league-owo-badges_3-en_us-640x360.png) · [한국어 원본](assets/ui-spike/league-owo-badges_3-ko_kr-640x360.png)

네 파일을 원본 크기로 비교했다. `badges_3` fixture에서 제목, 계급, 뱃지 경로, 다음 도전, 시설 상태와 행동 버튼이 화면 안에 유지됐다. 이 결과는 아직 긴 관장명·보상명 fixture의 안전성을 증명하지 않는다.

### 논리 해상도 427×240

[코드 드로잉 원본](assets/ui-spike/league-code-badges_3-427x240.png) · [owo 원본](assets/ui-spike/league-owo-badges_3-427x240.png)

![427×240 코드 드로잉과 owo 비교](assets/ui-spike/league-code-vs-owo-427x240.png)

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
| 키보드 행동 | TAB 포커스 후 ENTER로 행동 전달 | TAB 포커스 후 ENTER로 행동 전달 |
| 내레이션 | 포커스한 버튼의 제목·사용 안내 생성 | MBC 소유 전달 어댑터를 추가한 뒤 동일 안내 생성 |
| 닫기 | 화면 `onClose` 경로로 닫힘 | 화면 `onClose` 경로로 닫힘 |

따라서 owo를 사용하면 JEI 같은 기본 위젯 외형으로 고정된다는 가설은 이 스파이크에서 반례가 나왔다. owo 컴포넌트를 사용하면서도 League 테마를 코드 드로잉과 거의 같은 형태로 표현할 수 있었다.

코드 드로잉 첫 캡처에서는 사용자 정의 내용 전체가 흐려졌다. 화면이 직접 `renderBackground`를 호출한 뒤 `Screen.render`를 다시 호출해 배경 처리가 중복된 것이 원인이었다. 버튼만 직접 렌더링하도록 고친 뒤 위 비교 캡처를 다시 만들었다.

owo의 기본 `OwoUIAdapter`는 자신을 바닐라 화면의 포커스 대상으로 노출하지만 내레이션 우선순위와 내용을 제공하지 않았다. 내부 버튼은 내레이션을 만들 수 있어도 화면 밖으로 전달되지 않는 구조였다. 스파이크에는 포커스된 내부 `NarratableEntry`의 우선순위와 내용을 전달하는 `LeagueNarratingOwoAdapter`를 추가했다. 따라서 owo를 채택하면 이 전달 계층을 MBC 비공개 백엔드가 소유해야 한다(MUST).

입력 검증은 실제 개발 클라이언트의 `Screen.keyPressed` 경로에 TAB과 ENTER를 주입하고, 포커스 대상·내레이션 항목·행동 전달 상태를 검사했다. Minecraft의 `Screen.keyPressed(TAB)`은 포커스를 옮겨도 `false`를 반환하므로 반환값이 아니라 실제 포커스 결과를 판정했다. 이는 물리 키보드·마우스 조작이나 화면 읽기 프로그램의 음성 출력을 사람이 확인한 결과는 아니다.

두 한국어 실행에서 Cobblemon 자체 `cobblemon:lang/ko_kr.json`의 834행 부근이 잘못되어 해당 외부 언어 파일을 건너뛰었다는 경고가 발생했다. League와 MBC의 언어 파일 오류는 아니었고 League 한국어 화면과 내레이션은 정상 작동했다. 배포 조합에서는 Cobblemon 한국어 파일 제공 출처를 별도로 확인해야 한다.

정적 계약 테스트에서도 MBC의 `en_us`·`ko_kr` 574개 키와 League의 28개 키가 각각 일치하고, 빈 값·영문 번들의 한글 혼입·포맷 자리표시자 형식 불일치가 없음을 확인했다. MBC 번역값 수정은 필요하지 않았다.

## 현재 판단

owo는 MbcUI의 비공개 Minecraft 백엔드 선두 후보로 유지한다(SHOULD). 세 해상도, 한영 홈 화면, 키보드 행동·내레이션 전달과 닫기 경로를 통과했지만 다음 항목을 실제 게임에서 검증하기 전에는 최종 채택하면 안 된다(MUST NOT).

- 마우스 입력과 비활성 버튼 설명
- 긴 관장명·보상명 fixture
- 3D 트레이너 슬롯과 scissor 경계
- 프레임 시간과 입력 지연

## 임시 구현 경계

현재 `LeagueChallengeOwoSpikeScreen`은 비교를 위해 League 모듈에서 owo를 직접 참조한다. 이는 개발 환경 명령과 자동 캡처에만 쓰는 일회성 예외다. 제품 구조에서는 League가 owo 타입을 참조하면 안 된다(MUST NOT).

LGUI-3에 들어가기 전에 다음 중 하나를 수행해야 한다(MUST).

1. owo를 채택하면 렌더러 소유권을 MBC 안으로 옮기고 League는 백엔드 비종속 `MbcUI` 계약만 사용한다.
2. owo를 기각하면 `LeagueChallengeOwoSpikeScreen`, 관련 명령과 의존성을 제거한다.

이 예외가 남은 JAR은 제품 배포 대상으로 취급하지 않는다.

## 재현 방법

개발 클라이언트에서 수동으로 연다.

```text
/mbc-league-ui badges_3
/mbc-league-ui-owo badges_3
```

자동 캡처는 프로세스 환경 변수로 백엔드와 fixture를 고른다.

```text
MBC_LEAGUE_CAPTURE_BACKEND=code|owo
MBC_LEAGUE_CAPTURE_FIXTURE=badges_3
MBC_LEAGUE_CAPTURE_LOCALE=en_us|ko_kr
MBC_LEAGUE_CAPTURE_GUI_SCALE=1..4
```

캡처 하네스는 개발 환경에서만 설치된다. 지정한 언어와 GUI 배율을 적용한 뒤 화면을 열고, 키보드 포커스·내레이션·ENTER 행동을 검사한다. 20틱 뒤 `run/screenshots`에 PNG를 저장하고 닫기 경로를 확인한 다음 클라이언트를 종료한다.
