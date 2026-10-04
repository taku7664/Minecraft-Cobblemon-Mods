# 개발 서버 WorldEdit 설치 기록

2026-10-04, 로컬 개발 서버 `C:\Users\박주형\Documents\GitHub\Cobblemon-Mods\dev-server`의 `mods`에 `worldedit-mod-7.3.8.jar`를 설치했다. 대상은 Minecraft 1.21.1 / Fabric Loader 0.19.5이며, Modrinth의 Fabric 1.21.1 호환 버전 목록에서 7.3.8이 최신 릴리스였다.

- 출처: [Modrinth WorldEdit 7.3.8](https://modrinth.com/plugin/worldedit/version/7.3.8), 버전 ID `WTAFvuRx`
- 설치 파일 SHA-256: `5E7752C97876D87411E3760BCC573CC431F43C453722E6959FA7FE54DB1B01CA`
- 설치 파일 SHA-512: `E039492DF0B486E7CE76D0EAF8CB11EADAD2E78220600B8498AB8EEF4642A29E310A6BCB4378257553B3639C6D0BC1EBF0F73DF3A7A677C6AF6BAF86716B0BC7` (Modrinth 제공 값과 일치)

JAR의 `fabric.mod.json`은 모드 ID `worldedit`, 버전 `7.3.8+6939-7d32b45`를 선언한다. 설치 당시 개발 서버에는 플레이어가 접속해 있었고, 사용자 요청에 따라 서버를 재시작하지 않았다. 파일은 설치됐지만 실행 중인 서버에서 로드 여부는 아직 확인되지 않았다. 다음 서버 재시작 후 `logs/latest.log`의 WorldEdit 로드 메시지로 확인해야 한다. 운영 서버와 클라이언트 프로필은 변경하지 않았다.
