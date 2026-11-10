#!/usr/bin/python3
"""Run the scheduled backup when two Korea calendar days passed since success."""
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

STAMP = Path('/var/lib/ppakemon-gdrive/last-success')
KST = ZoneInfo('Asia/Seoul')


def due(today, last):
    return last is None or (today - last).days >= 2


def main():
    today = datetime.now(KST).date()
    last = datetime.fromtimestamp(STAMP.stat().st_mtime, KST).date() if STAMP.exists() else None
    if due(today, last):
        print(f'BACKUP_DUE today={today} last={last}', flush=True)
        return 0
    print(f'BACKUP_SKIP today={today} last={last}', flush=True)
    return 1


if __name__ == '__main__':
    raise SystemExit(main())
