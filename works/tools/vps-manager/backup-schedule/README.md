# VPS GDrive 예약 백업

VPS의 ppakemon-gdrive-backup.timer는 매일 04:00(Asia/Seoul)에 실행 여부를 확인합니다. 마지막으로 Drive 검증까지 성공한 백업의 한국 날짜에서 이틀 이상 지났을 때만 실제 백업을 실행합니다. 2026-11-10 성공본을 기준으로 첫 예정일은 11월 12일 04:00입니다. 실패하거나 다른 백업과 겹친 실행은 성공일을 갱신하지 않으므로 다음 날 다시 시도합니다.

- backup.py: VPS /opt/ppakemon-gdrive/backup.py. 기존 백업 구현에 검증 성공 후 /var/lib/ppakemon-gdrive/last-success 표식 갱신만 추가했습니다.
- schedule_due.py: VPS /opt/ppakemon-gdrive/schedule_due.py. 한국 날짜로 이틀 경과 여부를 판단합니다.
- interval.conf: VPS /etc/systemd/system/ppakemon-gdrive-backup.service.d/interval.conf. ExecCondition으로 판정합니다.
- ppakemon-gdrive-backup.timer: VPS /etc/systemd/system/ppakemon-gdrive-backup.timer. 매일 04:00 검사와 Persistent 재시도는 유지합니다.

2026-11-10 적용 전 타이머·서비스·백업 프로그램 원본은 VPS /var/lib/ppakemon-gdrive/schedule-change-20261110/에 보존했습니다. 설치 후 systemd-analyze verify와 날짜 판정 8건, 성공·실패·중복·스냅샷 전용 경로 4건을 확인했습니다. 실제 11월 12일 예약 백업 실행과 Drive 검증 결과는 그 시점에 별도 확인이 필요합니다. 게임 서버 재시작은 필요하지 않습니다.
