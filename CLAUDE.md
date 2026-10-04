# CLAUDE.md

> 글로벌 지침(`~/.claude/CLAUDE.md`) 상속: 사고규율(Before/After Acting), 행동규율(Break/Cross/Ground), 환경식별, 호칭, Python/uv 규칙 등.

## 응답 언어 (최우선)

- 사용자에게 보내는 모든 글은 한국어로 쓴다. 최종 답변, 작업 중간의 진행 보고, 도구 호출 사이에 쓰는 한두 줄 설명까지 전부 해당한다.
- 코드, 로그, 커밋 메시지, 도구 출력이 영어여도 답변은 한국어다. 영어 로그나 코드를 길게 읽은 직후, 긴 작업을 끝내고 결과를 보고할 때 특히 틀리기 쉬우니 보내기 전에 언어를 확인한다.
- 코드 식별자, 명령어, 파일 경로는 원문 그대로 두고, 설명은 한국어로 쓴다.

## Project Rules

- 개발 서버는 저장소 안 `develop-product/server` 하나뿐이다. 저장소 밖 서버(옛 `Mincraft-Cobblemon-Server`)나 옛 `dev-server/` 경로는 참조하지 않는다.
- 개발 클라이언트는 저장소 안 `develop-product/client`로 다룬다. 이 경로는 Modrinth 프로필 `%APPDATA%\ModrinthApp\profiles\cobblemon-dev`를 가리키는 정션이다. Modrinth가 링크 뒤의 프로필을 거부해서 실제 파일은 AppData에 둔다. 게임이 켜져 있으면 JAR을 복사하지 않는다.
- 릴리스 산출물은 `deploy-product/client`, `deploy-product/server`에 모은다. 완성된 JAR과 `VERSION.txt` 외에는 아무것도 두지 않는다(백업·임시파일·로그·소스 JAR 금지). 자세한 규칙은 `AGENTS.md`의 Product folders.
- 임시파일과 중간 산출물은 `build/`나 세션 scratchpad에 두고, 제품 폴더나 저장소 루트에 남기지 않는다.
<!-- 대화 중 발견된 프로젝트 규칙이 여기에 추가됩니다. -->

- 테스트를 위해 실행한 클라이언트는 검증이 끝나면 직접 종료한다.
