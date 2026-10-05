# jbro-policy MEMORY

작업 내역과 이슈를 `[YYYY-MM-DD HH:MM]` 형식으로 기록한다. 최신 항목을 위에 추가한다.
구현, 빌드, JAR 배치, 서버 기동, 실게임 검증은 서로 다른 상태로 적는다.

---

## [2026-10-05 16:20] 디스코드 `/위키` 개인 링크, `/전적` 분류 선택 (빌드·단위 테스트 확인, 디스코드 실사용 미확인)

- **빡대리님 지적:** 디스코드 `/위키` 링크로 들어가면 "내 정보"가 안 보인다. `/전적`은 한 번에 다 보여 줘서 난잡하니 리그챌린지·배틀팩토리·배틀타워·PvP로 나눈다.
- **원인:** 내 정보는 `/api/me`에 플레이어별 위키 토큰이 있어야 보이는데, 디스코드 `/위키`는 토큰 없는 공용 주소만 줬다.
- **`/위키`:** `/디코인증`으로 연동한 사람에게는 `WikiApi.sharedLinkFor`(MCC에 새로 연 API) 링크를 준다. 기존 `WikiApi.linkFor`는 쓰지 않는다. 오프라인 플레이어면 서버 안 포트(`localhost:8100`)를, 로컬 위키를 띄운 클라이언트면 그 PC의 `localhost` 주소를 주기 때문이다. 링크를 가진 사람은 누구나 그 플레이어 정보를 보므로 답은 모두 본인에게만 보이게(ephemeral) 했다. 연동하지 않은 사람은 공용 주소와 연동 안내를 받는다.
- **`/전적`:** 필수 옵션 `분류`(선택지 네 개)가 `닉네임` 앞에 온다. 고른 콘텐츠 카드만 보여 주고, 제목이 분류를 말하므로 카드 내용은 필드 대신 본문(description)에 넣었다.
- **검증:** `:jbro-policy:test`, `:more-cobblemon-contents:test --tests "*Wiki*"` 통과. 디스코드에서 명령을 실제로 쳐 보지는 않았다. 명령 정의가 바뀌었으니 서버를 다시 켜야 디스코드에 새 옵션이 등록된다.
- **함정:** 이 작업 중 `cobblemon-ui`의 빌드 산출물에서 `CobblemonUiOverlays.kt` 클래스가 통째로 빠졌는데 Gradle은 UP-TO-DATE로 봤다. MCC 컴파일이 `Unresolved reference 'CobblemonUiDialogScreen'`으로 깨지면 `./gradlew :cobblemon-ui:compileKotlin --rerun`으로 다시 컴파일한다.
