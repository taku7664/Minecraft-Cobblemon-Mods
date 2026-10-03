# cobblemon-dev 배포 — 2026-10-03

- 소스 커밋: `28b9aff997dafcf6c20dd6f2edb38f3b18d2416e`
- 대상: `C:\Users\박주형\AppData\Roaming\ModrinthApp\profiles\cobblemon-dev\mods`
- 파일: `cobblemon-client-defaults-0.1.0.jar`
- SHA-256: `9F3D1C98DE1D743BD667C9908290D678290632925BEA6BF42AB7ED79EFDA39CF`
- 검증: 단위 테스트 18개, Gradle build, JDK jar 검증 통과. 빌드와 배포 파일의 해시 일치, 같은 mod ID의 설치 파일은 하나.
- CLC 기본 적용 방식: `ALWAYS`. 게임을 실행할 때마다 `client.hud.enabled=false`를 적용.
- 기존 CLC 클라이언트 설정은 배포 전부터 `false`였으며 배포에서는 수정하지 않음.
- 초기 설정 모드의 `client.toml`은 첫 게임 실행에서 생성 예정.
- 실제 게임 실행, HUD 표시 및 Mod Menu 화면은 이번 배포에서 확인하지 않음.
- 클라이언트 전용 배포. 서버에는 배포하지 않음.
