# [2026-10-10 18:42] VPS 관리 GUI 구현·최종 fixture 검증 (main 반영·설치·실제 운영 변경 미완료)

- **사용자 결정·구현:** Windows 바탕화면에서 개발 폴더 패쳐처럼 GUI로 관리하며 광장 전용이 아닌 JSON·properties 등 변경 파일을 직접 선택해 커밋·푸시한다. Windows PowerShell 7 WinForms와 Python SSH RPC로 상태·변경 목록, 업데이트 검사·기능 적용, 선택 파일 커밋·푸시, 기존 커밋 푸시, 서버 시작·종료, 기존 콘솔, 로컬 연결 설정을 구현했다(메인 작업자 보고).
- **업데이트·보존:** 기능 파일만 반영하고 기존 config 값·루트 properties·run scripts·startup hooks·world·plaza·player와 생성된 `cobblemon-startup-hooks.zip`을 보존한다. 정상 저장·종료 확인 후 월드 전체와 설정을 서버 밖에 백업하며 자동 복구는 교체한 기능 파일만 되돌린다. 원래 실행 중이었던 경우에만 재기동한다. SSH 끊김에 대비한 detached 작업자·job ID 재조회와 원격 `flock`을 사용한다.
- **선택 커밋 정책:** 선택한 파일 이외의 변경은 보존하고 비밀값·일반 월드·플레이어 기록은 제외한다. 푸시가 실패한 커밋은 남겨 기존 커밋 푸시로 재시도한다. 이 동작과 보존·실패 처리는 테스트용 Git fixtures에서 검증한 범위다.
- **최종 검증:** 처음에는 backend 부재로 red import failure를 확인한 뒤 구현했다. VPS의 격리된 임시 Git fixture에서 Linux 최종 테스트 21개 모두 통과했다(1.512초). Windows 기존 20개 중 19개 통과·symlink 권한 1개 skip에 추가 LFS 실제 파일·손상 거부 테스트도 통과해 총 21개 중 20개 통과·1개 skip이다. 메인 작업자가 `RenderUiPath` 화면을 실제 이미지로 확인했고 PowerShell Parser 오류 0을 확인했다.
- **실제 SSH Probe·연결 분리:** GUI와 동일한 SSH 경로의 상태 Probe에서 커밋 `6f0ea24`·실행 중 상태·변경 파일 14개를 조회했고, 이미 실행 중인 서버를 대상으로 start job 생성·완료·결과 조회도 성공했다. 서버는 `tmux dead=0`, PID `16097`, cmd `java`로 변경 없이 유지됨을 확인했다. 부모 SSH 종료 후 별도 SSH로 detached 시험 작업의 진행 중→완료를 확인했다. 시험 dispatch는 3초 대기 후 메시지만 반환해 운영 파일을 변경하지 않았으며 해당 시험 job의 정확한 파일만 정리했다.
- **설치·남은 작업:** 설치 위치는 `%LOCALAPPDATA%/PPakemonVpsManager`, 바탕화면 바로가기는 `빡케몬 VPS 관리.lnk`로 계획했으며 아직 설치하지 않았다. 운영 저장소에는 관리 프로그램을 설치하지 않는다. main 커밋·푸시, 설치본 해시 확인, 바로가기 생성, 작업트리 정리가 아직 남아 있다.
- **검증 한계:** 새 GUI의 실제 운영 업데이트·선택 파일 커밋/푸시·종료 버튼 적용은 실행하지 않았다. fixture 통과와 읽기/시작 Probe를 실제 운영 파일 변경 완료로 해석하지 않는다. 이 기록은 메인 작업자의 구현·직접 검증 보고와 모듈 README를 근거로 하며 기록 담당자는 테스트·GUI·SSH 검증을 재실행하지 않았다.
