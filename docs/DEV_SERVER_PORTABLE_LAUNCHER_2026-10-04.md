# 개발 서버 실행 파일의 Java 경로 수정

2026-10-04, 다른 컴퓨터에서 받은 실행 출력은 시작 훅이 `prepared 3 server properties and 2 world rules for mbc-dev-world`까지 완료된 뒤 `The system cannot find the path specified.`와 종료 코드 3을 보여줬다. 해당 개발 서버의 `run.bat`은 `C:\Program Files\Java\jdk-21\bin\java.exe`를 고정 경로로 호출하고 있었다. 모드 호환성 검사는 통과했으므로 훅 실패가 아니라 실행 파일 경로 선택 문제다.

`tools/dev-server-run.bat`을 개발 서버 루트에 `run.bat`으로 배포한다. 유효한 `JAVA_HOME\bin\java.exe`가 있으면 사용하고, 아니면 `PATH`의 `java`를 사용한다. 선택한 Java를 실행할 수 없으면 Java 21 설치 안내를 표시하고 종료한다. 훅 시작과 Java 실행 전에도 진행 메시지를 표시한다. 훅과 Fabric 런처의 나머지 인자는 유지했다.

작업 당시 로컬 개발 서버 `C:\Users\박주형\Documents\GitHub\Cobblemon-Mods\dev-server\run.bat`에 배포했다. 서버는 이후 `develop-product/server`로 이동했으며, 현재 실행 파일은 `develop-product/server/run.bat`이다. 이전 파일의 당시 백업 위치는 `deployment-backups/20261004-portable-java-launcher/run.bat.before`였다. 배포 파일 SHA-256: `63299CEE65983034113E0ED5B27C9331325F5D3BFE8799619AEEAA4468C1A238`.

이 컴퓨터의 Java 21 실행과 배포 파일의 내용 일치를 확인했다. 다른 컴퓨터로의 동기화 및 그 컴퓨터에서의 실제 서버 기동은 확인하지 못했다.
